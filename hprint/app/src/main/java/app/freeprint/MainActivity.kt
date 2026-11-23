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
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * hprint — Direct USB OTG PCL 5e printing for HP LaserJet printers.
 */
class MainActivity : Activity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "app.freeprint.USB_PERMISSION"
        private const val REQ_OPEN_FILE = 42
        private const val REQ_SAVE_LOG = 43

        
        private const val COLOR_BG = 0xFFF3F6FA.toInt()         // Cool light surface
        private const val COLOR_CARD = 0xFFFFFFFF.toInt()       // Pure white preview card
        private const val COLOR_PRIMARY = 0xFF007DBA.toInt()    // Classic Blue
        private const val COLOR_TEXT_DARK = 0xFF0F2537.toInt()  // Deep navy-slate
        private const val COLOR_TEXT_MUTED = 0xFF4A6278.toInt() // Muted slate
        private const val COLOR_WARN = 0xFFB45309.toInt()       // Clear amber warning
        private const val COLOR_DIVIDER = 0xFFD8E1EA.toInt()    // Subtle cool border
        
    }

    private lateinit var usb: UsbManager
    private val worker = Executors.newSingleThreadExecutor()
    private val previewWorker = Executors.newSingleThreadExecutor()
    private val cancelFlag = AtomicBoolean(false)

    // UI references
    private lateinit var deviceText: TextView
    private lateinit var connectButton: Button
    private lateinit var selectButton: Button
    private lateinit var fileText: TextView
    private lateinit var previewView: ImageView
    private lateinit var pagesInput: EditText
    private lateinit var copiesInput: EditText
    private lateinit var paperSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var pictureOptionsRow: LinearLayout
    private lateinit var orientationSpinner: Spinner
    private lateinit var alignSpinner: Spinner
    private lateinit var printButton: Button
    private lateinit var testButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var stageText: TextView
    private lateinit var logText: TextView

    // State
    @Volatile private var busy = false
    private var preferredProtocol = 2 // 2 = bidirectional, 1 = unidirectional, 3 = vendor/special
    private var pendingAfterPermission: (() -> Unit)? = null

    private var activeFile: File? = null
    private var isPdf = false
    private var pageCount = 0
    private var documentName = "No file selected"
    private var previewBitmap: Bitmap? = null

    private var selectedPaper = PclDriver.PAPER_SIZES[0]
    private var selectedMode = PclDriver.PrintMode.DOCUMENT
    private var selectedOrientation = PclDriver.Orientation.AUTO
    private var selectedAlign = PclDriver.Align.CENTER

    private val logBuffer = StringBuilder()

    // ------------------------------------------------------------------ USB Broadcast Receiver

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    refreshDeviceLabel()
                    if (granted) {
                        log("USB permission granted.")
                        val next = pendingAfterPermission
                        pendingAfterPermission = null
                        next?.invoke() ?: inspectPrinter()
                    } else {
                        pendingAfterPermission = null
                        log("USB permission denied. Tap Connect and allow access.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDeviceLabel()
                    log("USB printer attached.")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDeviceLabel()
                    log("USB printer disconnected.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        buildUi()

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        log("hprint ready (Android ${Build.VERSION.RELEASE} / ${Build.MODEL}).")
        refreshDeviceLabel()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        refreshDeviceLabel()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        previewBitmap?.recycle()
        activeFile?.delete()
        worker.shutdownNow()
        previewWorker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI Builder (M1005-OTG Layout)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(COLOR_BG)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        setContentView(scroll)

        scroll.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            var top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            var bottom = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            }
            content.setPadding(dp(20), dp(16) + top, dp(20), dp(24) + bottom)
            insets
        }

        // Header row
        val header = hRow().apply {
            addView(label("hprint", 28f, COLOR_TEXT_DARK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            val diagBtn = ImageButton(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_manage)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Printer diagnostics"
                setOnClickListener { showDiagnosticsDialog() }
            }
            addView(diagBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        content.addView(header)
        content.addView(label("Wired USB OTG · PCL 5e", 14f, COLOR_TEXT_MUTED))

        // Printer status + Connect button
        deviceText = label("No USB printer detected", 15f, COLOR_WARN)
        content.addView(deviceText)
        connectButton = makeButton("Connect / Check Printer").apply {
            setOnClickListener { connectOrInspect() }
        }
        content.addView(connectButton, fullWidth())

        addDivider(content)

        // Document header + Choose button
        val docRow = hRow().apply {
            addView(label("Document", 18f, COLOR_TEXT_DARK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            selectButton = makeButton("Choose File").apply {
                setOnClickListener { openDocumentPicker() }
            }
            addView(selectButton)
        }
        content.addView(docRow)

        fileText = label(documentName, 14f, COLOR_TEXT_MUTED).apply { maxLines = 2 }
        content.addView(fileText)

        // Live Monochrome Preview Card
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), COLOR_DIVIDER)
            }
        }
        previewView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Print preview"
        }
        previewCard.addView(previewView, LinearLayout.LayoutParams(-1, dp(250)))
        val cardLp = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(10)) }
        content.addView(previewCard, cardLp)

        // Pages + Copies Row
        val pageCopyRow = hRow().apply {
            val pagesCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Pages", 13f, COLOR_TEXT_MUTED))
                pagesInput = EditText(this@MainActivity).apply {
                    isSingleLine = true
                    textSize = 15f
                    hint = "All (e.g. 1-3, 5)"
                    inputType = InputType.TYPE_CLASS_TEXT
                    filters = arrayOf(InputFilter.LengthFilter(128))
                }
                addView(pagesInput)
            }
            val copiesCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Copies", 13f, COLOR_TEXT_MUTED))
                copiesInput = EditText(this@MainActivity).apply {
                    isSingleLine = true
                    setText("1")
                    textSize = 15f
                    inputType = InputType.TYPE_CLASS_NUMBER
                    filters = arrayOf(InputFilter.LengthFilter(1))
                }
                addView(copiesInput)
            }
            addView(pagesCol, LinearLayout.LayoutParams(0, -2, 3f))
            addView(copiesCol, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(14), 0, 0, 0) })
        }
        content.addView(pageCopyRow)

        // Paper + Print Mode Row
        val paperModeRow = hRow().apply {
            val paperCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Paper Size", 13f, COLOR_TEXT_MUTED))
                paperSpinner = Spinner(this@MainActivity).apply {
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        PclDriver.PAPER_SIZES.map { it.name }
                    )
                    onItemSelectedListener = onSelect { pos ->
                        selectedPaper = PclDriver.PAPER_SIZES[pos]
                        refreshPreview()
                    }
                }
                addView(paperSpinner)
            }
            val modeCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Quality Mode", 13f, COLOR_TEXT_MUTED))
                modeSpinner = Spinner(this@MainActivity).apply {
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        PclDriver.PrintMode.values().map { it.label }
                    )
                    onItemSelectedListener = onSelect { pos ->
                        selectedMode = PclDriver.PrintMode.values()[pos]
                    }
                }
                addView(modeSpinner)
            }
            addView(paperCol, LinearLayout.LayoutParams(0, -2, 1f))
            addView(modeCol, LinearLayout.LayoutParams(0, -2, 1.4f).apply { setMargins(dp(12), 0, 0, 0) })
        }
        content.addView(paperModeRow)

        // Picture Orientation + Alignment Row (visible only when an image is loaded)
        pictureOptionsRow = hRow().apply {
            visibility = View.GONE
            val orientCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Orientation", 13f, COLOR_TEXT_MUTED))
                orientationSpinner = Spinner(this@MainActivity).apply {
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        PclDriver.Orientation.values().map { it.label }
                    )
                    onItemSelectedListener = onSelect { pos ->
                        selectedOrientation = PclDriver.Orientation.values()[pos]
                        refreshPreview()
                    }
                }
                addView(orientationSpinner)
            }
            val alignCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Alignment", 13f, COLOR_TEXT_MUTED))
                alignSpinner = Spinner(this@MainActivity).apply {
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        PclDriver.Align.values().map { it.label }
                    )
                    setSelection(PclDriver.Align.CENTER.ordinal)
                    onItemSelectedListener = onSelect { pos ->
                        selectedAlign = PclDriver.Align.values()[pos]
                        refreshPreview()
                    }
                }
                addView(alignSpinner)
            }
            addView(orientCol, LinearLayout.LayoutParams(0, -2, 1f))
            addView(alignCol, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(12), 0, 0, 0) })
        }
        content.addView(pictureOptionsRow)

        // Primary Print CTA + Secondary Test / Cancel
        printButton = makeButton("Print Document").apply {
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            setOnClickListener { confirmAndPrint() }
        }
        content.addView(printButton, fullWidth().apply { setMargins(0, dp(10), 0, dp(4)) })

        val actionRow = hRow().apply {
            testButton = makeButton("Load Test Page").apply {
                setOnClickListener { generateTestPdf() }
            }
            cancelButton = makeButton("Cancel").apply {
                setOnClickListener {
                    cancelFlag.set(true)
                    log("Cancellation requested...")
                    setStage("Cancelling...")
                }
            }
            addView(testButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(cancelButton, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(8), 0, 0, 0) })
        }
        content.addView(actionRow)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        content.addView(progressBar, fullWidth().apply { setMargins(0, dp(8), 0, dp(2)) })

        stageText = label("Idle", 14f, COLOR_PRIMARY)
        content.addView(stageText)

        addDivider(content)

        // Diagnostic Log Header + Save Button
        val logHeader = hRow().apply {
            addView(label("Diagnostic Log", 16f, COLOR_TEXT_DARK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            val saveBtn = ImageButton(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_save)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Export diagnostic log"
                setOnClickListener { exportLog() }
            }
            addView(saveBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        content.addView(logHeader)

        logText = label("", 12f, COLOR_TEXT_MUTED).apply { setTextIsSelectable(true) }
        content.addView(logText)

        updateButtons()
    }

    private fun hRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun fullWidth() = LinearLayout.LayoutParams(-1, -2)

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun makeButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(48)
        textSize = 15f
    }

    private fun addDivider(parent: LinearLayout) {
        val line = View(this).apply { setBackgroundColor(COLOR_DIVIDER) }
        parent.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(14), 0, dp(10)) })
    }

    private fun onSelect(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = block(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ------------------------------------------------------------------ Logging & UI State

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            logBuffer.append(ts).append("  ").append(msg).append('\n')
            if (logBuffer.length > 16000) logBuffer.delete(0, logBuffer.length - 12000)
            logText.text = logBuffer.toString()
        }
    }

    private fun setStage(msg: String) {
        runOnUiThread {
            if (!isDestroyed) stageText.text = msg
        }
    }

    private fun setProgress(percent: Int?) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (percent == null) {
                progressBar.isIndeterminate = true
            } else {
                progressBar.isIndeterminate = false
                progressBar.max = 100
                progressBar.progress = percent.coerceIn(0, 100)
            }
        }
    }

    private fun updateButtons() {
        val hasFile = activeFile != null
        connectButton.isEnabled = !busy
        selectButton.isEnabled = !busy
        testButton.isEnabled = !busy
        printButton.isEnabled = !busy && hasFile
        printButton.alpha = if (printButton.isEnabled) 1f else 0.45f
        cancelButton.isEnabled = busy
        pagesInput.isEnabled = !busy && isPdf
        copiesInput.isEnabled = !busy
        paperSpinner.isEnabled = !busy
        modeSpinner.isEnabled = !busy
        orientationSpinner.isEnabled = !busy
        alignSpinner.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun runTask(body: () -> Unit) {
        if (busy) return
        busy = true
        cancelFlag.set(false)
        setProgress(null)
        updateButtons()
        worker.execute {
            try {
                body()
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                log("Error: $msg")
                setStage("Failed: $msg")
            } finally {
                runOnUiThread {
                    if (!isDestroyed) {
                        busy = false
                        updateButtons()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ File Loading & Preview

    @Suppress("DEPRECATION")
    private fun openDocumentPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
            },
            REQ_OPEN_FILE
        )
    }

    @Suppress("DEPRECATION")
    private fun exportLog() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "hprint-diagnostic.txt")
            },
            REQ_SAVE_LOG
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_OPEN_FILE -> importUri(uri)
            REQ_SAVE_LOG -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(logBuffer.toString().toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "Diagnostic log saved", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    log("Export failed: ${e.message}")
                }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
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
        uri?.let { importUri(it) }
    }

    private fun importUri(uri: Uri) {
        runTask {
            setStage("Loading file...")
            var name = "Shared document"
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
            } catch (_: Throwable) {}

            val imported = File(cacheDir, "doc-${UUID.randomUUID()}.bin")
            try {
                contentResolver.openInputStream(uri)?.use { src ->
                    FileOutputStream(imported).use { dst -> src.copyTo(dst) }
                } ?: throw IOException("Could not open selected file.")

                val header = ByteArray(5)
                val read = imported.inputStream().use { it.read(header) }
                val pdf = read == 5 && String(header, Charsets.ISO_8859_1) == "%PDF-"

                var count = 1
                if (pdf) {
                    var pfd: ParcelFileDescriptor? = null
                    var renderer: PdfRenderer? = null
                    try {
                        pfd = ParcelFileDescriptor.open(imported, ParcelFileDescriptor.MODE_READ_ONLY)
                        renderer = PdfRenderer(pfd)
                        count = renderer.pageCount
                    } finally {
                        renderer?.close()
                        pfd?.close()
                    }
                } else {
                    val bmp = PclDriver.decodeImageWithExif(imported, 512, PclDriver.Orientation.PORTRAIT)
                    bmp.recycle()
                }

                setLoadedDocument(imported, pdf, count, name)
            } catch (t: Throwable) {
                imported.delete()
                throw t
            }
        }
    }

    private fun generateTestPdf() {
        runTask {
            setStage("Generating test page...")
            val file = File(cacheDir, "test-${UUID.randomUUID()}.pdf")
            val doc = PdfDocument()
            try {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
                val c = page.canvas
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                p.textSize = 28f; p.typeface = Typeface.DEFAULT_BOLD
                c.drawText("hprint · PCL 5e", 45f, 85f, p)
                p.textSize = 14f; p.typeface = Typeface.DEFAULT
                c.drawText("300 DPI PackBits · Android ${Build.VERSION.RELEASE} / ${Build.MODEL}", 45f, 118f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
                c.drawRect(45f, 150f, 550f, 750f, p)
                for (i in 0 until 10) {
                    p.style = Paint.Style.FILL
                    p.color = Color.rgb(i * 25, i * 25, i * 25)
                    c.drawRect(65f + i * 46f, 185f, 107f + i * 46f, 255f, p)
                }
                p.color = Color.BLACK; p.textSize = 16f
                c.drawText("ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789", 65f, 310f, p)
                c.drawText("abcdefghijklmnopqrstuvwxyz !@#\$%^&*()", 65f, 345f, p)
                doc.finishPage(page)
                FileOutputStream(file).use { doc.writeTo(it) }
            } finally {
                doc.close()
            }
            setLoadedDocument(file, true, 1, "hprint-test.pdf")
        }
    }

    private fun setLoadedDocument(file: File, pdf: Boolean, count: Int, name: String) {
        val preview = PclDriver.renderPreview(
            file, pdf, 0, selectedPaper, selectedAlign, selectedOrientation
        )
        runOnUiThread {
            if (isDestroyed) {
                preview.recycle()
                file.delete()
                return@runOnUiThread
            }
            val old = activeFile
            activeFile = file
            isPdf = pdf
            pageCount = count
            documentName = name
            if (old != null && old != file) old.delete()

            val oldPrev = previewBitmap
            previewBitmap = preview
            previewView.setImageBitmap(preview)
            oldPrev?.recycle()

            pagesInput.setText("")
            copiesInput.setText("1")
            fileText.text = if (pdf) "$name  ·  $count page(s)" else "$name  ·  Image"
            pictureOptionsRow.visibility = if (pdf) View.GONE else View.VISIBLE
            modeSpinner.setSelection(
                if (pdf) PclDriver.PrintMode.DOCUMENT.ordinal else PclDriver.PrintMode.PHOTO.ordinal
            )
            selectedMode = if (pdf) PclDriver.PrintMode.DOCUMENT else PclDriver.PrintMode.PHOTO
            setStage("Ready to print")
            log("Loaded $name (${if (pdf) "$count pages" else "image"}). Mode: ${selectedMode.label}")
            updateButtons()
        }
    }

    private fun refreshPreview() {
        val file = activeFile ?: return
        val pdf = isPdf
        val paper = selectedPaper
        val align = selectedAlign
        val orient = selectedOrientation
        previewWorker.execute {
            try {
                val bmp = PclDriver.renderPreview(file, pdf, 0, paper, align, orient)
                runOnUiThread {
                    if (isDestroyed) {
                        bmp.recycle()
                        return@runOnUiThread
                    }
                    val old = previewBitmap
                    previewBitmap = bmp
                    previewView.setImageBitmap(bmp)
                    old?.recycle()
                }
            } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ USB Helpers

    private class Target(val device: UsbDevice, val iface: UsbInterface, val out: UsbEndpoint)

    private fun findTarget(): Target? {
        var best: Target? = null
        var bestScore = -1
        for (d in usb.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                val score = if (itf.interfaceProtocol == preferredProtocol) 10
                else when (itf.interfaceProtocol) { 2 -> 3; 1 -> 2; else -> 1 }
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

    private fun findInEndpoint(iface: UsbInterface): UsbEndpoint? =
        (0 until iface.endpointCount)
            .map { iface.getEndpoint(it) }
            .firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_IN
            }

    private fun refreshDeviceLabel() {
        val target = findTarget()
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (target == null) {
                deviceText.text = "No USB printer detected — check OTG cable & power"
                deviceText.setTextColor(COLOR_WARN)
            } else {
                val name = target.device.productName ?: "USB Printer"
                val hasPerm = usb.hasPermission(target.device)
                deviceText.text = if (hasPerm) "$name connected (USB authorized)"
                else "$name connected — tap Connect to authorize"
                deviceText.setTextColor(if (hasPerm) COLOR_PRIMARY else COLOR_WARN)
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice, onGranted: () -> Unit) {
        pendingAfterPermission = onGranted
        val flags = (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) or
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        log("Requesting USB permission...")
        usb.requestPermission(device, pi)
    }

    private fun connectOrInspect() {
        val target = findTarget()
        refreshDeviceLabel()
        if (target == null) {
            describeAllUsbDevices()
            return
        }
        if (!usb.hasPermission(target.device)) {
            requestUsbPermission(target.device) { inspectPrinter() }
        } else {
            inspectPrinter()
        }
    }

    private fun describeAllUsbDevices() {
        val devices = usb.deviceList.values
        if (devices.isEmpty()) {
            log("No USB devices attached.")
            return
        }
        for (d in devices) {
            log("Device: ${d.productName ?: "?"} vid=0x%04x pid=0x%04x".format(d.vendorId, d.productId))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                log("  iface ${itf.id} alt ${itf.alternateSetting}: class=${itf.interfaceClass} proto=${itf.interfaceProtocol}")
            }
        }
    }

    private fun openClaimed(target: Target): UsbDeviceConnection {
        val conn = usb.openDevice(target.device) ?: throw IOException("Could not open USB device.")
        if (!conn.claimInterface(target.iface, true)) {
            conn.close()
            throw IOException("Could not claim USB printer interface.")
        }
        if (target.iface.alternateSetting != 0) {
            conn.setInterface(target.iface)
        }
        return conn
    }

    private fun inspectPrinter() {
        runTask {
            setStage("Checking printer...")
            val target = findTarget() ?: throw IOException("Printer disconnected.")
            val conn = openClaimed(target)
            try {
                val inEp = findInEndpoint(target.iface)
                if (inEp == null) {
                    log("Connected (one-way protocol ${target.iface.interfaceProtocol}). Ready.")
                    setStage("Printer ready (one-way)")
                    return@runTask
                }
                writeBytes(conn, target.out, PclDriver.PJL_INFO_ID)
                val buf = ByteArray(512)
                val sb = StringBuilder()
                val end = SystemClock.elapsedRealtime() + 3000
                while (SystemClock.elapsedRealtime() < end) {
                    val n = conn.bulkTransfer(inEp, buf, buf.size, 300)
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                    else if (sb.isNotEmpty()) break
                }
                val id = sb.toString().replace("\r", "").trim()
                log(if (id.isEmpty()) "Connected (no PJL reply)." else "Printer ID: $id")
                setStage(if (id.isEmpty()) "Printer ready" else id.lines().last())
            } finally {
                conn.releaseInterface(target.iface)
                conn.close()
            }
        }
    }

    // ------------------------------------------------------------------ Printing

    private fun confirmAndPrint() {
        val file = activeFile ?: return
        if (busy) return
        val target = findTarget() ?: run {
            log("Printer not found. Connect USB OTG cable and turn printer on.")
            return
        }
        if (!usb.hasPermission(target.device)) {
            requestUsbPermission(target.device) { confirmAndPrint() }
            return
        }

        val copies = copiesInput.text.toString().trim().toIntOrNull()?.takeIf { it in 1..9 } ?: run {
            log("Copies must be between 1 and 9.")
            return
        }
        val pages = try {
            if (isPdf) PclDriver.parsePages(pagesInput.text.toString(), pageCount) else listOf(0)
        } catch (e: IllegalArgumentException) {
            log(e.message ?: "Invalid page range.")
            return
        }

        val totalSheets = pages.size * copies
        AlertDialog.Builder(this)
            .setTitle("Send to Printer?")
            .setMessage(
                "$documentName\n" +
                    "${pages.size} page(s) × $copies cop${if (copies == 1) "y" else "ies"} = $totalSheets sheet(s)\n" +
                    "${selectedPaper.name} · ${selectedMode.label}"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Print") { _, _ -> executePrint(file, pages, copies) }
            .show()
    }

    private fun executePrint(file: File, pages: List<Int>, copies: Int) {
        val pdf = isPdf
        val paper = selectedPaper
        val align = selectedAlign
        val orient = selectedOrientation
        val mode = selectedMode

        runTask {
            val started = SystemClock.elapsedRealtime()
            val target = findTarget() ?: throw IOException("Printer disconnected.")
            val conn = openClaimed(target)
            try {
                val usbOut = UsbBulkOutputStream(conn, target.out, cancelFlag)
                if (pdf) {
                    PclDriver.streamPdfJob(file, pages, copies, paper, mode, usbOut, cancelFlag) { s ->
                        setStage(s)
                    }
                } else {
                    PclDriver.streamPictureJob(file, copies, paper, align, orient, mode, usbOut, cancelFlag) { s ->
                        setStage(s)
                    }
                }
                val secs = (SystemClock.elapsedRealtime() - started) / 1000.0
                val kb = usbOut.totalBytesSent / 1024
                val msg = String.format(Locale.US, "Sent %d KB in %.2fs", kb, secs)
                log(msg)
                setStage("Done ($msg)")
            } finally {
                conn.releaseInterface(target.iface)
                conn.close()
            }
        }
    }

    private fun writeBytes(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            if (cancelFlag.get()) throw InterruptedException("Cancelled.")
            val len = minOf(16384, data.size - offset)
            val n = conn.bulkTransfer(ep, data, offset, len, 10_000)
            if (n <= 0) throw IOException("USB transfer failed (result=$n) at byte $offset/${data.size}.")
            offset += n
        }
    }

    private class UsbBulkOutputStream(
        private val conn: UsbDeviceConnection,
        private val ep: UsbEndpoint,
        private val cancel: AtomicBoolean
    ) : OutputStream() {
        var totalBytesSent: Long = 0L
            private set

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (cancel.get() || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Cancelled.")
                }
                val chunk = minOf(16384, remaining)
                val n = conn.bulkTransfer(ep, b, offset, chunk, 10_000)
                if (n <= 0) {
                    throw IOException("USB bulk transfer failed (code=$n) after $totalBytesSent bytes.")
                }
                offset += n
                remaining -= n
                totalBytesSent += n
            }
        }
    }

    // ------------------------------------------------------------------ Diagnostics Menu

    private fun showDiagnosticsDialog() {
        val options = arrayOf(
            "Text test page (PCL)",
            "Graphics test page (PackBits)",
            "Ask printer ID (PJL)",
            "Cycle USB protocol (currently $preferredProtocol)",
            "List raw USB devices"
        )
        AlertDialog.Builder(this)
            .setTitle("Printer Diagnostics")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendRawDiagnostic(PclDriver.textTestJob(), "Text test")
                    1 -> sendRawDiagnostic(PclDriver.graphicsTestJob(), "Graphics test")
                    2 -> connectOrInspect()
                    3 -> {
                        preferredProtocol = when (preferredProtocol) { 2 -> 1; 1 -> 3; else -> 2 }
                        log("USB preferred protocol set to $preferredProtocol (2=two-way, 1=one-way, 3=special).")
                    }
                    4 -> describeAllUsbDevices()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sendRawDiagnostic(data: ByteArray, label: String) {
        val target = findTarget() ?: run {
            log("Printer not found.")
            return
        }
        if (!usb.hasPermission(target.device)) {
            requestUsbPermission(target.device) { sendRawDiagnostic(data, label) }
            return
        }
        runTask {
            setStage("Sending $label...")
            val conn = openClaimed(target)
            try {
                writeBytes(conn, target.out, data)
                log("$label sent (${data.size} bytes).")
                setStage("$label sent")
            } finally {
                conn.releaseInterface(target.iface)
                conn.close()
            }
        }
    }
}
