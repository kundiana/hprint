package app.freeprint

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbDevice
import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PCL 5e 300 DPI raster driver using TIFF PackBits compression (Mode 2)
 * and vertical blank-row skipping.
 */
object PclDriver : PrinterDriver {

    override val id: String = "pcl5e"
    override val displayName: String = "HP PCL 5e (300 DPI)"
    override val priority: Int = 50

    val JOB_FOOTER: ByteArray = "\u001BE\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)
    val PJL_INFO_ID: ByteArray =
        "\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    override fun supports(device: UsbDevice, identity: DeviceIdentity): Boolean {
        if (identity.commands.isEmpty()) return false
        if (identity.hasAnyCommand("PCL", "PCL5", "PCL5E", "PCL5C", "PCLXL", "PCL6")) {
            return true
        }
        return false
    }

    fun jobHeader(paper: PaperSize, copies: Int = 1): ByteArray {
        val copyCmd = if (copies > 1) "\u001B&l${copies}X" else ""
        return ("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n" +
            "\u001BE" +
            "\u001B&l${paper.pclCode}A" +
            "\u001B&l0E" +
            copyCmd +
            "\u001B&u300D")
            .toByteArray(Charsets.ISO_8859_1)
    }

    override fun streamPdfJob(
        request: PrintJobRequest,
        rawOut: OutputStream,
        cancel: AtomicBoolean,
        onStage: (String) -> Unit
    ) {
        val out = BufferedOutputStream(rawOut, 16384)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(request.file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            val hwCopies = if (request.pages.size == 1) request.copies else 1
            val swCopies = if (request.pages.size == 1) 1 else request.copies

            out.write(jobHeader(request.paper, hwCopies))

            val totalPages = request.pages.size * swCopies
            var done = 0
            val buffer = RasterEngine.BandBuffer(request.paper.widthDots)
            val packedRow = ByteArray(buffer.bpr + (buffer.bpr ushr 5) + 16)

            try {
                for (c in 0 until swCopies) {
                    for (pageIdx in request.pages) {
                        RasterEngine.checkCancelled(cancel)
                        done++
                        onStage("Spooling page $done/$totalPages...")
                        val page = renderer.openPage(pageIdx)
                        try {
                            val box = RasterEngine.fitRect(
                                page.width, page.height,
                                request.paper.widthDots, request.paper.heightDots,
                                0, Align.CENTER
                            )
                            streamSinglePage(
                                buffer = buffer,
                                packedRow = packedRow,
                                paper = request.paper,
                                mode = request.mode,
                                out = out,
                                cancel = cancel
                            ) { band, y, rows ->
                                val m = Matrix().apply {
                                    setScale(box.width() / page.width, box.height() / page.height)
                                    postTranslate(box.left, box.top - y)
                                }
                                page.render(
                                    band,
                                    Rect(0, 0, request.paper.widthDots, rows),
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
                buffer.recycle()
            }

            out.write(JOB_FOOTER)
            out.flush()
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    override fun streamPictureJob(
        request: PrintJobRequest,
        rawOut: OutputStream,
        cancel: AtomicBoolean,
        onStage: (String) -> Unit
    ) {
        onStage("Decoding image...")
        val image = RasterEngine.decodeImageWithExif(
            request.file,
            maxOf(request.paper.widthDots, request.paper.heightDots),
            request.orientation
        )
        val out = BufferedOutputStream(rawOut, 16384)
        val buffer = RasterEngine.BandBuffer(request.paper.widthDots)
        val packedRow = ByteArray(buffer.bpr + (buffer.bpr ushr 5) + 16)
        try {
            RasterEngine.checkCancelled(cancel)
            out.write(jobHeader(request.paper, request.copies))
            onStage("Spooling image...")

            val box = RasterEngine.fitRect(
                image.width, image.height,
                request.paper.widthDots, request.paper.heightDots,
                RasterEngine.PICTURE_SAFE_MARGIN_DOTS, request.align
            )
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            streamSinglePage(
                buffer = buffer,
                packedRow = packedRow,
                paper = request.paper,
                mode = request.mode,
                out = out,
                cancel = cancel
            ) { band, y, _ ->
                val shifted = RectF(box).apply { offset(0f, -y.toFloat()) }
                Canvas(band).drawBitmap(image, null, shifted, paint)
            }

            out.write(JOB_FOOTER)
            out.flush()
        } finally {
            buffer.recycle()
            image.recycle()
        }
    }

    private fun streamSinglePage(
        buffer: RasterEngine.BandBuffer,
        packedRow: ByteArray,
        paper: PaperSize,
        mode: PrintMode,
        out: OutputStream,
        cancel: AtomicBoolean,
        renderBand: (band: android.graphics.Bitmap, yOffset: Int, rows: Int) -> Unit
    ) {
        if (mode == PrintMode.PHOTO) buffer.resetDither()

        // Position at (0,0), 300 DPI, start raster, TIFF PackBits compression (Mode 2)
        out.write("\u001B*p0x0Y\u001B*t300R\u001B*r1A\u001B*b2M".toByteArray(Charsets.ISO_8859_1))

        var pendingBlankRows = 0
        RasterEngine.forEachBand(
            buffer = buffer,
            pageHeight = paper.heightDots,
            cancel = cancel,
            renderBand = renderBand
        ) { _, rows, pixels ->
            for (r in 0 until rows) {
                val hasInk = RasterEngine.rasterizeRow(buffer, pixels, r * paper.widthDots, mode)
                if (!hasInk) {
                    pendingBlankRows++
                } else {
                    if (pendingBlankRows > 0) {
                        out.write("\u001B*b${pendingBlankRows}Y".toByteArray(Charsets.ISO_8859_1))
                        pendingBlankRows = 0
                    }
                    val packedLen = encodePackBits(buffer.rawRow, buffer.bpr, packedRow)
                    out.write("\u001B*b${packedLen}W".toByteArray(Charsets.ISO_8859_1))
                    out.write(packedRow, 0, packedLen)
                }
            }
        }

        out.write("\u001B*rB\u000C".toByteArray(Charsets.ISO_8859_1))
    }

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
                dst[out++] = (1 - run).toByte()
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
                dst[out++] = (litLen - 1).toByte()
                System.arraycopy(src, litStart, dst, out, litLen)
                out += litLen
            }
        }
        return out
    }

    override fun textTestJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        pcl("\u001BE\u001B&k2G")
        pcl("hprint text test\r\nHP LaserJet over USB OTG\r\n")
        pcl("If you can read this, the phone-to-printer link works.\r\n")
        pcl("\u000C\u001BE\u001B%-12345X")
        return out.toByteArray()
    }

    override fun graphicsTestJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        pcl("\u001BE\u001B&k2G\u001B&u300D")
        pcl("hprint graphics test (PackBits PCL 5e)\r\n")
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
