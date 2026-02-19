# NewRepo Kernel v2.0

Bu kernel x86 32-bit mimarisinde çalisan, Multiboot ve MBR uyumlu, HDD destegi olan bir çekirdektir.

## Yeni Özellikler (v2.0)
- **Depolama Destegi:** ATA PIO modu ile HDD tanima ve boyut ölçümü.
- **Boot Kaynagi Tespiti:** ISO'dan mi yoksa HDD'den mi boot edildigini anlar.
- **HDD Kurulumu:** ISO'dan boot edildiginde, kendini HDD'nin ham sektörlerine (MBR + Kernel) kurabilir.
- **Güç Yönetimi:** QEMU/VirtualBox üzerinde klavye tusu ile bilgisayari kapatma (ACPI).
- **Bellek Bilgisi:** Toplam RAM miktarini gösterir.

## Ekran Çiktilari
### ISO'dan Boot Edildiginde:
- "Kernel Çalisiyor"
- "Durum: Isodan boot edildi"
- "Depolama Bilgisi: ... GB"
- "Bellek Bilgisi: ... MB RAM"
- "Kerneli depolama alanina aktarmak için herhangi bir tusa basin."

### HDD'den Boot Edildiginde:
- "Kernel Çalisiyor"
- "Durum: Depolama alanindan boot edildi"
- "Depolama Bilgisi: ... GB"
- "Bellek Bilgisi: ... MB RAM"
- "Bilgisayari kapatmak için herhangi bir tusa basin."

## Teknik Detaylar
1. **MBR Bootloader (`mbr.asm`):** 16-bit gerçek modda baslar, A20 hattini açar, GDT yükler ve 32-bit Korumalı Moda geçer. Kernel'i disk sektörlerinden (Sector 1+) 1MB adresine yükleyip çalistirir.
2. **HDD Kurulumu:** Kernel, kendi içindeki MBR binary'sini Sektör 0'a, kernel kodunu ise Sektör 1 ve sonrasına ham olarak yazar.
3. **Kapatma:** `0x604` (QEMU) ve `0x4004` (VirtualBox) I/O portlari üzerinden ACPI shutdown komutu gönderir.

## Derleme Komutlari

### 1. Bagimliliklar
```bash
sudo apt-get install nasm gcc-multilib grub-common grub-pc-bin xorriso mtools
```

### 2. MBR ve Kernel Derleme
```bash
nasm -f bin mbr.asm -o mbr.bin
nasm -f elf32 boot.asm -o boot.o
gcc -m32 -c kernel.c -o kernel.o -ffreestanding -O2 -Wall -Wextra -fno-stack-protector -fno-pie
gcc -m32 -T linker.ld -o myos.bin -ffreestanding -O2 -nostdlib boot.o kernel.o -lgcc -no-pie -Wl,--build-id=none
```

### 3. ISO Olusturma
```bash
mkdir -p isodir/boot/grub
cp myos.bin isodir/boot/myos.bin
grub-mkrescue -o NewRepo.iso isodir
```
