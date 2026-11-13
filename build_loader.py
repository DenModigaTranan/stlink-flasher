#!/usr/bin/env python3
"""Assemble flashloader.S with llvm-mc and print the stub for pasting into RamLoader.kt.

    python3 build_loader.py            # Kotlin intArrayOf(...) of little-endian halfwords
    python3 build_loader.py --bin out  # also write the raw bytes
    python3 build_loader.py --disasm   # also show llvm-objdump's disassembly (if installed)

Needs llvm-mc (LLVM). arm-none-eabi-as works too if you swap the command in assemble().
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "flashloader.S")


def text_section(elf: bytes) -> bytes:
    """Return the .text bytes of a 32-bit little-endian ELF object."""
    if elf[:4] != b"\x7fELF" or elf[4] != 1 or elf[5] != 1:
        sys.exit("not a 32-bit little-endian ELF")
    (shoff,) = struct.unpack_from("<I", elf, 0x20)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", elf, 0x2E)
    secs = [struct.unpack_from("<10I", elf, shoff + i * shentsize) for i in range(shnum)]
    stroff = secs[shstrndx][4]
    for s in secs:
        end = elf.index(b"\0", stroff + s[0])
        if elf[stroff + s[0]:end] == b".text":
            return elf[s[4]:s[4] + s[5]]
    sys.exit("no .text section")


def assemble(src: str = SRC, keep_obj: str = None) -> bytes:
    mc = shutil.which("llvm-mc")
    if not mc:
        sys.exit("llvm-mc not found")
    with tempfile.TemporaryDirectory() as d:
        obj = os.path.join(d, "loader.o")
        subprocess.run([mc, "-triple=thumbv7m-none-eabi", "-filetype=obj", "-o", obj, src], check=True)
        if keep_obj:
            shutil.copy(obj, keep_obj)
        with open(obj, "rb") as f:
            code = text_section(f.read())
    return code + b"\0" * (-len(code) % 4)  # word-align for 32-bit writes


def halfwords(code: bytes):
    return list(struct.unpack("<%dH" % (len(code) // 2), code))


def kotlin(code: bytes) -> str:
    hw = halfwords(code)
    rows = []
    for i in range(0, len(hw), 8):
        rows.append("        " + ", ".join("0x%04X" % h for h in hw[i:i + 8]) + ",")
    return "intArrayOf(\n" + "\n".join(rows) + "\n    )"


if __name__ == "__main__":
    code = assemble(keep_obj=os.path.join(tempfile.gettempdir(), "loader_disasm.o") if "--disasm" in sys.argv else None)
    print("// %d bytes" % len(code))
    print(kotlin(code))
    if "--bin" in sys.argv:
        out = sys.argv[sys.argv.index("--bin") + 1]
        with open(out, "wb") as f:
            f.write(code)
    if "--disasm" in sys.argv:
        objdump = shutil.which("llvm-objdump")
        if objdump:
            subprocess.run([objdump, "-d", "--triple=thumbv7m-none-eabi",
                            os.path.join(tempfile.gettempdir(), "loader_disasm.o")])
        else:
            print("llvm-objdump not found", file=sys.stderr)
