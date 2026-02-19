# NewRepo Kernel v4.1

Bu kernel x86 32-bit mimarisinde çalisan, yüksek kararlilikli HDD kurulum ve boot özelliklerine sahip bir çekirdektir.

## Yenilikler (v4.1)
- **Hassas HDD Boot:** Magic value (EAX) ve stack yönetimi düzeltildi. Artik ISO/HDD boot ayrimi %100 dogru çalismaktadir.
- **Güvenli Yazma & Dogrulama:** HDD'ye yazilan her sektör (MBR dahil) anlik olarak geri okunur ve karsilastirilir. Hata durumunda kurulum durdurulur.
- **Raw Binary Entegrasyonu:** Kernel artik kurulum için kendi raw binary'sini içinde tasir. Bu sayede ELF header çakismalari önlenmistir.
- **LBA 4.1:** MBR yükleyicisi 127 sektöre (~64KB) kadar kernel yükleyebilir ve LBA paketlerini kullanir.

## Ekran Çiktilari
- "MBR v4.1 Loading Kernel... OK. JMP 1MB..."
- "Durum: Depolama alanindan boot edildi"
- "MBR yaziliyor... OK"
- "Kernel yaziliyor... (dogrulama noktaları)"

## Derleme Komutlari

### 1. Bagimliliklar
```bash
sudo apt-get update
sudo apt-get install -y nasm gcc-multilib grub-common grub-pc-bin xorriso mtools
```

### 2. Derleme Süreci (build_kernel.sh)
```bash
# 1. MBR Derle
nasm -f bin mbr.asm -o mbr.bin

# 2. Geçici Kernel Derle (Raw Binary olusturmak için)
touch kernel.bin # Dummy
nasm -f elf32 boot.asm -o boot.o
gcc -m32 -c kernel.c -o kernel.o -ffreestanding -O2 -Wall -Wextra -fno-stack-protector -fno-pie
gcc -m32 -T linker.ld -o myos_tmp.bin -ffreestanding -O2 -nostdlib boot.o kernel.o -lgcc -no-pie -Wl,--build-id=none

# 3. Raw Binary Olustur
objcopy -O binary myos_tmp.bin kernel.bin

# 4. Final Kernel Derle (MBR ve Kernel.bin artik gömülü)
nasm -f elf32 boot.asm -o boot.o
gcc -m32 -c kernel.c -o kernel.o -ffreestanding -O2 -Wall -Wextra -fno-stack-protector -fno-pie
gcc -m32 -T linker.ld -o myos.bin -ffreestanding -O2 -nostdlib boot.o kernel.o -lgcc -no-pie -Wl,--build-id=none
```

### 3. ISO Olusturma
```bash
mkdir -p isodir/boot/grub
cp myos.bin isodir/boot/myos.bin
echo 'set timeout=0
set default=0
menuentry "NewRepo Kernel" {
    multiboot /boot/myos.bin
    boot
}' > isodir/boot/grub/grub.cfg
grub-mkrescue -o NewRepo.iso isodir
```
