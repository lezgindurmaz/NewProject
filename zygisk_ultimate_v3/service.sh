#!/system/bin/sh
MODDIR=${0%/*}

# Ultimate Stealth Hider - Aggressive Implementation
TARGET_LIST="/data/adb/zygisk_ultimate_packages.txt"

# Masking patterns
MASK_KEYWORDS="zygisk|lsposed|magisk|apatch|riru|kpm|sush"

# Fast loop to catch apps early (even before their first syscall)
(
    while true; do
        # Use logcat or periodic process scan to detect new apps
        # Periodic scan is safer and less intrusive
        while read -r pkg; do
            [ -z "$pkg" ] && continue
            pids=$(pidof "$pkg")
            for pid in $pids; do
                # Deep mask trigger
                sh "$MODDIR/scripts/deep_mask.sh" "$pid" "$pkg"
            done
        done < "$TARGET_LIST"
        sleep 2
    done
) &
