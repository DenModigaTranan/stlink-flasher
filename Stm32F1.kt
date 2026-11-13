package com.example.stlinkflasher

data class TargetInfo(
    val coreId: Int,
    val devId: Int,
    val revId: Int,
    val flashKb: Int,
    val pageSize: Int,
    val readProtected: Boolean,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "coreId" to coreId,
        "devId" to devId,
        "revId" to revId,
        "flashKb" to flashKb,
        "pageSize" to pageSize,
        "readProtected" to readProtected,
    )
}

typealias Progress = (stage: String, percent: Int) -> Unit

data class DiagStep(val name: String, val ok: Boolean, val detail: String) {
    fun toMap(): Map<String, Any> = mapOf("name" to name, "ok" to ok, "detail" to detail)
}

/**
 * Flash programming for STM32F1-family targets (STM32F103 and the GD32F103 clones that
 * keep the same FPEC register layout) over the SWD link from [StLink].
 *
 * Other families (F0/F3/F4, AT32, MM32 ...) use different flash registers, so [info]
 * refuses anything whose DBGMCU ID is not a known F1 ID rather than poking blind.
 */
class Stm32F1(private val link: StLink) {

    companion object {
        const val FLASH_BASE = 0x08000000L
        const val RAM_BASE = 0x20000000L

        private const val FLASH_KEYR = 0x40022004L
        private const val FLASH_OPTKEYR = 0x40022008L
        private const val FLASH_SR = 0x4002200CL
        private const val FLASH_CR = 0x40022010L
        private const val FLASH_AR = 0x40022014L
        private const val FLASH_OBR = 0x4002201CL
        private const val OPT_BASE = 0x1FFFF800L
        private const val FLASH_SIZE_REG = 0x1FFFF7E0L
        private const val DBGMCU_IDCODE = 0xE0042000L
        private const val DBGMCU_CR = 0xE0042004L
        private const val DBG_IWDG_WWDG_STOP = 0x00000300 // freeze both watchdogs while the core is halted

        private const val KEY1 = 0x45670123
        private const val KEY2 = 0xCDEF89AB.toInt()

        private const val SR_BSY = 0x01
        private const val SR_PGERR = 0x04
        private const val SR_WRPRTERR = 0x10
        private const val SR_EOP = 0x20

        private const val CR_PG = 0x0001
        private const val CR_PER = 0x0002
        private const val CR_MER = 0x0004
        private const val CR_OPTPG = 0x0010
        private const val CR_OPTER = 0x0020
        private const val CR_STRT = 0x0040
        private const val CR_LOCK = 0x0080
        private const val CR_OPTWRE = 0x0200

        private const val OBR_RDPRT = 0x02
        private const val CHUNK = 1024

        // low-/medium-density, high-density, connectivity, value-line, XL
        private val F1_IDS = setOf(0x410, 0x412, 0x414, 0x418, 0x420, 0x428, 0x430)
        private val BIG_PAGE_IDS = setOf(0x414, 0x418, 0x428, 0x430)
    }

    /** Set false to force the slow direct-write path (useful for debugging the loader). */
    var useRamLoader = true

    /** Whether the last [flash] call programmed through the RAM loader (false = fell back). */
    var lastWriteUsedLoader = false
        private set

    private fun rd(a: Long) = link.readReg32(a)
    private fun wr(a: Long, v: Int) = link.writeReg32(a, v)

    // ---------- identification ----------

    fun info(): TargetInfo {
        val core = link.coreId()
        if (core == 0 || core == -1) {
            throw StLinkException(
                "No target answered on SWD. Check SWDIO / SWCLK / GND and that the scooter is powered."
            )
        }
        link.halt()
        // Without this a running watchdog resets the scooter controller mid-flash while we hold it halted.
        runCatching { wr(DBGMCU_CR, rd(DBGMCU_CR) or DBG_IWDG_WWDG_STOP) }
        val idcode = rd(DBGMCU_IDCODE)
        val devId = idcode and 0xFFF
        val revId = (idcode ushr 16) and 0xFFFF
        if (devId !in F1_IDS) {
            throw StLinkException("Unsupported target (DBGMCU ID 0x%03X). Only STM32F1-style flash is supported.".format(devId))
        }
        val flashKb = rd(FLASH_SIZE_REG) and 0xFFFF
        if (flashKb == 0 || flashKb == 0xFFFF) throw StLinkException("Could not read flash size.")
        val page = if (devId in BIG_PAGE_IDS) 2048 else 1024
        val rdp = (rd(FLASH_OBR) and OBR_RDPRT) != 0
        return TargetInfo(core, devId, revId, flashKb, page, rdp)
    }

    // ---------- FPEC helpers ----------

