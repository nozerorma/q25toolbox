#!/system/bin/sh
# Recents Grid Patch bind-mount - Q25 Toolbox
#
# Bind mounts are kernel state, not persisted to disk - every reboot reverts
# /system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk back
# to the original unpatched file. This one-shot boot script re-applies the
# bind mount so the toggle survives reboot instead of silently reverting.
#
# Every app process (including Q25 Toolbox itself) gets its own private mount
# namespace on this ROM, so the mount has to land in PID 1's (the real, global)
# namespace to be visible to com.android.launcher3 - see RecentsTweaksController
# for the same reasoning. service.d scripts already run outside any app's
# namespace, but nsenter here is a harmless no-op in that case and guarantees
# correctness either way.

TARGET="/system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk"
PATCHED="/data/adb/q25toolbox/SearchLauncherQuickStep_patched.apk"

# /system_ext may not be mounted yet this early in boot - wait for the real
# target file to actually exist before attempting the bind, same class of fix
# as KeyRemapController's boot script racing driver init.
i=0
while [ ! -f "$TARGET" ] && [ "$i" -lt 60 ]; do
    sleep 1
    i=$((i + 1))
done

nsenter --mount=/proc/1/ns/mnt -- sh -c "umount -l '$TARGET' 2>/dev/null; mount -o bind '$PATCHED' '$TARGET'"
