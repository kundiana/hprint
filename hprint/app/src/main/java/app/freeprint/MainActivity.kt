/*
 * HPrint - An unofficial Android print app for older HP printers.
 * Copyright (C) 2026 kundiana
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.freeprint

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * hprint — print PDFs and pictures to a PCL printer over USB OTG.
 * USB / PCL protocol lives in PclDriver; this file is UI + Android glue only.
 */
class MainActivity : Activity() {

    private val actionPermission = "app.freeprint.USB_PERMISSION"
    private val pickRequest = 42

    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var fileLabel: TextView
    private lateinit var pagesInput: EditText
    private lateinit var ditherBox: CheckBox
    private lateinit var printButton: Button
    private lateinit var paperSpinner: Spinner
    private lateinit var alignSpinner: Spinner
    private lateinit var alignLabel: TextView

    @Volatile private var busy = false

    private var pendingJob = 0
    private var preferredProtocol = 2
    private var jobPagesText = ""
    private var jobDither = false
    private var loadedKind = ""  // "", "pdf", "picture"

    // Current selections (read on UI thread, captured into locals before background thread).
    private var selectedPaper: PclDriver.PaperSize = PclDriver.PAPER_SIZES[0]
    private var selectedAlign: PclDriver.Align = PclDriver.Align.CENTER

