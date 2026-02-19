[bits 16]
[org 0x7c00]

; FAT32 Boot Sector Structure
jmp short start
nop

; BPB for FAT32
OEMName             db "NEWREPO "
BytesPerSector      dw 512
SectorsPerCluster   db 8
ReservedSectors     dw 2048
NumberOfFATs        db 2
RootEntries         dw 0
TotalSectors16      dw 0
Media               db 0xF8
SectorsPerFAT16     dw 0
SectorsPerTrack     dw 63
HeadsPerDrive       dw 255
HiddenSectors       dd 0
TotalSectors32      dd 0

; FAT32 Extended fields
SectorsPerFAT32     dd 0
ExtFlags            dw 0
FSVersion           dw 0
RootCluster         dd 2
FSInfo              dw 1
BackupBootSector    dw 6
Reserved            times 12 db 0
DriveNumber         db 0x80
Reserved1           db 0
BootSignature       db 0x29
VolumeID            dd 0x12345678
VolumeLabel         db "NEWREPO    "
FileSystemType      db "FAT32   "

start:
    cli
    xor ax, ax
    mov ds, ax
    mov es, ax
    mov ss, ax
    mov sp, 0x7c00
    sti

    mov [boot_drive], dl

    mov si, msg_hello
    call print_string

    ; Enable A20 Line (Fast A20)
    in al, 0x92
    or al, 2
    out 0x92, al

    ; Check for LBA Extensions
    mov ah, 0x41
    mov bx, 0x55aa
    mov dl, [boot_drive]
    int 0x13
    jc no_lba
    cmp bx, 0xaa55
    jne no_lba

    mov si, msg_loading
    call print_string

    ; Load Kernel using LBA Extensions (Sector 1, load 127 sectors = ~64KB)
    mov ah, 0x42
    mov dl, [boot_drive]
    mov si, dap
    int 0x13
    jc disk_error

    mov si, msg_jumping
    call print_string

    ; Switch to Protected Mode
    cli
    lgdt [gdt_descriptor]
    mov eax, cr0
    or eax, 1
    mov cr0, eax

    ; Pass magic value via EAX for detection in kernel
    mov eax, 0x1337B001
    mov ebx, 0 ; No multiboot info

    jmp 0x08:init_pm

no_lba:
    mov si, msg_no_lba
    call print_string
    jmp halt

[bits 32]
init_pm:
    mov cx, 0x10
    mov ds, cx
    mov es, cx
    mov fs, cx
    mov gs, cx
    mov ss, cx

    ; Relocate kernel from 0x8000 to 1MB
    ; esi, edi are already used in previous version, but let's be explicit
    mov esi, 0x8000
    mov edi, 0x100000
    mov ecx, 16384 ; 64KB in dwords
    rep movsd

    ; Jump to kernel (1MB)
    ; Stack will be set up by kernel's boot.asm
    jmp 0x100000

halt:
    hlt
    jmp halt

[bits 16]
disk_error:
    mov si, msg_error
    call print_string
    jmp halt

print_string:
    lodsb
    or al, al
    jz .done
    mov ah, 0x0e
    int 0x10
    jmp print_string
.done:
    ret

; Disk Address Packet (Aligned)
align 4
dap:
    db 0x10    ; Size
    db 0       ; Reserved
    dw 127     ; Sectors to read (~64KB)
    dw 0x8000  ; Offset
    dw 0x0000  ; Segment
    dq 1       ; Start LBA (Sector 1)

boot_drive db 0x80
msg_hello   db "MBR v4.1", 13, 10, 0
msg_loading db "Loading Kernel...", 0
msg_jumping db " OK. JMP 1MB...", 13, 10, 0
msg_error   db 13, 10, "Disk Read Error!", 0
msg_no_lba  db 13, 10, "LBA Error!", 0

gdt_start:
    dd 0x0, 0x0
gdt_code:
    dw 0xffff, 0x0
    db 0x0, 10011010b, 11001111b, 0x0
gdt_data:
    dw 0xffff, 0x0
    db 0x0, 10010010b, 11001111b, 0x0
gdt_end:

gdt_descriptor:
    dw gdt_end - gdt_start - 1
    dd gdt_start

times 510-($-$$) db 0
dw 0xaa55
