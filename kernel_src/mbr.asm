[bits 16]
[org 0x7c00]

; FAT32 Boot Sector Structure
jmp short start
nop

; BPB for FAT32
OEMName             db "NEWREPO "
BytesPerSector      dw 512
SectorsPerCluster   db 8
ReservedSectors     dw 2048 ; Kernel lives here
NumberOfFATs        db 2
RootEntries         dw 0
TotalSectors16      dw 0
Media               db 0xF8
SectorsPerFAT16     dw 0
SectorsPerTrack     dw 63
HeadsPerDrive       dw 255
HiddenSectors       dd 0
TotalSectors32      dd 0 ; Filled by installer

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

    mov si, msg_loading
    call print_string

    ; Check for LBA Extensions
    mov ah, 0x41
    mov bx, 0x55aa
    mov dl, [boot_drive]
    int 0x13
    jc lba_not_supported
    cmp bx, 0xaa55
    jne lba_not_supported

    ; Load Kernel using LBA Extensions (Sector 1, load 256 sectors = 128KB)
    mov ah, 0x42
    mov dl, [boot_drive]
    mov si, dap
    int 0x13
    jc disk_error

    ; Switch to Protected Mode
    cli
    lgdt [gdt_descriptor]
    mov eax, cr0
    or eax, 1
    mov cr0, eax
    jmp 0x08:init_pm

lba_not_supported:
    mov si, msg_no_lba
    call print_string
    jmp halt

[bits 32]
init_pm:
    mov ax, 0x10
    mov ds, ax
    mov es, ax
    mov fs, ax
    mov gs, ax
    mov ss, ax

    ; Relocate kernel from 0x8000 to 1MB
    mov esi, 0x8000
    mov edi, 0x100000
    mov ecx, 32768 ; 128KB in dwords
    rep movsd

    mov esp, 0x90000
    push 0x1337B001 ; Magic for HDD boot
    push 0x0        ; No Multiboot info
    call 0x100000

halt:
    hlt
    jmp halt

disk_error:
    mov si, msg_error
    call print_string
    jmp halt

[bits 16]
print_string:
    lodsb
    or al, al
    jz .done
    mov ah, 0x0e
    int 0x10
    jmp print_string
.done:
    ret

; Disk Address Packet
align 4
dap:
    db 0x10    ; Size
    db 0       ; Reserved
    dw 256     ; Sectors to read
    dw 0x8000  ; Offset
    dw 0x0000  ; Segment
    dq 1       ; Start LBA

boot_drive db 0x80
msg_loading db "Loading Kernel...", 13, 10, 0
msg_error   db "Disk Error!", 13, 10, 0
msg_no_lba  db "LBA Error!", 13, 10, 0

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
