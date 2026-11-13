#!/usr/bin/env python3
"""Runs the assembled flash loader in a small Thumb-16 emulator against a model of the
STM32F1 flash controller (FPEC). The decoder only knows the instructions the loader uses;
anything else raises, so a stray encoding can't slip through.

    python3 test_flashloader.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_loader import assemble  # noqa: E402

M32 = 0xFFFFFFFF
FLASH_BASE, FLASH_SIZE = 0x08000000, 0x4000
RAM_BASE, RAM_SIZE = 0x20000000, 0x2000
FPEC, IWDG = 0x40022000, 0x40003000
BUF = RAM_BASE + 0x100


class Halt(Exception):
    pass


class Machine:
    def __init__(self, write_protect=()):
        self.r = [0] * 16
        self.flash = bytearray(b"\xff" * FLASH_SIZE)
        self.ram = bytearray(RAM_SIZE)
        self.write_protect = set(write_protect)  # halfword-aligned flash offsets
        self.cr = 0
        self.sr = 0
        self.busy = 0
        self.iwdg_kicks = 0
        self.ram_writes = 0
        self.stray_writes = 0       # flash writes while PG was clear
        self.overlap_writes = 0     # flash writes while BSY was set
        self.n = self.z = self.c = self.v = 0

    # ----- memory -----
    def _flash_off(self, a):
        o = a - FLASH_BASE
        return o if 0 <= o < FLASH_SIZE else None

    def read(self, a, size):
        if RAM_BASE <= a < RAM_BASE + RAM_SIZE:
            return int.from_bytes(self.ram[a - RAM_BASE:a - RAM_BASE + size], "little")
        o = self._flash_off(a)
        if o is not None:
            return int.from_bytes(self.flash[o:o + size], "little")
        if a == FPEC + 0x0C and size == 4:
            v = self.sr | (1 if self.busy > 0 else 0)
            if self.busy > 0:
                self.busy -= 1
            return v
        if a == FPEC + 0x10 and size == 4:
            return self.cr
        raise RuntimeError("read of unmapped 0x%08X" % a)

    def write(self, a, size, val):
        if RAM_BASE <= a < RAM_BASE + RAM_SIZE:
            self.ram_writes += 1
            self.ram[a - RAM_BASE:a - RAM_BASE + size] = val.to_bytes(size, "little")
            return
        o = self._flash_off(a)
        if o is not None:
            self._flash_write(o, size, val)
            return
        if a == FPEC + 0x10 and size == 4:
            self.cr = val
            return
        if a == IWDG and size == 4:
            if val == 0xAAAA:
                self.iwdg_kicks += 1
            return
        raise RuntimeError("write to unmapped 0x%08X" % a)

    def _flash_write(self, o, size, val):
        if size != 2:
            raise RuntimeError("flash write must be a halfword, got %d bytes" % size)
        if self.busy > 0:
            self.overlap_writes += 1
        if not (self.cr & 1):            # PG clear: hardware ignores the write
            self.stray_writes += 1
            return
        if o in self.write_protect:
            self.sr |= 0x10              # WRPRTERR
        else:
            cur = int.from_bytes(self.flash[o:o + 2], "little")
            if cur != 0xFFFF and val != 0x0000:
                self.sr |= 0x04          # PGERR: target not erased
            else:
                self.flash[o:o + 2] = val.to_bytes(2, "little")
        self.sr |= 0x20                  # EOP
        self.busy = 3                    # BSY for the next three SR reads

    # ----- ALU helpers -----
    def _nz(self, v):
        self.n = (v >> 31) & 1
        self.z = int(v == 0)

    @staticmethod
    def _awc(a, b, cin):
        s = a + b + cin
        res = s & M32
        c = int(s > M32)
        v = int((((~(a ^ b)) & (a ^ res)) & 0x80000000) != 0)
        return res, c, v

    def _setflags(self, res, c, v):
        self._nz(res)
        self.c, self.v = c, v

    # ----- one instruction -----
    def step(self):
        pc = self.r[15]
        hw = self.read(pc, 2)
        nxt = pc + 2
        r = self.r

        if hw >> 13 == 0b000:
            op = (hw >> 11) & 3
            if op == 3:
                sub, imm = (hw >> 9) & 1, (hw >> 10) & 1
                x, rn, rd = (hw >> 6) & 7, (hw >> 3) & 7, hw & 7
                b = x if imm else r[x]
                res, c, v = self._awc(r[rn], (~b & M32) if sub else b, sub)
                r[rd] = res
                self._setflags(res, c, v)
            else:
                imm5, rm, rd = (hw >> 6) & 31, (hw >> 3) & 7, hw & 7
                if op == 0:      # LSLS
                    if imm5:
                        self.c = (r[rm] >> (32 - imm5)) & 1
                    res = (r[rm] << imm5) & M32
                elif op == 1:    # LSRS
                    sh = imm5 or 32
                    self.c = (r[rm] >> (sh - 1)) & 1
                    res = r[rm] >> sh if sh < 32 else 0
                else:
                    raise NotImplementedError("ASRS")
                r[rd] = res
                self._nz(res)
        elif hw >> 13 == 0b001:
            op, rd, imm = (hw >> 11) & 3, (hw >> 8) & 7, hw & 0xFF
            if op == 0:          # MOVS
                r[rd] = imm
                self._nz(imm)
            elif op == 1:        # CMP
                self._setflags(*self._awc(r[rd], ~imm & M32, 1))
            elif op == 2:        # ADDS
                res, c, v = self._awc(r[rd], imm, 0)
                r[rd] = res
                self._setflags(res, c, v)
            else:                # SUBS
                res, c, v = self._awc(r[rd], ~imm & M32, 1)
                r[rd] = res
                self._setflags(res, c, v)
        elif hw >> 10 == 0b010000:
            op, rm, rdn = (hw >> 6) & 15, (hw >> 3) & 7, hw & 7
            if op == 0b1000:     # TST
                self._nz(r[rdn] & r[rm])
            elif op == 0b1100:   # ORRS
                r[rdn] |= r[rm]
                self._nz(r[rdn])
            else:
                raise NotImplementedError("data-processing op %s" % bin(op))
        elif hw >> 10 == 0b010001:
            if (hw >> 8) & 3 != 2:
                raise NotImplementedError("special data op")
            rm = (hw >> 3) & 15
            rd = (((hw >> 7) & 1) << 3) | (hw & 7)
            r[rd] = r[rm]        # MOV, no flags
        elif hw >> 13 == 0b011:
            if (hw >> 12) & 1:
                raise NotImplementedError("byte load/store")
            load = (hw >> 11) & 1
            addr = r[(hw >> 3) & 7] + ((hw >> 6) & 31) * 4
            rt = hw & 7
            if load:
                r[rt] = self.read(addr, 4)
            else:
                self.write(addr, 4, r[rt])
        elif hw >> 12 == 0b1000:
            load = (hw >> 11) & 1
            addr = r[(hw >> 3) & 7] + ((hw >> 6) & 31) * 2
            rt = hw & 7
            if load:
                r[rt] = self.read(addr, 2)
            else:
                self.write(addr, 2, r[rt] & 0xFFFF)
        elif hw >> 12 == 0b1101:
            cond, imm8 = (hw >> 8) & 15, hw & 0xFF
            take = {0: self.z == 1, 1: self.z == 0, 2: self.c == 1, 3: self.c == 0}[cond]
            if take:
                off = imm8 - 256 if imm8 & 0x80 else imm8
                nxt = pc + 4 + off * 2
        elif hw >> 11 == 0b11100:
            imm11 = hw & 0x7FF
            off = imm11 - 2048 if imm11 & 0x400 else imm11
            nxt = pc + 4 + off * 2
        elif hw >> 8 == 0xBE:
            raise Halt()
        else:
            raise NotImplementedError("opcode 0x%04X at 0x%08X" % (hw, pc))
        r[15] = nxt

    def run(self, pc, max_steps=500000):
        self.r[15] = pc
        for _ in range(max_steps):
            try:
                self.step()
            except Halt:
                return
        raise RuntimeError("loader did not reach BKPT")


CODE = assemble()


def run_loader(data, dst_off=0, preset=None, write_protect=()):
    m = Machine(write_protect)
    for off, val in (preset or {}).items():
        m.flash[off:off + 2] = val.to_bytes(2, "little")
    m.ram[0:len(CODE)] = CODE
    m.ram[BUF - RAM_BASE:BUF - RAM_BASE + len(data)] = data
    m.ram_writes = 0
    m.r[0] = FLASH_BASE + dst_off
    m.r[1] = BUF
    m.r[2] = len(data)
    m.r[13] = RAM_BASE + 0x1200
    m.run(RAM_BASE)
    return m


class LoaderTests(unittest.TestCase):
    def test_programs_data_and_reports_success(self):
        data = bytes((i * 7 + 3) & 0xFF for i in range(1024))
        m = run_loader(data, dst_off=0x400)
        self.assertEqual(m.r[0], 0)
        self.assertEqual(bytes(m.flash[0x400:0x800]), data)
        self.assertEqual(m.flash[0x3FE:0x400], b"\xff\xff")   # nothing before
        self.assertEqual(m.flash[0x800:0x802], b"\xff\xff")   # nothing after
        self.assertEqual(m.cr, 0, "PG must be cleared at the end")

    def test_never_writes_while_busy_or_without_pg(self):
        m = run_loader(bytes(range(256)) * 4)
        self.assertEqual(m.overlap_writes, 0)
        self.assertEqual(m.stray_writes, 0)

    def test_does_not_touch_ram(self):
        self.assertEqual(run_loader(bytes(range(64))).ram_writes, 0)

    def test_kicks_watchdog_once_per_halfword(self):
        self.assertEqual(run_loader(bytes(range(200))).iwdg_kicks, 100)

    def test_zero_length_is_a_clean_noop(self):
        m = run_loader(b"")
        self.assertEqual(m.r[0], 0)
        self.assertEqual(m.cr, 0)
        self.assertEqual(m.flash, bytearray(b"\xff" * FLASH_SIZE))
        self.assertEqual(m.iwdg_kicks, 0)

    def test_all_ones_and_all_zeros_halfwords(self):
        data = b"\xff\xff" + b"\x00\x00" + b"\x12\x34"
        m = run_loader(data)
        self.assertEqual(m.r[0], 0)
        self.assertEqual(bytes(m.flash[0:6]), data)

    def test_reports_pgerr_and_stops(self):
        m = run_loader(b"\x11\x11\x22\x22\x33\x33\x44\x44", preset={4: 0x1234})
        self.assertNotEqual(m.r[0] & 0x04, 0, "R0 should carry the PGERR flag")
        self.assertEqual(bytes(m.flash[0:4]), b"\x11\x11\x22\x22")   # before the bad spot
        self.assertEqual(bytes(m.flash[4:6]), b"\x34\x12")          # untouched
        self.assertEqual(bytes(m.flash[6:8]), b"\xff\xff")          # never reached
        self.assertEqual(m.cr, 0, "PG must be cleared on the error path too")

    def test_reports_write_protection(self):
        m = run_loader(b"\x11\x11\x22\x22", write_protect={2})
        self.assertNotEqual(m.r[0] & 0x10, 0, "R0 should carry the WRPRTERR flag")
        self.assertEqual(m.cr, 0)

    def test_size_is_word_aligned_and_small(self):
        self.assertEqual(len(CODE) % 4, 0)
        self.assertLess(len(CODE), 0x100, "must fit before the data buffer")


if __name__ == "__main__":
    unittest.main(verbosity=2)
