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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * Milestone 3: choose a PDF or a picture inside the app (no Share menu needed) and print it
 * to a PCL printer (HP LaserJet 1320) over USB OTG.
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

    // 0 = none, 1 = text test, 2 = graphics test, 3 = ask printer, 4 = print the loaded file
    private var pendingJob = 0
    private var preferredProtocol = 2 // 2 = two-way (the one that works), 1 = one-way, 3 = special
    private var jobPagesText = ""
    private var jobDither = false
    private var loadedKind = "" // "", "pdf" or "picture"

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    private val jobHeader = "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n\u001BE\u001B&u300D"
        .toByteArray(Charsets.ISO_8859_1)
    private val jobFooter = "\u001BE\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != actionPermission) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log(if (granted) "USB permission granted." else "USB permission DENIED. Tap the button again and choose OK.")
            val job = pendingJob
            pendingJob = 0
            if (granted && job != 0) sendJob(job)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        fileLabel = TextView(this).apply {
            text = "Nothing loaded yet. Tap 'Choose PDF or picture' below."
            textSize = 15f
        }
        val pickButton = Button(this).apply {
            text = "Choose PDF or picture"
            setOnClickListener { pickFile() }
        }
        pagesInput = EditText(this).apply {
            hint = "Pages, e.g. 1-3 (empty = all pages; PDFs only)"
        }
        ditherBox = CheckBox(this).apply {
            text = "Photo mode (for pictures; text looks rougher)"
        }
        val printButton = Button(this).apply {
            text = "Print"
            setOnClickListener { startPrint(4) }
        }
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
                preferredProtocol = when (preferredProtocol) {
                    2 -> 1
                    1 -> 3
                    else -> 2
                }
                log("USB mode is now protocol $preferredProtocol (2 = two-way, 1 = one-way, 3 = special). Use 2 normally.")
            }
        }
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        column.addView(fileLabel)
        column.addView(pickButton)
        column.addView(pagesInput)
        column.addView(ditherBox)
        column.addView(printButton)
        column.addView(scan)
        column.addView(textTest)
        column.addView(fullTest)
        column.addView(askTest)
        column.addView(modeButton)
        column.addView(logView)
        scrollView = ScrollView(this).apply { addView(column) }
        setContentView(scrollView)

        val filter = IntentFilter(actionPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
        log("Plug the printer in with the OTG cable and switch it on. Then choose a PDF or picture.")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun log(message: String) {
        runOnUiThread {
            logView.append(message + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private class Target(val device: UsbDevice, val iface: UsbInterface, val out: UsbEndpoint)

    // ------------------------------------------------------------ choosing a file

    @Suppress("DEPRECATION")
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
        }
        startActivityForResult(intent, pickRequest)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickRequest && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) loadFile(uri) else log("No file was chosen.")
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) loadFile(uri)
    }

    /** Copy the chosen file into the app's own storage, then work out whether it is a PDF or a picture. */
    private fun loadFile(uri: Uri) {
        log("Loading the file...")
        thread {
            try {
                val file = File(cacheDir, "current.file")
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    log("Could not open that file.")
                    return@thread
                }
                input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }

                val magic = ByteArray(5)
                val got = file.inputStream().use { it.read(magic) }
                val isPdf = got == 5 && String(magic, Charsets.ISO_8859_1) == "%PDF-"
                if (isPdf) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    val count = renderer.pageCount
                    renderer.close()
                    pfd.close()
                    loadedKind = "pdf"
                    runOnUiThread {
                        fileLabel.text = "Loaded: PDF, $count page(s)."
                        ditherBox.isChecked = false
                    }
                    log("PDF loaded: $count page(s). Choose pages if you like, then tap 'Print'.")
                } else {
                    val bounds = BitmapFactory.Options()
                    bounds.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        loadedKind = ""
                        log("That file is not a PDF or a picture hprint can read.")
                        return@thread
                    }
                    loadedKind = "picture"
                    runOnUiThread {
                        fileLabel.text = "Loaded: picture, ${bounds.outWidth} x ${bounds.outHeight}."
                        ditherBox.isChecked = true
                    }
                    log("Picture loaded. Photo mode is switched on. Tap 'Print' when ready.")
                }
            } catch (e: Throwable) {
                loadedKind = ""
                log("Could not read that file: ${e.message}")
            }
        }
    }

    /** "" = all pages, "3" = page 3, "2-5" = pages 2 to 5. Returns zero-based page numbers. */
    private fun parsePages(text: String, count: Int): List<Int> {
        val t = text.trim()
        if (t.isEmpty()) return (0 until count).toList()
        val parts = t.split("-")
        val a = parts[0].trim().toIntOrNull()
        val b = if (parts.size > 1) parts[1].trim().toIntOrNull() else a
        if (a == null || b == null) return emptyList()
        val from = maxOf(1, minOf(a, b))
        val to = minOf(count, maxOf(a, b))
        if (from > to) return emptyList()
        return (from - 1 until to).toList()
    }

    // --------------------------------------------------------------- USB

    /** List every USB device and interface, so we can see exactly what the printer offers. */
    private fun describeDevices() {
        val devices = usb.deviceList.values
        if (devices.isEmpty()) {
            log("No USB device found. Check the OTG cable, and that the printer is switched on.")
            return
        }
        for (d in devices) {
            log("Device: ${d.productName ?: "?"} / ${d.manufacturerName ?: "?"}  " +
                "vid=0x%04x pid=0x%04x".format(d.vendorId, d.productId))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                log("  interface ${itf.id} alt ${itf.alternateSetting}: class=${itf.interfaceClass} " +
                    "sub=${itf.interfaceSubclass} proto=${itf.interfaceProtocol} endpoints=${itf.endpointCount}")
            }
        }
        val t = findTarget()
        if (t != null) {
            log("Will use interface ${t.iface.id} alt ${t.iface.alternateSetting} " +
                "(protocol ${t.iface.interfaceProtocol}). Ready.")
        } else {
            log("No usable printer interface (class 7 with a bulk-out endpoint). Send this log to Claude.")
        }
    }

    /** Choose the printer interface, preferring the protocol picked with 'Switch USB mode'. */
    private fun findTarget(): Target? {
        var best: Target? = null
        var bestScore = -1
        for (d in usb.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                val score = if (itf.interfaceProtocol == preferredProtocol) {
                    10
                } else {
                    when (itf.interfaceProtocol) {
                        2 -> 3
                        1 -> 2
                        else -> 1
                    }
                }
                if (score <= bestScore) continue
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        best = Target(d, itf, ep)
                        bestScore = score
                        break
                    }
                }
            }
        }
        return best
    }

    /** The bulk-in endpoint of an interface (where the printer's answers come from), if any. */
    private fun findInEndpoint(iface: UsbInterface): UsbEndpoint? {
        var found: UsbEndpoint? = null
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_IN
            ) {
                found = ep
            }
        }
        return found
    }

    private fun startPrint(job: Int) {
        if (job == 4) {
            if (loadedKind.isEmpty() || !File(cacheDir, "current.file").exists()) {
                log("Nothing loaded yet. Tap 'Choose PDF or picture' first.")
                return
            }
            jobPagesText = pagesInput.text.toString()
            jobDither = ditherBox.isChecked
        }
        val target = findTarget()
        if (target == null) {
            describeDevices()
            return
        }
        if (!usb.hasPermission(target.device)) {
            log("Asking Android for permission to use the printer. Tap OK in the pop-up.")
            pendingJob = job
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(actionPermission).setPackage(packageName), flags
            )
            usb.requestPermission(target.device, pi)
            return
        }
        sendJob(job)
    }

    /** Open the printer and switch it to the chosen USB mode. Returns null on failure. */
    private fun openClaimed(target: Target): UsbDeviceConnection? {
        val conn = usb.openDevice(target.device)
        if (conn == null) {
            log("Could not open the device (permission problem?).")
            return null
        }
        if (!conn.claimInterface(target.iface, true)) {
            log("Could not claim the printer interface.")
            conn.close()
            return null
        }
        if (!conn.setInterface(target.iface)) {
            log("Warning: could not switch to alternate setting ${target.iface.alternateSetting}.")
        }
        log("Using interface ${target.iface.id} alt ${target.iface.alternateSetting} " +
            "(protocol ${target.iface.interfaceProtocol}).")
        return conn
    }

    /** Write all bytes to the printer in chunks. Returns false if anything fails. */
    private fun writeAll(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray): Boolean {
        var offset = 0
        val chunk = 16 * 1024
        while (offset < data.size) {
            val len = minOf(chunk, data.size - offset)
            val n = conn.bulkTransfer(ep, data, offset, len, 10_000)
            if (n < 0) {
                log("Transfer failed at byte $offset. Is the printer on and does it have paper?")
                return false
            }
            offset += n
        }
        return true
    }

    /**
     * Closing the USB connection right after sending makes Android reset the printer's USB
     * buffer, which throws away data the printer has not read yet. So at the end of a job
     * we ask the printer a question and wait for the answer: it only answers after it has
     * read everything that came before the question.
     */
    private fun waitUntilPrinterCaughtUp(conn: UsbDeviceConnection, target: Target) {
        val inEndpoint = findInEndpoint(target.iface)
        if (inEndpoint == null) {
            log("One-way USB mode: cannot tell when the printer is done, so waiting 8 seconds.")
            Thread.sleep(8000)
            return
        }
        val question = "\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)
        if (!writeAll(conn, target.out, question)) return
        val buf = ByteArray(512)
        val end = System.currentTimeMillis() + 30_000
        var answered = false
        while (System.currentTimeMillis() < end) {
            val n = conn.bulkTransfer(inEndpoint, buf, buf.size, 500)
            if (n > 0) {
                answered = true
                break
            }
        }
        if (!answered) log("The printer did not answer within 30 seconds.")
        Thread.sleep(1000)
    }

    /** Run a job on a background thread. */
    private fun sendJob(job: Int) {
        thread {
            val target = findTarget()
            if (target == null) {
                log("Printer not found. Re-plug it and try again.")
                return@thread
            }
            val conn = openClaimed(target) ?: return@thread
            try {
                when (job) {
                    3 -> askPrinter(conn, target)
                    4 -> {
                        if (loadedKind == "picture") {
                            printPictureJob(conn, target)
                        } else {
                            printPdfJob(conn, target)
                        }
                    }
                    else -> {
                        val data = if (job == 1) buildTextJob() else buildFullJob()
                        log("Sending ${data.size} bytes...")
                        if (writeAll(conn, target.out, data)) {
                            log("Sent. Waiting for the printer to finish reading the job...")
                            waitUntilPrinterCaughtUp(conn, target)
                            log("Done. A page should be coming out now.")
                        }
                    }
                }
            } finally {
                conn.releaseInterface(target.iface)
                conn.close()
            }
        }
    }

    /** Ask the printer for its name using PJL and read the answer from the IN endpoint. */
    private fun askPrinter(conn: UsbDeviceConnection, target: Target) {
        val inEndpoint = findInEndpoint(target.iface) ?: run {
            log("This USB mode is one-way, so the printer cannot answer. Tap 'Switch USB mode' until it says protocol 2.")
            return
        }
        val question = "\u001B%-12345X@PJL INFO ID\r\n\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)
        log("Asking the printer for its name...")
        if (!writeAll(conn, target.out, question)) return

        val buf = ByteArray(512)
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            val n = conn.bulkTransfer(inEndpoint, buf, buf.size, 500)
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            } else if (sb.isNotEmpty()) {
                break
            }
        }
        val reply = sb.toString().replace("\r", "").replace("\u000C", "").trim()
        if (reply.isEmpty()) {
            log("No answer from the printer.")
        } else {
            log("Printer answered: $reply")
        }
    }

    // ------------------------------------------------------------- printing a PDF

    private fun printPdfJob(conn: UsbDeviceConnection, target: Target) {
        val file = File(cacheDir, "current.file")
        if (!file.exists()) {
            log("Nothing loaded. Choose a PDF first.")
            return
        }
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pages = parsePages(jobPagesText, renderer.pageCount)
            if (pages.isEmpty()) {
                log("Those page numbers do not match this PDF (it has ${renderer.pageCount} pages).")
                return
            }
            log("Printing ${pages.size} page(s)...")
            if (!writeAll(conn, target.out, jobHeader)) return
            for ((i, pageIndex) in pages.withIndex()) {
                log("Preparing page ${pageIndex + 1} (${i + 1} of ${pages.size})...")
                val data = renderPageToPcl(renderer, pageIndex, jobDither)
                if (!writeAll(conn, target.out, data)) return
                log("Sent page ${pageIndex + 1}.")
            }
            if (!writeAll(conn, target.out, jobFooter)) return
            log("All pages sent. Waiting for the printer to finish reading...")
            waitUntilPrinterCaughtUp(conn, target)
            log("Done.")
        } catch (e: Throwable) {
            log("Printing failed: ${e.message}")
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    /** Draw one PDF page, scaled to fit the printer's printable area, and turn it into PCL. */
    private fun renderPageToPcl(renderer: PdfRenderer, index: Int, dither: Boolean): ByteArray {
        val maxW = 2376 // dots at 300 dpi, a multiple of 8
        val maxH = 3400
        val page = renderer.openPage(index)
        val bitmap: Bitmap = try {
            val scale = minOf(maxW.toFloat() / page.width, maxH.toFloat() / page.height)
            val bw = maxOf(8, minOf(maxW, (page.width * scale).toInt()))
            val bh = maxOf(1, minOf(maxH, (page.height * scale).toInt()))
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setScale(scale, scale)
            page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            b
        } finally {
            page.close()
        }
        return bitmapToPcl(bitmap, dither)
    }

    // ------------------------------------------------------------- printing a picture

    private fun printPictureJob(conn: UsbDeviceConnection, target: Target) {
        try {
            log("Preparing the picture...")
            val bitmap = loadPictureForPrint(File(cacheDir, "current.file")) ?: run {
                log("Could not read that picture.")
                return
            }
            val data = bitmapToPcl(bitmap, jobDither)
            if (!writeAll(conn, target.out, jobHeader)) return
            if (!writeAll(conn, target.out, data)) return
            if (!writeAll(conn, target.out, jobFooter)) return
            log("Sent. Waiting for the printer to finish reading...")
            waitUntilPrinterCaughtUp(conn, target)
            log("Done.")
        } catch (e: Throwable) {
            log("Printing failed: ${e.message}")
        }
    }

    /** Load a picture, apply the camera's rotation, and fit it onto a white page-sized bitmap. */
    private fun loadPictureForPrint(file: File): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Load it at about the size we need, to save memory.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 3400) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        opts.inPreferredConfig = Bitmap.Config.RGB_565
        var bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        // Respect the camera's rotation flag, if there is one.
        var degrees = 0f
        try {
            val exif = ExifInterface(file.absolutePath)
            degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            // no rotation information; carry on
        }
        val sideways = degrees == 90f || degrees == 270f
        val shownW = if (sideways) bmp.height else bmp.width
        val shownH = if (sideways) bmp.width else bmp.height
        if (shownW > shownH) degrees += 90f // wide pictures use the tall page better when turned
        if (degrees % 360f != 0f) {
            val m = Matrix()
            m.postRotate(degrees)
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }

        val scale = minOf(2376f / bmp.width, 3400f / bmp.height)
        val w = maxOf(8, (bmp.width * scale).toInt())
        val h = maxOf(1, (bmp.height * scale).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()
        return out
    }

    // ------------------------------------------------------------- bitmap -> PCL

    /** Turn a bitmap into one printed page of PCL raster graphics at 300 dpi. */
    private fun bitmapToPcl(bitmap: Bitmap, dither: Boolean): ByteArray {
        val bw = bitmap.width
        val bh = bitmap.height
        val bytesPerRow = (bw + 7) / 8
        val out = ByteArrayOutputStream(bh * (bytesPerRow + 8) + 64)
        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        put("\u001B*p0x0Y")      // top-left of the printable area
        put("\u001B*t300R")      // raster resolution: 300 dpi
        put("\u001B*r1A")        // start raster graphics
        put("\u001B*b0M")        // no compression
        val rowHeader = "\u001B*b${bytesPerRow}W".toByteArray(Charsets.ISO_8859_1)
        val pixels = IntArray(bw)
        val row = ByteArray(bytesPerRow)
        for (y in 0 until bh) {
            bitmap.getPixels(pixels, 0, bw, 0, y, bw, 1)
            row.fill(0)
            for (x in 0 until bw) {
                val p = pixels[x]
                val lum = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                val threshold = if (dither) bayer[(y and 3) * 4 + (x and 3)] * 16 + 8 else 128
                if (lum < threshold) {
                    val idx = x shr 3
                    row[idx] = (row[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
            out.write(rowHeader)
            out.write(row)
        }
        bitmap.recycle()
        put("\u001B*rB")         // end raster graphics
        put("\u000C")            // eject the page
        return out.toByteArray()
    }

    // ------------------------------------------------------------- PCL test jobs
    // PCL commands start with the ESC character (\u001B).
    // Each job is wrapped in a PJL header/footer ("\u001B%-12345X" is the standard job delimiter).

    /** Simplest possible job: a few lines of text, then eject the page. */
    private fun buildTextJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        put("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        put("\u001BE")        // reset printer
        put("\u001B&k2G")     // line ending: CR+LF
        put("hprint text test\n")
        put("HP LaserJet 1320 over USB OTG\n")
        put("If you can read this, the phone-to-printer link works.\n")
        put("\u000C")         // form feed: eject page
        put("\u001BE")        // reset
        put("\u001B%-12345X")
        return out.toByteArray()
    }

    /** Text plus a 2-inch box with a diagonal line, drawn as a raster graphic at 300 dpi. */
    private fun buildFullJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        put("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        put("\u001BE")
        put("\u001B&k2G")
        put("\u001B&u300D")   // units: 300 per inch
        put("hprint test page\n")
        put("HP LaserJet 1320. Raster graphics test below.\n")

        val size = 600                 // 600 dots = 2 inches at 300 dpi
        val bytesPerRow = size / 8     // 75 bytes
        put("\u001B*p150x500Y")        // move to x=150, y=500 (dots)
        put("\u001B*t300R")            // raster resolution: 300 dpi
        put("\u001B*r1A")              // start raster graphics at cursor
        put("\u001B*b0M")              // no compression
        val row = ByteArray(bytesPerRow)
        for (y in 0 until size) {
            row.fill(0)
            if (y < 6 || y >= size - 6) {
                row.fill(0xFF.toByte())                  // top and bottom border
            } else {
                row[0] = 0xFF.toByte()                   // left border
                row[bytesPerRow - 1] = 0xFF.toByte()     // right border
                val idx = y / 8
                row[idx] = (row[idx].toInt() or (0x80 ushr (y % 8))).toByte() // diagonal
            }
            put("\u001B*b${bytesPerRow}W")
            out.write(row)
        }
        put("\u001B*rB")               // end raster graphics
        put("\u000C")
        put("\u001BE")
        put("\u001B%-12345X")
        return out.toByteArray()
    }
}
