; kernel_src/boot.asm
MBALIGN  equ  1 << 0
MEMINFO  equ  1 << 1
FLAGS    equ  MBALIGN | MEMINFO
MAGIC    equ  0x1BADB002
CHECKSUM equ -(MAGIC + FLAGS)

section .text
global _start
_start:
    jmp hdd_entry ; This jump will be at the very start of the binary

align 4
multiboot_header:
    dd MAGIC
    dd FLAGS
    dd CHECKSUM

hdd_entry:
    ; Stack setup
    mov esp, stack_top

    ; Push parameters for kmain(mb_info, magic)
    ; When booting from MBR: EAX=0x1337B001, EBX=0
    ; When booting from GRUB: EAX=0x2BADB002, EBX=multiboot_info_ptr
    push eax ; magic
    push ebx ; mb_info

    extern kmain
    call kmain

    ; If kmain returns, hang
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

global kernel_bin
kernel_bin:
    incbin "kernel.bin"
kernel_bin_end:
global kernel_bin_size
kernel_bin_size: dd kernel_bin_end - kernel_bin

section .bss
align 16
stack_bottom:
resb 16384 ; 16 KiB
stack_top:
