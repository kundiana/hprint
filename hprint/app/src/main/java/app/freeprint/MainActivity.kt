package app.freeprint

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Milestone 1b: talk to a PCL printer (HP LaserJet 1320) over USB OTG.
 *
 * The 1320 understands PCL natively, so no driver is needed: we just send
 * plain PCL commands. Two tests:
 *   2. text-only job (the simplest possible job)
 *   3. text + a small raster graphic (the way real pages will be printed)
 */
class MainActivity : Activity() {

    private val actionPermission = "app.freeprint.USB_PERMISSION"
    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private var pendingJob = 0 // 0 = none, 1 = text test, 2 = text + graphics test

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val scan = Button(this).apply {
            text = "1. Find printer"
            setOnClickListener { describeDevices() }
        }
        val textTest = Button(this).apply {
            text = "2. Print text test"
            setOnClickListener { startPrint(1) }
        }
        val fullTest = Button(this).apply {
            text = "3. Print text + graphics test"
            setOnClickListener { startPrint(2) }
        }
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scan)
        root.addView(textTest)
        root.addView(fullTest)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        val filter = IntentFilter(actionPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
        log("Switch the printer OFF and ON first. Plug it in with the OTG cable, then tap 'Find printer'.")
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun log(message: String) {
        runOnUiThread { logView.append(message + "\n") }
    }

    private class Target(val device: UsbDevice, val iface: UsbInterface, val out: UsbEndpoint)

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
                "(protocol ${t.iface.interfaceProtocol}). Ready to print.")
        } else {
            log("No usable printer interface (class 7 with a bulk-out endpoint). Send this log to Claude.")
        }
    }

    /**
     * Choose the printer interface. USB printers offer several "alternate settings":
     * protocol 2 = bidirectional (best), protocol 1 = one-way, protocol 3 = IEEE 1284.4
     * (needs special packet wrapping, so it is the last choice).
     */
    private fun findTarget(): Target? {
        var best: Target? = null
        var bestScore = -1
        for (d in usb.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                val score = when (itf.interfaceProtocol) {
                    2 -> 3
                    1 -> 2
                    else -> 1
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

    private fun startPrint(job: Int) {
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

    /** Send a PCL job to the printer on a background thread. */
    private fun sendJob(job: Int) {
        thread {
            val target = findTarget()
            if (target == null) {
                log("Printer not found. Re-plug it and try again.")
                return@thread
            }
            val data = if (job == 1) buildTextJob() else buildFullJob()
            val conn = usb.openDevice(target.device)
            if (conn == null) {
                log("Could not open the device (permission problem?).")
                return@thread
            }
            try {
                if (!conn.claimInterface(target.iface, true)) {
                    log("Could not claim the printer interface.")
                    return@thread
                }
                if (!conn.setInterface(target.iface)) {
                    log("Warning: could not switch to alternate setting ${target.iface.alternateSetting}.")
                }
                log("Using interface ${target.iface.id} alt ${target.iface.alternateSetting} " +
                    "(protocol ${target.iface.interfaceProtocol}).")
                log("Sending ${data.size} bytes...")
                var offset = 0
                val chunk = 16 * 1024
                while (offset < data.size) {
                    val len = minOf(chunk, data.size - offset)
                    val n = conn.bulkTransfer(target.out, data, offset, len, 10_000)
                    if (n < 0) {
                        log("Transfer failed at byte $offset. Is the printer on and does it have paper?")
                        return@thread
                    }
                    offset += n
                }
                log("Done sending. A page should come out in a few seconds.")
                conn.releaseInterface(target.iface)
            } finally {
                conn.close()
            }
        }
    }

    // ------------------------------------------------------------- PCL jobs
    // PCL commands start with the ESC character (\u001B).

    /** Simplest possible job: reset, A4, a few lines of text, eject the page. */
    private fun buildTextJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        put("\u001BE")        // reset printer
        put("\u001B&l26A")    // paper size: A4
        put("\u001B&k2G")     // line ending: CR+LF
        put("hprint text test\n")
        put("HP LaserJet 1320 over USB OTG\n")
        put("If you can read this, the phone-to-printer link works.\n")
        put("\u000C")         // form feed: eject page
        put("\u001BE")        // reset
        return out.toByteArray()
    }

    /** Text plus a 2-inch box with a diagonal line, drawn as a raster graphic at 300 dpi. */
    private fun buildFullJob(): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        put("\u001BE")
        put("\u001B&l26A")
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
        return out.toByteArray()
    }
}
