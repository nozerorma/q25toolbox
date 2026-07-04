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
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    exit 0
fi
echo $$ > "$LOCK"

TIMEOUT=__TIMEOUT_MIN__
idle=0
while true; do
    sleep 60
    if [ "$(settings get global bluetooth_on 2>/dev/null)" != "1" ]; then
        idle=0
        continue
    fi
    # Check for any actively connected BT device.
    #
    # We parse the dumpsys bluetooth_manager output using four strategies:
    #   1. Device Table: Line containing a MAC address and "Connected" but NOT "NotConnected".
    #   2. Active Audio: Profile A2dpService or HeadsetService has mActiveDevice != null (pointing to a MAC).
    #   3. Active Playback: A2dpService indicates mIsPlaying: true.
    #   4. State Machines: Any profile connection status set to STATE_CONNECTED or 2.
    #   5. GATT maps: GattClientMap or GattServerMap has Entries > 0 (e.g. active wearables).
    _bt_dump="$(dumpsys bluetooth_manager 2>/dev/null)"
    _connected=false

    # 1. Device Table Status Check
    if echo "$_bt_dump" | grep -E "([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}" | grep -Fv "NotConnected" | grep -qi "Connected"; then
        _connected=true
    fi

    # 2. Active Audio Device
    if ! $_connected; then
        echo "$_bt_dump" | grep -iA 5 "Profile: A2dpService" | grep -qiE "mActiveDevice[[:space:]]*:[[:space:]]*([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}" && _connected=true
    fi
    if ! $_connected; then
        echo "$_bt_dump" | grep -iA 5 "Profile: HeadsetService" | grep -qiE "mActiveDevice[[:space:]]*:[[:space:]]*([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}" && _connected=true
    fi

    # 3. Active Playback check
    if ! $_connected; then
        echo "$_bt_dump" | grep -qi "mIsPlaying[[:space:]]*:[[:space:]]*true" && _connected=true
    fi

    # 4. Connection State check
    if ! $_connected; then
        echo "$_bt_dump" | grep -qiE "mConnectionState[[:space:]]*:[[:space:]]*(STATE_CONNECTED|2)" && _connected=true
    fi

    # 5. GATT Clients/Servers map
    if ! $_connected; then
        _gatt_clients=$(echo "$_bt_dump" | grep -A 2 "GATT Client Map" | grep -oE "Entries:[[:space:]]*[0-9]+" | grep -oE "[0-9]+")
        _gatt_servers=$(echo "$_bt_dump" | grep -A 2 "GATT Server Map" | grep -oE "Entries:[[:space:]]*[0-9]+" | grep -oE "[0-9]+")
        if [ "${_gatt_clients:-0}" -gt 0 ] || [ "${_gatt_servers:-0}" -gt 0 ]; then
            _connected=true
        fi
    fi

    if $_connected; then
        idle=0
    else
        idle=$((idle + 1))
        if [ "$idle" -ge "$TIMEOUT" ]; then
            cmd bluetooth_manager disable >/dev/null 2>&1
            idle=0
        fi
    fi
done
