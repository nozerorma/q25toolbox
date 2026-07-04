# Changelog

All notable changes to Q25 Toolbox are documented here. This app started as
a fork of [Key2 Toolbox](../Key2Toolbox) for the BlackBerry Key2 - entries
below [1.0-beta1] are inherited history from before the fork.

## [1.0-beta4] - 2026-07-04

### Added
- **ZRAM** control (re-added from Key2 Toolbox, MTK-adapted): compressed-swap
  size (Off / 2 / 3 / 4 / 6 / 8 GB for the Q25's 12 GB RAM), compression
  algorithm (kernel-supported only: lzo/lzo-rle/lz4/zstd) and swappiness.
  Saves for next boot; "Apply now" reinitialises zram0 live behind a
  confirmation. No Qualcomm post-boot wait (the Q25 is MediaTek). The live
  swapoff/reset/mkswap/swapon sequence was verified on-device.
- **Key2 App Spoof** (`ProdFixController`): installs wumbomumbo's BBProdFix Lite
  as a KernelSU/Magisk module into `/data/adb/modules/bb-prodfix/` (bundled in
  `assets/bbprodfix/`) - `system.prop` spoofing `ro.product.*` to KEY2/
  blackberry/bbf100, plus the `com.blackberry.only` shared library BB-only apps
  require. Enabling prompts a reboot; disabling flags the module for removal.
  Delivered as a module because both pieces only work applied at boot from a
  systemless overlay. Jar copy verified binary-intact through the app's
  installer.

## [1.0-beta3] - 2026-07-04

### Added
- **Key Remapper** - generalises the old "Right Shift → Ctrl" module. Each of
  the five modifier keys (Left Alt, Left/Right Shift, Right SYM, Currency) can
  be remapped to Default / Ctrl / Shift / Alt / Meta / Tab, and the currency
  key's typed symbol can be personalised (` ` ` / $ / € / £ / ¥ / ₹ / ₩ / ¢).
  Applied by rewriting `Generic.kl` (scancode → keycode) and `Generic.kcm` (the
  GRAVE `base:` char) and reloading the i2c keyboard driver. Scancodes sourced
  from the pastiera Q25 map and verified on-device.
- **Custom resolution** in Per-App Display Scaling: targets are now full
  width×height (not just squares), with presets from q25-res-changer (720×772 …
  720×1440) and a Custom… dialog to enter any W×H. Storage moved to `pkg=WxH`.

### Notes
- **MTK Analog Audio Fix** (invalidsudo) was evaluated: the Q25 is the exact
  target hardware (MT6789/Helio G99 + MT6366) and `tinymix` runs, but
  `Headset Volume` already reads a calibrated `31 31` (not the uncalibrated
  `0 0` the fix targets). That fix is for non-stock ROMs/GSIs that drop the
  factory calibration; the Q25's stock BenOS already sets it, so the fix was
  not implemented.

## [1.0-beta2] - 2026-07-04

On-device debugging of the ported modules against the actual Q25 (BenOS/MTK
Android 14) hardware. Several modules were fixed once the real mechanism was
found; two were dropped as infeasible on this hardware.

### Fixed
- **Right Shift → Ctrl** now targets `Generic.kl`, not `Q25_keyboard.kl`. The
  keyboard resolves its key *layout* to `Generic.kl` (confirmed via `dumpsys
  input`), so the old edits had no effect. Also rebinds the i2c keyboard driver
  to reload the layout live.
- **Double-Tap to Wake** is now a software watchdog. The Q25 has no
  hardware/driver gesture-wake and ignores `double_tap_to_wake`, so a daemon
  watches the touchscreen via `getevent` and injects `KEYCODE_WAKEUP` on a
  double-tap. Adapted from nozerorma/q25-double-tap-wake and reworked for
  stability (cheap `lcd-backlight` screen-state read, idempotent
  `KEYCODE_WAKEUP` instead of `KEY_POWER`) to avoid that project's crash.
- **Global Telemetry Block** is now a watchdog daemon (re-scan every 30 min via
  `nsenter` into the init mount namespace) instead of a one-shot boot pass,
  since apps re-enable Crashlytics at runtime.

### Changed
- **Per-App Display Scaling** reworked: `am compat DOWNSCALE_*`, GameManager
  downscale and `wm density` are all no-ops on this ROM (verified). Only
  `wm size` works, so scaling is now per-app by foreground-switching the global
  resolution via the accessibility service (square-resolution presets), with a
  reset to native on exit.
- **App-list screens** (Per-App Keyboard Block, Per-App Display Scaling) got a
  compact collapsing top bar (title inline with Back), a 3-dot overflow menu
  with a **Show system apps** toggle (off by default), and list-only scrolling
  so the search/controls stay pinned on the small square screen.
- **Renamed** `Key2AccessibilityService`/`Key2PassthroughIme` → `Q25…`, prefs
  file `key2tweaks` → `q25tweaks`, and stripped the dead BlackBerry-Key2 Nav
  Lock code (it targeted a `0dbutton`/Synaptics sysfs node absent on the Q25).

### Removed
- **Adaptive Keyboard Backlight** - the Q25's `bbqX0kbd` (i2c_puppet) keyboard
  exposes no host-controllable backlight (no `/sys/class/backlight` node, no
  `/dev/i2c`); it's set only by the firmware combo Sym + Right-Shift + 1..9.
- **Increase Volume Steps** - raising `ro.config.*_vol_steps` broke volume
  control above the default step count on this device.

## [1.0-beta1] - 2026-07-03

Initial Q25 Toolbox release - ports Key2 Toolbox to the Zinwa Q25. Package
renamed `com.kgr.key2toolbox` → `com.kgr.q25toolbox`, version reset to
1.0-beta1 since this is now a separate app for different hardware.

### Added
- **Chat Enter-to-Send** and **Calculator Keys** (Keyboard tab): physical-key
  fixes ported from [nozerorma/q25-input-helper](https://github.com/nozerorma/q25-input-helper).
  Chat Enter-to-Send makes a plain Enter post the message (Alt/Shift+Enter for
  a newline) in Messages, WhatsApp, Telegram, Signal, Element/Matrix,
  Mattermost, ChatGPT and Perplexity; Calculator Keys route number/operator
  keys to the AOSP/Google Calculator. Both run off the existing accessibility
  service (no root), gated by `key2tweaks` prefs and off by default. The
  handler classes live under `inputfix/` as Java, copied near-verbatim from the
  source app.
- **Adaptive Brightness (One-Shot)** (`OneShotBrightnessController`): a root
  watchdog daemon (`service.d/one_shot_brightness.sh`) that measures ambient
  light once each time the screen wakes, then holds that brightness steady in
  manual mode until the next screen-off - reproducing LineageOS's one-shot
  auto-brightness on this ROM, which lacks the LineageSettings framework flag.
  It reads the committed brightness from `dumpsys display` and clamps to a
  minimum so a dark reading can't blank the screen. Disabling restores normal
  continuous adaptive brightness.
- **Extra Dim schedule** (`ExtraDimController`): a "Auto Night Dim" section
  that turns Extra Dim on/off at a chosen start and end hour every day, via a
  `service.d/extra_dim_schedule.sh` watchdog (only writes on a transition, so
  a manual toggle in between isn't immediately overwritten).
- **Increase Volume Steps** (`VolumeStepsController`): raises
  `ro.config.media_vol_steps` / `ro.config.vc_call_vol_steps` above their
  stock defaults for finer-grained volume control. Applies live via KernelSU
  `resetprop` and persists via `service.d/volume_steps.sh`.
- **Extra Dimming** (`ExtraDimController`): toggle + 0-100% intensity slider
  for Android's built-in "Reduce Bright Colors" accessibility feature, to dim
  below the system's normal brightness floor.
- **Per-App Display Scaling** (`AppScalingController`): per-app resolution
  downscale via the compat-framework `DOWNSCALE_*` change IDs, to force a
  phone-sized layout on apps that misbehave at the Q25's 720×720/sw554dp
  screen.

### Changed (hardware adaptations for the Q25)
- **Right Shift → Ctrl remap**: Key2's `/vendor` remount + in-place `sed` on
  `stmpe.kl` doesn't apply here (different partition, different keylayout
  file). Now bind-mounts a modified copy of `Q25_keyboard.kl` (key 54) over
  the read-only original, and unbind/rebinds the i2c keyboard driver for the
  change to take effect live.
- **Keyboard backlight**: now targets the Q25's single `bbq20kbd-backlight`
  class device instead of the Key2's three separate LED nodes.
- **Double-Tap to Wake**: now uses the standard `double_tap_to_wake` secure
  setting instead of a raw touch-driver sysfs write - the Q25's driver
  honors it directly.

### Removed (Key2-specific, not applicable to the Q25)
- **Keyboard Nav Lock** - capacitive nav buttons (`0dbutton` sysfs node) that
  this locked don't exist on the Q25.
- **5GHz Hotspot Workaround** - was specific to the Key2's WiFi regdomain
  channel tables.
- **CPU Performance Tuning** - tuned Key2-specific Schedutil/CAF sysfs paths;
  not re-verified against the Q25's cpufreq layout.
- **ZRAM** - not carried over for this initial release.

## [4.2-beta1] - 2026-06-28 (Key2 Toolbox)

### Added
- **Network tab** — new bottom-bar section grouping all network-adjacent tweaks.
- **CPU Performance Tuning** (`PerformanceController`): tunes the Schedutil
  `up_rate_limit_us` on the LITTLE cluster (policy0) and the CAF input-boost
  frequency/duration via `/sys/devices/system/cpu/cpu_boost/`. Settings apply
  live and persist via `service.d/cpu_performance.sh`; the script waits for
  `init.svc.qcom-post-boot` to finish so it wins the race against the Qualcomm
  post-boot tuner.
- **Global Telemetry Block** (`TelemetryController`): scans every installed app
  for `com.google.firebase.crashlytics.xml` and sets
  `firebase_crashlytics_collection_enabled` to `false`. Runs once at boot
  (after a 15-second delay so app data directories exist) and can also be
  applied live from the screen, which reports how many apps are affected vs
  already blocked.
- **Wearable Power Saver** (`WatchController`, formerly Galaxy Watch module):
  lists all wearables registered in GMS's `connectionconfig.db` (watches,
  trackers, any GMS-paired device) and lets you toggle each into **Dormant**
  mode. Dormant devices have `connectionEnabled = 0` written to the GMS
  SQLite database, stopping GMS from firing Bluetooth reconnect alarms for
  out-of-range devices. GMS is force-stopped to pick up the change immediately.
  A `service.d/wearable_dormant.sh` boot script re-applies dormant state for
  any selected MACs, since GMS can reset the field on a cold boot.
- **Bluetooth Auto-Disable** (`BtIdleController`): installs a watchdog daemon
  (`service.d/bt_idle.sh`) that turns Bluetooth off after a configurable
  timeout (5 / 10 / 15 / 30 / 60 min, default 15) with no device connected.
  Uses five detection strategies against `dumpsys bluetooth_manager`: device
  table status, active A2DP/Headset device, `mIsPlaying` flag, profile
  connection state, and GATT client/server map entries. Connecting any device
  resets the timer. A PID lock prevents stacked instances.

### Changed
- **ZRAM UI defaults** updated to `lz4` compression, 3 GB size, and
  swappiness 40 — values confirmed optimal for this device.
- **Boot script race conditions fixed** for `force_us_wifi.sh` and
  `adb_wireless.sh`: both now wait for `init.svc.qcom-post-boot` to reach
  `stopped` before applying settings, preventing the Qualcomm post-boot script
  from overwriting them.
- **Wearable module generalised**: previously hardcoded for Galaxy Watch; now
  reads all paired GMS wearable devices dynamically from `connectionconfig.db`.

## [4.1-beta4] - 2026-06-22 (Key2 Toolbox)

### Added
- **Bottom navigation** with three sections: **Info / Keyboard / System**.
- **Info** landing screen: device (model, Android, LineageOS, security patch,
  kernel, build), battery (level, status, health, temperature, voltage,
  technology, capacity-health % and charge cycles from sysfs), and live root +
  accessibility-service status.
- **Per-App Keyboard Block**: in selected apps, physical key presses reach the
  app directly instead of the BlackBerry IME, by switching to a bundled
  do-nothing passthrough IME (`Key2PassthroughIme`) while the app is foreground
  and restoring the previous IME on exit.
- **5GHz Hotspot Workaround**: forces the WiFi region to US (live + persisted
  boot script) so 5GHz SoftAP works, since EU regdomains expose no 5GHz AP
  channels on this build.
- **Material You (Monet)** theming that follows the system light/dark setting.

### Changed
- Per-app keyboard block now switches IME instead of toggling
  `show_ime_with_hard_keyboard` (which didn't actually stop the BlackBerry IME
  from intercepting keys).
- Fixed scrolling glitches by letting the `Scaffold` own the system-bar insets
  instead of each screen re-applying them.

### Removed
- **Audio FX** (system-wide EQ / BassBoost / LoudnessEnhancer) and its
  `MODIFY_AUDIO_SETTINGS` permission. The in-app, userspace `AudioEffect`
  approach was always a compromise (the EQ ate headroom and the makeup-gain
  compressor pumped). **NLSound** does the job better by operating at the audio
  HAL level, so the in-app audio mods are dropped in its favour.

### Notes
- WiFi hotspot on this build only starts with **WPA2** (WPA3/SAE fails with
  `UNSUPPORTED_CONFIGURATION`); the empty EU 5GHz AP regdomain and the SAE
  failure are ROM/driver-level and tracked upstream.