    private fun unlock() {
        if ((rd(FLASH_CR) and CR_LOCK) != 0) {
            wr(FLASH_KEYR, KEY1)
            wr(FLASH_KEYR, KEY2)
            if ((rd(FLASH_CR) and CR_LOCK) != 0) throw StLinkException("Flash would not unlock.")
        }
    }

    private fun lock() = wr(FLASH_CR, rd(FLASH_CR) or CR_LOCK)

    private fun waitReady(timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val sr = rd(FLASH_SR)
            if ((sr and SR_BSY) == 0) {
                wr(FLASH_SR, SR_PGERR or SR_WRPRTERR or SR_EOP) // clear sticky flags
                if ((sr and SR_WRPRTERR) != 0) throw StLinkException("Flash is write-protected (WRP bits set).")
                if ((sr and SR_PGERR) != 0) throw StLinkException("Flash programming error (page not erased?).")
                return
            }
            if (System.currentTimeMillis() > end) throw StLinkException("Flash operation timed out.")
        }
    }

    private fun erasePage(addr: Long) {
        wr(FLASH_CR, CR_PER)
        wr(FLASH_AR, addr.toInt())
        wr(FLASH_CR, CR_PER or CR_STRT)
        waitReady(2_000)
        wr(FLASH_CR, 0)
    }

    private fun programChunk(addr: Long, chunk: ByteArray) {
        wr(FLASH_CR, CR_PG)
        link.writeMem32(addr, chunk) // bus splits each word into two half-word programs
        waitReady(2_000)
        wr(FLASH_CR, 0)
    }

    // ---------- public operations ----------

    fun backup(length: Int?, progress: Progress): ByteArray {
        val t = info()
        if (t.readProtected) throw StLinkException("Chip is read-protected, so flash can't be read.")
        val len = ((length ?: (t.flashKb * 1024)) + 3) and 3.inv()
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = minOf(CHUNK, len - off)
            link.readMem32(FLASH_BASE + off, n).copyInto(out, off)
            off += n
            progress("read", off * 100 / len)
        }
        return out
    }

    fun flash(image: ByteArray, verify: Boolean, skipImageCheck: Boolean, progress: Progress) {
        val t = info()
        if (t.readProtected) {
            throw StLinkException(
                "Chip is read-protected, so flash can't be written. Use 'remove read protection' first (this erases the stock firmware)."
            )
        }
        val capacity = t.flashKb * 1024
        if (image.isEmpty() || image.size > capacity) {
            throw StLinkException("Image is ${image.size} bytes but flash is $capacity bytes.")
        }
        if (!skipImageCheck) checkImage(image, capacity)

        val size = (image.size + 3) and 3.inv()
        val data = ByteArray(size) { 0xFF.toByte() }
        image.copyInto(data)

        unlock()
        try {
            // Check the RAM loader works on this probe/target before erasing anything.
            val loader = if (useRamLoader) RamLoader(link) else null
            val loaderOk = loader?.selfTest() ?: false
            lastWriteUsedLoader = loaderOk

            val pages = (size + t.pageSize - 1) / t.pageSize
            for (p in 0 until pages) {
                erasePage(FLASH_BASE + p.toLong() * t.pageSize)
                progress("erase", (p + 1) * 100 / pages)
            }
            if (loaderOk) {
                loader!!.program(FLASH_BASE, data) { progress("write", it) }
            } else {
                var off = 0
                while (off < size) {
                    val n = minOf(CHUNK, size - off)
                    programChunk(FLASH_BASE + off, data.copyOfRange(off, off + n))
                    off += n
                    progress("write", off * 100 / size)
                }
            }
        } finally {
            runCatching { lock() }
        }

        if (verify) {
            var off = 0
            while (off < size) {
                val n = minOf(CHUNK, size - off)
                val got = link.readMem32(FLASH_BASE + off, n)
                for (i in 0 until n) {
                    if (got[i] != data[off + i]) {
                        throw StLinkException("Verify failed at 0x%08X".format(FLASH_BASE + off + i))
                    }
                }
                off += n
                progress("verify", off * 100 / size)
            }
        }
        progress("reset", 100)
        link.resetSystem()
    }

    fun eraseAll(progress: Progress) {
        val t = info()
        if (t.readProtected) throw StLinkException("Chip is read-protected. Use 'remove read protection' instead.")
        unlock()
        try {
            progress("erase", 0)
            wr(FLASH_CR, CR_MER)
            wr(FLASH_CR, CR_MER or CR_STRT)
            waitReady(30_000)
            wr(FLASH_CR, 0)
            progress("erase", 100)
        } finally {
            runCatching { lock() }
        }
    }

    /**
     * Clears read-out protection. Erasing the option bytes while RDP is active makes the
     * chip mass-erase main flash, so the stock firmware is destroyed. Option bytes are only
     * reloaded on a power-on reset: the user must power-cycle the scooter afterwards.
     */
    fun removeReadProtection(progress: Progress) {
        info()
        unlock()
        try {
            progress("unlock", 0)
            wr(FLASH_OPTKEYR, KEY1)
            wr(FLASH_OPTKEYR, KEY2)
            if ((rd(FLASH_CR) and CR_OPTWRE) == 0) throw StLinkException("Option bytes would not unlock.")

            wr(FLASH_CR, CR_OPTER or CR_OPTWRE)
            wr(FLASH_CR, CR_OPTER or CR_STRT or CR_OPTWRE)
            waitReady(30_000)

            // RDP = 0xA5 (+ complement 0x5A), USER = 0xFF (+ complement 0x00)
            wr(FLASH_CR, CR_OPTPG or CR_OPTWRE)
            wr(OPT_BASE, 0x00FF5AA5)
            waitReady(2_000)
            wr(FLASH_CR, 0)
            progress("unlock", 100)
        } finally {
            runCatching { lock() }
        }
    }

    fun reset() = link.resetSystem()

    /**
     * Non-destructive bring-up check. Halts the core and uses SRAM and core registers, but never
     * writes flash or option bytes. Stops at the first step whose failure makes later steps
     * meaningless (no SWD answer); otherwise runs every step so one report shows all problems.
     * Leaves the target reset and running.
     */
    fun diagnose(): List<DiagStep> {
        val out = mutableListOf<DiagStep>()
        fun step(name: String, block: () -> String): Boolean = try {
            out += DiagStep(name, true, block())
            true
        } catch (e: Exception) {
            out += DiagStep(name, false, e.message ?: e.toString())
            false
        }

        var t: TargetInfo? = null
        if (!step("Target answers on SWD") {
                val i = info()
                t = i
                "core ID 0x%08X".format(i.coreId)
            }
        ) return out
        val target = t!!

        step("Core halts") {
            val d = link.readDebugReg(StLink.DHCSR)
            require((d and 0x00020000) != 0) { "S_HALT not set (DHCSR = 0x%08X)".format(d) }
            "DHCSR = 0x%08X".format(d)
        }

        step("Chip identified") {
            "DBGMCU ID 0x%03X rev 0x%04X, %d KB flash, %d-byte pages, read protection %s".format(
                target.devId, target.revId, target.flashKb, target.pageSize,
                if (target.readProtected) "ON" else "off",
            )
        }

        step("SRAM write/read") {
            val a = RAM_BASE + 0x400
            for (pat in listOf(0xA5, 0x5A)) {
                val p = ByteArray(16) { (pat xor it).toByte() }
                link.writeMem32(a, p)
                if (!link.readMem32(a, 16).contentEquals(p)) {
                    throw StLinkException("Read-back differs (pattern 0x%02X)".format(pat))
                }
            }
            "ok at 0x%08X".format(a)
        }

        step("Core register write/read") {
            link.writeCoreReg(4, 0x12345678)
            val v = link.readCoreReg(4)
            if (v != 0x12345678) throw StLinkException("Wrote 0x12345678 to R4, read 0x%08X".format(v))
            "ok (the RAM loader depends on this)"
        }

        step("Flash read") {
            if (target.readProtected) {
                "skipped: chip is read-protected"
            } else {
                val b = link.readMem32(FLASH_BASE, 8)
                val w0 = java.nio.ByteBuffer.wrap(b, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                val w1 = java.nio.ByteBuffer.wrap(b, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                "initial SP 0x%08X, reset vector 0x%08X".format(w0, w1)
            }
        }

        step("RAM loader self-test") {
            RamLoader(link).selfTestOrThrow()
            "loader ran from SRAM and halted on BKPT"
        }

        step("Reset and run") {
            link.resetSystem()
            "ok"
        }
        return out
    }

    // ---------- safety ----------

    /** Cheap guard against flashing encrypted / wrong files: a Cortex-M image starts with SP then reset vector. */
    private fun checkImage(img: ByteArray, capacity: Int) {
        if (img.size < 8) throw StLinkException("Image too small to be firmware.")
        fun word(i: Int): Long =
            (img[i].toLong() and 0xFF) or
                ((img[i + 1].toLong() and 0xFF) shl 8) or
                ((img[i + 2].toLong() and 0xFF) shl 16) or
                ((img[i + 3].toLong() and 0xFF) shl 24)

        val sp = word(0)
        val reset = word(4)
        val spOk = (sp and 0xFFF00000L) == RAM_BASE
        val resetOk = (reset and 1L) == 1L && (reset - 1) in FLASH_BASE until (FLASH_BASE + capacity)
        if (!spOk || !resetOk) {
            throw StLinkException(
                "This doesn't look like a raw Cortex-M firmware image (bad initial stack pointer or reset vector). " +
                    "It may be encrypted, a partial update, or built for another address. Override only if you're sure."
            )
        }
    }
}
