/*
 * HPrint - An unofficial Android print app for older HP printers.
 * Copyright (C) 2026 kundiana
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 */

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
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-speed PCL 5e raster driver & banded rendering pipeline for HP LaserJet printers.
 *
 * Uses:
 * - 128-row banded rendering (low RAM, zero full-page float/Long allocations)
 * - PCL Compression Mode 2 (TIFF PackBits: ESC * b 2 M)
 * - Vertical blank-row skipping (ESC * b # Y)
 * - Streaming directly into a BufferedOutputStream over USB bulk transfers
 */
object PclDriver {

    private const val BAND_ROWS = 128
    private const val PICTURE_SAFE_MARGIN_DOTS = 24 // ~2 mm @ 300 DPI inside PCL printable area
    private const val TEXT_LUMA_THRESHOLD = 165      // 0..255; darker than this becomes solid black

    data class PaperSize(
        val name: String,
        val widthDots: Int,   // PCL logical printable width @ 300 DPI (divisible by 8)
        val heightDots: Int,  // PCL logical printable height @ 300 DPI
        val pclCode: Int
    )

    // True PCL logical printable dimensions @ 300 DPI (accounts for ~4-5mm hardware margins)
    val PAPER_SIZES = listOf(
        PaperSize("A4",     2376, 3400, 26),
        PaperSize("Letter", 2400, 3200,  2),
        PaperSize("Legal",  2400, 4100,  3),
        PaperSize("A5",     1648, 2380, 100)
    )

    enum class Align(val label: String) {
        TOP_LEFT("Top-Left"),
        TOP_CENTER("Top-Center"),
        TOP_RIGHT("Top-Right"),
        CENTER_LEFT("Center-Left"),
        CENTER("Center"),
        CENTER_RIGHT("Center-Right"),
        BOTTOM_LEFT("Bottom-Left"),
        BOTTOM_CENTER("Bottom-Center"),
        BOTTOM_RIGHT("Bottom-Right")
    }

    enum class Orientation(val label: String) {
        AUTO("Auto (Best Fit)"),
        PORTRAIT("Keep Upright"),
        LANDSCAPE("Rotate 90°")
    }

    enum class PrintMode(val label: String) {
        DOCUMENT("Document (Crisp Text)"),
        PHOTO("Photo (Dithered)")
    }

    val JOB_FOOTER: ByteArray = "\u001BE\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    val PJL_INFO_ID: ByteArray =
        "\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    fun jobHeader(paper: PaperSize, copies: Int = 1): ByteArray {
        val copyCmd = if (copies > 1) "\u001B&l${copies}X" else ""
        return ("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n" +
            "\u001BE" +
            "\u001B&l${paper.pclCode}A" +
            "\u001B&l0E" +     // Top margin = 0 lines
            copyCmd +
            "\u001B&u300D")
            .toByteArray(Charsets.ISO_8859_1)
    }

    // ------------------------------------------------------------------ Page Parsing

    fun parsePages(input: String, pageCount: Int, maxPages: Int = 50): List<Int> {
        if (pageCount < 1) throw IllegalArgumentException("Document has no pages.")
        val text = input.trim()
        val result = LinkedHashSet<Int>()
        if (text.isEmpty()) {
            if (pageCount > maxPages) {
                throw IllegalArgumentException("Max $maxPages pages per job; enter a page range (e.g. 1-10).")
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

    // ------------------------------------------------------------------ Geometry & EXIF

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
            inPreferredConfig = Bitmap.Config.ARGB_8888 // Preserves PNG alpha for clean white compositing
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

    private fun fitRect(
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

    // ------------------------------------------------------------------ Live Grayscale Preview

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

    // ------------------------------------------------------------------ Streaming Print Jobs

    fun streamPdfJob(
        file: File,
        pages: List<Int>,
        copies: Int,
        paper: PaperSize,
        mode: PrintMode,
        rawOut: OutputStream,
        cancel: AtomicBoolean,
        onStage: (String) -> Unit
    ) {
        val out = BufferedOutputStream(rawOut, 16384)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            // For single-page jobs, let the LaserJet handle copies in hardware (instant!).
            // For multi-page jobs, loop copies in software so output is collated [1,2, 1,2].
            val hwCopies = if (pages.size == 1) copies else 1
            val swCopies = if (pages.size == 1) 1 else copies

            out.write(jobHeader(paper, hwCopies))

            val totalPages = pages.size * swCopies
            var done = 0
            val ctx = RasterContext(paper.widthDots, paper.heightDots)
            try {
                for (c in 0 until swCopies) {
                    for (pageIdx in pages) {
                        checkCancelled(cancel)
                        done++
                        onStage("Spooling page $done/$totalPages...")
                        val page = renderer.openPage(pageIdx)
                        try {
                            val box = fitRect(page.width, page.height, paper.widthDots, paper.heightDots, 0, Align.CENTER)
                            streamPageBands(
                                ctx = ctx,
                                paper = paper,
                                mode = mode,
                                out = out,
                                cancel = cancel
                            ) { band, y, rows ->
                                val m = Matrix().apply {
                                    setScale(box.width() / page.width, box.height() / page.height)
                                    postTranslate(box.left, box.top - y)
                                }
                                page.render(
                                    band,
                                    Rect(0, 0, paper.widthDots, rows),
                                    m,
                                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                                )
                            }
                        } finally {
                            page.close()
                        }
                    }
                }
            } finally {
                ctx.recycle()
            }

            out.write(JOB_FOOTER)
            out.flush()
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    fun streamPictureJob(
        file: File,
        copies: Int,
        paper: PaperSize,
        align: Align,
        orientation: Orientation,
        mode: PrintMode,
        rawOut: OutputStream,
        cancel: AtomicBoolean,
        onStage: (String) -> Unit
    ) {
        onStage("Decoding image...")
        val image = decodeImageWithExif(file, maxOf(paper.widthDots, paper.heightDots), orientation)
        val out = BufferedOutputStream(rawOut, 16384)
        val ctx = RasterContext(paper.widthDots, paper.heightDots)
        try {
            checkCancelled(cancel)
            out.write(jobHeader(paper, copies))
            onStage("Spooling image...")
            val box = fitRect(
                image.width, image.height,
                paper.widthDots, paper.heightDots,
                PICTURE_SAFE_MARGIN_DOTS, align
            )
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            streamPageBands(
                ctx = ctx,
                paper = paper,
                mode = mode,
                out = out,
                cancel = cancel
            ) { band, y, _ ->
                val shifted = RectF(box).apply { offset(0f, -y.toFloat()) }
                Canvas(band).drawBitmap(image, null, shifted, paint)
            }
            out.write(JOB_FOOTER)
            out.flush()
        } finally {
            ctx.recycle()
            image.recycle()
        }
    }

    private class RasterContext(val width: Int, val height: Int) {
        val bpr = (width + 7) ushr 3
        val band: Bitmap = Bitmap.createBitmap(width, BAND_ROWS, Bitmap.Config.ARGB_8888)
        val bandPixels = IntArray(width * BAND_ROWS)
        val rawRow = ByteArray(bpr)
        val packedRow = ByteArray(bpr + (bpr ushr 5) + 16)
        val errCurr = IntArray(width)
        val errNext = IntArray(width)

        fun recycle() {
            band.recycle()
        }
    }

    private inline fun streamPageBands(
        ctx: RasterContext,
        paper: PaperSize,
        mode: PrintMode,
        out: OutputStream,
        cancel: AtomicBoolean,
        renderBand: (band: Bitmap, yOffset: Int, rows: Int) -> Unit
    ) {
        val pw = paper.widthDots
        val ph = paper.heightDots
        val bpr = ctx.bpr
        val dither = (mode == PrintMode.PHOTO)

        if (dither) {
            ctx.errCurr.fill(0)
            ctx.errNext.fill(0)
        }

        // Start PCL raster @ (0,0), 300 DPI, Compression Mode 2 (TIFF PackBits)
        out.write("\u001B*p0x0Y\u001B*t300R\u001B*r1A\u001B*b2M".toByteArray(Charsets.ISO_8859_1))

        var pendingBlankRows = 0
        var y = 0
        while (y < ph) {
            checkCancelled(cancel)
            val rows = minOf(BAND_ROWS, ph - y)
            ctx.band.eraseColor(Color.WHITE)
            renderBand(ctx.band, y, rows)

            // Single JNI read for the entire band instead of per-row getPixels
            ctx.band.getPixels(ctx.bandPixels, 0, pw, 0, 0, pw, rows)

            for (r in 0 until rows) {
                val rowOffset = r * pw
                ctx.rawRow.fill(0)
                val hasInk = if (!dither) {
                    rasterizeDocumentRow(ctx.bandPixels, rowOffset, pw, ctx.rawRow)
                } else {
                    rasterizeFloydSteinbergRow(
                        ctx.bandPixels, rowOffset, pw,
                        ctx.errCurr, ctx.errNext, ctx.rawRow
                    )
                }

                if (!hasInk) {
                    pendingBlankRows++
                } else {
                    if (pendingBlankRows > 0) {
                        out.write("\u001B*b${pendingBlankRows}Y".toByteArray(Charsets.ISO_8859_1))
                        pendingBlankRows = 0
                    }
                    val packedLen = encodePackBits(ctx.rawRow, bpr, ctx.packedRow)
                    out.write("\u001B*b${packedLen}W".toByteArray(Charsets.ISO_8859_1))
                    out.write(ctx.packedRow, 0, packedLen)
                }
            }
            y += rows
        }

        // End raster graphics & form feed (trailing blank rows don't need to be transmitted)
        out.write("\u001B*rB\u000C".toByteArray(Charsets.ISO_8859_1))
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
            if (p == -1) continue // Fast path: 0xFFFFFFFF pure white
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
            var lum = if (p == -1) {
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

            // Clamp bright highlights to clean paper white so background never speckles
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

    /**
     * TIFF PackBits encoder (PCL Compression Mode 2).
     * Never emits -128 (0x80 no-op).
     */
    private fun encodePackBits(src: ByteArray, len: Int, dst: ByteArray): Int {
        var i = 0
        var out = 0
        while (i < len) {
            var run = 1
            val b = src[i]
            while (i + run < len && run < 128 && src[i + run] == b) {
                run++
            }
            if (run >= 3) {
                dst[out++] = (1 - run).toByte() // -2 .. -127
                dst[out++] = b
                i += run
            } else {
                val litStart = i
                var litLen = 0
                while (i < len && litLen < 128) {
                    if (i + 2 < len && src[i] == src[i + 1] && src[i] == src[i + 2]) {
                        break
                    }
                    i++
                    litLen++
                }
                dst[out++] = (litLen - 1).toByte() // 0 .. 127
                System.arraycopy(src, litStart, dst, out, litLen)
                out += litLen
            }
        }
        return out
    }

    private fun checkCancelled(cancel: AtomicBoolean) {
        if (cancel.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Print job cancelled by user.")
        }
    }

    // ------------------------------------------------------------------ Built-in Diagnostic Jobs

    fun textTestJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        pcl("\u001BE\u001B&k2G")
        pcl("hprint text test\r\nHP LaserJet over USB OTG\r\n")
        pcl("If you can read this, the phone-to-printer link works.\r\n")
        pcl("\u000C\u001BE\u001B%-12345X")
        return out.toByteArray()
    }

    fun graphicsTestJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        pcl("\u001BE\u001B&k2G\u001B&u300D")
        pcl("hprint graphics test (PackBits PCL5)\r\n")
        val size = 600
        val bpr = size / 8
        pcl("\u001B*p150x500Y\u001B*t300R\u001B*r1A\u001B*b2M")
        val row = ByteArray(bpr)
        val packed = ByteArray(bpr * 2)
        for (y in 0 until size) {
            row.fill(0)
            if (y < 6 || y >= size - 6) {
                row.fill(0xFF.toByte())
            } else {
                row[0] = 0xFF.toByte()
                row[bpr - 1] = 0xFF.toByte()
                val idx = y / 8
                row[idx] = (row[idx].toInt() or (0x80 ushr (y % 8))).toByte()
            }
            val n = encodePackBits(row, bpr, packed)
            pcl("\u001B*b${n}W")
            out.write(packed, 0, n)
        }
        pcl("\u001B*rB\u000C\u001BE\u001B%-12345X")
        return out.toByteArray()
    }
}
