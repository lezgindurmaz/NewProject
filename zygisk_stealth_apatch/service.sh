#!/system/bin/sh
MODDIR=${0%/*}

# Zygisk Stealth Hider Logic
# Purpose: Deep concealment of Zygisk, LSPosed and APatch

# Path to the list of targeted packages (managed by WebUI)
TARGET_LIST="/data/adb/zygisk_stealth_packages.txt"

# Ensure the list exists
[ ! -f "$TARGET_LIST" ] && touch "$TARGET_LIST"

# Function to perform deep cleanup for a specific PID
cleanup_app_traces() {
    local pid=$1
    if [ -d "/proc/$pid" ]; then
        # 1. Unmount sensitive Zygisk/Magisk/APatch paths from app's namespace
        # We use nsenter to enter the app's mount namespace
        nsenter -t "$pid" -m sh -c "
            grep -E 'zygisk|lsposed|magisk|apatch|riru' /proc/self/mounts | cut -d' ' -f2 | xargs -r umount -l
        " 2>/dev/null

        # 2. Additional 'kernel-assisted' hide via APatch if available
        # APatch can handle this via its own internal exclude list sync
    fi
}

# Monitoring loop to detect new target app launches
(
    while true; do
        while read -r pkg; do
            [ -z "$pkg" ] && continue

            # Find PIDs of the target package
            pids=$(pidof "$pkg")
            for pid in $pids; do
                # Apply traces cleanup
                cleanup_app_traces "$pid"
            done
        done < "$TARGET_LIST"

        sleep 5
    done
) &
