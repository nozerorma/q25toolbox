#!/system/bin/sh
# Auto-schedule Extra Dim - Q25 Toolbox
#
# Turns Extra Dim on during [__START_HOUR__:00, __END_HOUR__:00) and off
# outside that window. Only writes the setting on an on/off transition (not
# every poll), so a manual toggle in between the transitions isn't
# immediately overwritten - matches how Android's own Night Light schedule
# behaves with manual overrides.

LOCK=/data/adb/.extra_dim_schedule.lock
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    exit 0
fi
echo $$ > "$LOCK"

START_HOUR=__START_HOUR__
END_HOUR=__END_HOUR__
LAST_STATE=""

in_window() {
    HOUR=$1
    if [ "$START_HOUR" -lt "$END_HOUR" ]; then
        [ "$HOUR" -ge "$START_HOUR" ] && [ "$HOUR" -lt "$END_HOUR" ]
    else
        [ "$HOUR" -ge "$START_HOUR" ] || [ "$HOUR" -lt "$END_HOUR" ]
    fi
}

while true; do
    HOUR=$(date +%H)
    HOUR=${HOUR#0}
    [ -z "$HOUR" ] && HOUR=0

    if in_window "$HOUR"; then
        DESIRED=1
    else
        DESIRED=0
    fi

    if [ "$DESIRED" != "$LAST_STATE" ]; then
        settings put secure reduce_bright_colors_activated $DESIRED
        LAST_STATE=$DESIRED
    fi

    sleep 60
done
