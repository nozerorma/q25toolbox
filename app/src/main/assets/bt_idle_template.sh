#!/system/bin/sh
# Auto-disable Bluetooth when idle - Q25 Toolbox
#
# Turns Bluetooth off after __TIMEOUT_MIN__ minutes with no device connected,
# so an idle radio (bonded devices + GMS/Fast Pair scanning) can't hold
# hal_bluetooth_lock and drain the battery overnight. Connecting any device
# (earbuds, watch, speaker) resets the timer; manually it just stays off until
# you re-enable Bluetooth.
#
# Runs as a root daemon: launched at boot from /data/adb/service.d (the module
# manager runs service.d scripts in their own process, so the loop is fine) and
# also started live by the app. A pid lock keeps a single instance.

LOCK=/data/adb/.bt_idle.lock
LOG=/data/adb/.bt_idle.log
# Belt-and-braces PID lock: unlike a plain "kill -0 $PID" check (which can
# false-positive on an unrelated process that has since reused the same PID),
# this also confirms /proc/$PID/cmdline still names this same script before
# treating the lock as held.
if [ -f "$LOCK" ]; then
    OLD_PID=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$OLD_PID" ] && grep -q bt_idle "/proc/$OLD_PID/cmdline" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$LOCK"

TIMEOUT=__TIMEOUT_MIN__

# Small rotating log, so "Bluetooth didn't turn off last night" is answerable
# after the fact (did the daemon see a connection? did the disable fail?)
# instead of being invisible.
log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
    if [ "$(wc -l < "$LOG" 2>/dev/null || echo 0)" -gt 200 ]; then
        tail -n 100 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
    fi
}

bt_on() {
    [ "$(settings get global bluetooth_on 2>/dev/null)" = "1" ]
}

# Is any device actually connected right now?
#
# Every check below has to be *specific*: a false "yes" silently resets the idle
# timer forever, which is the failure this daemon is most prone to - and it fails
# closed (Bluetooth just stays on), so nothing announces it. An earlier revision
# matched any line carrying a MAC address and the word "Connected" as long as the
# literal string "NotConnected" wasn't on it, which also matched perfectly ordinary
# *disconnection* log lines ("... : CONNECTED -> DISCONNECTED"), i.e. it got stuck
# precisely after you finished using a device - exactly when it should have fired.
# It also counted merely *registered* GATT clients (any app doing a BLE scan) as a
# live connection.
#
# Signals used, in order:
#   1. AdapterProperties ConnectionState - the adapter's own ACL-level state,
#      covering LE-only devices (a watch) that own no classic audio profile.
#   2/3. A2DP / Headset active device, i.e. a MAC is actually selected for audio.
#   4. A2DP reporting playback in progress.
#   5. AVRCP's volume table marking a device Connected (line ends in "Connected",
#      never "NotConnected").
# Several independent signals on purpose: one of them going stale on a ROM update
# shouldn't be able to turn Bluetooth off underneath a device that's in use.
bt_connected() {
    _dump="$(dumpsys bluetooth_manager 2>/dev/null)"
    [ -z "$_dump" ] && return 0  # can't tell -> assume in use, never disable blind

    echo "$_dump" | grep -A 12 "^AdapterProperties" \
        | grep -qE "ConnectionState:[[:space:]]*STATE_CONNECT(ED|ING)" && return 0

    echo "$_dump" | grep -iA 6 "Profile: A2dpService" \
        | grep -qEi "mActiveDevice[[:space:]]*:[[:space:]]*([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}" && return 0

    echo "$_dump" | grep -iA 6 "Profile: HeadsetService" \
        | grep -qEi "mActiveDevice[[:space:]]*:[[:space:]]*([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}" && return 0

    echo "$_dump" | grep -qEi "mIsPlaying[[:space:]]*:[[:space:]]*true" && return 0

    echo "$_dump" | grep -E "([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}" \
        | grep -qE ":[[:space:]]*Connected[[:space:]]*$" && return 0

    return 1
}

# Turn the radio off and confirm it actually went off, rather than firing the
# command into the dark and waiting out another full timeout if it didn't take.
disable_bt() {
    cmd bluetooth_manager disable >/dev/null 2>&1
    _i=0
    while [ "$_i" -lt 10 ]; do
        sleep 1
        bt_on || return 0
        _i=$((_i + 1))
    done

    # Didn't take. `svc bluetooth` goes through a different entry point into the
    # same manager service and is worth one try before giving up for this round.
    log "cmd bluetooth_manager disable did not take, trying svc"
    svc bluetooth disable >/dev/null 2>&1
    _i=0
    while [ "$_i" -lt 5 ]; do
        sleep 1
        bt_on || return 0
        _i=$((_i + 1))
    done

    log "WARN: Bluetooth still on after both disable attempts"
    return 1
}

# Idle is tracked as a wall-clock deadline rather than by counting loop passes:
# `sleep` runs on CLOCK_MONOTONIC, which does not advance while the device is
# suspended, so counting 60s passes measured *awake* time only - overnight, when
# the phone is asleep almost the whole time and this matters most, the counter
# barely moved and the radio stayed on for hours past the timeout.
idle_since=""
while true; do
    sleep 60

    if ! bt_on; then
        idle_since=""
        continue
    fi

    if bt_connected; then
        idle_since=""
        continue
    fi

    now=$(date +%s)
    if [ -z "$idle_since" ]; then
        idle_since=$now
        continue
    fi

    # Guard against a clock jump backwards (NTP correction after boot).
    if [ "$now" -lt "$idle_since" ]; then
        idle_since=$now
        continue
    fi

    if [ $(( (now - idle_since) / 60 )) -ge "$TIMEOUT" ]; then
        if disable_bt; then
            log "Bluetooth off after ${TIMEOUT}min idle"
        fi
        idle_since=""
    fi
done
