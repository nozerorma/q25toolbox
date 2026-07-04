#!/system/bin/sh
# DT2W (software) - Q25 Toolbox
#
# The Q25 touch panel exposes no hardware/driver gesture-wake, and the
# double_tap_to_wake secure setting is not wired to anything on this ROM. So
# DT2W is done in software: watch the touchscreen for a quick double-tap while
# the screen is off and inject KEYCODE_WAKEUP.
#
# Reworked from nozerorma/q25-double-tap-wake for stability (that version crashed
# the system after a few hours):
#   - screen state comes from the cheap lcd-backlight sysfs node, NOT a repeated
#     `dumpsys power` (which is heavy and was polled on every tap);
#   - wake uses KEYCODE_WAKEUP, which is idempotent (only ever wakes, never
#     sleeps) - the original injected KEY_POWER, which toggles and could turn the
#     screen back off;
#   - a single getevent stream is supervised with a restart backoff.

LOCK=/data/adb/.dt2w.lock
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    exit 0
fi
echo $$ > "$LOCK"

BL=/sys/class/leds/lcd-backlight/brightness
DOUBLE_TAP_WINDOW_MS=600   # max gap between the two taps (DOWN -> DOWN)
SINGLE_TAP_MAX_MS=250      # max press duration to still count as a tap

screen_off() {
    b=$(cat "$BL" 2>/dev/null)
    [ "$b" = "0" ]
}

find_touch_dev() {
    for d in /dev/input/event*; do
        if getevent -pl "$d" 2>/dev/null | grep -q "BTN_TOUCH"; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 5
done

DEV=""
while [ -z "$DEV" ]; do
    DEV=$(find_touch_dev)
    [ -z "$DEV" ] && sleep 5
done

# State lives inside the `getevent | while` subshell; it persists for the life of
# one getevent stream and resets cleanly if the stream restarts - which is fine.
while true; do
    pending_down=0
    prev_valid=0
    last_down=0
    getevent -l "$DEV" 2>/dev/null | while read -r line; do
        case "$line" in
            *BTN_TOUCH*DOWN*)
                t=$(date +%s%3N)
                if [ "$prev_valid" = "1" ]; then
                    gap=$(( t - last_down ))
                    if [ "$gap" -le "$DOUBLE_TAP_WINDOW_MS" ] && screen_off; then
                        input keyevent KEYCODE_WAKEUP
                        prev_valid=0
                    fi
                fi
                pending_down=$t
                ;;
            *BTN_TOUCH*UP*)
                t=$(date +%s%3N)
                if [ "$pending_down" -gt 0 ]; then
                    press=$(( t - pending_down ))
                    if [ "$press" -le "$SINGLE_TAP_MAX_MS" ]; then
                        prev_valid=1
                        last_down=$pending_down
                    else
                        prev_valid=0
                    fi
                    pending_down=0
                fi
                ;;
        esac
    done
    sleep 2
done
