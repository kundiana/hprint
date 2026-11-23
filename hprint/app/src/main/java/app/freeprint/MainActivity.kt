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

import android.app.Activity
import android.app.AlertDialog
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
import android.graphics.drawable.GradientDrawable
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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val actionPermission = "app.freeprint.USB_PERMISSION"
    private val pickRequest = 42
    private val usbChunkSize = 4096

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var usb: UsbManager
    private lateinit var statusView: TextView
    private lateinit var previewImage: ImageView
    private lateinit var previewHint: TextView
    private lateinit var settingsSummaryView: TextView
    private lateinit var pagesInput: EditText
    private lateinit var printButton: Button

    private val logBuilder = StringBuilder()
    @Volatile private var busy = false

    private var pendingPrintAction = false
    private var preferredProtocol = 2
    private var loadedKind = "" // "", "pdf", "picture"
    private var pdfPageCount = 0

    private var selectedPaper: PclDriver.PaperSize = PclDriver.PAPER_SIZES[0]
    private var selectedAlign: PclDriver.Align = PclDriver.Align.CENTER
    private var photoMode: Boolean = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != actionPermission) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log(if (granted) "USB permission granted." else "USB permission denied.")
            refreshUsbStatus()
            val shouldRun = pendingPrintAction
            pendingPrintAction = false
            if (granted && shouldRun) {
                beginRenderAndPrint()
            }
        }
    }

    private val usbAttachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("USB device attached.")
                    refreshUsbStatus()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("USB device detached.")
                    refreshUsbStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = "HPrint"
            textSize = 24f
            setTextColor(Color.parseColor("#111827"))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val diagButton = Button(this).apply {
            text = "Diagnostics"
            setOnClickListener { showDiagnosticsDialog() }
        }
        val aboutButton = Button(this).apply {
            text = "About"
            setOnClickListener { showAboutDialog() }
        }
        topBar.addView(titleView)
        topBar.addView(diagButton)
        topBar.addView(aboutButton)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 16)
        }
        statusView = TextView(this).apply {
            text = "No Printer Detected"
            textSize = 15f
            setTextColor(Color.parseColor("#374151"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val chooseButton = Button(this).apply {
            text = "Choose File"
            setOnClickListener { pickFile() }
        }
        statusRow.addView(statusView)
        statusRow.addView(chooseButton)

        val previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(3, Color.parseColor("#CBD5E1"))
                cornerRadius = 12f
            }
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        previewHint = TextView(this).apply {
            text = "First-page preview\nTap \"Choose File\" to load a PDF or image"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B7280"))
        }
        previewContainer.addView(previewImage)
        previewContainer.addView(previewHint)

        pagesInput = EditText(this).apply {
            hint = "Pages (e.g. 1-3, empty = all)"
            textSize = 14f
            visibility = View.GONE
            setPadding(0, 12, 0, 4)
        }

        settingsSummaryView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#1D4ED8"))
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
            setOnClickListener { showSettingsDialog() }
            updateSettingsSummary()
        }

        printButton = Button(this).apply {
            text = "Print"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1D4ED8"))
            minimumHeight = 140
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onPrintClicked() }
        }

        root.addView(topBar)
        root.addView(statusRow)
        root.addView(previewContainer)
        root.addView(pagesInput)
        root.addView(settingsSummaryView)
        root.addView(printButton)
        setContentView(root)

        registerReceivers()
        refreshUsbStatus()
        log("Ready. Choose a PDF or image and connect printer via USB OTG.")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshUsbStatus()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(permissionReceiver)
            unregisterReceiver(usbAttachDetachReceiver)
        } catch (_: IllegalArgumentException) {
        }
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun registerReceivers() {
        val permFilter = IntentFilter(actionPermission)
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachDetachReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, permFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachDetachReceiver, usbFilter)
        }
    }

    private fun updateSettingsSummary() {
        val modeLabel = if (photoMode) "Photo" else "B&W"
        settingsSummaryView.text = "${selectedPaper.name} · ${selectedAlign.label} · $modeLabel"
    }

    private fun setBusy(active: Boolean) {
        busy = active
        runOnUiThread {
            printButton.isEnabled = !active
        }
    }

    private fun log(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(logBuilder) {
            logBuilder.append("[$stamp] $msg\n")
        }
    }

    private fun getLogText(): String = synchronized(logBuilder) { logBuilder.toString() }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }

        val paperLabel = TextView(this).apply {
            text = "Paper Size"
            paint.isFakeBoldText = true
            setPadding(0, 8, 0, 8)
        }
        val paperGroup = RadioGroup(this).apply {
            PclDriver.PAPER_SIZES.forEachIndexed { index, paper ->
                addView(RadioButton(this@MainActivity).apply {
                    id = index
                    text = paper.name
                    isChecked = paper.name == selectedPaper.name
                })
            }
        }

        val alignLabel = TextView(this).apply {
            text = "Image Alignment"
            paint.isFakeBoldText = true
            setPadding(0, 20, 0, 8)
        }
        val alignGroup = RadioGroup(this).apply {
            PclDriver.Align.values().forEachIndexed { index, align ->
                addView(RadioButton(this@MainActivity).apply {
                    id = index
                    text = align.label
                    isChecked = align == selectedAlign
                })
            }
        }

        val photoCheck = CheckBox(this).apply {
            text = "Photo mode (Floyd-Steinberg dithering)"
            isChecked = photoMode
            setPadding(0, 20, 0, 0)
        }

        container.addView(paperLabel)
        container.addView(paperGroup)
        container.addView(alignLabel)
        container.addView(alignGroup)
        container.addView(photoCheck)

        val scroll = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Print Settings")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                val pIdx = paperGroup.checkedRadioButtonId
                if (pIdx in PclDriver.PAPER_SIZES.indices) {
                    selectedPaper = PclDriver.PAPER_SIZES[pIdx]
                }
                val aIdx = alignGroup.checkedRadioButtonId
                if (aIdx in PclDriver.Align.values().indices) {
                    selectedAlign = PclDriver.Align.values()[aIdx]
                }
                photoMode = photoCheck.isChecked
                updateSettingsSummary()
                if (loadedKind.isNotEmpty()) {
                    renderFirstPagePreview(File(cacheDir, "current.file"))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDiagnosticsDialog() {
        val text = getLogText().ifEmpty { "No diagnostic entries yet." }
        val scroll = ScrollView(this).apply {
            setPadding(36, 24, 36, 12)
            addView(TextView(this@MainActivity).apply {
                text = text
                textSize = 12f
                setTextIsSelectable(true)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setView(scroll)
            .setPositiveButton("Save .txt") { _, _ -> saveLogToTxt() }
            .setNeutralButton("Clear") { _, _ ->
                synchronized(logBuilder) { logBuilder.clear() }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun saveLogToTxt() {
        ioExecutor.execute {
            try {
                val outFile = File(cacheDir, "hprint_diagnostics_${System.currentTimeMillis()}.txt")
                outFile.writeText(getLogText(), Charsets.UTF_8)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Diagnostics Saved")
                        .setMessage("Saved log to:\n${outFile.absolutePath}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Throwable) {
                log("Failed to save log: ${e.message}")
            }
        }
    }

    private fun showAboutDialog() {
        val message = """
            HPrint — Unofficial USB OTG PCL Print App
            Copyright (C) 2026 kundiana

            License: GNU General Public License v2 or later (GPL-2.0-or-later).
            Contact / Project: https://github.com/kundiana/hprint

            Disclaimer: Provided "AS IS" without warranty of any kind. Test on spare paper first; verify printer compatibility and spool behavior before unattended jobs.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("About HPrint")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
            },
            pickRequest
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickRequest && resultCode == RESULT_OK) {
            data?.data?.let { loadFile(it) }
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
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let { loadFile(it) }
    }

    private fun loadFile(uri: Uri) {
        if (busy) return
        setBusy(true)
        ioExecutor.execute {
            try {
                val targetFile = File(cacheDir, "current.file")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                } ?: run {
                    log("Unable to open selected URI.")
                    runOnUiThread { previewHint.text = "Could not open file." }
                    return@execute
                }

                val magic = ByteArray(5)
                val read = targetFile.inputStream().use { it.read(magic) }
                if (read == 5 && String(magic, Charsets.ISO_8859_1) == "%PDF-") {
                    ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            pdfPageCount = renderer.pageCount
                        }
                    }
                    loadedKind = "pdf"
                    log("Loaded PDF ($pdfPageCount page(s)).")
                    runOnUiThread {
                        pagesInput.visibility = if (pdfPageCount > 1) View.VISIBLE else View.GONE
                    }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(targetFile.absolutePath, opts)
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                        loadedKind = ""
                        log("Unsupported file format.")
                        runOnUiThread {
                            previewImage.visibility = View.GONE
                            previewHint.visibility = View.VISIBLE
                            previewHint.text = "Unsupported file format."
                        }
                        return@execute
                    }
                    loadedKind = "picture"
                    pdfPageCount = 0
                    log("Loaded picture (${opts.outWidth}x${opts.outHeight}).")
                    runOnUiThread { pagesInput.visibility = View.GONE }
                }

                renderFirstPagePreview(targetFile)
            } catch (e: Throwable) {
                loadedKind = ""
                log("Load failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun renderFirstPagePreview(file: File) {
        if (!file.exists() || loadedKind.isEmpty()) return
        ioExecutor.execute {
            try {
                val thumb: Bitmap? = when (loadedKind) {
                    "pdf" -> {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            PdfRenderer(pfd).use { renderer ->
                                if (renderer.pageCount <= 0) null
                                else renderer.openPage(0).use { page ->
                                    val w = 720
                                    val h = ((w.toFloat() / page.width) * page.height).roundToInt().coerceAtLeast(1)
                                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                                        bmp.eraseColor(Color.WHITE)
                                        page.render(
                                            bmp,
                                            null,
                                            null,
                                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "picture" -> loadPictureForPrint(file, selectedPaper, selectedAlign)
                    else -> null
                }

                runOnUiThread {
                    if (thumb != null) {
                        previewHint.visibility = View.GONE
                        previewImage.visibility = View.VISIBLE
                        previewImage.setImageBitmap(thumb)
                    } else {
                        previewImage.visibility = View.GONE
                        previewHint.visibility = View.VISIBLE
                        previewHint.text = "Preview unavailable."
                    }
                }
            } catch (e: Throwable) {
                log("Preview error: ${e.message}")
            }
        }
    }

    private data class UsbTarget(
        val device: UsbDevice,
        val iface: UsbInterface,
        val outEndpoint: UsbEndpoint
    )

    private fun findTarget(): UsbTarget? {
        var best: UsbTarget? = null
        var bestScore = -1
        for (device in usb.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                val score = when {
                    iface.interfaceProtocol == preferredProtocol -> 10
                    iface.interfaceProtocol == 2 -> 4
                    iface.interfaceProtocol == 1 -> 3
                    else -> 1
                }
                if (score <= bestScore) continue
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        best = UsbTarget(device, iface, ep)
                        bestScore = score
                        break
                    }
                }
            }
        }
        return best
    }

    private fun refreshUsbStatus() {
        val target = findTarget()
        runOnUiThread {
            if (target == null) {
                statusView.text = "No Printer Detected"
                statusView.setTextColor(Color.parseColor("#B91C1C"))
            } else {
                val name = target.device.productName?.takeIf { it.isNotBlank() }
                    ?: "USB Printer (%04X:%04X)".format(target.device.vendorId, target.device.productId)
                val permTag = if (usb.hasPermission(target.device)) "" else " (Permission needed)"
                statusView.text = "Connected: $name$permTag"
                statusView.setTextColor(Color.parseColor("#15803D"))
            }
        }
    }

    private fun onPrintClicked() {
        if (busy) return
        val sourceFile = File(cacheDir, "current.file")
        if (loadedKind.isEmpty() || !sourceFile.exists()) {
            log("No document loaded.")
            AlertDialog.Builder(this)
                .setMessage("Choose a PDF or image first.")
                .setPositiveButton("Choose File") { _, _ -> pickFile() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val target = findTarget()
        if (target == null) {
            refreshUsbStatus()
            log("No USB printer found.")
            return
        }

        if (!usb.hasPermission(target.device)) {
            pendingPrintAction = true
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this,
                0,
                Intent(actionPermission).setPackage(packageName),
                flags
            )
            usb.requestPermission(target.device, pi)
            return
        }

        beginRenderAndPrint()
    }

    private fun beginRenderAndPrint() {
        val paper = selectedPaper
        val align = selectedAlign
        val dither = photoMode
        val kind = loadedKind
        val pageRangeStr = pagesInput.text.toString()

        setBusy(true)
        ioExecutor.execute {
            val spoolFile = File(cacheDir, "job_spool.pcl")
            try {
                val t0 = System.nanoTime()
                val sourceFile = File(cacheDir, "current.file")
                val renderedPages = renderJobToSpoolFile(
                    sourceFile = sourceFile,
                    spoolFile = spoolFile,
                    kind = kind,
                    paper = paper,
                    align = align,
                    dither = dither,
                    pageRangeStr = pageRangeStr
                )
                if (renderedPages <= 0) {
                    log("Nothing rendered (check page range).")
                    return@execute
                }
                val renderMs = (System.nanoTime() - t0) / 1_000_000
                log("Spooled $renderedPages page(s) (${spoolFile.length()} bytes) in ${renderMs} ms.")

                val target = findTarget() ?: run {
                    log("Printer disconnected before USB transfer.")
                    refreshUsbStatus()
                    return@execute
                }
                val t1 = System.nanoTime()
                val sentBytes = sendSpoolFileOverUsb(target, spoolFile)
                val sendMs = (System.nanoTime() - t1) / 1_000_000
                if (sentBytes >= 0) {
                    log("Sent $sentBytes bytes in ${sendMs} ms. Connection closed.")
                }
            } catch (e: Throwable) {
                log("Print error: ${e.message}")
            } finally {
                if (spoolFile.exists()) spoolFile.delete()
                setBusy(false)
                refreshUsbStatus()
            }
        }
    }

    private fun renderJobToSpoolFile(
        sourceFile: File,
        spoolFile: File,
        kind: String,
        paper: PclDriver.PaperSize,
        align: PclDriver.Align,
        dither: Boolean,
        pageRangeStr: String
    ): Int {
        BufferedOutputStream(FileOutputStream(spoolFile), 16384).use { spoolOut ->
            when (kind) {
                "pdf" -> {
                    ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            val indices = parsePages(pageRangeStr, renderer.pageCount)
                            if (indices.isEmpty()) return 0
                            PclDriver.writeJobHeader(spoolOut, paper)
                            for (idx in indices) {
                                val pageBmp = renderPdfPage(renderer, idx, paper)
                                PclDriver.writePageToStream(pageBmp, dither, spoolOut)
                            }
                            PclDriver.writeJobFooter(spoolOut)
                            spoolOut.flush()
                            return indices.size
                        }
                    }
                }
                "picture" -> {
                    val bmp = loadPictureForPrint(sourceFile, paper, align) ?: return 0
                    PclDriver.writeJobHeader(spoolOut, paper)
                    PclDriver.writePageToStream(bmp, dither, spoolOut)
                    PclDriver.writeJobFooter(spoolOut)
                    spoolOut.flush()
                    return 1
                }
                else -> return 0
            }
        }
    }

    private fun sendSpoolFileOverUsb(target: UsbTarget, spoolFile: File): Long {
        val conn: UsbDeviceConnection = usb.openDevice(target.device) ?: run {
            log("usb.openDevice returned null.")
            return -1L
        }
        var totalSent = 0L
        try {
            if (!conn.claimInterface(target.iface, true)) {
                log("Failed to claim USB interface ${target.iface.id}.")
                return -1L
            }
            conn.setInterface(target.iface)

            val buf = ByteArray(usbChunkSize)
            BufferedInputStream(FileInputStream(spoolFile), usbChunkSize * 2).use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    var offset = 0
                    while (offset < read) {
                        val toSend = read - offset
                        val written = conn.bulkTransfer(
                            target.outEndpoint,
                            buf,
                            offset,
                            toSend,
                            10_000
                        )
                        if (written <= 0) {
                            log("USB bulkTransfer failed at byte $totalSent (ret=$written).")
                            return -1L
                        }
                        offset += written
                        totalSent += written
                    }
                }
            }
            return totalSent
        } finally {
            try {
                conn.releaseInterface(target.iface)
            } catch (_: Throwable) {
            }
            conn.close()
        }
    }

    private fun parsePages(text: String, count: Int): List<Int> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return (0 until count).toList()
        val parts = trimmed.split("-")
        val a = parts[0].trim().toIntOrNull() ?: return emptyList()
        val b = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: return emptyList() else a
        val from = minOf(a, b).coerceIn(1, count)
        val to = maxOf(a, b).coerceIn(1, count)
        if (from > to) return emptyList()
        return (from - 1 until to).toList()
    }

    private fun renderPdfPage(renderer: PdfRenderer, index: Int, paper: PclDriver.PaperSize): Bitmap {
        val pw = paper.widthDots
        val ph = paper.heightDots
        return renderer.openPage(index).use { page ->
            val scale = minOf(pw.toFloat() / page.width, ph.toFloat() / page.height)
            val imgW = (page.width * scale).roundToInt().coerceIn(1, pw)
            val imgH = (page.height * scale).roundToInt().coerceIn(1, ph)

            val canvasBmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBmp)
            canvas.drawColor(Color.WHITE)

            val offX = ((pw - imgW) / 2).coerceIn(0, (pw - imgW).coerceAtLeast(0))
            val offY = ((ph - imgH) / 2).coerceIn(0, (ph - imgH).coerceAtLeast(0))
            val dst = Rect(offX, offY, offX + imgW, offY + imgH)

            val pageBmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            pageBmp.eraseColor(Color.WHITE)
            page.render(
                pageBmp,
                null,
                Matrix().apply { setScale(scale, scale) },
                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
            )
            canvas.drawBitmap(pageBmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            pageBmp.recycle()
            canvasBmp
        }
    }

    private fun loadPictureForPrint(
        file: File,
        paper: PclDriver.PaperSize,
        align: PclDriver.Align
    ): Bitmap? {
        val pw = paper.widthDots
        val ph = paper.heightDots

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxOf(pw, ph)) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

        var rotation = 0f
        try {
            rotation = when (
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Throwable) {
        }

        val isSidewaysExif = rotation == 90f || rotation == 270f
        val orientedW = if (isSidewaysExif) bmp.height else bmp.width
        val orientedH = if (isSidewaysExif) bmp.width else bmp.height
        if (orientedW > orientedH) {
            rotation += 90f
        }

        val modRot = ((rotation % 360f) + 360f) % 360f
        if (modRot != 0f) {
            val rotated = Bitmap.createBitmap(
                bmp,
                0,
                0,
                bmp.width,
                bmp.height,
                Matrix().apply { postRotate(modRot) },
                true
            )
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }

        val scale = minOf(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
        val imgW = (bmp.width * scale).roundToInt().coerceIn(1, pw)
        val imgH = (bmp.height * scale).roundToInt().coerceIn(1, ph)

        val maxX = (pw - imgW).coerceAtLeast(0)
        val maxY = (ph - imgH).coerceAtLeast(0)

        val offX = when (align) {
            PclDriver.Align.TOP_LEFT, PclDriver.Align.CENTER_LEFT, PclDriver.Align.BOTTOM_LEFT -> 0
            PclDriver.Align.TOP_CENTER, PclDriver.Align.CENTER, PclDriver.Align.BOTTOM_CENTER -> maxX / 2
            PclDriver.Align.TOP_RIGHT, PclDriver.Align.CENTER_RIGHT, PclDriver.Align.BOTTOM_RIGHT -> maxX
        }
        val offY = when (align) {
            PclDriver.Align.TOP_LEFT, PclDriver.Align.TOP_CENTER, PclDriver.Align.TOP_RIGHT -> 0
            PclDriver.Align.CENTER_LEFT, PclDriver.Align.CENTER, PclDriver.Align.CENTER_RIGHT -> maxY / 2
            PclDriver.Align.BOTTOM_LEFT, PclDriver.Align.BOTTOM_CENTER, PclDriver.Align.BOTTOM_RIGHT -> maxY
        }

        val canvasBmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.WHITE)
        val dst = Rect(offX, offY, offX + imgW, offY + imgH)
        canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()

        return canvasBmp
    }
}
