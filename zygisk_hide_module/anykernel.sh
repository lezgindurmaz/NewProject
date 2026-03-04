#!/sbin/sh
# AnyKernel3 Script for Zygisk Stealth KPM

properties() { '
kernel.string=Zygisk Stealth Hide KPM
do.devicecheck=0
do.modules=0
do.systemless=1
do.cleanup=1
do.cleanuponabort=0
'; }

block=auto;
is_slot_device=auto;
ramdisk_compression=auto;
patch_vbmeta_flag=auto;

. /tmp/anykernel/tools/ak3-helper.sh;

ui_print " ";
ui_print "Installing Zygisk Stealth Hide KPM...";

# Identify KPM directory in APatch
KPM_DIR="/data/adb/apatch/kpm"

if [ -d /data/adb/apatch ]; then
    ui_print "- APatch environment detected"
    [ ! -d "$KPM_DIR" ] && mkdir -p "$KPM_DIR"

    ui_print "- Deploying zygisk_hide.ko to $KPM_DIR"
    cp /tmp/anykernel/kpm/zygisk_hide.ko "$KPM_DIR/"
    chmod 644 "$KPM_DIR/zygisk_hide.ko"

    ui_print "- Creating module.prop for APatch Manager"
    cp /tmp/anykernel/module.prop "$KPM_DIR/zygisk_hide.prop"
else
    ui_print "! APatch not found. Please install APatch first."
    exit 1
fi

ui_print " ";
ui_print "Installation complete. Reboot to activate KPM.";