    // ------------------------------------------------------------------ permission receiver

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != actionPermission) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log(if (granted) "USB permission granted."
                else "USB permission DENIED. Tap the button again and choose OK.")
            val job = pendingJob; pendingJob = 0
            if (granted && job != 0) sendJob(job)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        // File label
        fileLabel = TextView(this).apply {
            text = "Nothing loaded yet. Tap 'Choose PDF or picture' below."
            textSize = 15f
        }

        // Pick button
        val pickButton = Button(this).apply {
            text = "Choose PDF or picture"
            setOnClickListener { pickFile() }
        }

        // Paper size spinner
        val paperLabel = TextView(this).apply { text = "Paper size:" }
        paperSpinner = Spinner(this).also { sp ->
            val names = PclDriver.PAPER_SIZES.map { it.name }
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedPaper = PclDriver.PAPER_SIZES[pos]
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Alignment spinner (shown only for pictures)
        alignLabel = TextView(this).apply { text = "Image alignment:" }
        val alignNames = listOf(
            "Top-Left",    "Top-Center",    "Top-Right",
            "Center-Left", "Center",        "Center-Right",
            "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )
        val alignValues = PclDriver.Align.values()
        alignSpinner = Spinner(this).also { sp ->
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, alignNames)
            sp.setSelection(4) // default: Center
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedAlign = alignValues[pos]
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Pages input (PDFs only)
        pagesInput = EditText(this).apply {
            hint = "Pages, e.g. 1-3  (empty = all, PDFs only)"
        }

        // Photo mode checkbox
        ditherBox = CheckBox(this).apply {
            text = "Photo mode (smooth gradients; text looks rougher)"
        }

        // Print button
        printButton = Button(this).apply {
            text = "Print"
            setOnClickListener { startPrint(4) }
        }

        // Diagnostic buttons
        val scan = Button(this).apply {
            text = "Find printer"
            setOnClickListener { describeDevices() }
        }
        val textTest = Button(this).apply {
            text = "Text test"
            setOnClickListener { startPrint(1) }
        }
        val fullTest = Button(this).apply {
            text = "Text + graphics test"
            setOnClickListener { startPrint(2) }
        }
        val askTest = Button(this).apply {
            text = "Ask printer its name"
            setOnClickListener { startPrint(3) }
        }
        val modeButton = Button(this).apply {
            text = "Switch USB mode"
            setOnClickListener {
                preferredProtocol = when (preferredProtocol) { 2 -> 1; 1 -> 3; else -> 2 }
                log("USB mode: protocol $preferredProtocol  (2 = two-way, 1 = one-way, 3 = special).")
            }
        }

        logView = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }

        for (v in listOf(
            fileLabel, pickButton,
            paperLabel, paperSpinner,
            alignLabel, alignSpinner,
            pagesInput, ditherBox, printButton,
            scan, textTest, fullTest, askTest, modeButton,
            logView
        )) column.addView(v)

        scrollView = ScrollView(this).apply { addView(column) }
        setContentView(scrollView)

        // Alignment controls visible only for pictures.
        setAlignVisible(false)

        val filter = IntentFilter(actionPermission)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)

        log("Plug the printer in and switch it on. Then choose a PDF or picture.")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIntent(intent) }
    override fun onDestroy() { unregisterReceiver(permissionReceiver); super.onDestroy() }

    private fun setAlignVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        runOnUiThread { alignLabel.visibility = vis; alignSpinner.visibility = vis }
    }

    // ------------------------------------------------------------------ logging

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setPrinting(active: Boolean) {
        runOnUiThread { printButton.isEnabled = !active }
    }

    // ------------------------------------------------------------------ file pick

    @Suppress("DEPRECATION")
    private fun pickFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
            }, pickRequest
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickRequest && resultCode == RESULT_OK)
            data?.data?.let { loadFile(it) } ?: log("No file was chosen.")
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let { loadFile(it) }
    }

    private fun loadFile(uri: Uri) {
        log("Loading...")
        thread {
            try {
                val file = File(cacheDir, "current.file")
                contentResolver.openInputStream(uri)?.use { src ->
                    file.outputStream().use { dst -> src.copyTo(dst) }
                } ?: run { log("Could not open that file."); return@thread }

                val magic = ByteArray(5)
                val got = file.inputStream().use { it.read(magic) }
                if (got == 5 && String(magic, Charsets.ISO_8859_1) == "%PDF-") {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        val count = PdfRenderer(pfd).use { it.pageCount }
                        loadedKind = "pdf"
                        runOnUiThread {
                            fileLabel.text = "Loaded: PDF, $count page(s)."
                            ditherBox.isChecked = false
                        }
                        setAlignVisible(false)
                        log("PDF: $count page(s). Set pages if needed, then tap Print.")
                    }
                } else {
                    val b = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, b)
                    if (b.outWidth <= 0) {
                        loadedKind = ""; log("Not a PDF or a picture hprint can read."); return@thread
                    }
                    loadedKind = "picture"
                    runOnUiThread {
                        fileLabel.text = "Loaded: picture, ${b.outWidth} × ${b.outHeight}."
                        ditherBox.isChecked = false   // default OFF; user can enable for photos
                    }
                    setAlignVisible(true)
                    log("Picture loaded. Tap Print. Turn on Photo mode for real photos.")
                }
            } catch (e: Throwable) { loadedKind = ""; log("Could not load: ${e.message}") }
        }
    }

    private fun parsePages(text: String, count: Int): List<Int> {
        val t = text.trim()
        if (t.isEmpty()) return (0 until count).toList()
        val parts = t.split("-")
        val a = parts[0].trim().toIntOrNull() ?: return emptyList()
        val b = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: return emptyList() else a
        val from = maxOf(1, minOf(a, b)); val to = minOf(count, maxOf(a, b))
        return if (from > to) emptyList() else (from - 1 until to).toList()
    }

    // ------------------------------------------------------------------ USB helpers

    private class Target(val device: UsbDevice, val iface: UsbInterface, val out: UsbEndpoint)

    private fun describeDevices() {
        val devices = usb.deviceList.values
        if (devices.isEmpty()) { log("No USB device found. Check the OTG cable and that the printer is on."); return }
        for (d in devices) {
            log("Device: ${d.productName ?: "?"} / ${d.manufacturerName ?: "?"}  " +
                "vid=0x%04x pid=0x%04x".format(d.vendorId, d.productId))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                log("  iface ${itf.id} alt ${itf.alternateSetting}: class=${itf.interfaceClass} proto=${itf.interfaceProtocol} ep=${itf.endpointCount}")
            }
        }
        val t = findTarget()
        if (t != null) log("Will use iface ${t.iface.id} alt ${t.iface.alternateSetting} (proto ${t.iface.interfaceProtocol}). Ready.")
        else log("No usable printer interface (class 7 + bulk-out). Send this log.")
    }

    private fun findTarget(): Target? {
        var best: Target? = null; var bestScore = -1
        for (d in usb.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                val score = if (itf.interfaceProtocol == preferredProtocol) 10
                            else when (itf.interfaceProtocol) { 2 -> 3; 1 -> 2; else -> 1 }
                if (score <= bestScore) continue
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        best = Target(d, itf, ep); bestScore = score; break
                    }
                }
            }
        }
        return best
    }

    private fun findInEndpoint(iface: UsbInterface): UsbEndpoint? =
        (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }

    private fun startPrint(job: Int) {
        if (busy) { log("Still printing — please wait."); return }
        if (job == 4) {
            if (loadedKind.isEmpty() || !File(cacheDir, "current.file").exists()) {
                log("Nothing loaded. Tap 'Choose PDF or picture' first."); return
            }
            jobPagesText = pagesInput.text.toString()
            jobDither = ditherBox.isChecked
        }
        val target = findTarget() ?: run { describeDevices(); return }
        if (!usb.hasPermission(target.device)) {
            log("Asking for USB permission. Tap OK in the pop-up.")
            pendingJob = job
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(actionPermission).setPackage(packageName), flags)
            usb.requestPermission(target.device, pi); return
        }
        sendJob(job)
    }

    private fun openClaimed(target: Target): UsbDeviceConnection? {
        val conn = usb.openDevice(target.device) ?: run { log("Could not open USB device."); return null }
        if (!conn.claimInterface(target.iface, true)) { log("Could not claim printer interface."); conn.close(); return null }
        if (!conn.setInterface(target.iface)) log("Warning: setInterface failed.")
        log("Using iface ${target.iface.id} alt ${target.iface.alternateSetting} (proto ${target.iface.interfaceProtocol}).")
        return conn
    }

    private fun writeAll(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            val len = minOf(16 * 1024, data.size - offset)
            val n = conn.bulkTransfer(ep, data, offset, len, 10_000)
            if (n < 0) { log("Transfer failed at byte $offset. Is the printer on and loaded with paper?"); return false }
            offset += n
        }
        return true
    }

    private fun waitUntilPrinterCaughtUp(conn: UsbDeviceConnection, target: Target) {
        val inEp = findInEndpoint(target.iface) ?: run {
            log("One-way mode: waiting 8 s."); Thread.sleep(8000); return
        }
        if (!writeAll(conn, target.out, PclDriver.PJL_INFO_ID)) return
        val buf = ByteArray(512); val end = System.currentTimeMillis() + 30_000; var answered = false
        while (System.currentTimeMillis() < end) {
            if (conn.bulkTransfer(inEp, buf, buf.size, 500) > 0) { answered = true; break }
        }
        if (!answered) log("Printer did not answer within 30 s.")
        Thread.sleep(1000)
    }

    private fun sendJob(job: Int) {
        // Capture UI selections on the UI thread before spinning up a background thread.
        val paper = selectedPaper
        val align = selectedAlign
        busy = true; setPrinting(true)
        thread {
            try {
                val target = findTarget() ?: run { log("Printer not found. Re-plug it and try again."); return@thread }
                val conn = openClaimed(target) ?: return@thread
                try {
                    when (job) {
                        3 -> askPrinter(conn, target)
                        4 -> if (loadedKind == "picture") printPictureJob(conn, target, paper, align)
                             else printPdfJob(conn, target, paper)
                        else -> {
                            val data = if (job == 1) PclDriver.textTestJob() else PclDriver.graphicsTestJob()
                            log("Sending ${data.size} bytes...")
                            if (writeAll(conn, target.out, data)) {
                                log("Sent. Waiting for printer..."); waitUntilPrinterCaughtUp(conn, target); log("Done.")
                            }
                        }
                    }
                } finally { conn.releaseInterface(target.iface); conn.close() }
            } finally { busy = false; setPrinting(false) }
        }
    }

    // ------------------------------------------------------------------ ask printer

    private fun askPrinter(conn: UsbDeviceConnection, target: Target) {
        val inEp = findInEndpoint(target.iface) ?: run {
            log("One-way mode: printer can't answer. Switch to protocol 2."); return
        }
        log("Asking printer for its name...")
        if (!writeAll(conn, target.out, PclDriver.PJL_INFO_ID)) return
        val buf = ByteArray(512); val sb = StringBuilder(); val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            val n = conn.bulkTransfer(inEp, buf, buf.size, 500)
            if (n > 0) sb.append(String(buf, 0, n, Charsets.ISO_8859_1)) else if (sb.isNotEmpty()) break
        }
        log(if (sb.isEmpty()) "No answer." else "Printer: ${sb.toString().replace("\r","").trim()}")
    }

    // ------------------------------------------------------------------ print PDF

    private fun printPdfJob(conn: UsbDeviceConnection, target: Target, paper: PclDriver.PaperSize) {
        val file = File(cacheDir, "current.file")
        var pfd: ParcelFileDescriptor? = null; var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pages = parsePages(jobPagesText, renderer.pageCount)
            if (pages.isEmpty()) { log("Those page numbers don't match this PDF (${renderer.pageCount} pages)."); return }
            log("Printing ${pages.size} page(s) on ${paper.name}...")
            if (!writeAll(conn, target.out, PclDriver.jobHeader(paper))) return
            for ((i, pageIndex) in pages.withIndex()) {
                log("Preparing page ${pageIndex + 1} (${i + 1}/${pages.size})...")
                val bitmap = renderPdfPage(renderer, pageIndex, paper)
                val data = PclDriver.bitmapToPage(bitmap, jobDither)
                if (!writeAll(conn, target.out, data)) return
                log("Sent page ${pageIndex + 1}.")
            }
            if (!writeAll(conn, target.out, PclDriver.JOB_FOOTER)) return
            log("All pages sent. Waiting for printer..."); waitUntilPrinterCaughtUp(conn, target); log("Done.")
        } catch (e: Throwable) { log("Print failed: ${e.message}") }
        finally { renderer?.close(); pfd?.close() }
    }

    /**
     * Render one PDF page scaled to fill the paper's printable area.
     * Scale is chosen to fit width AND height (no cropping, aspect ratio preserved).
     * The bitmap is exactly paper.widthDots × paper.heightDots so the PCL cursor
     * and page-size command match the actual paper in the printer.
     */
    private fun renderPdfPage(renderer: PdfRenderer, index: Int, paper: PclDriver.PaperSize): Bitmap {
        val pw = paper.widthDots; val ph = paper.heightDots
        val page = renderer.openPage(index)
        return try {
            // Scale to fit inside the paper area, maintaining aspect ratio.
            val scale = minOf(pw.toFloat() / page.width, ph.toFloat() / page.height)
            val imgW = maxOf(8, (page.width  * scale).toInt())
            val imgH = maxOf(1, (page.height * scale).toInt())
            // Create full-page canvas and center the content.
            val canvas = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            canvas.eraseColor(Color.WHITE)
            val offX = (pw - imgW) / 2
            val offY = (ph - imgH) / 2
            val dst = Rect(offX, offY, offX + imgW, offY + imgH)
            // Render into a temporary bitmap first, then draw onto the canvas.
            val tmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            tmp.eraseColor(Color.WHITE)
            page.render(tmp, null, Matrix().also { it.setScale(scale, scale) },
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            android.graphics.Canvas(canvas).drawBitmap(tmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            tmp.recycle()
            canvas
        } finally { page.close() }
    }

    // ------------------------------------------------------------------ print picture

    private fun printPictureJob(
        conn: UsbDeviceConnection, target: Target,
        paper: PclDriver.PaperSize, align: PclDriver.Align
    ) {
        try {
            log("Preparing picture on ${paper.name} (${align.name.replace('_', ' ')})...")
            val bitmap = loadPictureForPrint(File(cacheDir, "current.file"), paper, align)
                ?: run { log("Could not read that picture."); return }
            if (!writeAll(conn, target.out, PclDriver.jobHeader(paper))) return
            if (!writeAll(conn, target.out, PclDriver.bitmapToPage(bitmap, jobDither))) return
            if (!writeAll(conn, target.out, PclDriver.JOB_FOOTER)) return
            log("Sent. Waiting for printer..."); waitUntilPrinterCaughtUp(conn, target); log("Done.")
        } catch (e: Throwable) { log("Print failed: ${e.message}") }
    }

    /**
     * Load a picture, apply EXIF rotation, auto-rotate landscape images to portrait,
     * scale to fit the paper while preserving aspect ratio, and place it on a full-page
     * white canvas using the chosen alignment.
     */
    private fun loadPictureForPrint(
        file: File, paper: PclDriver.PaperSize, align: PclDriver.Align
    ): Bitmap? {
        val pw = paper.widthDots; val ph = paper.heightDots

        // 1. Read dimensions only.
        val b = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, b)
        if (b.outWidth <= 0) return null

        // 2. Subsample to avoid OOM (never below paper resolution).
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxOf(pw, ph)) sample *= 2
        val opts = BitmapFactory.Options().also { it.inSampleSize = sample; it.inPreferredConfig = Bitmap.Config.RGB_565 }
        var bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        // 3. EXIF rotation.
        var degrees = 0f
        try {
            degrees = when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {}

        // 4. Auto-rotate landscape images to use the page better.
        val sideways = degrees == 90f || degrees == 270f
        val visW = if (sideways) bmp.height else bmp.width
        val visH = if (sideways) bmp.width  else bmp.height
        if (visW > visH) degrees += 90f   // wide image → rotate 90° to fit tall page

        if (degrees % 360f != 0f) {
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                Matrix().also { it.postRotate(degrees) }, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }

        // 5. Scale to fit paper, preserving aspect ratio.
        val scale = minOf(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
        val imgW = maxOf(8, (bmp.width  * scale).toInt())
        val imgH = maxOf(1, (bmp.height * scale).toInt())

        // 6. Compute top-left corner based on alignment.
        val offX = when (align) {
            PclDriver.Align.TOP_LEFT,    PclDriver.Align.CENTER_LEFT,    PclDriver.Align.BOTTOM_LEFT   -> 0
            PclDriver.Align.TOP_CENTER,  PclDriver.Align.CENTER,         PclDriver.Align.BOTTOM_CENTER -> (pw - imgW) / 2
            PclDriver.Align.TOP_RIGHT,   PclDriver.Align.CENTER_RIGHT,   PclDriver.Align.BOTTOM_RIGHT  -> pw - imgW
        }
        val offY = when (align) {
            PclDriver.Align.TOP_LEFT,    PclDriver.Align.TOP_CENTER,    PclDriver.Align.TOP_RIGHT    -> 0
            PclDriver.Align.CENTER_LEFT, PclDriver.Align.CENTER,        PclDriver.Align.CENTER_RIGHT -> (ph - imgH) / 2
            PclDriver.Align.BOTTOM_LEFT, PclDriver.Align.BOTTOM_CENTER, PclDriver.Align.BOTTOM_RIGHT -> ph - imgH
        }

        // 7. Draw onto full-page white canvas.
        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bmp, null, Rect(offX, offY, offX + imgW, offY + imgH), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        bmp.recycle()
        return out
    }
}
