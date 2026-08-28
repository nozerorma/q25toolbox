#!/system/bin/sh
# Recents Provider repair bind-mount - Q25 Toolbox
#
# Only installed when the user runs "Repair Recents Provider" (recovery for the
# BenOS OTA that ships a misaligned SearchLauncherQuickStep.apk and leaves the
# device with no Overview provider). It bind-mounts a re-aligned + re-signed
# copy of the device's OWN launcher over the system path. Bind mounts are kernel
# state and do not survive reboot, so this re-applies it at boot.
#
# Every app process on this ROM gets its own mount namespace, so the mount must
# land in PID 1's global namespace. service.d already runs outside any app
# namespace; nsenter here is a harmless no-op in that case.

TARGET="/system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk"
FIXED="/data/adb/q25toolbox/SearchLauncherQuickStep_fixed.apk"

# /system_ext may not be mounted yet this early in boot - wait for the target.
i=0
while [ ! -f "$TARGET" ] && [ "$i" -lt 60 ]; do
    sleep 1
    i=$((i + 1))
done

[ -f "$FIXED" ] && nsenter --mount=/proc/1/ns/mnt -- sh -c "umount -l '$TARGET' 2>/dev/null; mount -o bind '$FIXED' '$TARGET'"
