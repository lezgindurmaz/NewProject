#!/bin/bash
# WinPE Builder Script - Enhanced Version v1.1
set -e

# Configuration
ISO_NAME="winpe_x64_recovery_v1.1.iso"
WORK_DIR="/tmp/winpe_build_root"
TOOLS_DIR="$WORK_DIR/tools"
EXTRACT_DIR="$WORK_DIR/extracted"
ISO_ROOT="$WORK_DIR/iso_root"
WIM_PATH="$ISO_ROOT/sources/boot.wim"
REPO="lezgindurmaz/NewProject"
TAG="winpe-v1.1"

echo "=== Windows 10 x64 WinPE Recovery Builder v1.1 ==="

# 1. Prerequisites
echo "[*] Checking and installing dependencies..."
sudo apt-get update && sudo apt-get install -y wimtools xorriso cabextract p7zip-full curl unzip isolinux syslinux-common

# 2. Setup Directories
rm -rf "$WORK_DIR"
mkdir -p "$TOOLS_DIR" "$EXTRACT_DIR" "$ISO_ROOT/sources" "$ISO_ROOT/boot/isolinux" "$ISO_ROOT/EFI/boot"

# 3. Download Source Components
echo "[*] Fetching WinPE components from Microsoft..."
curl -Lk "https://go.microsoft.com/fwlink/?linkid=2120253" -o "$WORK_DIR/adkwinpesetup.exe"
7z x "$WORK_DIR/adkwinpesetup.exe" -o"$WORK_DIR/manifest" -y > /dev/null

