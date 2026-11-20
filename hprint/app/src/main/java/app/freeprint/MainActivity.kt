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
import kotlin.concurrent.thread

/**
 * Milestone 1: prove the phone can talk to the HP P1102 over USB OTG.
 *
 * It lists every USB device it can see (so we learn exactly how the printer
 * presents itself), finds the printer interface, asks for permission, and
 * sends one pre-made test page (assets/testpage.zjs) to the printer.
 */
class MainActivity : Activity() {

    private val actionPermission = "app.freeprint.USB_PERMISSION"
    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private var pendingPrint = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != actionPermission) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log(if (granted) "USB permission granted." else "USB permission DENIED. Tap the button again and choose OK.")
            if (granted && pendingPrint) {
                pendingPrint = false
                sendTestPage()
            }
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
            setOnClickListener { scanDevices() }
        }
        val print = Button(this).apply {
            text = "2. Print test page"
            setOnClickListener { startPrint() }
        }
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scan)
        root.addView(print)
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
        log("Plug the printer in with the OTG cable, switch it on, then tap 'Find printer'.")
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun log(message: String) {
        runOnUiThread {
            logView.append(message + "\n")
        }
    }

    private class Target(val device: UsbDevice, val iface: UsbInterface, val out: UsbEndpoint)

    /** Describe every connected USB device, then pick the printer interface if there is one. */
    private fun scanDevices(): Target? {
        val devices = usb.deviceList.values
        if (devices.isEmpty()) {
            log("No USB device found. Check the OTG cable, and that the printer is switched on.")
            return null
        }
        var found: Target? = null
        for (d in devices) {
            log("Device: ${d.productName ?: "?"} / ${d.manufacturerName ?: "?"}  " +
                "vid=0x%04x pid=0x%04x".format(d.vendorId, d.productId))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                log("  interface ${itf.id} alt ${itf.alternateSetting}: class=${itf.interfaceClass} " +
                    "sub=${itf.interfaceSubclass} proto=${itf.interfaceProtocol} endpoints=${itf.endpointCount}")
                if (found == null && itf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                    for (e in 0 until itf.endpointCount) {
                        val ep = itf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_OUT
                        ) {
                            found = Target(d, itf, ep)
                        }
                    }
                }
            }
        }
        if (found != null) {
            log("Printer interface found. Ready to print.")
        } else {
            log("No printer interface (class 7) with a bulk-out endpoint. " +
                "If you see a storage interface (class 8) only, the printer may be in " +
                "'HP Smart Install' mode. Send this log to Claude.")
        }
        return found
    }

    private fun startPrint() {
        val target = scanDevices() ?: return
        if (!usb.hasPermission(target.device)) {
            log("Asking Android for permission to use the printer. Tap OK in the pop-up.")
            pendingPrint = true
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(actionPermission).setPackage(packageName), flags
            )
            usb.requestPermission(target.device, pi)
            return
        }
        sendTestPage()
    }

    /** Send the bundled test page to the printer on a background thread. */
    private fun sendTestPage() {
        thread {
            val target = scanDeviceQuiet()
            if (target == null) {
                log("Printer disappeared. Re-plug it and try again.")
                return@thread
            }
            val data = assets.open("testpage.zjs").use { it.readBytes() }
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
                log("Sending ${data.size} bytes...")
                var offset = 0
                val chunk = 16 * 1024
                while (offset < data.size) {
                    val len = minOf(chunk, data.size - offset)
                    val n = conn.bulkTransfer(target.out, data, offset, len, 10_000)
                    if (n < 0) {
                        log("Transfer failed at byte $offset. Is the printer on and has paper?")
                        return@thread
                    }
                    offset += n
                }
                log("Done sending. Watch the printer: a page should come out in a few seconds.")
                conn.releaseInterface(target.iface)
            } finally {
                conn.close()
            }
        }
    }

    /** Same search as scanDevices() but without logging, for the background thread. */
    private fun scanDeviceQuiet(): Target? {
        for (d in usb.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) return Target(d, itf, ep)
                }
            }
        }
        return null
    }
}
