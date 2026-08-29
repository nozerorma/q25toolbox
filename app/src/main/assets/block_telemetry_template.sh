#!/system/bin/sh
# Block Telemetry & Crashlytics - Q25 Toolbox
#
# Firebase SDKs re-derive their "collection enabled" flag from the app manifest
# on every cold start and persist it back into shared_prefs, so a one-shot boot
# pass gets undone. This runs as a watchdog: a burst of passes shortly after
# boot, then a re-scan every __INTERVAL_MIN__ minutes, plus an extra burst
# whenever an app is installed/removed (packages.list line count changes).
#
# Surfaces neutralised (true -> false, injected where the SDK expects the file
# but the app hasn't written the flag yet):
#   - Crashlytics       firebase_crashlytics_collection_enabled
#   - Analytics / GA     measurement_enabled, measurement_enabled_from_api,
#                        firebase_analytics_collection_enabled
#   - Performance Mon.   firebase_performance_collection_enabled
#   - Firebase master    firebase_data_collection_default_enabled
#
# apply_block runs inside the init (pid 1) mount namespace so it sees the real
# /data/data both at boot AND when launched live from the app's root shell
# (which may sit in a different mount namespace).
LOCK=/data/adb/.block_telemetry.lock
PKGLIST=/data/system/packages.list
INTERVAL_MIN=__INTERVAL_MIN__

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

INNER='
KEYS="firebase_crashlytics_collection_enabled firebase_analytics_collection_enabled firebase_performance_collection_enabled firebase_data_collection_default_enabled measurement_enabled measurement_enabled_from_api"
RE=$(echo "$KEYS" | sed "s/ /|/g")

# 1. flip any known telemetry flag from true to false wherever it is persisted.
grep -rlE "\"($RE)\" value=\"true\"" /data/data/*/shared_prefs 2>/dev/null | while read f; do
    [ -f "$f" ] || continue
    for k in $KEYS; do
        sed -i "s/\"$k\" value=\"true\"/\"$k\" value=\"false\"/g" "$f"
    done
done

# 2. inject the disable flag into the files the SDK reads on init, for apps that
#    have the file but have not written the flag (fresh install / first run).
find /data/data -name "com.google.firebase.crashlytics.xml" 2>/dev/null | while read f; do
    [ -f "$f" ] || continue
    grep -q "firebase_crashlytics_collection_enabled" "$f" || \
        sed -i "s#</map>#    <boolean name=\"firebase_crashlytics_collection_enabled\" value=\"false\" />\n</map>#" "$f"
done
find /data/data -name "com.google.android.gms.measurement.prefs.xml" 2>/dev/null | while read f; do
    [ -f "$f" ] || continue
    grep -q "\"measurement_enabled\"" "$f" || \
        sed -i "s#</map>#    <boolean name=\"measurement_enabled\" value=\"false\" />\n</map>#" "$f"
    grep -q "\"measurement_enabled_from_api\"" "$f" || \
        sed -i "s#</map>#    <boolean name=\"measurement_enabled_from_api\" value=\"false\" />\n</map>#" "$f"
done
'

apply_block() {
    nsenter --mount=/proc/1/ns/mnt -- sh -c "$INNER"
}

burst() {
    n=0
    while [ "$n" -lt 4 ]; do
        apply_block
        n=$((n + 1))
        [ "$n" -lt 4 ] && sleep 30
    done
}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 5
done
sleep 15
burst

last_count=$(wc -l < "$PKGLIST" 2>/dev/null || echo 0)

while true; do
    waited=0
    target=$((INTERVAL_MIN * 60))
    while [ "$waited" -lt "$target" ]; do
        sleep 30
        waited=$((waited + 30))
        now_count=$(wc -l < "$PKGLIST" 2>/dev/null || echo 0)
        if [ "$now_count" != "$last_count" ]; then
            last_count=$now_count
            sleep 20
            burst
            break
        fi
    done
    apply_block
done
