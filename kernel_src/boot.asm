; kernel_src/boot.asm
MBALIGN  equ  1 << 0
MEMINFO  equ  1 << 1
FLAGS    equ  MBALIGN | MEMINFO
MAGIC    equ  0x1BADB002
CHECKSUM equ -(MAGIC + FLAGS)

section .text
global _start
_start:
    jmp hdd_entry

align 4
multiboot_header:
    dd MAGIC
    dd FLAGS
    dd CHECKSUM

hdd_entry:
    mov esp, stack_top

    push eax ; magic
    push ebx ; mb_info

    extern kmain
    call kmain
    cli
.hang:
    hlt
    jmp .hang

section .data
global mbr_bin
mbr_bin:
    incbin "mbr.bin"
mbr_bin_end:
global mbr_bin_size
mbr_bin_size: dd mbr_bin_end - mbr_bin

section .bss
align 16
stack_bottom:
resb 16384 ; 16 KiB
stack_top:
