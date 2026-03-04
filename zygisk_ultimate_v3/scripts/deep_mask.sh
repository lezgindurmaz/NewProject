#!/system/bin/sh
# Ultimate Deep Masking Script
PID=$1
PKG=$2

[ -z "$PID" ] && exit 1

# Pattern to find sensitive Zygisk/APatch entries
MASK_KEYWORDS="zygisk|lsposed|magisk|apatch|riru|kpm|sush"

# Enter app's mount namespace
nsenter -t "$PID" -m sh -c "
    # 1. Clean up known zygisk/lsposed mounts
    grep -E '$MASK_KEYWORDS' /proc/self/mountinfo | cut -d' ' -f5 | xargs -r umount -l

    # 2. Bind mount /dev/null or empty files over sensitive paths
    # This fools 'stat' and 'access' calls
    touch /dev/shm/.mask_file
    mount --bind /dev/null /data/adb/zygisk 2>/dev/null
    mount --bind /dev/null /data/adb/lsposed 2>/dev/null
    mount --bind /dev/null /data/adb/modules 2>/dev/null

    # 3. Proc self maps/mounts masking - Ultimate Step
    # Create a clean version of the maps for the app
    grep -vE '$MASK_KEYWORDS' /proc/self/maps > /dev/shm/.clean_maps_$PID
    mount --bind /dev/shm/.clean_maps_$PID /proc/self/maps 2>/dev/null

    grep -vE '$MASK_KEYWORDS' /proc/self/mountinfo > /dev/shm/.clean_mountinfo_$PID
    mount --bind /dev/shm/.clean_mountinfo_$PID /proc/self/mountinfo 2>/dev/null
" 2>/dev/null

# Mark PID as processed to avoid CPU waste
touch "/dev/shm/.stealth_$PID"
