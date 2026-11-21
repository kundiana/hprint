package app.freeprint

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Everything that turns a Bitmap (or a test pattern) into PCL bytes.
 * No Android Activity, no USB — just bytes in, bytes out.
 * This makes the protocol testable offline and keeps MainActivity small.
 */
object PclDriver {

    // PJL / PCL job envelope
    val JOB_HEADER: ByteArray =
        "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n\u001BE\u001B&u300D"
            .toByteArray(Charsets.ISO_8859_1)
    val JOB_FOOTER: ByteArray =
        "\u001BE\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    // PJL query used as a "barrier" to know the printer has finished reading the job.
    val PJL_INFO_ID: ByteArray =
        "\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    /**
     * Render one Bitmap as a full PCL raster page (without the PJL envelope).
     * The caller wraps it in JOB_HEADER / JOB_FOOTER.
     *
     * @param bitmap   The page image; this function recycles it when done.
     * @param dither   false → simple 180/255 threshold (crisp text, clean backgrounds).
     *                 true  → Floyd-Steinberg error diffusion (smooth photo gradients).
     */
    fun bitmapToPage(bitmap: Bitmap, dither: Boolean): ByteArray {
        val bw = bitmap.width
        val bh = bitmap.height
        val bytesPerRow = (bw + 7) / 8
        val out = ByteArrayOutputStream(bh * (bytesPerRow + 10) + 64)

        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B*p0x0Y")   // move cursor to top-left
        pcl("\u001B*t300R")   // raster resolution: 300 dpi
        pcl("\u001B*r1A")     // start raster graphics
        pcl("\u001B*b0M")     // compression: none

        // Build a full float luminance map upfront.
        // Near-white pixels are clamped to 1.0 immediately so backgrounds stay clean.
        val whiteClamp = if (dither) 0.90f else 0.70f
        val lum = Array(bh) { y ->
            val px = IntArray(bw)
            bitmap.getPixels(px, 0, bw, 0, y, bw, 1)
            FloatArray(bw) { x ->
                val p = px[x]
                val l = (77 * ((p shr 16) and 0xFF) +
                         150 * ((p shr  8) and 0xFF) +
                          29 * ( p         and 0xFF)) / 255f
                if (l > whiteClamp) 1f else l
            }
        }
        bitmap.recycle()

        val row = ByteArray(bytesPerRow)
        val rowHdr = "\u001B*b${bytesPerRow}W".toByteArray(Charsets.ISO_8859_1)
        val threshold = 180f / 255f   // used only when dither = false

        for (y in 0 until bh) {
            row.fill(0)
            for (x in 0 until bw) {
                val old = lum[y][x]
                val black = if (dither) old < 0.5f else old < threshold
                if (black) {
                    val idx = x shr 3
                    row[idx] = (row[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                }
                if (dither) {
                    val new = if (black) 0f else 1f
                    val err = old - new
                    // Floyd-Steinberg weights: right 7/16, below-left 3/16, below 5/16, below-right 1/16
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
            }
            out.write(rowHdr)
            out.write(row)
        }

        pcl("\u001B*rB")   // end raster graphics
        pcl("\u000C")      // form feed: eject page
        return out.toByteArray()
    }

    /** Simple text-only test job (complete, with PJL envelope). */
    fun textTestJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        pcl("\u001BE\u001B&k2G")
        pcl("hprint text test\n")
        pcl("HP LaserJet 1320 over USB OTG\n")
        pcl("If you can read this, the phone-to-printer link works.\n")
        pcl("\u000C\u001BE\u001B%-12345X")
        return out.toByteArray()
    }

    /** Text + a 2-inch raster box with diagonal (complete, with PJL envelope). */
    fun graphicsTestJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pcl(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        pcl("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        pcl("\u001BE\u001B&k2G\u001B&u300D")
        pcl("hprint test page\nHP LaserJet 1320. Raster graphics test below.\n")

        val size = 600
        val bpr = size / 8
        pcl("\u001B*p150x500Y\u001B*t300R\u001B*r1A\u001B*b0M")
        val row = ByteArray(bpr)
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
            pcl("\u001B*b${bpr}W")
            out.write(row)
        }
        pcl("\u001B*rB\u000C\u001BE\u001B%-12345X")
        return out.toByteArray()
    }
}
