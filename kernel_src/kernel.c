#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct multiboot_info {
    uint32_t flags;
    uint32_t mem_lower;
    uint32_t mem_upper;
} __attribute__((packed));

enum vga_color {
    VGA_COLOR_WHITE = 15,
    VGA_COLOR_BLUE = 1,
};

static inline uint8_t vga_entry_color(enum vga_color fg, enum vga_color bg) {
    return fg | bg << 4;
}

static inline uint16_t vga_entry(unsigned char uc, uint8_t color) {
    return (uint16_t) uc | (uint16_t) color << 8;
}

static const size_t VGA_WIDTH = 80;
static const size_t VGA_HEIGHT = 25;
size_t terminal_row;
size_t terminal_column;
uint8_t terminal_color;
uint16_t* terminal_buffer;

void terminal_initialize(void) {
    terminal_row = 0;
    terminal_column = 0;
    terminal_color = vga_entry_color(VGA_COLOR_WHITE, VGA_COLOR_BLUE);
    terminal_buffer = (uint16_t*) 0xB8000;
    for (size_t y = 0; y < VGA_HEIGHT; y++) {
        for (size_t x = 0; x < VGA_WIDTH; x++) {
            terminal_buffer[y * VGA_WIDTH + x] = vga_entry(' ', terminal_color);
        }
    }
}

void terminal_putchar(char c) {
    if (c == '\n') {
        terminal_column = 0;
        if (++terminal_row == VGA_HEIGHT) terminal_row = 0;
        return;
    }
    if (c == '\b') {
        if (terminal_column > 0) terminal_column--;
        terminal_buffer[terminal_row * VGA_WIDTH + terminal_column] = vga_entry(' ', terminal_color);
        return;
    }
    terminal_buffer[terminal_row * VGA_WIDTH + terminal_column] = vga_entry(c, terminal_color);
    if (++terminal_column == VGA_WIDTH) {
        terminal_column = 0;
        if (++terminal_row == VGA_HEIGHT) terminal_row = 0;
    }
}

void terminal_writestring(const char* data) {
    for (size_t i = 0; data[i] != '\0'; i++) terminal_putchar(data[i]);
}

void terminal_writeuint(uint32_t n) {
    if (n == 0) { terminal_putchar('0'); return; }
    char buf[11];
    int i = 10;
    buf[i--] = '\0';
    while (n > 0) { buf[i--] = (n % 10) + '0'; n /= 10; }
    terminal_writestring(&buf[i+1]);
}

static inline void outb(uint16_t port, uint8_t val) { asm volatile ( "outb %0, %1" : : "a"(val), "Nd"(port) ); }
static inline uint8_t inb(uint16_t port) { uint8_t ret; asm volatile ( "inb %1, %0" : "=a"(ret) : "Nd"(port) ); return ret; }
static inline uint16_t inw(uint16_t port) { uint16_t ret; asm volatile ( "inw %1, %0" : "=a"(ret) : "Nd"(port) ); return ret; }
static inline void outw(uint16_t port, uint16_t val) { asm volatile ( "outw %0, %1" : : "a"(val), "Nd"(port) ); }

uint32_t hdd_sectors = 0;

void ata_identify() {
    outb(0x1F6, 0xA0);
    outb(0x1F2, 0);
    outb(0x1F3, 0);
    outb(0x1F4, 0);
    outb(0x1F5, 0);
    outb(0x1F7, 0xEC);
    uint8_t status = inb(0x1F7);
    if (status == 0) { terminal_writestring("HDD bulunamadi."); return; }
    while (inb(0x1F7) & 0x80);
    if (inb(0x1F4) != 0 || inb(0x1F5) != 0) { terminal_writestring("ATA degil."); return; }
    while (!(inb(0x1F7) & 0x08));
    uint16_t data[256] = {0};
    for (int i = 0; i < 256; i++) data[i] = inw(0x1F0);
    hdd_sectors = *((uint32_t*)(data + 60));
    terminal_writestring("Primary Master HDD: ");
    terminal_writeuint(hdd_sectors / 2 / 1024 / 1024);
    terminal_writestring(" GB");
}

