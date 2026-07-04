#!/system/bin/sh
# One-shot adaptive brightness - Q25 Toolbox
#
# On each screen-on, briefly enable auto-brightness to take a single ambient
# reading, then switch back to manual so the brightness holds steady until the
# next wake. Mirrors LineageOS one-shot auto-brightness; this ROM (BenOS/MTK)
# has no LineageSettings AUTO_BRIGHTNESS_ONE_SHOT flag, so we do it in
# userspace from root.
#
# Screen on/off is detected from the panel backlight node (0 = off/doze). The
# committed auto brightness is read from
# `dumpsys display` (a 0.0-1.0 float; the setting <-> backlight spline is 1:1
# on this panel) since `screen_brightness` doesn't track the auto value here.

LOCK=/data/adb/.one_shot_brightness.lock
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    exit 0
fi
echo $$ > "$LOCK"

BL=/sys/class/leds/lcd-backlight/brightness
SETTLE=__SETTLE_S__
FLOOR=__FLOOR__
LAST_ON=1   # assume on at start so we don't fire a needless one-shot at boot

while true; do
    LCD=$(cat "$BL" 2>/dev/null)
    case "$LCD" in
        ''|*[!0-9]*) sleep 1; continue ;;
    esac

    if [ "$LCD" -gt 0 ]; then
        if [ "$LAST_ON" -eq 0 ]; then
            # Screen just turned on: take one ambient reading, then freeze.
            settings put system screen_brightness_mode 1
            sleep "$SETTLE"
            FLOAT=$(dumpsys display 2>/dev/null | grep -m1 'Display Brightness=' | sed 's/.*=//' | tr -d '\r ')
            settings put system screen_brightness_mode 0
            case "$FLOAT" in
                ''|*[!0-9.]*)
                    # NaN / empty (e.g. sensor not ready): leave brightness as-is.
                    ;;
                *)
                    # float 0.0-1.0 -> int 0-255, in portable integer shell math.
                    whole=${FLOAT%%.*}
                    rest=${FLOAT#*.}
                    [ "$rest" = "$FLOAT" ] && rest=0
                    rest3=$(printf '%s' "${rest}000" | cut -c1-3)
                    frac=$(( 1$rest3 - 1000 ))
                    milli=$(( ${whole:-0} * 1000 + frac ))
                    INT=$(( milli * 255 / 1000 ))
                    [ "$INT" -lt "$FLOOR" ] && INT="$FLOOR"
                    [ "$INT" -gt 255 ] && INT=255
                    settings put system screen_brightness "$INT"
                    ;;
            esac
        fi
        LAST_ON=1
    else
        LAST_ON=0
    fi
    sleep 0.5
done
