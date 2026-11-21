package app.freeprint

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Everything that turns a Bitmap (or a test pattern) into PCL bytes.
 * No Android Activity, no USB — just bytes in, bytes out.
 */
object PclDriver {

    val JOB_HEADER: ByteArray =
        "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n\u001BE\u001B&u300D"
            .toByteArray(Charsets.ISO_8859_1)
    val JOB_FOOTER: ByteArray =
        "\u001BE\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)
    val PJL_INFO_ID: ByteArray =
        "\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    /**
     * Render one Bitmap as a PCL raster page (no PJL envelope — caller adds that).
     *
     * dither = false  →  "Document mode": good for screenshots and text images.
     *   Uses a local adaptive threshold: for every pixel we look at the surrounding
     *   area to decide what counts as "dark enough to print". This survives the
     *   blurring that happens when Android scales a tall screenshot down to A4 width,
     *   because a blurred dark letter is still darker than its blurred background.
     *   No white-clamping: we trust the adaptive threshold to handle backgrounds.
     *
     * dither = true   →  "Photo mode": good for real photographs with smooth gradients.
     *   Uses Floyd-Steinberg error diffusion for natural-looking halftones.
     *   White pixels (lum > 0.95) are forced pure white to keep plain backgrounds clean.
     *
     * @param bitmap   Recycled by this function when it is done with it.
     * @param dither   false = document/text, true = photo.
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

        // Build the full luminance map (0.0 = black, 1.0 = white).
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
            // ---- Document mode: local adaptive threshold (Sauvola-lite) ----
            // For each pixel, the threshold = mean of neighbours × 0.85.
            // A pixel is black if its luminance is well below the local average.
            // This handles both "white background with black text" and
            // "light-grey background with dark-grey text" correctly.
            // Radius 8 gives a 17×17 neighbourhood at 300 dpi ≈ 1.5 mm.
            val R = 8
            // Build an integral image for fast box-mean computation.
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
                    // Print the pixel if it is darker than 85% of the local mean.
                    // The 0.15 slack stops noise in flat areas from printing as dots.
                    if (lum[y][x] < mean * 0.85f - 0.01f) {
                        val idx = x shr 3
                        row[idx] = (row[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
                out.write(rowHdr)
                out.write(row)
            }
        } else {
            // ---- Photo mode: Floyd-Steinberg error diffusion ----
            // Clamp near-white to pure white first so plain backgrounds don't speckle.
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
