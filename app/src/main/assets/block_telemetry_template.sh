#!/system/bin/sh
# Block Telemetry & Crashlytics - Q25 Toolbox
#
# Apps rewrite com.google.firebase.crashlytics.xml at runtime (Crashlytics
# re-enables collection on app start), so a one-shot boot pass gets undone.
# This runs as a watchdog: an initial pass shortly after boot, then a re-scan
# every __INTERVAL_MIN__ minutes. A pid lock keeps a single instance.
#
# apply_block runs inside the init (pid 1) mount namespace so it sees the real
# /data/data both at boot AND when launched live from the app's root shell
# (which may sit in a different mount namespace).
LOCK=/data/adb/.block_telemetry.lock
# Belt-and-braces PID lock: unlike a plain "kill -0 $PID" check (which can
# false-positive on an unrelated process that has since reused the same PID),
# this also confirms /proc/$PID/cmdline still names this same script before
# treating the lock as held.
if [ -f "$LOCK" ]; then
    OLD_PID=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$OLD_PID" ] && grep -q block_telemetry "/proc/$OLD_PID/cmdline" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$LOCK"

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 5
done
sleep 15

INTERVAL_MIN=__INTERVAL_MIN__

INNER='find /data/data/ -name "com.google.firebase.crashlytics.xml" 2>/dev/null | while read f; do
[ -f "$f" ] || continue
if grep -q "firebase_crashlytics_collection_enabled" "$f"; then
sed -i "s/firebase_crashlytics_collection_enabled\" value=\"true\"/firebase_crashlytics_collection_enabled\" value=\"false\"/g" "$f"
else
sed -i "s#</map>#    <boolean name=\"firebase_crashlytics_collection_enabled\" value=\"false\" />\n</map>#g" "$f"
fi
done'

apply_block() {
    nsenter --mount=/proc/1/ns/mnt -- sh -c "$INNER"
}

while true; do
    apply_block
    sleep $(( INTERVAL_MIN * 60 ))
done
