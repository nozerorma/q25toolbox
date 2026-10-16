#!/system/bin/sh
# Lockscreen keys via root - Q25 Toolbox
#
# Types the Q25's letter-digits (W E R / S D F / Z X C = 1..9) into the lockscreen PIN pad and, optionally,
# opens the PIN pad on Enter. Runs from /data/adb/service.d, so it also works BEFORE the first unlock after a
# boot, when the accessibility service cannot run (it is not direct-boot aware).
#
# It only OBSERVES the keyboard (getevent is read-only); it cannot swallow a key. So it injects the digit and
# leaves the raw letter to the system, which ignores letters on a PIN pad, and it leaves Enter / Delete to the
# native handling (the accessibility service, when alive, consumes Enter on the plain lockscreen itself).
#
# Never logs which digit was pressed: the log is only about the daemon's own life.

LOCK=/data/adb/.pin_keys.lock
LOG=/data/adb/.pin_keys.log
if [ -f "$LOCK" ]; then
    OLD_PID=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$OLD_PID" ] && grep -q pin_keys "/proc/$OLD_PID/cmdline" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$LOCK"

PIN=__PIN__                  # 1: letters type digits
ENTER_OPENS=__ENTER_OPENS__  # 1: Enter on the plain lockscreen opens the PIN pad
IS_LINEAGE=__IS_LINEAGE__    # 1: LineageOS specific handling
BACKLIGHT=/sys/class/leds/lcd-backlight/brightness
CACHE_S=2        # how long a lock-state answer is trusted
TYPED_S=15       # after this long without a digit, Enter means "open the pad" again

log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
    if [ "$(wc -l < "$LOG" 2>/dev/null || echo 0)" -gt 100 ]; then
        tail -n 50 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
    fi
}

# The Q25 Trackpad Customizer's helper EVIOCGRABs the physical keyboard and re-emits
# its events through uinput as a virtual "Custom Q25 Keyboard": while it runs, the
# physical node stays silent, so BOTH nodes are watched and whichever delivers the
# scancode types the digit. Without the customizer only the physical node exists.
find_nodes() {
    for d in /sys/class/input/event*; do
        n=$(cat "$d/device/name" 2>/dev/null)
        if [ "$n" = "Q25_keyboard" ] || [ "$n" = "Custom Q25 Keyboard" ]; then
            echo "/dev/input/${d##*/}"
        fi
    done
}

# One dumpsys call (~55 ms) answers "keyguard up, not covered by an app, screen awake".
locked_awake() {
    s=$(dumpsys window policy 2>/dev/null)
    echo "$s" | grep -q 'INTERACTIVE_STATE_AWAKE' || return 1
    echo "$s" | grep -q '^ *showing=true' || return 1
    echo "$s" | grep -q '^ *occluded=true' && return 1
    return 0
}

log "start pin=$PIN enter_opens=$ENTER_OPENS"

while true; do
    NODES=$(find_nodes)
    if [ -z "$NODES" ]; then sleep 2; continue; fi

    lock_ok=0; lock_ts=0; typed=0; typed_ts=0; was_off=1
    # getevent prints "type code value" per line for a single device, but prefixes each
    # line with "/dev/input/eventN:" when watching several, so both shapes are parsed.
    getevent -q $NODES 2>/dev/null | while read -r a b c d; do
        case "$a" in
            *event*) t=$b; code=$c; v=$d ;;
            *)       t=$a; code=$b; v=$c ;;
        esac
        [ "$t" = "0001" ] && [ "$v" = "00000001" ] || continue

        # Which key? Anything else costs nothing, in particular no dumpsys.
        act=""
        case "$code" in
            0011) act=8 ;;  0012) act=9 ;;  0013) act=10 ;;   # W E R -> KEYCODE_1..3
            001f) act=11 ;; 0020) act=12 ;; 0021) act=13 ;;   # S D F -> 4..6
            002c) act=14 ;; 002d) act=15 ;; 002e) act=16 ;;   # Z X C -> 7..9
            001c|0060) act=ENTER ;;
            *) continue ;;
        esac
        if [ "$act" = "ENTER" ]; then
            [ "$ENTER_OPENS" = "1" ] || continue
        else
            [ "$PIN" = "1" ] || continue
        fi

        # Screen off: nothing to do, and the next key must re-check the lock state.
        if [ -r "$BACKLIGHT" ] && [ "$(cat "$BACKLIGHT" 2>/dev/null)" = "0" ]; then
            lock_ok=0; was_off=1; typed=0
            continue
        fi

        now=$(date +%s)
        if [ "$was_off" = "1" ] || [ $((now - lock_ts)) -gt $CACHE_S ]; then
            if locked_awake; then lock_ok=1; else lock_ok=0; typed=0; fi
            lock_ts=$now; was_off=0
        fi
        [ "$lock_ok" = "1" ] || continue
        [ $((now - typed_ts)) -gt $TYPED_S ] && typed=0

        if [ "$act" = "ENTER" ]; then
            # Enter after digits submits the PIN (native); with none typed on plain lockscreen it opens the pad.
            if [ "$typed" = "0" ]; then
                s=$(dumpsys window policy 2>/dev/null)
                if echo "$s" | grep -q '^ *showing=true'; then
                    cmd window dismiss-keyguard >/dev/null 2>&1
                fi
            fi
            lock_ts=0   # Enter may unlock: look again next time
        else
            if [ "$IS_LINEAGE" = "1" ]; then
                # The accessibility service is only alive after the first unlock (credential-encrypted storage
                # available); from then on it clicks the keys itself. Before that, only this daemon can type.
                if [ "$(getprop sys.user.0.ce_available)" = "true" ]; then
                    : # Handled by Q25AccessibilityService with direct node click
                else
                    case "$act" in
                        8) input tap 278 301 >/dev/null 2>&1 ;;
                        9) input tap 359 301 >/dev/null 2>&1 ;;
                        10) input tap 441 301 >/dev/null 2>&1 ;;
                        11) input tap 278 372 >/dev/null 2>&1 ;;
                        12) input tap 359 372 >/dev/null 2>&1 ;;
                        13) input tap 441 372 >/dev/null 2>&1 ;;
                        14) input tap 278 444 >/dev/null 2>&1 ;;
                        15) input tap 359 444 >/dev/null 2>&1 ;;
                        16) input tap 441 444 >/dev/null 2>&1 ;;
                        *) input keyevent "$act" >/dev/null 2>&1 ;;
                    esac
                fi
            else
                input keyevent "$act" >/dev/null 2>&1
            fi
            typed=1; typed_ts=$now
        fi
    done
    # getevent ended: the keyboard node went away (driver reload, uevent remove/add). Find it again.
    sleep 1
done
