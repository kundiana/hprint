package app.freeprint

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Everything that turns a Bitmap (or a test pattern) into PCL bytes.
 * No Android Activity, no USB — just bytes in, bytes out.
 */
object PclDriver {

    // ------------------------------------------------------------------ paper sizes

    /** Paper size as dots at 300 dpi, with the PCL page-size command code. */
    data class PaperSize(val name: String, val widthDots: Int, val heightDots: Int, val pclCode: Int)

    val PAPER_SIZES = listOf(
        PaperSize("A4",     2376, 3400, 26),   // 210 × 297 mm
        PaperSize("Letter", 2550, 3300,  2),   // 8.5 × 11 in
        PaperSize("Legal",  2550, 4200,  3),   // 8.5 × 14 in
        PaperSize("A5",     1684, 2384, 100)   // 148 × 210 mm  (PCL code 100 = A5)
    )

    // ------------------------------------------------------------------ image alignment

    enum class Align { TOP_LEFT, TOP_CENTER, TOP_RIGHT,
                       CENTER_LEFT, CENTER, CENTER_RIGHT,
                       BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

    // ------------------------------------------------------------------ PJL constants

    val JOB_FOOTER: ByteArray = "\u001BE\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    val PJL_INFO_ID: ByteArray =
        "\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    /** Build the PJL/PCL job header for the given paper size. */
    fun jobHeader(paper: PaperSize): ByteArray =
        ("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n" +
         "\u001BE"            +   // reset
         "\u001B&l${paper.pclCode}A" +   // paper size
         "\u001B&u300D")          // unit = 1/300 inch
            .toByteArray(Charsets.ISO_8859_1)

    // ------------------------------------------------------------------ bitmapToPage

    /**
     * Render one Bitmap as a PCL raster page (no PJL envelope — caller adds that).
     * The bitmap canvas should already be exactly paper.widthDots × paper.heightDots
     * (or smaller; the PCL cursor is placed at 0,0 and the page eject comes at the end).
     *
     * @param bitmap   Recycled by this function.
     * @param dither   false = document/text (adaptive threshold), true = photo (Floyd-Steinberg).
     */
    fun bitmapToPage(bitmap: Bitmap, dither: Boolean): ByteArray {
        val bw = bitmap.width
        val bh = bitmap.height
        val bytesPerRow = (bw + 7) / 8
        val out = ByteArrayOutputStream(bh * (bytesPerRow + 10) + 64)

        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B*p0x0Y")
        pcl("\u001B*t300R")
        pcl("\u001B*r1A")
        pcl("\u001B*b0M")

        // Build full float luminance map (0.0 = black, 1.0 = white).
        val lum = Array(bh) { y ->
            val px = IntArray(bw)
            bitmap.getPixels(px, 0, bw, 0, y, bw, 1)
            FloatArray(bw) { x ->
                val p = px[x]
                (77 * ((p shr 16) and 0xFF) +
                 150 * ((p shr  8) and 0xFF) +
                  29 * ( p         and 0xFF)) / 255f
            }
        }
        bitmap.recycle()

        val row = ByteArray(bytesPerRow)
        val rowHdr = "\u001B*b${bytesPerRow}W".toByteArray(Charsets.ISO_8859_1)

        if (!dither) {
            // Document mode: local adaptive threshold (Sauvola-lite).
            // For each pixel, threshold = localMean × 0.85.
            // A pixel is black if its luminance is below the local average minus slack.
            // This handles blurred text (from downscaling) and grey backgrounds correctly.
            val R = 8   // 17×17 neighbourhood ≈ 1.4 mm at 300 dpi
            // Integral image for O(1) box-mean queries.
            val integral = Array(bh + 1) { LongArray(bw + 1) }
            for (y in 0 until bh)
                for (x in 0 until bw)
                    integral[y + 1][x + 1] = (lum[y][x] * 65535).toLong() +
                        integral[y][x + 1] + integral[y + 1][x] - integral[y][x]

            for (y in 0 until bh) {
                row.fill(0)
                val y1 = maxOf(0, y - R); val y2 = minOf(bh, y + R + 1)
                for (x in 0 until bw) {
                    val x1 = maxOf(0, x - R); val x2 = minOf(bw, x + R + 1)
                    val area = (y2 - y1) * (x2 - x1)
                    val sum = integral[y2][x2] - integral[y1][x2] -
                              integral[y2][x1] + integral[y1][x1]
                    val mean = sum.toFloat() / (area * 65535f)
                    if (lum[y][x] < mean * 0.85f - 0.01f) {
                        val idx = x shr 3
                        row[idx] = (row[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
                out.write(rowHdr)
                out.write(row)
            }
        } else {
            // Photo mode: Floyd-Steinberg error diffusion.
            // Near-white pixels forced to pure white so blank backgrounds don't speckle.
            for (y in 0 until bh)
                for (x in 0 until bw)
                    if (lum[y][x] > 0.95f) lum[y][x] = 1f

            for (y in 0 until bh) {
                row.fill(0)
                for (x in 0 until bw) {
                    val old = lum[y][x]
                    val black = old < 0.5f
                    if (black) {
                        val idx = x shr 3
                        row[idx] = (row[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                    val err = old - if (black) 0f else 1f
                    if (x + 1 < bw)
                        lum[y    ][x + 1] = (lum[y    ][x + 1] + err * 7f / 16f).coerceIn(0f, 1f)
                    if (y + 1 < bh) {
                        if (x > 0)
                            lum[y + 1][x - 1] = (lum[y + 1][x - 1] + err * 3f / 16f).coerceIn(0f, 1f)
                        lum[y + 1][x    ] = (lum[y + 1][x    ] + err * 5f / 16f).coerceIn(0f, 1f)
                        if (x + 1 < bw)
                            lum[y + 1][x + 1] = (lum[y + 1][x + 1] + err * 1f / 16f).coerceIn(0f, 1f)
                    }
                }
                out.write(rowHdr)
                out.write(row)
            }
        }

        pcl("\u001B*rB")
        pcl("\u000C")
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ test jobs

    fun textTestJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        pcl("\u001BE\u001B&k2G")
        pcl("hprint text test\nHP LaserJet 1320 over USB OTG\n")
        pcl("If you can read this, the phone-to-printer link works.\n")
        pcl("\u000C\u001BE\u001B%-12345X")
        return out.toByteArray()
    }

    fun graphicsTestJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        pcl("\u001BE\u001B&k2G\u001B&u300D")
        pcl("hprint test page\nHP LaserJet 1320. Raster graphics test below.\n")
        val size = 600; val bpr = size / 8
        pcl("\u001B*p150x500Y\u001B*t300R\u001B*r1A\u001B*b0M")
        val row = ByteArray(bpr)
        for (y in 0 until size) {
            row.fill(0)
            if (y < 6 || y >= size - 6) { row.fill(0xFF.toByte()) }
            else {
                row[0] = 0xFF.toByte(); row[bpr - 1] = 0xFF.toByte()
                val idx = y / 8
                row[idx] = (row[idx].toInt() or (0x80 ushr (y % 8))).toByte()
            }
            pcl("\u001B*b${bpr}W"); out.write(row)
        }
        pcl("\u001B*rB\u000C\u001BE\u001B%-12345X")
        return out.toByteArray()
    }
}
