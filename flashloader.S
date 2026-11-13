@ RAM flash loader for STM32F1-style FPEC (STM32F103 / GD32F103), Cortex-M3, Thumb-16 only.
@
@ Runs from SRAM. The host halts the core, writes this stub to SRAM, puts the data in an
@ SRAM buffer, sets the registers below, resumes the core and waits for the BKPT.
@
@ In:   r0 = destination address in flash (halfword aligned, area already erased)
@       r1 = source address in SRAM
@       r2 = length in bytes (multiple of 2; 0 is allowed)
@ Out:  r0 = 0 on success, otherwise the FLASH_SR value that contained PGERR/WRPRTERR
@ Uses: r3..r7. No stack. Flash must already be unlocked by the host.
@
@ Each halfword also writes 0xAAAA to IWDG_KR so a running independent watchdog is kept
@ alive (harmless when the watchdog is not enabled).

    .syntax unified
    .thumb
    .text
    .global _start
_start:
    movs r3, #0x40
    lsls r3, r3, #24
    movs r4, #0x22
    lsls r4, r4, #12
    adds r3, r3, r4          @ r3 = 0x40022000  FLASH registers
    movs r4, #1
    str  r4, [r3, #0x10]     @ FLASH_CR = PG
    movs r6, #0xAA
    lsls r7, r6, #8
    orrs r6, r7              @ r6 = 0xAAAA      IWDG reload key
    movs r7, #0x40
    lsls r7, r7, #24
    movs r4, #3
    lsls r4, r4, #12
    adds r7, r7, r4          @ r7 = 0x40003000  IWDG_KR
next:
    cmp  r2, #0
    beq  done
    ldrh r4, [r1]
    strh r4, [r0]            @ program one halfword
    str  r6, [r7]            @ kick watchdog
wait:
    ldr  r4, [r3, #0x0C]     @ FLASH_SR
    lsrs r5, r4, #1          @ carry = BSY
    bcs  wait
    movs r5, #0x14           @ PGERR | WRPRTERR
    tst  r4, r5
    bne  fail
    adds r0, #2
    adds r1, #2
    subs r2, #2
    b    next
done:
    movs r4, #0
    str  r4, [r3, #0x10]     @ FLASH_CR = 0
    movs r0, #0
    bkpt #0
fail:
    movs r5, #0
    str  r5, [r3, #0x10]     @ FLASH_CR = 0
    mov  r0, r4              @ return SR so the host can tell what went wrong
    bkpt #0
