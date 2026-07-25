package sh.haven.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broker for the phone's USB devices.
 *
 * This is the Android side of Haven's USB Layer-B primitive: a non-rooted
 * phone denies `/dev/bus/usb` to both the app uid and the proot tracee, so the
 * only way a Linux guest (or the agent) can touch a USB device is through an
 * Android [UsbManager] app that owns the connection. [UsbBroker] is that owner.
 *
 * Slice 1 exposes enumeration + runtime permission + raw control/bulk transfers
 * directly to the MCP agent. Later slices add a userspace proxy socket that a
 * guest shim ([sh.haven.core.usb] over an abstract `LocalSocket`) speaks, so
 * unmodified guest apps reach the device through these same transfers.
 *
 * Permission handling mirrors
 * `sh.haven.core.fido.FidoAuthenticator.ensureUsbPermission` — same
 * `FLAG_MUTABLE` + explicit-package fix (#15) so the system grant dialog
 * round-trips correctly on Android 12+.
 *
 * Devices are keyed by [UsbDevice.getDeviceName] (the `/dev/bus/usb/BBB/DDD`
 * path), which is stable for the life of an attachment and unique per device.
 */
@Singleton
class UsbBroker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessGate: UsbAccessGate,
) {
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Open connections keyed by device name, plus the interfaces we claimed. */
    private data class OpenHandle(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        // Concurrent: per-endpoint export lanes may claim the same interface
        // (e.g. a CDC data interface with both bulk endpoints) concurrently.
        val claimedInterfaces: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
    )

    // Concurrent: the detach receiver (main thread) closes handles while export
    // lanes read/transfer on other threads.
    private val open = ConcurrentHashMap<String, OpenHandle>()

    init {
        // A physical unplug — or an ESP32-S3 re-enumerating when esptool resets it —
        // silently invalidates an open connection. Without this, the export server
        // keeps issuing transfers on a dead handle and the handle leaks until the
        // next openDevice. Mirror FidoAuthenticator's USB receiver registration.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val name = device?.deviceName ?: return
                if (open.containsKey(name)) {
                    Log.d(TAG, "USB device $name detached — closing handle")
                    closeDevice(name)
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    // ---- Enumeration ------------------------------------------------------

    /**
     * Snapshot every attached USB device. Descriptor strings (manufacturer,
     * product, serial) are only populated when permission is already held —
     * Android returns null for them otherwise, and reading them must not
     * trigger a permission prompt from a read-only listing.
     */
    fun listDevices(): List<UsbDeviceInfo> =
        usbManager.deviceList.values.map { describe(it) }

    private fun describe(device: UsbDevice): UsbDeviceInfo {
        val permitted = usbManager.hasPermission(device)
        val interfaces = (0 until device.interfaceCount).map { i ->
            val iface: UsbInterface = device.getInterface(i)
            val endpoints = (0 until iface.endpointCount).map { e ->
                val ep: UsbEndpoint = iface.getEndpoint(e)
                UsbEndpointInfo(
                    address = ep.address,
                    direction = if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                    type = endpointTypeName(ep.type),
                    maxPacketSize = ep.maxPacketSize,
                )
            }
            UsbInterfaceInfo(
                id = iface.id,
                interfaceClass = iface.interfaceClass,
                interfaceSubclass = iface.interfaceSubclass,
                interfaceProtocol = iface.interfaceProtocol,
                endpoints = endpoints,
            )
        }
        return UsbDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            manufacturerName = if (permitted) device.manufacturerName else null,
            productName = if (permitted) device.productName else null,
            serialNumber = runCatching { if (permitted) device.serialNumber else null }.getOrNull(),
            hasPermission = permitted,
            isOpen = open.containsKey(device.deviceName),
            interfaces = interfaces,
        )
    }

    private fun endpointTypeName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "unknown"
    }

    private fun findDevice(deviceName: String): UsbDevice =
        usbManager.deviceList[deviceName]
            ?: throw IOException("USB device '$deviceName' not attached")

    // ---- Permission -------------------------------------------------------

    fun hasPermission(deviceName: String): Boolean =
        usbManager.deviceList[deviceName]?.let { usbManager.hasPermission(it) } ?: false

    /**
     * Request runtime permission for [deviceName], waiting for the system
     * dialog. Returns true if granted (or already held). Mirrors the FIDO
     * permission flow, including the FLAG_MUTABLE + explicit-package fix.
     */
    suspend fun requestPermission(deviceName: String): Boolean {
        val device = findDevice(deviceName)
        if (usbManager.hasPermission(device)) return true

        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    deferred.complete(
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                    )
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        // UsbManager fills the granted-flag extra back into the intent, so it
        // must stay MUTABLE; an explicit-by-package intent keeps Android 14+
        // from rejecting a mutable PendingIntent around an implicit Intent (#15).
        val permIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        usbManager.requestPermission(
            device,
            PendingIntent.getBroadcast(context, 0, permIntent, flags),
        )
        val granted = try {
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        return when (granted) {
            null -> throw IOException("USB permission timed out — no response from system dialog")
            else -> granted
        }
    }

    // ---- Open / close -----------------------------------------------------

    /** Open [deviceName] (requesting permission first if needed) and cache the connection. */
    suspend fun openDevice(deviceName: String): UsbDeviceInfo {
        val device = findDevice(deviceName)
        open[deviceName]?.let {
            // A cached connection is only valid while permission is still held.
            // A detach/replug (or a revoked grant) silently invalidates the
            // connection while leaving our handle behind — after which every
            // later openDevice would be a no-op that can never re-prompt, and
            // transfers fail with rc=-1. Evict the stale handle and re-open.
            if (usbManager.hasPermission(device)) return describe(device)
            Log.d(TAG, "Evicting stale handle for $deviceName (permission lost — likely re-enumerated)")
            closeDevice(deviceName)
        }
        if (!requestPermission(deviceName)) throw IOException("USB permission denied for $deviceName")
        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device $deviceName")
        open[deviceName] = OpenHandle(device, connection)
        Log.d(TAG, "Opened $deviceName (${"%04x".format(device.vendorId)}:${"%04x".format(device.productId)})")
        return describe(device)
    }

    fun closeDevice(deviceName: String) {
        open.remove(deviceName)?.let { handle ->
            handle.claimedInterfaces.forEach { id ->
                runCatching {
                    handle.connection.releaseInterface(handle.device.getInterface(indexOfInterfaceId(handle.device, id)))
                }
            }
            runCatching { handle.connection.close() }
            Log.d(TAG, "Closed $deviceName")
        }
    }

    /** Release all open connections — call on broker teardown / app stop. */
    fun closeAll() {
        open.keys.toList().forEach { closeDevice(it) }
    }

    /** Whether a handle is currently open for [deviceName] (export servers poll this to stop on device loss). */
    fun isOpen(deviceName: String): Boolean = open.containsKey(deviceName)

    private fun handleFor(deviceName: String): OpenHandle =
        open[deviceName] ?: throw IOException("USB device '$deviceName' not open — call openDevice first")

    /**
     * The device + active-config descriptors as the kernel would return them
     * (the bytes `UsbDeviceConnection.getRawDescriptors()` exposes). The proxy
     * server hands these to the guest shim so it can synthesize the HID report
     * descriptor / libudev attributes without a real device node.
     */
    fun rawDescriptors(deviceName: String): ByteArray =
        handleFor(deviceName).connection.rawDescriptors ?: ByteArray(0)

    // ---- Transfers --------------------------------------------------------

    /**
     * Endpoint-0 control transfer. [data] is the OUT payload (ignored for IN
     * transfers, where [length] sizes the read buffer). Returns the bytes
     * transferred plus, for IN transfers, the data read.
     */
    fun controlTransfer(
        deviceName: String,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): TransferResult {
        val handle = handleFor(deviceName)
        // Pause while a priority consumer (FIDO auth on its own connection) holds
        // the key, so the export isn't transferring on the shared endpoints at the
        // same time (#FIDO-storm contention).
        accessGate.awaitClear(deviceName, EXPORT_YIELD_MS)
        // An interface-recipient request (bmRequestType bits 0-4 == 1) — e.g. the
        // HID GET_DESCRIPTOR(REPORT) that usbhid issues at probe time — only
        // succeeds on Android once that interface is claimed. Without the claim the
        // report-descriptor read stalls, we surface it as -EPIPE, and the remote
        // usbhid fails with "can't add hid device: -32" (no hidraw ⇒ FIDO unusable).
        // Mirrors bulkTransfer's lazy per-interface claim; wIndex low byte = iface#.
        if (requestType and 0x1F == 0x01) claimInterfaceByNumber(handle, index and 0xFF)
        val isIn = (requestType and UsbConstants.USB_DIR_IN) != 0
        val buffer = if (isIn) ByteArray(length) else (data ?: ByteArray(0))
        // A single UsbDeviceConnection has no built-in thread safety — a
        // concurrent caller on another thread (e.g. UsbDriveVmManager's
        // keep-alive control transfer racing a USB/IP export lane's bulk
        // transfer) can corrupt an in-flight USB transaction. Observed live:
        // a keep-alive GET_STATUS racing bulk reads reset the device mid-VM-
        // boot and it never re-enumerated for the rest of that session.
        val transferred = synchronized(handle.connection) {
            handle.connection.controlTransfer(
                requestType, request, value, index, buffer, if (isIn) length else buffer.size, timeoutMs,
            )
        }
        if (transferred < 0) throw IOException("controlTransfer failed (rc=$transferred)")
        val out = if (isIn) buffer.copyOf(transferred) else ByteArray(0)
        return TransferResult(transferred, out)
    }

    /**
     * Bulk or interrupt transfer on the endpoint with [endpointAddress]. The
     * owning interface is claimed on first use. Direction is taken from the
     * endpoint descriptor; [data] is the OUT payload, [length] the IN buffer size.
     */
    fun bulkTransfer(
        deviceName: String,
        endpointAddress: Int,
        data: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): TransferResult {
        val handle = handleFor(deviceName)
        accessGate.awaitClear(deviceName, EXPORT_YIELD_MS) // yield to FIDO auth — see controlTransfer
        val (iface, endpoint) = locateEndpoint(handle.device, endpointAddress)
        val isIn = endpoint.direction == UsbConstants.USB_DIR_IN
        val buffer = if (isIn) ByteArray(length) else (data ?: ByteArray(0))
        val total = if (isIn) length else buffer.size
        synchronized(handle.connection) {
            if (handle.claimedInterfaces.add(iface.id)) {
                if (!handle.connection.claimInterface(iface, true)) {
                    handle.claimedInterfaces.remove(iface.id)
                    throw IOException("Failed to claim interface ${iface.id} for endpoint $endpointAddress")
                }
            }
        }
        // Chunk loop deliberately does NOT hold the lock across the whole
        // transfer — a mass-storage device's BULK OUT (data) and BULK IN
        // (status) traffic for the SAME command must interleave on separate
        // UsbIpServer lane threads (worse with UAS command queuing). Holding
        // one lock for an entire multi-chunk write starved the status-read
        // lane long enough that the guest's own SCSI command timeout fired
        // and reset the device mid-write. Lock only each individual native
        // call — see the class-level race this whole scheme guards against.
        val transferred = transferInChunks(handle.connection, endpoint, buffer, total, timeoutMs)
        if (transferred < 0) throw IOException("bulkTransfer failed (rc=$transferred)")
        val out = if (isIn) buffer.copyOf(transferred) else ByteArray(0)
        return TransferResult(transferred, out)
    }

    /**
     * A single `UsbDeviceConnection.bulkTransfer` call above [MAX_TRANSFER_CHUNK]
     * can silently truncate on Android's legacy synchronous USB API (no kernel
     * URB streaming) — observed live as a guest's large multi-segment write
     * (cryptsetup wiping a LUKS header, 15KB+ in one URB) desyncing the
     * device's USB transaction state machine, which cheap flash controllers
     * respond to with a hard reset instead of a clean short-write error.
     * Loop in bounded chunks instead of trusting one call to move everything.
     */
    private fun transferInChunks(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        total: Int,
        timeoutMs: Int,
    ): Int {
        var offset = 0
        while (offset < total) {
            val chunkLen = minOf(MAX_TRANSFER_CHUNK, total - offset)
            val rc = synchronized(connection) {
                connection.bulkTransfer(endpoint, buffer, offset, chunkLen, timeoutMs)
            }
            if (rc < 0) return if (offset == 0) rc else offset
            offset += rc
            if (rc < chunkLen) break // short transfer — device signaled end of data
        }
        return offset
    }

    private fun locateEndpoint(device: UsbDevice, address: Int): Pair<UsbInterface, UsbEndpoint> {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.address == address) return iface to ep
            }
        }
        throw IOException("No endpoint with address 0x${"%02x".format(address)} on ${device.deviceName}")
    }

    private fun indexOfInterfaceId(device: UsbDevice, id: Int): Int =
        (0 until device.interfaceCount).first { device.getInterface(it).id == id }

    /**
     * Claim (once) the interface whose bInterfaceNumber is [number], mirroring
     * [bulkTransfer]'s lazy claim. Needed before an interface-recipient control
     * transfer (Android routes those to a claimed interface only). No-op if the
     * device has no such interface — an unknown wIndex shouldn't fail the request.
     */
    private fun claimInterfaceByNumber(handle: OpenHandle, number: Int) {
        val iface = (0 until handle.device.interfaceCount)
            .map { handle.device.getInterface(it) }
            .firstOrNull { it.id == number } ?: return
        synchronized(handle.connection) {
            if (handle.claimedInterfaces.add(iface.id)) {
                if (!handle.connection.claimInterface(iface, true)) {
                    handle.claimedInterfaces.remove(iface.id)
                    throw IOException("Failed to claim interface ${iface.id} for control transfer")
                }
            }
        }
    }

    companion object {
        private const val TAG = "UsbBroker"
        private const val PERMISSION_TIMEOUT_MS = 30_000L

        /**
         * Max time an export transfer waits for a FIDO auth lease to clear before
         * proceeding anyway. Above FIDO's 30s user-presence USB timeout so a real
         * auth finishes first, but bounded so a dead holder can't wedge the export.
         */
        private const val EXPORT_YIELD_MS = 35_000L

        /**
         * Safe per-call size for [transferInChunks] — comfortably under the
         * ~16KB threshold where Android's synchronous UsbDeviceConnection.
         * bulkTransfer has been observed to silently truncate on some
         * devices/API levels.
         */
        private const val MAX_TRANSFER_CHUNK = 16 * 1024

        /** Broadcast action for the runtime-permission grant callback. */
        const val ACTION_USB_PERMISSION = "sh.haven.core.usb.USB_PERMISSION"
    }
}
