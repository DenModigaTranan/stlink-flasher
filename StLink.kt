package com.example.stlinkflasher

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StLinkException(message: String) : Exception(message)

data class StLinkInfo(
    val stlinkVer: Int,
    val jtagVer: Int,
    val swimVer: Int,
    val vid: Int,
    val pid: Int,
)

/**
 * Minimal ST-Link V2 / V2-1 / V3 driver over Android USB Host (bulk transfers, SWD only).
 * Command set follows the open-source stlink project (libstlink).
 * All addresses are Long so 0xE000EDF0-style values don't overflow; they are cast on the wire.
 */
class StLink(
    private val manager: UsbManager,
    private val device: UsbDevice,
) : AutoCloseable {

    companion object {
        const val VID = 0x0483
        // V2 (most clones), V2-1, V2-1 no MSD, V3, V3E, V3-mini
        val SUPPORTED_PIDS = setOf(0x3748, 0x374B, 0x3752, 0x374E, 0x374F, 0x3753)

        fun isStLink(d: UsbDevice) = d.vendorId == VID && d.productId in SUPPORTED_PIDS

        private const val CMD_LEN = 16
        private const val TIMEOUT_MS = 3000
        private const val MAX_XFER = 1024

        private const val GET_VERSION = 0xF1
        private const val DEBUG = 0xF2
        private const val DFU = 0xF3
        private const val GET_MODE = 0xF5

        private const val DFU_EXIT = 0x07
        private const val MODE_DFU = 0
        private const val MODE_DEBUG = 2

        // Debug sub-commands
        private const val D_READMEM32 = 0x07
        private const val D_WRITEMEM32 = 0x08
        private const val D_WRITEMEM8 = 0x0D
        private const val D_EXIT = 0x21
        private const val D_READCOREID = 0x22
        private const val D_ENTER = 0x30
        private const val D_RESETSYS = 0x32
        private const val D_WRITEDEBUGREG = 0x35
        private const val D_READDEBUGREG = 0x36
        private const val D_READREG = 0x33
        private const val D_WRITEREG = 0x34
        private const val ENTER_SWD = 0xA3
        private const val OK = 0x80

        const val DHCSR = 0xE000EDF0L
        const val DEMCR = 0xE000EDFCL

        private const val D_DRIVE_NRST = 0x3C
        private const val NRST_LOW = 0x00
        private const val NRST_HIGH = 0x01
        private const val DEMCR_TRCENA_VC_CORERESET = 0x01000001
    }

    private var conn: UsbDeviceConnection? = null
    private var catchReset = false
    private var epOut: UsbEndpoint? = null
    private var epIn: UsbEndpoint? = null

    // ---------- lifecycle ----------

    /**
     * @param underReset hold NRST low while attaching, so the core halts at its reset vector
     * before the scooter firmware can remap/disable the SWD pins. Needs the ST-Link's NRST
     * pin wired to the controller's reset line.
     */
    fun open(underReset: Boolean = false): StLinkInfo {
        val c = manager.openDevice(device)
            ?: throw StLinkException("Cannot open USB device (permission denied?)")

        var found = false
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) continue
            var out: UsbEndpoint? = null
            var inn: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    if (out == null) out = ep
                } else if (inn == null || ep.address == 0x81) {
                    inn = ep
                }
            }
            if (out != null && inn != null && c.claimInterface(intf, true)) {
                epOut = out
                epIn = inn
                found = true
                break
            }
        }
        if (!found) {
            c.close()
            throw StLinkException("No usable bulk endpoints on this ST-Link")
        }
        conn = c

        val info = version()
        when (currentMode()) {
            MODE_DFU -> exitDfu()
            MODE_DEBUG -> exitDebug()
        }
        if (underReset) {
            driveNrst(NRST_LOW)
            Thread.sleep(20)
            enterSwd()
            writeDebugReg(DEMCR, DEMCR_TRCENA_VC_CORERESET) // halt on reset-vector fetch
            catchReset = true
            halt()
            driveNrst(NRST_HIGH)
            Thread.sleep(20)
        } else {
            enterSwd()
        }
        return info
    }

    override fun close() {
        if (catchReset) runCatching { writeDebugReg(DEMCR, 0) } // don't leave reset-catch armed
        catchReset = false
        try { exitDebug() } catch (_: Exception) {}
        conn?.close()
        conn = null
    }

    // ---------- raw USB ----------

    private fun cmdBytes(vararg v: Int): ByteArray {
        val b = ByteArray(CMD_LEN)
        v.forEachIndexed { i, x -> b[i] = x.toByte() }
        return b
    }

    private fun usbWrite(data: ByteArray) {
        val c = conn ?: throw StLinkException("Not connected")
        val ep = epOut ?: throw StLinkException("Not connected")
        val n = c.bulkTransfer(ep, data, data.size, TIMEOUT_MS)
        if (n != data.size) throw StLinkException("USB write failed ($n/${data.size})")
    }

    private fun usbRead(len: Int): ByteArray {
        val c = conn ?: throw StLinkException("Not connected")
        val ep = epIn ?: throw StLinkException("Not connected")
        val buf = ByteArray(len)
        val n = c.bulkTransfer(ep, buf, len, TIMEOUT_MS)
        if (n != len) throw StLinkException("USB read failed ($n/$len)")
        return buf
    }

    /** Sends a 0xF2 debug command and optionally reads [rx] bytes of reply. */
    private fun dbg(sub: Int, rx: Int, fill: (ByteBuffer) -> Unit = {}): ByteArray {
        val b = ByteBuffer.allocate(CMD_LEN).order(ByteOrder.LITTLE_ENDIAN)
        b.put(DEBUG.toByte())
        b.put(sub.toByte())
        fill(b)
        usbWrite(b.array())
        return if (rx > 0) usbRead(rx) else ByteArray(0)
    }

    private fun checkOk(r: ByteArray, what: String) {
        if ((r[0].toInt() and 0xFF) != OK) {
            throw StLinkException("$what failed (status 0x${"%02X".format(r[0])})")
        }
    }

    // ---------- mode / identity ----------

    fun version(): StLinkInfo {
        usbWrite(cmdBytes(GET_VERSION))
        val r = usbRead(6)
        val v = ((r[0].toInt() and 0xFF) shl 8) or (r[1].toInt() and 0xFF)
        return StLinkInfo(
            stlinkVer = (v shr 12) and 0xF,
            jtagVer = (v shr 6) and 0x3F,
            swimVer = v and 0x3F,
            vid = ((r[3].toInt() and 0xFF) shl 8) or (r[2].toInt() and 0xFF),
            pid = ((r[5].toInt() and 0xFF) shl 8) or (r[4].toInt() and 0xFF),
        )
    }

    private fun currentMode(): Int {
        usbWrite(cmdBytes(GET_MODE))
        return usbRead(2)[0].toInt() and 0xFF
    }

    private fun exitDfu() = usbWrite(cmdBytes(DFU, DFU_EXIT))
    private fun exitDebug() = usbWrite(cmdBytes(DEBUG, D_EXIT))

    private fun enterSwd() {
        val r = dbg(D_ENTER, 2) { it.put(ENTER_SWD.toByte()) }
        checkOk(r, "Enter SWD")
    }

    private fun driveNrst(level: Int) {
        checkOk(dbg(D_DRIVE_NRST, 2) { it.put(level.toByte()) }, "Drive NRST")
    }

    /** Cortex-M core ID (0x1BA01477 on F1 = Cortex-M3). 0 usually means no target wired. */
    fun coreId(): Int =
        ByteBuffer.wrap(dbg(D_READCOREID, 4)).order(ByteOrder.LITTLE_ENDIAN).int

    // ---------- memory ----------

    fun readMem32(addr: Long, len: Int): ByteArray {
        require(addr % 4 == 0L && len % 4 == 0) { "readMem32 needs 4-byte alignment" }
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = minOf(MAX_XFER, len - off)
            val a = (addr + off).toInt()
            val r = dbg(D_READMEM32, n) { it.putInt(a); it.putShort(n.toShort()) }
            r.copyInto(out, off)
            off += n
        }
        return out
    }

    fun writeMem32(addr: Long, data: ByteArray) {
        require(addr % 4 == 0L && data.size % 4 == 0) { "writeMem32 needs 4-byte alignment" }
        var off = 0
        while (off < data.size) {
            val n = minOf(MAX_XFER, data.size - off)
            val a = (addr + off).toInt()
            dbg(D_WRITEMEM32, 0) { it.putInt(a); it.putShort(n.toShort()) }
            usbWrite(data.copyOfRange(off, off + n))
            off += n
        }
    }

    fun writeMem8(addr: Long, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(64, data.size - off)
            val a = (addr + off).toInt()
            dbg(D_WRITEMEM8, 0) { it.putInt(a); it.putShort(n.toShort()) }
            usbWrite(data.copyOfRange(off, off + n))
            off += n
        }
    }

    fun readReg32(addr: Long): Int =
        ByteBuffer.wrap(readMem32(addr, 4)).order(ByteOrder.LITTLE_ENDIAN).int

    fun writeReg32(addr: Long, value: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        writeMem32(addr, b)
    }

    // ---------- core control ----------

    fun readDebugReg(addr: Long): Int {
        val r = dbg(D_READDEBUGREG, 8) { it.putInt(addr.toInt()) }
        checkOk(r, "Read debug reg")
        return ByteBuffer.wrap(r, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun writeDebugReg(addr: Long, value: Int) {
        val r = dbg(D_WRITEDEBUGREG, 2) { it.putInt(addr.toInt()); it.putInt(value) }
        checkOk(r, "Write debug reg")
    }

    fun halt() = writeDebugReg(DHCSR, 0xA05F0003.toInt())   // DBGKEY | C_HALT | C_DEBUGEN
    fun resume() = writeDebugReg(DHCSR, 0xA05F0001.toInt()) // DBGKEY | C_DEBUGEN

    /** Core registers by DCRSR index: 0-12 = R0-R12, 13 = SP, 14 = LR, 15 = PC, 16 = xPSR. Core must be halted. */
    fun readCoreReg(index: Int): Int {
        val r = dbg(D_READREG, 8) { it.put(index.toByte()) }
        checkOk(r, "Read core register")
        return ByteBuffer.wrap(r, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun writeCoreReg(index: Int, value: Int) {
        checkOk(dbg(D_WRITEREG, 2) { it.put(index.toByte()); it.putInt(value) }, "Write core register")
    }

    /** Resume with PendSV/SysTick/external interrupts masked, so no firmware ISR can hijack a RAM routine. */
    fun runMasked() = writeDebugReg(DHCSR, 0xA05F0009.toInt()) // DBGKEY | C_MASKINTS | C_DEBUGEN

    fun isHalted(): Boolean = (readDebugReg(DHCSR) and 0x00020000) != 0 // S_HALT

    /** Polls until the core halts (e.g. on BKPT). Returns false on timeout. */
    fun waitHalted(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (isHalted()) return true
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(1)
        }
    }

    /** Resets the chip and lets the core run (C_HALT survives a system reset, so clear it). */
    fun resetSystem() {
        checkOk(dbg(D_RESETSYS, 2), "Reset")
        Thread.sleep(20)
        resume()
    }
}
