# Changelog

All notable changes to Q25 Toolbox are documented here. This app started as
a fork of [Key2 Toolbox](../Key2Toolbox) for the BlackBerry Key2 - entries
below [1.0-beta1] are inherited history from before the fork.

## [1.0-beta11] - 2026-07-06

### Added
- **Battery Usage screen**: a new screen under Battery Health shows a real
  per-app battery estimate (percentage and mAh, since last charge), read
  directly from the system's own power model via root. Works even though the
  native Settings "Battery usage" screen never populates on this device (it
  additionally requires a full-charge signal the charging driver never
  reports).
- **Recents (BlackBerry key) remap option**: the dedicated recent-apps/
  task-switcher key can now be reassigned to Ctrl alongside Currency and
  Right Shift.
- **IME Suggestion Shortcuts**: Ctrl+W/E/R picks suggestion 1/2/3 from the
  physical keyboard's candidate strip (BlackBerry Keyboard, Harpocrat, and
  similar). Only acts when a suggestion is actually showing.
- **Round clock time picker for Extra Dim's schedule**: replaced the
  scrollable hour-chip rows with a proper 24-hour dial, and the schedule now
  supports any time of day (e.g. 00:35), not just whole hours.

### Fixed
- **Auto-focus getting stuck after losing focus mid-session** (e.g. tapping a
  back arrow, touching the screen elsewhere in the same app): replaced a
  "have we tried already" flag with a live check of whether a text field is
  actually focused, so it naturally retries whenever focus is genuinely lost.
- **Auto-focus not working in Gmail/Maps, or landing on the wrong element**:
  the check for "is something already focused" was matching non-editable
  widgets those apps focus by default (a list item, the map surface). Now
  only counts an actual text field as "already focused."
- **Auto-focus silently failing in some apps (needed two keypresses)**: the
  previous approach re-simulated the triggering keypress after focusing,
  which turned out unreliable - confirmed via logging that the character
  never actually landed in some fields, and the simulated event didn't even
  re-enter our own key handling like a real press does. Now sets the field's
  text directly through the Accessibility API instead, sidestepping IME/
  input-connection timing entirely.
- **Dialer's physical letter keys typing the letter instead of the phone
  digit** (a regression from the fix above): the generic text-insertion path
  now maps letters to their phone-keypad digit (F → 6) when the target is
  the dialer, matching the existing in-call shortcut mapping.
- **Long delay switching apps that use the per-app keyboard block** (e.g. the
  dialer), which could take up to ~3 seconds and made the first keypress
  after switching apps get lost: IME switching now runs on its own dedicated
  thread instead of sharing one with the slower auto-focus/dialpad polling
  logic, cutting the real end-to-end delay to a few hundred milliseconds.
- **In-call dialpad needing two presses for the first digit**: the digit
  handler assumed the dialpad was already open; now it verifies the dialpad
  actually finished opening (polling, not a fixed guess) before injecting.
- **Key-remap corruption when switching source keys while enabled**: the
  boot script unmounted the old keylayout override *before* unbinding the
  driver, so the old bind-mount hadn't actually released yet when the script
  went to copy the "original" file - reading through a stale or deleted
  mount instead. Reordered to unbind first, matching the sequence already
  used by the disable path.
- **Slight lag on the lockscreen PIN pad**: ported the leaner approach from
  the original `q25pininput` project - looking up the pressed digit's button
  directly by resource id from the root, instead of first scoping into an
  intermediate PIN-pad container via a manual tree walk.
- **Misleading charge-cycle count** on the Battery Health card: the MTK gauge
  driver reports a static, non-incrementing value (always 1) with no
  alternate source on this hardware, so it's no longer shown (the
  capacity-based health percentage, which is accurate, stays).
- Leaked Dutch words in the Catalan and Spanish translations of the Key
  Remap screen's description.
- Missing translations for the Recents key-remap option and the IME
  Suggestion Shortcuts screen, across all 7 supported languages.

### Changed
- **Normalized every screen's header** to a single row (back arrow + title),
  matching the newer per-app-picker screens' style.
- **Per-app picker screens** (Auto-focus, Per-App Keyboard Block, Per-App
  Display Scaling): only the search field stays pinned while scrolling now;
  the header, switch, and description scroll away with the list so more of
  it is visible at once.

