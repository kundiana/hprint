package app.freeprint

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class PaperSize(
    val name: String,
    val widthDots: Int,   // Logical printable width @ 300 DPI (divisible by 8)
    val heightDots: Int,  // Logical printable height @ 300 DPI
    val pclCode: Int
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

enum class Orientation(val label: String) {
    AUTO("Auto (Best Fit)"),
    PORTRAIT("Keep Upright"),
    LANDSCAPE("Rotate 90°")
}

enum class PrintMode(val label: String) {
    DOCUMENT("Document (Crisp Text)"),
    PHOTO("Photo (Dithered)")
}

object PrintPapers {
    val ALL = listOf(
        PaperSize("A4",     2376, 3400, 26),
        PaperSize("Letter", 2400, 3200,  2),
        PaperSize("Legal",  2400, 4100,  3),
        PaperSize("A5",     1648, 2380, 100)
    )
}

data class PrintJobRequest(
    val file: File,
    val isPdf: Boolean,
    val pages: List<Int>,
    val copies: Int,
    val paper: PaperSize,
    val align: Align,
    val orientation: Orientation,
    val mode: PrintMode
)

/**
 * Parsed IEEE 1284 Device ID string returned by USB Printer Class GET_DEVICE_ID.
 * Example: MFG:Hewlett-Packard;MDL:HP LaserJet 1320;CMD:PJL,MLC,PCL,PCLXL;
 */
data class DeviceIdentity(
    val raw: String,
    val manufacturer: String,
    val model: String,
    val commands: Set<String>
) {
    val isEmpty: Boolean get() = raw.isBlank()

    fun hasAnyCommand(vararg candidates: String): Boolean =
        candidates.any { it.uppercase(Locale.US) in commands }

    fun displayTitle(fallbackDevice: UsbDevice): String = when {
        model.isNotBlank() -> model
        !fallbackDevice.productName.isNullOrBlank() -> fallbackDevice.productName!!
        else -> "USB Printer (0x%04X:0x%04X)".format(fallbackDevice.vendorId, fallbackDevice.productId)
    }

    companion object {
        val EMPTY = DeviceIdentity("", "", "", emptySet())

        fun parse(rawString: String): DeviceIdentity {
            val clean = rawString.trim()
            if (clean.isEmpty()) return EMPTY

            var mfg = ""
            var mdl = ""
            val cmds = LinkedHashSet<String>()

            for (entry in clean.split(';')) {
                val idx = entry.indexOf(':')
                if (idx <= 0) continue
                val key = entry.substring(0, idx).trim().uppercase(Locale.US)
                val value = entry.substring(idx + 1).trim()
                when (key) {
                    "MFG", "MANUFACTURER" -> mfg = value
                    "MDL", "MODEL" -> mdl = value
                    "CMD", "COMMAND SET" -> {
                        value.split(',', ' ')
                            .map { it.trim().uppercase(Locale.US) }
                            .filter { it.isNotEmpty() }
                            .forEach { cmds.add(it) }
                    }
                }
            }
            return DeviceIdentity(clean, mfg, mdl, cmds)
        }
    }
}

data class PortStatus(
    val known: Boolean,
    val rawBits: Int,
    val paperEmpty: Boolean,
    val selected: Boolean,
    val hasError: Boolean
) {
    fun summaryText(): String = when {
        !known -> "Connected (port status unavailable)"
        paperEmpty -> "Printer reports paper out — load paper tray"
        hasError -> "Printer reports an error — check jam, toner, or cover"
        else -> "Ready (status 0x%02X)".format(rawBits)
    }

    companion object {
        val UNKNOWN = PortStatus(known = false, rawBits = -1, paperEmpty = false, selected = true, hasError = false)

        fun fromByte(bits: Int): PortStatus = PortStatus(
            known = true,
            rawBits = bits and 0xFF,
            paperEmpty = (bits and 0x20) != 0,
            selected = (bits and 0x10) != 0,
            hasError = (bits and 0x08) == 0
        )
    }
}

/**
 * Standard USB Printer Class (Class 7) control requests.
 */
object UsbProbe {
    private const val REQ_TYPE_CLASS_IN = 0xA1
    private const val REQ_GET_DEVICE_ID = 0x00
    private const val REQ_GET_PORT_STATUS = 0x01

    fun readDeviceId(conn: UsbDeviceConnection, iface: UsbInterface): DeviceIdentity {
        val buf = ByteArray(1024)
        val wIndex = (iface.id shl 8) or (iface.alternateSetting and 0xFF)
        val n = conn.controlTransfer(REQ_TYPE_CLASS_IN, REQ_GET_DEVICE_ID, 0, wIndex, buf, buf.size, 1500)
        if (n <= 2) return DeviceIdentity.EMPTY

        val declaredLen = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        val end = if (declaredLen in 3..n) declaredLen else n
        val raw = String(buf, 2, end - 2, Charsets.US_ASCII)
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
        return DeviceIdentity.parse(raw)
    }

    fun readPortStatus(conn: UsbDeviceConnection, iface: UsbInterface): PortStatus {
        val buf = ByteArray(1)
        val n = conn.controlTransfer(REQ_TYPE_CLASS_IN, REQ_GET_PORT_STATUS, 0, iface.id, buf, 1, 1500)
        return if (n == 1) PortStatus.fromByte(buf[0].toInt()) else PortStatus.UNKNOWN
    }
}

interface PrinterDriver {
    val id: String
    val displayName: String
    val priority: Int

    fun supports(device: UsbDevice, identity: DeviceIdentity): Boolean

    fun streamPdfJob(
        request: PrintJobRequest,
        rawOut: OutputStream,
        cancel: AtomicBoolean,
        onStage: (String) -> Unit
    )

    fun streamPictureJob(
        request: PrintJobRequest,
        rawOut: OutputStream,
        cancel: AtomicBoolean,
        onStage: (String) -> Unit
    )

    fun textTestJob(): ByteArray? = null
    fun graphicsTestJob(): ByteArray? = null
}

data class DriverResolution(
    val driver: PrinterDriver?,
    val usedFallback: Boolean,
    val reason: String
)

object DriverRegistry {
    // Register additional drivers (e.g. XqxDriver, ZjsDriver, Pcl3Driver) here.
    private val drivers: List<PrinterDriver> = listOf(
        PclDriver
    ).sortedByDescending { it.priority }

    private val defaultFallback: PrinterDriver = PclDriver

    fun resolve(device: UsbDevice, identity: DeviceIdentity): DriverResolution {
        val match = drivers.firstOrNull { it.supports(device, identity) }
        if (match != null) {
            return DriverResolution(
                driver = match,
                usedFallback = false,
                reason = "Matched ${match.displayName}"
            )
        }
        if (identity.isEmpty) {
            return DriverResolution(
                driver = defaultFallback,
                usedFallback = true,
                reason = "No IEEE 1284 ID returned; using ${defaultFallback.displayName} fallback"
            )
        }
        val cmds = if (identity.commands.isEmpty()) "none" else identity.commands.joinToString(", ")
        return DriverResolution(
            driver = null,
            usedFallback = false,
            reason = "Unsupported printer language ($cmds) on ${identity.displayTitle(device)}"
        )
    }
}
