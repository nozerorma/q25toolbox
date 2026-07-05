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
#
# The shell rework above still degraded SystemUI after a few hours of normal use
# (same symptom as the original module) because every single touch on the
# device - not just screen-off wake gestures - forked `date +%s%3N` (twice, for
# DOWN and UP) through the `while read` loop, all day, forever. That's
# thousands of fork/exec cycles/hour system-wide just to time taps that in the
# overwhelming majority of cases don't even happen while the screen is off.
# Now handled entirely inside one persistent awk process fed by getevent's own
# monotonic per-line timestamp (`-t`), so timing needs no subprocess at all,
# and the backlight sysfs node is read via awk's own getline instead of
# spawning `cat`. The only subprocess left is the (rare) actual wake action.

LOCK=/data/adb/.dt2w.lock
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    exit 0
fi
echo $$ > "$LOCK"

BL=/sys/class/leds/lcd-backlight/brightness
DOUBLE_TAP_WINDOW_S=0.6   # max gap between the two taps (DOWN -> DOWN)
SINGLE_TAP_MAX_S=0.25     # max press duration to still count as a tap

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

# State lives inside the awk process; it persists for the life of one getevent
# stream and resets cleanly if the stream restarts - which is fine.
while true; do
    getevent -lt "$DEV" 2>/dev/null | awk -v bl="$BL" -v gap_max="$DOUBLE_TAP_WINDOW_S" -v press_max="$SINGLE_TAP_MAX_S" '
        function screen_off(   b, rc) {
            rc = (getline b < bl)
            close(bl)
            return (rc > 0 && b == "0")
        }
        {
            # Line looks like: [   16779.302009] EV_KEY  BTN_TOUCH  DOWN
            # - the space after "[" makes it its own field, so the timestamp is $2.
            ts = $2
            gsub(/[\[\]]/, "", ts)
            t = ts + 0
        }
        /BTN_TOUCH/ && /DOWN/ {
            if (prev_valid && (t - last_down) <= gap_max && screen_off()) {
                system("input keyevent KEYCODE_WAKEUP")
                prev_valid = 0
            }
            pending_down = t
            next
        }
        /BTN_TOUCH/ && /UP/ {
            if (pending_down > 0) {
                if ((t - pending_down) <= press_max) {
                    prev_valid = 1
                    last_down = pending_down
                } else {
                    prev_valid = 0
                }
                pending_down = 0
            }
        }
    '
    sleep 2
done
