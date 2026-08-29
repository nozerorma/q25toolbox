#!/system/bin/sh
# Regenerate the module's hosts file from persisted state before the
# systemless mount snapshot is taken, in case blacklist.txt or the
# enabled/disabled state changed since the last boot (e.g. edited via
# WebUI, then rebooted, without the app re-running rebuild itself).
MODDIR=${0%/*}
sh "$MODDIR/hosts_ctl.sh" rebuild >/dev/null 2>&1
