[bits 16]
[org 0x7c00]

start:
    xor ax, ax
    mov ds, ax
    mov es, ax
    mov ss, ax
    mov sp, 0x7c00

    mov si, msg_loading
    call print_string

    ; Reset disk system
    xor ax, ax
    int 0x13

    ; Load kernel from disk
    ; Load 127 sectors (max for some BIOS) starting from sector 2
    mov ax, 0x0000
    mov es, ax
    mov bx, 0x8000
    mov ah, 0x02
    mov al, 127
    mov ch, 0
    mov dh, 0
    mov cl, 2
    int 0x13
    jc disk_error

    ; Switch to PM
    cli
    lgdt [gdt_descriptor]
    mov eax, cr0
    or eax, 1
    mov cr0, eax
    jmp 0x08:init_pm

[bits 32]
init_pm:
    mov ax, 0x10
    mov ds, ax
    mov es, ax
    mov fs, ax
    mov gs, ax
    mov ss, ax

    ; Move kernel from 0x8000 to 0x100000
    mov esi, 0x8000
    mov edi, 0x100000
    mov ecx, 16384 ; 64KB
    rep movsd

    ; Prepare stack
    mov esp, 0x90000

    ; Jump to kernel
    ; kmain(mb_info, magic)
    push 0x1337B001 ; magic
    push 0x0        ; mb_info
    call 0x100000

halt:
    hlt
    jmp halt

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

msg_loading db "Loading...", 13, 10, 0
msg_error   db "Error!", 13, 10, 0

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
