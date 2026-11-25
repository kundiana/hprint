package app.freeprint

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared banded rasterization, EXIF handling, preview rendering, and 1-bit dithering
 * usable across any PrinterDriver implementation.
 */
object RasterEngine {

    const val BAND_ROWS = 128
    const val PICTURE_SAFE_MARGIN_DOTS = 24
    private const val TEXT_LUMA_THRESHOLD = 165

    fun parsePages(input: String, pageCount: Int, maxPages: Int = 50): List<Int> {
        if (pageCount < 1) throw IllegalArgumentException("Document has no pages.")
        val text = input.trim()
        val result = LinkedHashSet<Int>()
        if (text.isEmpty()) {
            if (pageCount > maxPages) {
                throw IllegalArgumentException("Max $maxPages pages per job; enter a range (e.g. 1-10).")
            }
            for (i in 0 until pageCount) result.add(i)
            return result.toList()
        }
        if (!text.matches(Regex("[0-9,\\-\\s]+"))) {
            throw IllegalArgumentException("Use page format like: 1-3, 5")
        }
        for (part in text.split(",")) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("Use page format like: 1-3, 5")
            val range = trimmed.split("-")
            if (range.size > 2) throw IllegalArgumentException("Use page format like: 1-3, 5")
            val first = range[0].trim().toIntOrNull()
                ?: throw IllegalArgumentException("Invalid page number in '$trimmed'")
            val last = if (range.size == 1) first else (range[1].trim().toIntOrNull()
                ?: throw IllegalArgumentException("Invalid page range in '$trimmed'"))
            if (first < 1 || last > pageCount || first > last) {
                throw IllegalArgumentException("Page $first-$last out of range (1..$pageCount).")
            }
            for (p in first..last) {
                result.add(p - 1)
                if (result.size > maxPages) {
                    throw IllegalArgumentException("Max $maxPages pages per job.")
                }
            }
        }
        return result.toList()
    }

    fun decodeImageWithExif(
        file: File,
        maxDimension: Int,
        orientationMode: Orientation
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Cannot read image dimensions.")
        }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IOException("Failed to decode image.")

        val matrix = Matrix()
        try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(90f); matrix.postScale(1f, -1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            }
        } catch (_: Throwable) {}

        if (!matrix.isIdentity) {
            val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (upright !== bmp) bmp.recycle()
            bmp = upright
        }

        val shouldRotate90 = when (orientationMode) {
            Orientation.AUTO -> bmp.width > bmp.height
            Orientation.PORTRAIT -> false
            Orientation.LANDSCAPE -> true
        }
        if (shouldRotate90) {
            val turned = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(90f) }, true
            )
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }
        return bmp
    }

    fun fitRect(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        margin: Int,
        align: Align
    ): RectF {
        val availW = (dstW - margin * 2f).coerceAtLeast(1f)
        val availH = (dstH - margin * 2f).coerceAtLeast(1f)
        val scale = minOf(availW / srcW.toFloat(), availH / srcH.toFloat())
        val w = srcW * scale
        val h = srcH * scale

        val left = when (align) {
            Align.TOP_LEFT, Align.CENTER_LEFT, Align.BOTTOM_LEFT -> margin.toFloat()
            Align.TOP_CENTER, Align.CENTER, Align.BOTTOM_CENTER -> (dstW - w) / 2f
            Align.TOP_RIGHT, Align.CENTER_RIGHT, Align.BOTTOM_RIGHT -> dstW - margin - w
        }
        val top = when (align) {
            Align.TOP_LEFT, Align.TOP_CENTER, Align.TOP_RIGHT -> margin.toFloat()
            Align.CENTER_LEFT, Align.CENTER, Align.CENTER_RIGHT -> (dstH - h) / 2f
            Align.BOTTOM_LEFT, Align.BOTTOM_CENTER, Align.BOTTOM_RIGHT -> dstH - margin - h
        }
        return RectF(left, top, left + w, top + h)
    }

    fun renderPreview(
        file: File,
        isPdf: Boolean,
        pageIndex: Int,
        paper: PaperSize,
        align: Align,
        orientation: Orientation,
        targetHeight: Int = 720
    ): Bitmap {
        val prevH = targetHeight
        val prevW = (targetHeight.toFloat() * paper.widthDots / paper.heightDots).toInt().coerceAtLeast(100)
        val colorPage = Bitmap.createBitmap(prevW, prevH, Bitmap.Config.ARGB_8888)
        colorPage.eraseColor(Color.WHITE)

        if (isPdf) {
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var page: PdfRenderer.Page? = null
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                val safeIndex = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                page = renderer.openPage(safeIndex)
                val box = fitRect(page.width, page.height, prevW, prevH, 0, Align.CENTER)
                val m = Matrix().apply {
                    setScale(box.width() / page.width, box.height() / page.height)
                    postTranslate(box.left, box.top)
                }
                page.render(colorPage, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            } finally {
                page?.close()
                renderer?.close()
                pfd?.close()
            }
        } else {
            val scaledMargin = (PICTURE_SAFE_MARGIN_DOTS.toFloat() * prevH / paper.heightDots).toInt().coerceAtLeast(2)
            val img = decodeImageWithExif(file, 1400, orientation)
            try {
                val box = fitRect(img.width, img.height, prevW, prevH, scaledMargin, align)
                Canvas(colorPage).drawBitmap(img, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                img.recycle()
            }
        }

        val mono = Bitmap.createBitmap(prevW, prevH, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        Canvas(mono).drawBitmap(colorPage, 0f, 0f, grayPaint)
        colorPage.recycle()
        return mono
    }

    class BandBuffer(val width: Int) {
        val bpr = (width + 7) ushr 3
        val band: Bitmap = Bitmap.createBitmap(width, BAND_ROWS, Bitmap.Config.ARGB_8888)
        val bandPixels = IntArray(width * BAND_ROWS)
        val rawRow = ByteArray(bpr)
        val errCurr = IntArray(width)
        val errNext = IntArray(width)

        fun resetDither() {
            errCurr.fill(0)
            errNext.fill(0)
        }

        fun recycle() {
            band.recycle()
        }
    }

    fun forEachBand(
        buffer: BandBuffer,
        pageHeight: Int,
        cancel: AtomicBoolean,
        renderBand: (band: Bitmap, yOffset: Int, rows: Int) -> Unit,
        onBandPixels: (yOffset: Int, rows: Int, pixels: IntArray) -> Unit
    ) {
        var y = 0
        while (y < pageHeight) {
            checkCancelled(cancel)
            val rows = minOf(BAND_ROWS, pageHeight - y)
            buffer.band.eraseColor(Color.WHITE)
            renderBand(buffer.band, y, rows)
            buffer.band.getPixels(buffer.bandPixels, 0, buffer.width, 0, 0, buffer.width, rows)
            onBandPixels(y, rows, buffer.bandPixels)
            y += rows
        }
    }

    fun rasterizeRow(
        buffer: BandBuffer,
        pixels: IntArray,
        rowOffset: Int,
        mode: PrintMode
    ): Boolean {
        buffer.rawRow.fill(0)
        return if (mode == PrintMode.DOCUMENT) {
            rasterizeDocumentRow(pixels, rowOffset, buffer.width, buffer.rawRow)
        } else {
            rasterizeFloydSteinbergRow(
                pixels, rowOffset, buffer.width,
                buffer.errCurr, buffer.errNext, buffer.rawRow
            )
        }
    }

    private fun rasterizeDocumentRow(
        pixels: IntArray,
        offset: Int,
        width: Int,
        outRow: ByteArray
    ): Boolean {
        var hasInk = false
        for (x in 0 until width) {
            val p = pixels[offset + x]
            if (p == -1) continue
            val a = p ushr 24
            if (a == 0) continue
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            var lum = (77 * r + 150 * g + 29 * b) shr 8
            if (a < 255) {
                lum = (lum * a + 255 * (255 - a)) / 255
            }
            if (lum < TEXT_LUMA_THRESHOLD) {
                val idx = x ushr 3
                outRow[idx] = (outRow[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                hasInk = true
            }
        }
        return hasInk
    }

    private fun rasterizeFloydSteinbergRow(
        pixels: IntArray,
        offset: Int,
        width: Int,
        errCurr: IntArray,
        errNext: IntArray,
        outRow: ByteArray
    ): Boolean {
        var hasInk = false
        for (x in 0 until width) {
            val p = pixels[offset + x]
            val lum = if (p == -1) {
                255
            } else {
                val a = p ushr 24
                if (a == 0) {
                    255
                } else {
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    val raw = (77 * r + 150 * g + 29 * b) shr 8
                    if (a < 255) (raw * a + 255 * (255 - a)) / 255 else raw
                }
            }

            val adjusted = if (lum > 246 && errCurr[x] >= -8) 255 else (lum + errCurr[x]).coerceIn(0, 255)
            val black = adjusted < 128
            if (black) {
                val idx = x ushr 3
                outRow[idx] = (outRow[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                hasInk = true
            }
            val err = adjusted - if (black) 0 else 255
            if (err != 0) {
                if (x + 1 < width) errCurr[x + 1] += (err * 7) shr 4
                if (x > 0) errNext[x - 1] += (err * 3) shr 4
                errNext[x] += (err * 5) shr 4
                if (x + 1 < width) errNext[x + 1] += err shr 4
            }
        }
        System.arraycopy(errNext, 0, errCurr, 0, width)
        errNext.fill(0)
        return hasInk
    }

    fun checkCancelled(cancel: AtomicBoolean) {
        if (cancel.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Print job cancelled by user.")
        }
    }
}
