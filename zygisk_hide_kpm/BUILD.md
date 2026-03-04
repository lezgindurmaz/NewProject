# Zygisk Stealth Hide KPM - Build Instructions

This module is designed for Android kernels (ARM64).
To compile it, you need the Kernel Source for your device and a Cross-Compiler (Clang/GCC).

## Compilation Steps:

1. Set up your build environment:
   ```bash
   export ARCH=arm64
   export CROSS_COMPILE=aarch64-linux-gnu-
   # Or use Clang from NDK
   ```

2. Point to your kernel source:
   ```bash
   make -C /path/to/your/kernel_source M=$(PWD) modules
   ```

3. The output `zygisk_hide.ko` should be placed in the `kpm/` directory of the flashable ZIP.

## Packaging:
Zip the contents of the `zygisk_hide_module` directory to create the installer.
