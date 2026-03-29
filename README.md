# NewRepo Kernel v5.0

Bu kernel x86 32-bit mimarisinde çalisan, gelismis donanim tespiti ve yüksek kararlilikli HDD kurulum özelliklerine sahip bir çekirdektir.

## Yenilikler (v5.0)
- **Detayli HDD Tespiti:** Artik HDD Model Adini (örn: QEMU HARDDISK) ve hassas boyut bilgisini (GB/MB ve Sektör bazinda) gösterir.
- **Düzeltilmis RAM Raporlama:** Multiboot üzerinden gelen bellek bilgisi hem ISO hem HDD boot durumlarinda dogru islenir.
- **Hatasiz Metinler:** Tüm ekran çiktilari ASCII uyumlu standart Türkçe (i, s, c, g, o, u) karakterler ile temizlendi.
- **Sessiz ve Kararli Boot:** Bootloader mesajlari sadelestirildi, A20 hatti ve LBA yükleme mantigi optimize edildi.
- **Güvenli Dogrulama:** Kurulum sirasinda MBR ve Kernel verileri yazildiktan sonra anlik olarak dogrulanmaya devam eder.

## Ekran Çiktilari
- "Kernel Calisiyor"
- "Durum: Isodan boot edildi"
- "Depolama Bilgisi: [Model Name] | 1.25 GB (2621440 sektor)"
- "Bellek Bilgisi: 512 MB RAM (524288 KB)"

## Derleme Komutlari

### 1. Bagimliliklar
```bash
sudo apt-get update
sudo apt-get install -y nasm gcc-multilib grub-common grub-pc-bin xorriso mtools
```

### 2. Derleme Süreci (Build)
```bash
# 1. MBR Derle
nasm -f bin mbr.asm -o mbr.bin

# 2. Kernel Raw Binary Hazirla
touch kernel.bin
nasm -f elf32 boot.asm -o boot.o
gcc -m32 -c kernel.c -o kernel.o -ffreestanding -O2 -Wall -Wextra -fno-stack-protector -fno-pie
gcc -m32 -T linker.ld -o myos_tmp.bin -ffreestanding -O2 -nostdlib boot.o kernel.o -lgcc -no-pie -Wl,--build-id=none
objcopy -O binary myos_tmp.bin kernel.bin

# 3. Final Kernel Derle
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
