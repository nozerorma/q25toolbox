#!/system/bin/sh
# Re-assert the GMS activity-recognition block at boot - Q25 Toolbox
#
# The ACTIVITY_RECOGNITION block (see GmsSensorGuardController) is persisted
# by the OS in /data/system/appops.xml and normally survives reboots on its
# own. This script is belt-and-braces for the ways it can get cleared behind
# our back: "Reset app preferences", a Play services reinstall, or a ROM
# sanitizing appops at boot. It is a one-shot: runs once from
# /data/adb/service.d at boot and exits - no daemon, no lock file.
#
# The UID is resolved here at runtime rather than baked in at install time:
# package-level `cmd appops set com.google.android.gms ...` is accepted but
# silently never applied on this ROM, only the UID form sticks - and the UID
# is not stable across devices or re-flashes. `pm list packages -U`
# prefix-matches, so the exact-name grep keeps sibling packages (gms.supervision
# et al.) and their own UIDs out of the result.

UID_GMS=$(pm list packages -U com.google.android.gms 2>/dev/null | grep 'com.google.android.gms uid:' | head -n1 | tr -dc '0-9')
[ -n "$UID_GMS" ] && cmd appops set --uid "$UID_GMS" ACTIVITY_RECOGNITION ignore
exit 0
