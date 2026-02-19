# NewRepo Kernel v3.0

Bu kernel x86 32-bit mimarisinde çalisan, Multiboot ve MBR uyumlu, FAT32 dosya sistemi yapisina sahip bir çekirdektir.

## Yenilikler (v3.0)
- **Klavye & RAM Testi:** Yazdiginiz verileri 0x1100000 adresine yazar ve anlik olarak dogrular.
- **Gelistirilmis Depolama:** LBA48 destegi ile TB seviyesindeki disklerin boyutunu dogru gösterir.
- **FAT32 Uyumluluk:** HDD'ye kurulumda diski FAT32 standartlarina (BPB) uygun formatlar.
- **Kararli Boot:** HDD'den boot ederken modern LBA Extensions (\`int 0x13 ah=0x42\`) kullanir.
- **Gelistirilmis Giris:** Kernel artik bir jump talimati ile baslar, bu da HDD boot kararliligini artirir.

## Ekran Çiktilari
- \"Kernel Çalisiyor\"
- \"Durum: Isodan/Depolama alanindan boot edildi\"
- \"Depolama Bilgisi: ... GB (... sektor)\"
- \"Bellek Bilgisi: ... MB RAM\"

## Derleme Komutlari

### 1. Sistem Bagimliliklari
\`\`\`bash
sudo apt-get update
sudo apt-get install -y nasm gcc-multilib grub-common grub-pc-bin xorriso mtools
\`\`\`

### 2. Derleme (Build)
\`\`\`bash
# MBR Derleme
nasm -f bin mbr.asm -o mbr.bin

# Kernel Derleme
nasm -f elf32 boot.asm -o boot.o
gcc -m32 -c kernel.c -o kernel.o -ffreestanding -O2 -Wall -Wextra -fno-stack-protector -fno-pie
gcc -m32 -T linker.ld -o myos.bin -ffreestanding -O2 -nostdlib boot.o kernel.o -lgcc -no-pie -Wl,--build-id=none
\`\`\`

### 3. ISO Olusturma
\`\`\`bash
mkdir -p isodir/boot/grub
cp myos.bin isodir/boot/myos.bin
echo 'set timeout=0
set default=0
menuentry "NewRepo Kernel" {
    multiboot /boot/myos.bin
    boot
}' > isodir/boot/grub/grub.cfg
grub-mkrescue -o NewRepo.iso isodir
\`\`\`
