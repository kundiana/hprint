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
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Turns Bitmaps and test jobs into PJL/PCL byte streams.
 * No Android Activity or USB dependencies — pure stream/byte encoding.
 */
object PclDriver {

    data class PaperSize(
        val name: String,
        val widthDots: Int,
        val heightDots: Int,
        val pclCode: Int
    )

    val PAPER_SIZES = listOf(
        PaperSize("A4",     2376, 3400, 26),
        PaperSize("Letter", 2550, 3300,  2),
        PaperSize("Legal",  2550, 4200,  3),
        PaperSize("A5",     1684, 2384, 100)
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

    val JOB_FOOTER: ByteArray = "\u001BE\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    val PJL_INFO_ID: ByteArray =
        "\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    fun jobHeader(paper: PaperSize): ByteArray =
        ("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n" +
            "\u001BE" +
            "\u001B&l${paper.pclCode}A" +
            "\u001B&u300D")
            .toByteArray(Charsets.ISO_8859_1)

    fun writeJobHeader(out: OutputStream, paper: PaperSize) {
        out.write(jobHeader(paper))
    }

    fun writeJobFooter(out: OutputStream) {
        out.write(JOB_FOOTER)
    }

    fun bitmapToPage(bitmap: Bitmap, dither: Boolean): ByteArray {
        val baos = ByteArrayOutputStream()
        writePageToStream(bitmap, dither, baos)
        return baos.toByteArray()
    }

    /**
     * Streams a single Bitmap as a PCL raster page directly to [out] and recycles [bitmap].
     * @param dither false = crisp document/text (aggressive dark-pixel retention + local adaptive),
     *               true  = photo mode (Floyd-Steinberg error diffusion).
     */
    fun writePageToStream(bitmap: Bitmap, dither: Boolean, out: OutputStream) {
        val bw = bitmap.width
        val bh = bitmap.height
        val bytesPerRow = (bw + 7) / 8

        fun pcl(cmd: String) = out.write(cmd.toByteArray(Charsets.ISO_8859_1))

        pcl("\u001B*p0x0Y")
        pcl("\u001B*t300R")
        pcl("\u001B*r1A")
        pcl("\u001B*b0M")

        // Normalize luminance into [0.0f, 1.0f] where 0.0f = black and 1.0f = white.
        val lum = Array(bh) { y ->
            val px = IntArray(bw)
            bitmap.getPixels(px, 0, bw, 0, y, bw, 1)
            FloatArray(bw) { x ->
                val p = px[x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                ((77 * r + 150 * g + 29 * b) / (255f * 256f)).coerceIn(0f, 1f)
            }
        }
        bitmap.recycle()

        val row = ByteArray(bytesPerRow)
        val rowHdr = "\u001B*b${bytesPerRow}W".toByteArray(Charsets.ISO_8859_1)

        if (!dither) {
            // Document mode: tight neighborhood + strong dark-pixel retention so thin text
            // and ink strokes stay crisp without muddying clean backgrounds.
            val radius = 5
            val qScale = 65535f
            val integral = Array(bh + 1) { LongArray(bw + 1) }
            for (y in 0 until bh) {
                var rowSum = 0L
                for (x in 0 until bw) {
                    rowSum += (lum[y][x] * qScale).toLong()
                    integral[y + 1][x + 1] = integral[y][x + 1] + rowSum
                }
            }

            for (y in 0 until bh) {
                row.fill(0)
                val y1 = (y - radius).coerceAtLeast(0)
                val y2 = (y + radius + 1).coerceAtMost(bh)
                for (x in 0 until bw) {
                    val x1 = (x - radius).coerceAtLeast(0)
                    val x2 = (x + radius + 1).coerceAtMost(bw)
                    val area = ((y2 - y1) * (x2 - x1)).coerceAtLeast(1)
                    val sum = integral[y2][x2] - integral[y1][x2] -
                        integral[y2][x1] + integral[y1][x1]
                    val localMean = sum.toFloat() / (area * qScale)
                    val pxLum = lum[y][x]

                    val isDarkInk = pxLum < 0.34f ||
                        (pxLum < localMean * 0.74f - 0.03f && localMean < 0.985f)

                    if (isDarkInk) {
                        val byteIdx = x ushr 3
                        row[byteIdx] = (row[byteIdx].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
                out.write(rowHdr)
                out.write(row)
            }
        } else {
            // Photo mode: Floyd-Steinberg error diffusion in [0.0f, 1.0f].
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    if (lum[y][x] > 0.96f) lum[y][x] = 1f
                }
            }

            for (y in 0 until bh) {
                row.fill(0)
                for (x in 0 until bw) {
                    val old = lum[y][x]
                    val black = old < 0.5f
                    if (black) {
                        val byteIdx = x ushr 3
                        row[byteIdx] = (row[byteIdx].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                    val err = old - if (black) 0f else 1f
                    if (x + 1 < bw) {
                        lum[y][x + 1] = (lum[y][x + 1] + err * 7f / 16f).coerceIn(0f, 1f)
                    }
                    if (y + 1 < bh) {
                        if (x > 0) {
                            lum[y + 1][x - 1] = (lum[y + 1][x - 1] + err * 3f / 16f).coerceIn(0f, 1f)
                        }
                        lum[y + 1][x] = (lum[y + 1][x] + err * 5f / 16f).coerceIn(0f, 1f)
                        if (x + 1 < bw) {
                            lum[y + 1][x + 1] = (lum[y + 1][x + 1] + err * 1f / 16f).coerceIn(0f, 1f)
                        }
                    }
                }
                out.write(rowHdr)
                out.write(row)
            }
        }

        pcl("\u001B*rB")
        pcl("\u000C")
    }

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
        pcl("hprint test page\r\nRaster graphics test below.\r\n")
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
