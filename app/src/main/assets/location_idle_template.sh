#!/system/bin/sh
# Auto-disable Location when idle - Q25 Toolbox
#
# GMS keeps low-power passive/network location listeners registered nearly all
# the time in the background (fused/network location provider, activity
# recognition, etc.) - that's normal and not a sign anything is actually using
# your location right now, so it can't be the "in use" signal. The GPS
# provider's own mStarted flag in `dumpsys location` is: it only goes true
# while something (navigation, ride-hailing pickup, camera geotag, ...) is
# actively pulling a live fix. Turns Location off after __TIMEOUT_MIN__
# minutes with the GPS provider continuously idle. Any such app starting a fix
# resets the timer; once disabled it stays off until re-enabled manually or
# the app is reopened.
#
# Runs as a root daemon: launched at boot from /data/adb/service.d and also
# started live by the app. A PID+cmdline lock keeps a single instance.

LOCK=/data/adb/.location_idle.lock
# Belt-and-braces PID lock: unlike a plain "kill -0 $PID" check (which can
# false-positive on an unrelated process that has since reused the same PID),
# this also confirms /proc/$PID/cmdline still names this same script before
# treating the lock as held.
if [ -f "$LOCK" ]; then
    OLD_PID=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$OLD_PID" ] && grep -q location_idle "/proc/$OLD_PID/cmdline" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$LOCK"

TIMEOUT=__TIMEOUT_MIN__
idle=0
while true; do
    sleep 60
    if [ "$(settings get secure location_mode 2>/dev/null)" = "0" ]; then
        idle=0
        continue
    fi
    if dumpsys location 2>/dev/null | grep -m1 "mStarted" | grep -q "mStarted=true"; then
        idle=0
    else
        idle=$((idle + 1))
        if [ "$idle" -ge "$TIMEOUT" ]; then
            cmd location set-location-enabled false >/dev/null 2>&1
            idle=0
        fi
    fi
done
