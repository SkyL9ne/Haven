package sh.haven.core.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [UsbBroker.listDevices] / describe(): the parts that run
 * without the system permission round-trip or a live connection. Transfer
 * dispatch is verified on-device (Slice 1 acceptance) since it routes entirely
 * through Android framework objects.
 */
class UsbBrokerTest {

    private fun endpoint(address: Int, dir: Int, type: Int): UsbEndpoint = mockk {
        every { this@mockk.address } returns address
        every { direction } returns dir
        every { this@mockk.type } returns type
        every { maxPacketSize } returns 64
    }

    private fun iface(id: Int, cls: Int, eps: List<UsbEndpoint>): UsbInterface = mockk {
        every { this@mockk.id } returns id
        every { interfaceClass } returns cls
        every { interfaceSubclass } returns 0
        every { interfaceProtocol } returns 0
        every { endpointCount } returns eps.size
        eps.forEachIndexed { i, ep -> every { getEndpoint(i) } returns ep }
    }

    private fun device(
        name: String,
        vid: Int,
        pid: Int,
        ifaces: List<UsbInterface>,
    ): UsbDevice = mockk {
        every { deviceName } returns name
        every { vendorId } returns vid
        every { productId } returns pid
        every { deviceClass } returns 0
        every { manufacturerName } returns "Evolv"
        every { productName } returns "DNA 100C"
        every { serialNumber } returns "SN123"
        every { interfaceCount } returns ifaces.size
        ifaces.forEachIndexed { i, f -> every { getInterface(i) } returns f }
    }

    private fun broker(usbManager: UsbManager): UsbBroker {
        val context: Context = mockk {
            every { getSystemService(Context.USB_SERVICE) } returns usbManager
            every { registerReceiver(any(), any()) } returns null
            every { registerReceiver(any(), any(), any<Int>()) } returns null
        }
        return UsbBroker(context, UsbAccessGate())
    }

    @Test
    fun `describes endpoints and interfaces`() {
        val epIn = endpoint(0x81, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_INT)
        val epOut = endpoint(0x01, UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK)
        val dev = device("/dev/bus/usb/001/004", 0x9999, 0x0001, listOf(
            iface(0, UsbConstants.USB_CLASS_HID, listOf(epIn, epOut)),
        ))
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns true
        }

        val info = broker(usb).listDevices().single()

        assertEquals("/dev/bus/usb/001/004", info.deviceName)
        assertEquals("9999:0001", info.vidPid)
        assertTrue(info.hasPermission)
        assertFalse(info.isOpen)
        assertEquals("Evolv", info.manufacturerName)
        assertEquals("DNA 100C", info.productName)
        assertEquals("SN123", info.serialNumber)

