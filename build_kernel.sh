#!/bin/bash
set -e
cd kernel_src

# 1. Compile MBR
nasm -f bin mbr.asm -o mbr.bin

# 2. Compile Kernel components (Temporary)
nasm -f elf32 boot.asm -o boot.o
gcc -m32 -c kernel.c -o kernel.o -ffreestanding -O2 -Wall -Wextra -fno-stack-protector -fno-pie
gcc -m32 -T linker.ld -o myos_tmp.bin -ffreestanding -O2 -nostdlib boot.o kernel.o -lgcc -no-pie -Wl,--build-id=none

# 3. Create raw kernel binary
objcopy -O binary myos_tmp.bin kernel.bin

# 4. Re-compile boot.asm with the newly created kernel.bin and mbr.bin embedded
nasm -f elf32 boot.asm -o boot.o
gcc -m32 -c kernel.c -o kernel.o -ffreestanding -O2 -Wall -Wextra -fno-stack-protector -fno-pie
gcc -m32 -T linker.ld -o myos.bin -ffreestanding -O2 -nostdlib boot.o kernel.o -lgcc -no-pie -Wl,--build-id=none

echo "Build successful: myos.bin created."
