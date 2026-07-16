#!/system/bin/sh
# Auto-schedule Extra Dim - Q25 Toolbox
#
# Turns Extra Dim on/off at specific times of day (minutes-since-midnight, so
# any time like 00:35 is supported, not just whole hours). Only writes the
# setting on an on/off transition (not every poll), so a manual toggle in
# between the transitions isn't immediately overwritten - matches how
# Android's own Night Light schedule behaves with manual overrides.

LOCK=/data/adb/.extra_dim_schedule.lock
# Belt-and-braces PID lock: unlike a plain "kill -0 $PID" check (which can
# false-positive on an unrelated process that has since reused the same PID),
# this also confirms /proc/$PID/cmdline still names this same script before
# treating the lock as held. (toybox's flock -n on this device's /system/bin/sh
# was tried instead and consistently returned "Bad file descriptor" even for a
# freshly exec'd fd, so it isn't usable here.)
if [ -f "$LOCK" ]; then
    OLD_PID=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$OLD_PID" ] && grep -q extra_dim_schedule "/proc/$OLD_PID/cmdline" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$LOCK"

START_MINUTES=__START_MINUTES__
END_MINUTES=__END_MINUTES__
LAST_STATE=""

in_window() {
    M=$1
    if [ "$START_MINUTES" -lt "$END_MINUTES" ]; then
        [ "$M" -ge "$START_MINUTES" ] && [ "$M" -lt "$END_MINUTES" ]
    else
        [ "$M" -ge "$START_MINUTES" ] || [ "$M" -lt "$END_MINUTES" ]
    fi
}

while true; do
    HOUR=$(date +%H)
    HOUR=${HOUR#0}
    [ -z "$HOUR" ] && HOUR=0
    MIN=$(date +%M)
    MIN=${MIN#0}
    [ -z "$MIN" ] && MIN=0
    NOW_MINUTES=$((HOUR * 60 + MIN))

    if in_window "$NOW_MINUTES"; then
        DESIRED=1
    else
        DESIRED=0
    fi

    if [ "$DESIRED" != "$LAST_STATE" ]; then
        settings put secure reduce_bright_colors_activated $DESIRED
        LAST_STATE=$DESIRED
    fi

    sleep 30
done
