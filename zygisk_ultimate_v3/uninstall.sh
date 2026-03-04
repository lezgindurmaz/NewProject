#!/system/bin/sh
# Ultimate Stealth Cleanup Script

# 1. Kill the monitoring service
pkill -f "service.sh"
pkill -f "deep_mask.sh"

# 2. Clean up memory-based masks
rm /dev/shm/.clean_maps_* 2>/dev/null
rm /dev/shm/.clean_mountinfo_* 2>/dev/null
rm /dev/shm/.stealth_* 2>/dev/null

# 3. Clean up package lists
rm /data/adb/zygisk_ultimate_packages.txt 2>/dev/null

# 4. Global unmasking (revert bind mounts if possible)
# This is usually handled by a reboot, but we try our best here.
grep -E "zygisk|lsposed|apatch" /proc/self/mountinfo | cut -d' ' -f5 | xargs -r umount -l

exit 0