## [1.0-beta10] - 2026-07-05

### Fixed
- **DT2W degrading SystemUI after a few hours**: the watchdog forked `date`
  and `cat` on every single touch anywhere on the device (not just wake
  gestures), continuously, for as long as it ran. Rewritten to do all timing
  and state inside one persistent `awk` process fed by `getevent`'s own
  timestamps, so no subprocess is spawned except the rare actual wake action.
- **Typing lag and occasional double-typed characters in auto-focus apps**:
  a leftover `autoFocusDone` flag was set but never checked, so every
  keystroke in an auto-focus-enabled app (Gmail, Maps, banking apps, browser)
  redid a full window-tree search even after the field was already focused.
  Now the focus attempt only ever runs once per app-foreground session, which
  also removes the race where a fast second keypress could queue its own
  overlapping focus-and-reinject before the first one had landed.

## [1.0-beta9] - 2026-07-05

### Added
- **Recents key remap option**: the dedicated recent-apps/task-switcher key
  (BlackBerry key) can now be reassigned to Ctrl alongside the Currency and
  Right Shift options.
- **Update check**: the Info screen now checks GitHub for a newer release on
  load and shows a tappable link to the release page when one is available.

### Fixed
- **Auto-focus in Maps/Gmail**: search boxes that activate via click rather
  than accessibility focus (Google Maps' omnibox, Gmail's search bar) are now
  correctly detected and typed into, instead of silently failing.
- **Auto-focus in browsers**: when a WebView page has no input fields, typing
  no longer falls through to focus the browser's own address bar.
- **Key-remap script for 3+ digit scancodes**: the boot-script's keylayout
  edit now tolerates the layout file's variable column spacing, which
  previously made 3-digit scancodes (like the Recents key) silently fail to
  remap.
- **Lockscreen PIN input lag**: the PIN-button lookup no longer keeps walking
  the whole keypad tree after finding an exact resource-id match.

## [1.0-beta8] - 2026-07-05

### Added
- **Info-screen access status**: the Access card now reports the current root,
  accessibility-service, and IME state at a glance, and the related rows open
  the relevant Android settings screens directly.
- **Key remapping improvements**: spare hardware keys can be reassigned to
  Ctrl/other modifiers and applied live with persistent boot-script support.
- **Auto-focus input**: the app can now focus the first editable field in
  selected apps, including the Google Phone dialer when it opens.
- **In-call shortcuts for Google Phone**: press M to mute, $ to toggle the
  speaker, the dialer opens automatically for calls, and numeric keys can be
  routed directly to the dialer keypad.
- **Improved beta versioning**: the app version name and code now match the
  beta8 release so update trackers such as Obtainium can detect the new build
  correctly.

### Changed
- Simplified the Access card wording while keeping the accessibility and IME
  shortcuts clear.
- Added localized strings for the new Info-screen copy in the existing supported
  languages.

## [1.0-beta7] - 2026-07-05

### Added
- **Complete key-remap redesign**: remapping is now applied through the
  keyboard layout itself, with live reload via driver unbind/rebind and a
  persistent boot script for future reboots.
- **Expanded localization**: translated the main UI strings into Catalan,
  German, Spanish, French, Italian, Dutch, and Portuguese.

### Changed
- Cleaned up the accessibility-service key handling to reduce soft-intercept
  overhead while keeping the remap behavior intact.

## [1.0-beta6] - 2026-07-05

### Added
- **Hardware-key remap to Right Ctrl**: a spare key (Currency or Right Shift)
  can now be remapped to Right Ctrl directly from the app.
- **New Key Remap screen**: an in-app screen exposes the remapping option with
  simple on/off controls.

### Changed
- Integrated the remap feature with the newer string-resource translation flow
  so the UI is easier to localize and maintain.

## [1.0-beta5] - 2026-07-04

### Fixed
- **More stable per-app scaling**: improved the foreground/window tracking logic
  so scaling behaves more reliably across apps.
- **Lockscreen reset and taskbar suppression**: fixed the reset path when the
  screen locks and prevented the Android taskbar from appearing after scaling.

### Changed
- Added additional guards around scaling commands, including a busy-state guard
  and timeouts to prevent shell stalls while applying resolution changes.

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