void ata_write_sector(uint32_t lba, uint16_t* buffer) {
    outb(0x1F6, 0xE0 | ((lba >> 24) & 0x0F));
    outb(0x1F2, 1);
    outb(0x1F3, (uint8_t)lba);
    outb(0x1F4, (uint8_t)(lba >> 8));
    outb(0x1F5, (uint8_t)(lba >> 16));
    outb(0x1F7, 0x30);
    while (inb(0x1F7) & 0x80);
    while (!(inb(0x1F7) & 0x08));
    for (int i = 0; i < 256; i++) outw(0x1F0, buffer[i]);
    outb(0x1F7, 0xE7);
    while (inb(0x1F7) & 0x80);
}

char get_key() {
    if (inb(0x64) & 1) {
        uint8_t sc = inb(0x60);
        if (sc & 0x80) return 0;
        static char kbd[] = {0,27,'1','2','3','4','5','6','7','8','9','0','-','=','\b','\t','q','w','e','r','t','y','u','i','o','p','[',']','\n',0,'a','s','d','f','g','h','j','k','l',';','\'','`',0,'\\','z','x','c','v','b','n','m',',','.','/',0,'*',0,' '};
        if (sc < sizeof(kbd)) return kbd[sc];
    }
    return 0;
}

void wait_key() {
    char c;
    while (!(c = get_key()));
}

void shutdown() {
    terminal_writestring("\nSistem kapatiliyor...\n");
    outw(0x604, 0x2000); // QEMU
    outw(0x4004, 0x3400); // VBox
    outw(0xB004, 0x2000); // Bochs
    while(1) asm("hlt");
}

extern uint8_t mbr_bin[];
extern uint32_t mbr_bin_size;
extern uint8_t _kernel_start[];
extern uint8_t _kernel_end[];

void install_to_hdd() {
    terminal_writestring("\nHDD'ye kopyalaniyor...\n");
    ata_write_sector(0, (uint16_t*)mbr_bin);
    uint8_t* k_start = _kernel_start;
    uint8_t* k_end = _kernel_end;
    uint32_t k_size = (uint32_t)(k_end - k_start);
    uint32_t num_sectors = (k_size + 511) / 512;
    terminal_writestring("Kernel boyutu: ");
    terminal_writeuint(k_size);
    terminal_writestring(" byte (");
    terminal_writeuint(num_sectors);
    terminal_writestring(" sektor)\n");
    for (uint32_t i = 0; i < num_sectors; i++) {
        ata_write_sector(i + 1, (uint16_t*)(k_start + i * 512));
        if (i % 10 == 0) terminal_putchar('.');
    }
    terminal_writestring("\nTamamlandi! ISO'yu cikarin ve yeniden baslatin.\n");
    wait_key();
}

void ram_keyboard_test() {
    terminal_writestring("\n--- Klavye & RAM Testi ---\n");
    terminal_writestring("Yazdiginiz her karakter RAM'e (0x1100000) yazilir ve dogrulanir.\n");
    terminal_writestring("Cikmak icin ENTER'a basin.\n");
    volatile char* ram_ptr = (volatile char*)0x1100000;
    while (true) {
        char c = get_key();
        if (c) {
            if (c == '\n') break;
            terminal_putchar(c);
            *ram_ptr = c;
            if (*ram_ptr != c) terminal_writestring("[RAM HATASI!]");
        }
    }
    terminal_writestring("\nTest bitti.\n");
}

void kmain(struct multiboot_info* mb_info, uint32_t magic) {
    terminal_initialize();
    terminal_writestring("Kernel Calisiyor\n");
    bool from_hdd = (magic == 0x1337B001);
    if (from_hdd) terminal_writestring("Durum: Depolama alanindan boot edildi\n");
    else terminal_writestring("Durum: Isodan boot edildi\n");
    terminal_writestring("Depolama Bilgisi: ");
    ata_identify();
    terminal_putchar('\n');
    terminal_writestring("Bellek Bilgisi: ");
    if (magic == 0x2BADB002 && mb_info && (mb_info->flags & 1)) {
        terminal_writeuint((mb_info->mem_lower + mb_info->mem_upper) / 1024 + 1);
        terminal_writestring(" MB RAM\n");
    } else { terminal_writestring("Bilinmiyor\n"); }

    ram_keyboard_test();

    if (!from_hdd) {
        terminal_writestring("\nKerneli depolama alanina aktarmak icin herhangi bir tusa basin.\n");
        wait_key();
        install_to_hdd();
    } else {
        terminal_writestring("\nBilgisayari kapatmak icin herhangi bir tusa basin.\n");
        wait_key();
        shutdown();
    }
}
