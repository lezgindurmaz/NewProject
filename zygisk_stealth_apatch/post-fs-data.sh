#!/system/bin/sh
MODDIR=${0%/*}

# APatch Stealth Module - Setup
# Minimal footprint optimization

# Ensure we are not leaving traces in /data/local/tmp or other public dirs
rm -rf /data/local/tmp/zygisk_stealth_test 2>/dev/null

# Set proper permissions for the package list
chmod 600 /data/adb/zygisk_stealth_packages.txt 2>/dev/null

exit 0