        val iface = info.interfaces.single()
        assertEquals(UsbConstants.USB_CLASS_HID, iface.interfaceClass)
        assertEquals(2, iface.endpoints.size)
        val inEp = iface.endpoints.first { it.direction == "in" }
        assertEquals(0x81, inEp.address)
        assertEquals("interrupt", inEp.type)
        val outEp = iface.endpoints.first { it.direction == "out" }
        assertEquals("bulk", outEp.type)
    }

    @Test
    fun `hides descriptor strings without permission`() {
        val dev = device("/dev/bus/usb/001/005", 0x1, 0x2, emptyList())
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns false
        }

        val info = broker(usb).listDevices().single()

        assertFalse(info.hasPermission)
        assertNull(info.manufacturerName)
        assertNull(info.productName)
        assertNull(info.serialNumber)
    }

    @Test
    fun `a USB detach broadcast evicts the open handle`() = runBlocking {
        val dev = device("/dev/bus/usb/001/006", 0x1, 0x2, emptyList())
        val conn = mockk<UsbDeviceConnection>(relaxed = true)
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns true
            every { openDevice(dev) } returns conn
        }
        val recvSlot = slot<BroadcastReceiver>()
        val context: Context = mockk {
            every { getSystemService(Context.USB_SERVICE) } returns usb
            every { registerReceiver(capture(recvSlot), any()) } returns null
            every { registerReceiver(any(), any(), any<Int>()) } returns null
        }
        val broker = UsbBroker(context, UsbAccessGate())

        broker.openDevice(dev.deviceName)
        assertTrue(broker.isOpen(dev.deviceName))

        // Fire the detach broadcast the receiver registered for (SDK_INT defaults
        // to 0 in unit tests → the deprecated getParcelableExtra path).
        val intent: Intent = mockk {
            every { action } returns UsbManager.ACTION_USB_DEVICE_DETACHED
            every { getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) } returns dev
        }
        recvSlot.captured.onReceive(context, intent)

        assertFalse(broker.isOpen(dev.deviceName))
        verify { conn.close() }
    }

    @Test
    fun `controlTransfer and bulkTransfer never run concurrently on the same connection`() = runBlocking {
        // Regression test for a real race found live: a keep-alive control
        // transfer (UsbDriveVmManager, Dispatchers.IO) and a USB/IP export
        // lane's bulk transfer (its own ExecutorService thread) both called
        // into the same UsbDeviceConnection with no locking — corrupting an
        // in-flight transfer and resetting the device mid-VM-boot.
        val epIn = endpoint(0x81, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK)
        val dev = device(
            "/dev/bus/usb/001/007", 0x1, 0x2,
            listOf(iface(0, UsbConstants.USB_CLASS_MASS_STORAGE, listOf(epIn))),
        )
        val inCriticalSection = java.util.concurrent.atomic.AtomicBoolean(false)
        val raceDetected = java.util.concurrent.atomic.AtomicBoolean(false)
        fun enterThenLeave(result: Int): Int {
            if (!inCriticalSection.compareAndSet(false, true)) raceDetected.set(true)
            Thread.sleep(15)
            inCriticalSection.set(false)
            return result
        }
        val conn: UsbDeviceConnection = mockk {
            every { claimInterface(any(), any()) } returns true
            every { controlTransfer(any(), any(), any(), any(), any(), any(), any()) } answers { enterThenLeave(2) }
            every { bulkTransfer(any(), any(), any(), any()) } answers { enterThenLeave(8) }
        }
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns true
            every { openDevice(dev) } returns conn
        }
        val broker = broker(usb)
        broker.openDevice(dev.deviceName)

        val threads = (1..8).map { i ->
            Thread {
                if (i % 2 == 0) {
                    repeat(5) { broker.controlTransfer(dev.deviceName, 0x80, 0, 0, 0, null, 2, 1000) }
                } else {
                    repeat(5) { broker.bulkTransfer(dev.deviceName, 0x81, null, 8, 1000) }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertFalse("controlTransfer and bulkTransfer overlapped on the same connection", raceDetected.get())
    }

    @Test
    fun `a large OUT transfer is split into bounded chunks, not one call`() {
        // Regression test for a real failure found live: a single bulkTransfer
        // call above ~16KB can silently truncate on Android's synchronous USB
        // API, desyncing the device's transaction state — cheap flash sticks
        // respond to that by resetting instead of erroring cleanly. Reproduced
        // with cryptsetup's LUKS-header wipe (a 15KB+ single write URB).
        val epOut = endpoint(0x01, UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK)
        val dev = device(
            "/dev/bus/usb/001/008", 0x1, 0x2,
            listOf(iface(0, UsbConstants.USB_CLASS_MASS_STORAGE, listOf(epOut))),
        )
        val callSizes = mutableListOf<Int>()
        val conn: UsbDeviceConnection = mockk {
            every { claimInterface(any(), any()) } returns true
            every { bulkTransfer(any(), any(), any(), any(), any()) } answers {
                val length = it.invocation.args[3] as Int
                callSizes.add(length)
                length
            }
        }
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns true
            every { openDevice(dev) } returns conn
        }
        val broker = broker(usb)
        runBlocking { broker.openDevice(dev.deviceName) }

        val totalSize = 40 * 1024 // well above the 16KB safe-chunk ceiling
        val result = broker.bulkTransfer(dev.deviceName, 0x01, ByteArray(totalSize), totalSize, 5000)

        assertEquals(totalSize, result.bytesTransferred)
        assertTrue("expected multiple chunked calls, got ${callSizes.size}: $callSizes", callSizes.size > 1)
        assertTrue("no single call should exceed the safe chunk size", callSizes.all { it <= 16 * 1024 })
    }

    @Test
    fun `an interface-recipient control transfer claims that interface first`() {
        // Regression for FIDO-over-USB/IP: usbhid's GET_DESCRIPTOR(REPORT) is an
        // interface-recipient control request (bmRequestType 0x81). Android only
        // routes those to a *claimed* interface — without the claim it stalls, we
        // return -EPIPE, and the remote usbhid fails "can't add hid device: -32"
        // (no hidraw ⇒ FIDO unusable). wIndex low byte selects the interface.
        val ep = endpoint(0x84, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_INT)
        val if0 = iface(0, UsbConstants.USB_CLASS_HID, listOf(ep))
        val dev = device("/dev/bus/usb/001/009", 0x1050, 0x0406, listOf(if0))
        val conn: UsbDeviceConnection = mockk {
            every { claimInterface(any(), any()) } returns true
            every { controlTransfer(any(), any(), any(), any(), any(), any(), any()) } returns 0
        }
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns true
            every { openDevice(dev) } returns conn
        }
        val broker = broker(usb)
        runBlocking { broker.openDevice(dev.deviceName) }

        // GET_DESCRIPTOR(REPORT) on interface 0: rt=0x81, req=0x06, wValue=0x2200.
        broker.controlTransfer(dev.deviceName, 0x81, 0x06, 0x2200, 0, null, 64, 1000)

        verify { conn.claimInterface(if0, true) }
    }

    @Test
    fun `a device-recipient control transfer claims no interface`() {
        // GET_DESCRIPTOR(DEVICE/CONFIG) is device-recipient (rt=0x80); it must go
        // out on ep0 without claiming anything, or config enumeration would stall.
        val ep = endpoint(0x84, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_INT)
        val if0 = iface(0, UsbConstants.USB_CLASS_HID, listOf(ep))
        val dev = device("/dev/bus/usb/001/010", 0x1050, 0x0406, listOf(if0))
        val conn: UsbDeviceConnection = mockk {
            every { claimInterface(any(), any()) } returns true
            every { controlTransfer(any(), any(), any(), any(), any(), any(), any()) } returns 0
        }
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns true
            every { openDevice(dev) } returns conn
        }
        val broker = broker(usb)
        runBlocking { broker.openDevice(dev.deviceName) }

        broker.controlTransfer(dev.deviceName, 0x80, 0x06, 0x0200, 0, null, 64, 1000)

        verify(exactly = 0) { conn.claimInterface(any(), any()) }
    }

    @Test
    fun `transfer result equality is content-based`() {
        val a = TransferResult(3, byteArrayOf(1, 2, 3))
        val b = TransferResult(3, byteArrayOf(1, 2, 3))
        val c = TransferResult(3, byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