SETUP_URL=$(curl -Ik https://go.microsoft.com/fwlink/?linkid=2120253 | grep -i "Location" | awk '{print $2}' | tr -d '\r')
BASE_URL=$(echo "$SETUP_URL" | sed 's|/[^/]*$||')

# CABs for x64
CABS=(
    "690b8ac88bc08254d351654d56805aea.cab" # WIM Part 1
    "6d3c63e785ac9ac618ae3f1416062098.cab" # WIM Part 2
    "9722214af0ab8aa9dffb6cfdafd937b7.cab" # Boot Part 1
    "a32918368eba6a062aaaaf73e3618131.cab" # Boot Part 2
    "aa25d18a5fcce134b0b89fb003ec99ff.cab" # Boot Part 3
)

for cab in "${CABS[@]}"; do
    echo "[*] Downloading $cab..."
    curl -Lk "$BASE_URL/Installers/$cab" -o "$WORK_DIR/$cab"
    echo "[*] Extracting $cab..."
    cabextract "$WORK_DIR/$cab" -d "$EXTRACT_DIR" > /dev/null
done

# 4. Identify Files
echo "[*] Identifying WinPE files..."
# Find x64 WIM
WIM_FILE=$(wimlib-imagex info $(find "$EXTRACT_DIR" -size +150M) 2>/dev/null | grep -B 10 "x86_64" | grep "Path:" | awk '{print $2}' | head -n 1)
if [ -z "$WIM_FILE" ]; then
    # Fallback to size-based search if wiminfo fails
    WIM_FILE=$(find "$EXTRACT_DIR" -size +250M -size -350M | head -n 1)
fi

if [ -z "$WIM_FILE" ]; then
    echo "[!] Could not find x64 WIM file."
    exit 1
fi
echo "[+] Found WIM: $WIM_FILE"
cp "$WIM_FILE" "$WIM_PATH"

# Find bootmgr (x64) - It should be around 400KB+ and a boot application
BOOTMGR=$(file "$EXTRACT_DIR"/fil* | grep "PE32+ executable (Windows boot application) x86-64" | cut -d: -f1 | head -n 1)
# Find bootx64.efi
BOOTX64=$(file "$EXTRACT_DIR"/fil* | grep "PE32+ executable (DLL) (EFI application) x86-64" | cut -d: -f1 | head -n 1)
# Find boot.sdi
BOOTSDI=$(find "$EXTRACT_DIR" -size 3170304c | head -n 1)

echo "[+] Found BOOTMGR: $BOOTMGR"
echo "[+] Found BOOTX64: $BOOTX64"
echo "[+] Found BOOTSDI: $BOOTSDI"

if [ -z "$BOOTMGR" ] || [ -z "$BOOTX64" ] || [ -z "$BOOTSDI" ]; then
    echo "[!] Missing critical boot files."
    exit 1
fi

cp "$BOOTMGR" "$ISO_ROOT/bootmgr"
cp "$BOOTSDI" "$ISO_ROOT/boot/boot.sdi"
cp "$BOOTX64" "$ISO_ROOT/EFI/boot/bootx64.efi"

# Extract BCD-Template from WIM
wimlib-imagex extract "$WIM_PATH" 1 /Windows/System32/config/BCD-Template --dest-dir="$WORK_DIR" --no-acls
cp "$WORK_DIR/BCD-Template" "$ISO_ROOT/boot/bcd"
mkdir -p "$ISO_ROOT/EFI/microsoft/boot"
cp "$WORK_DIR/BCD-Template" "$ISO_ROOT/EFI/microsoft/boot/bcd"

# 5. Download Tools
echo "[*] Downloading Explorer++..."
curl -Lk "https://explorerplusplus.com/software/explorer++_1.3.5_x64.zip" -o "$TOOLS_DIR/explorer++.zip"
unzip -q "$TOOLS_DIR/explorer++.zip" -d "$TOOLS_DIR/explorer++"

# 6. Customize WIM
echo "[*] Customizing WinPE Image..."

# Create Recovery Menu Script
cat << 'EOF' > "$TOOLS_DIR/recovery.cmd"
@echo off
:menu
cls
echo ===================================================
echo        WINPE X64 RECOVERY TOOLS MENU v1.1
echo ===================================================
echo 1. Start Explorer++ (File Manager)
echo 2. Start DiskPart (Partitioning/GPT)
echo 3. Scan all disks (chkdsk)
echo 4. Display Disk Information
echo 5. Command Prompt
echo 6. Reboot
echo ===================================================
set /p choice=Select an option (1-6):

if "%choice%"=="1" start "" "\Windows\System32\Explorer++.exe" & goto menu
if "%choice%"=="2" diskpart & goto menu
if "%choice%"=="3" goto scan
if "%choice%"=="4" goto info
if "%choice%"=="5" cmd.exe & goto menu
if "%choice%"=="6" wpeutil reboot
goto menu

:scan
echo Scanning all drive letters...
for %%d in (C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
    if exist %%d:\ (
        echo Scanning drive %%d:...
        chkdsk %%d:
    )
)
echo Scan finished.
pause
goto menu

:info
echo list disk | diskpart
echo list volume | diskpart
pause
goto menu
EOF

# Update startnet.cmd
cat << 'EOF' > "$TOOLS_DIR/startnet.cmd"
wpeinit
call \Windows\System32\recovery.cmd
EOF

# Apply updates to WIM
wimlib-imagex update "$WIM_PATH" 1 << EOF
add '$TOOLS_DIR/explorer++/Explorer++.exe' '/Windows/System32/Explorer++.exe'
add '$TOOLS_DIR/recovery.cmd' '/Windows/System32/recovery.cmd'
add '$TOOLS_DIR/startnet.cmd' '/Windows/System32/startnet.cmd'
EOF

# 7. Setup ISO Bootloader
echo "[*] Setting up ISO bootloader..."
curl -Lk https://github.com/ipxe/wimboot/releases/latest/download/wimboot -o "$ISO_ROOT/wimboot"
cp /usr/lib/ISOLINUX/isolinux.bin "$ISO_ROOT/boot/isolinux/"
cp /usr/lib/syslinux/modules/bios/ldlinux.c32 "$ISO_ROOT/boot/isolinux/"

cat << EOF > "$ISO_ROOT/boot/isolinux/isolinux.cfg"
DEFAULT winpe
LABEL winpe
  KERNEL /wimboot
  APPEND initrd=/bootmgr,/boot/bcd,/boot/boot.sdi,/sources/boot.wim
EOF

# 8. Build ISO
echo "[*] Generating ISO image..."
xorriso -as mkisofs \
    -iso-level 3 -rock -joliet \
    -V "WINPE_REC" \
    -o "$WORK_DIR/$ISO_NAME" \
    -b boot/isolinux/isolinux.bin \
    -c boot/isolinux/boot.cat \
    -no-emul-boot -boot-load-size 4 -boot-info-table \
    -eltorito-alt-boot \
    -e EFI/boot/bootx64.efi \
    -no-emul-boot \
    "$ISO_ROOT"

echo "[+] ISO created: $WORK_DIR/$ISO_NAME"

# 9. GitHub Upload
if [ -n "$GITHUB_TOKEN" ]; then
    echo "[*] Authenticating with GitHub..."
    echo "$GITHUB_TOKEN" | gh auth login --with-token
    echo "[*] Creating/Updating release $TAG..."
    gh release create "$TAG" \
        --repo "$REPO" \
        --title "WinPE x64 Recovery v1.1" \
        --notes "Enhanced recovery features, Explorer++, and automatic drive scan script." || true

    gh release upload "$TAG" "$WORK_DIR/$ISO_NAME" --repo "$REPO" --clobber
    echo "[+] Release uploaded successfully!"
else
    echo "[!] GITHUB_TOKEN not set. Skipping upload."
fi

echo "=== Done ==="
