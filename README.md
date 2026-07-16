# Q25 Toolbox

A root app for the Zinwa Q25 (KernelSU, MediaTek-based, physical QWERTY
keyboard) that bundles a set of tweaks into one UI, organised into five
bottom-bar sections:

- **Info** — device status landing page: build info and battery (level,
  health, temperature, voltage, capacity), plus an entry into the Battery
  Usage breakdown.
- **Keyboard** — key remapping, lockscreen PIN entry on the physical
  keyboard, per-app keyboard block, chat Enter-to-send, calculator-key
  routing, and IME suggestion shortcuts.
- **System** — extra dimming (with an optional night schedule), per-app
  display scaling, auto-focus input, and in-call shortcuts.
- **Network** — telemetry blocking, wireless ADB, and Bluetooth auto-disable.
- **Settings** — update checking (with in-app download + install), quick
  links to the Accessibility/Input Method system settings, contributors, and
  about.

Ported from [Key2 Toolbox](../Key2Toolbox), the same app built for the
BlackBerry Key2. Most of the UI and architecture carried over unchanged; the
hardware underneath several modules did not, so a few Key2-only features
(ZRAM control, the Key2 App Spoof module) were left out of this Q25 build.

The UI follows Material You (Monet), in light or dark to match the system.
Most modules are stateless: they fire root commands on demand and persist by
installing a script to `/data/adb/service.d/`. PIN keyboard, the input fixes,
Per-App Keyboard Block, Per-App Display Scaling, Auto-Focus, and In-Call
Shortcuts instead depend on a long-lived `Q25AccessibilityService` that
watches window/IME/foreground state and intercepts physical key events, since
none of that is observable from a one-shot root command. Their settings live
in a `q25tweaks` SharedPreferences file rather than going through
`AssetInstaller`.

The accessibility-service modules only work once **Q25 Toolbox** is enabled
under Settings → Accessibility - each of their screens shows a banner with a
direct link there if it isn't. Reinstalling the app resets this, so it needs
re-enabling after every fresh install.

Two boot-time daemons (Extra Dim's schedule, Global Telemetry Block) run
detached (`setsid`) and are guarded by a PID lock that also verifies
`/proc/$PID/cmdline` still names the same script - not just `kill -0`, which
can false-positive once an unrelated process reuses a stale PID after
reboot. Their screens self-heal (relaunch the daemon) if they find it dead
on open. Bluetooth Auto-Disable's watchdog still uses the plain `kill -0`
form and hasn't hit the same failure mode yet, but is a candidate for the
same fix if it does.

## Signing with your existing keystore

`app/build.gradle.kts` has a commented-out `signingConfigs { create("release") {...} }`
block referencing `kgr_signing.keystore` / alias `kgr`. Uncomment it, point
`storeFile` at your keystore path, and wire `signingConfig = signingConfigs.getByName("release")`
into the `release` build type. Consider passing the password via
`gradle.properties` (gitignored) or an env var rather than committing it in
the build script. Until then, both build types are signed with the debug key.

## How each module works

### Key Remapper (`KeyRemapController`)
- Remaps one of three source keys to Right Ctrl at the hardware keylayout
  level: the **Currency key** (`GRAVE`, scancode 41 - the firmware-miswired
  key next to Space), **Right Shift** (scancode 54), or the **Recents**
  BlackBerry key (`APP_SWITCH`, scancode 580 - remapping it means losing the
  hardware shortcut for recents).
- Rewrites `/system/usr/keylayout/Q25_keyboard.kl` (confirmed live via
  `dumpsys input` that the keyboard actually resolves its KeyLayoutFile to
  this exact path - not `Generic.kl`, despite the two files historically
  sharing near-identical content). A generated boot script
  (`service.d/key_remap.sh`) unbinds the i2c driver first (so EventHub
  releases its file descriptor on the layout file), `umount`s any previous
  bind, copies the pristine original, `sed`s the target scancode's keycode to
  `CTRL_RIGHT`, `chcon`s it to `system_file` so `system_server` can read it,
  bind-mounts the copy over the original, and rebinds the driver
  (`/sys/bus/i2c/drivers/Q25_keyboard/{un,}bind`, device `6-001f`) so
  EventHub reloads it. The same script runs live on save, so no reboot is
  needed.
- Settings live in the shared `q25tweaks` prefs file.

### Auto-Focus Input (`AutoFocusController`)
- Uses the accessibility service to focus the first editable field in
  selected apps once a printable key is pressed, so e.g. the Google Phone
  dialer or a browser's search box is ready for immediate keyboard input
  without tapping it first.
- Focuses via `ACTION_FOCUS`/`ACTION_CLICK` then inserts the triggering
  character via `ACTION_SET_TEXT` once the field actually receives input
  focus - woken by the next relevant accessibility event rather than a fixed
  poll interval, with a 1s timeout as a safety net for apps where that event
  never cleanly fires.
- The per-app target list is stored in the same `q25tweaks` prefs set used by
  the other accessibility-service modules, so the behavior can be enabled or
  disabled independently for each app.

### In-Call Shortcuts (`Q25AccessibilityService`)
- Adds physical-keyboard shortcuts on Google Phone's in-call screen: **M**
  toggles mute, the **currency key** (its raw `GRAVE` keycode, or
  `CTRL_RIGHT` if remapped above) toggles speaker, and `W E R S D F Z X C 0`
  route to the dialpad as `1 2 3 4 5 6 7 8 9 0` - inserted via
  `ACTION_SET_TEXT` directly into the digits field rather than shell-injected
  `input keyevent`, which is unreliable immediately after the dialpad's
  own open/visibility transition. The dialpad also auto-opens when the call
  screen loads.
- Runs with priority ahead of Auto-Focus's own key handling on the in-call
  screen specifically (identified by the presence of the expected
  keypad/mute/speaker toggle triple), so a call in progress can't have its
  mute/speaker presses stolen by Auto-Focus reaching for an unrelated
  unfocused field elsewhere in the same window tree. Auto-Focus still handles
  every other dialer screen (pre-call dial pad, contact search) normally.

### Extra Dimming (`ExtraDimController`)
- Toggles Android's built-in Accessibility "Extra Dim" feature
  (`reduce_bright_colors_activated` / `reduce_bright_colors_level` secure
  settings) directly from the toolbox, with a 0-100% intensity slider.
- The manual toggle/level need no persistence - they're standard system
  settings that survive reboot on their own.
- **Auto Night Dim schedule** (optional): a `service.d/extra_dim_schedule.sh`
  watchdog turns Extra Dim on/off at a chosen start/end time, accurate to the
  minute (window may wrap past midnight). It only writes the setting on an
  on/off transition, so a manual override in between the two edges isn't
  immediately clobbered - matching how Android's own Night Light schedule
  coexists with manual toggles.

### Per-App Display Scaling (`AppScalingController` + `Q25AccessibilityService`)
- This ROM ignores every *per-app* scaling mechanism - the compat-framework
  `DOWNSCALE_*` changes and GameManager downscale are both no-ops, and `wm
  density` has no effect either (all verified on-device, byte-identical
  screenshots). The one display knob that works is `wm size` (physical
  resolution).
- So "per-app scaling" is done by switching the **global** resolution: the
  accessibility service (which already tracks the foreground app for the
  keyboard-block module) runs `wm size <W>x<H>` when a chosen app comes to
  the foreground and `wm size reset` when leaving.
- Targets are full width×height (not just squares), so an app can be given a
  taller portrait aspect. Presets follow duc1607's
  [q25-res-changer](../q25-res-changer) (720×720 native, 720×772, 720×960,
  720×1280, 720×1440; the SystemUI-breaking 780×780 is omitted), plus a
  **Custom…** entry to type any W×H (240–2000).
- The whole screen (incl. system UI) briefly relayouts on enter/exit -
  inherent to a global resolution change. Per-app targets are stored as a
  `pkg=WxH` StringSet in the `q25tweaks` prefs; the service always resets to
  native on teardown so it can't leave the screen stuck at a scaled
  resolution.

### Persistent wireless ADB (`WirelessAdbController`)
- User enters a port; **persist** installs `assets/adb_wireless_template.sh`
  (with `__PORT__` substituted) to `/data/adb/service.d/adb_wireless.sh`,
  which waits for `sys.boot_completed` then sets `adb_wifi_enabled` and pins
  `persist.adb.tcp.port` / `service.adb.tcp.port`.
- **Live apply** sets the same properties immediately.
- The screen also shows the device's current WLAN IP and the live port, so
  you can confirm the `adb connect <ip>:<port>` target at a glance.

### Bluetooth Auto-Disable (`BtIdleController`)
- A root watchdog daemon (`service.d/bt_idle.sh`) that checks once per minute
  whether Bluetooth has any device actively connected and turns it off after
  a configurable number of idle minutes (5 / 10 / 15 / 30 / 60, default 15).
- Connection detection uses five strategies against `dumpsys
  bluetooth_manager`: device table status, active A2DP/Headset device,
  `mIsPlaying` flag, profile connection state, and GATT client/server map
  entries. A PID lock file (plain `kill -0`, not yet the PID+cmdline check
  used by Extra Dim/Telemetry) ensures only one daemon instance runs at a
  time.

### Global Telemetry Block (`TelemetryController`)
- Scans `/data/data/*/com.google.firebase.crashlytics.xml` across all
  installed apps and sets `firebase_crashlytics_collection_enabled` to
  `false`.
- Apps rewrite that XML at runtime (Crashlytics re-enables collection on app
  start), so a one-shot pass gets undone. `service.d/block_telemetry.sh` is
  therefore a **watchdog daemon**: an initial pass ~15s after boot, then a
  re-scan every 30 minutes. It runs the scan inside the init (pid 1) mount
  namespace via `nsenter` so it sees the real `/data/data` both at boot and
  when launched live from the app. A PID+cmdline lock keeps a single
  instance.

### Lockscreen PIN on Keyboard (`Q25AccessibilityService`)
No root needed. While the keyguard is locked, maps physical key presses to
taps on SystemUI's PIN pad via `AccessibilityNodeInfo`, so the PIN can be
entered on the hardware keyboard instead of the touchscreen. Digits map
phone-dialpad style onto QWERTY: `W E R` = `1 2 3`, `S D F` = `4 5 6`, `Z X C`
= `7 8 9`, `Q` = `0` (number row also works directly). Enter/D-pad-center
confirms, Delete/Backspace deletes. Button lookup goes straight to the known
SystemUI resource ID for the pressed digit, falling back to a recursive node
search by visible text or content description if that ID doesn't match on
this build.

### Per-App Keyboard Block (`Q25AccessibilityService` + `Q25PassthroughIme`)
In a chosen set of apps, physical key presses are routed straight to the app
instead of going through the keyboard - useful for games or any app that
wants raw key events rather than IME-translated text. The service tracks the
foreground app and, when a selected package is in front, switches the
default IME to a bundled do-nothing input method (`Q25PassthroughIme`) via
root `ime enable`/`ime set`, saving the previous IME to restore on the way
out. The picked packages are a `StringSet` in the `q25tweaks` prefs; the app
list uses a `<queries>` launcher intent so it can enumerate launchable apps
on Android 11+.

### Chat Enter-to-Send / Calculator Keys (`Q25AccessibilityService` + `inputfix/`)
Ported from [smh786/q25-input-helper](https://github.com/smh786/q25-input-helper).
No root needed. Both are physical-key handlers dispatched from the
accessibility service's `onKeyEvent`, gated by their own `q25tweaks` prefs
(`chat_composer_enabled` / `calculator_enabled`, both off by default). The
handler classes (`ComposerEnterKeyHandler`, `CalculatorInputFix`,
`Q25KeyTranslator`, `AccessibilityNodes`) live under `inputfix/` as Java,
copied largely verbatim from the source app; each inspects the foreground app
itself and no-ops outside its target apps, so they're safe to call for every
key.
- **Chat Enter-to-Send**: in a set of messaging apps (Messages, WhatsApp,
  Telegram, Signal, Element/Matrix, Mattermost, ChatGPT, Perplexity), a plain
  Enter on a non-empty composer clicks the app's send button (found by view
  ID / content-description / label heuristics), while Alt+Enter and
  Shift+Enter fall through as a normal newline.
- **Calculator Keys**: in the AOSP/Google Calculator, maps digit and operator
  keys to the calculator's buttons (by view ID with a label fallback), and
  inserts `!`, `(`, `)` straight into the formula field via `ACTION_SET_TEXT`.

### IME Suggestion Shortcuts (`Q25AccessibilityService`)
No root needed. Ctrl+W/E/R picks suggestion 1/2/3 from the physical
keyboard's candidate strip (confirmed against BlackBerry Keyboard; other
BlackBerry-derived keyboards like Harpocrat are expected to share the
structure). Finds the Nth clickable `TextView` in the IME window - only
consumes the key combo if a suggestion was actually found and clicked, so
e.g. Ctrl+W still closes a browser tab normally when nothing's showing.

### Battery Usage (`BatteryUsageController`)
Per-app battery estimate (percentage, mAh, and a breakdown pie chart) since
the last reset, read via root from the same underlying power model Android's
native "Battery usage" screen uses (`dumpsys batterystats --checkin`) - which
never populates on this device natively, since it additionally requires a
`BATTERY_STATUS_FULL` transition the charging driver never reports. Reached
from a card on the Info screen. Stats auto-reset once charging crosses a
configurable threshold (default 100%, set from the screen's `⋮` menu), or you
can reset manually at any time. Charge-cycle count is deliberately not shown
- this gauge driver reports a static, non-incrementing value regardless of
actual usage, with no alternate source on this hardware.

### Settings tab (`settings/`)
- **Updates**: checks `github.com/nozerorma/q25toolbox` releases, comparing
  version cores and pre-release suffixes properly (so e.g. `beta9` isn't
  misjudged newer than `beta14`) rather than just flagging any different tag.
  "Download & install" fetches the release's `.apk` asset and installs it via
  root (`pm install -r`) without leaving the app.
- **Quick Access**: shortcuts straight to the system Accessibility and Input
  Method settings screens.
- **Contributors**: avatars pulled live from the GitHub API.
- **About**: current version and repo link.

### Double-Tap to Wake (`Dt2wController`) - currently hidden from the UI
- The Q25 touch panel has **no** hardware/driver gesture-wake, and the
  `double_tap_to_wake` secure setting isn't wired to anything on this ROM. So
  DT2W was done in software by a root watchdog daemon (`service.d/dt2w.sh`,
  adapted from
  [nozerorma/q25-double-tap-wake](https://github.com/nozerorma/q25-double-tap-wake)):
  it watches the touchscreen via `getevent` and, on a quick double-tap while
  the screen is off, injects `KEYCODE_WAKEUP`.
- The controller, screen, and boot script are still in the repo, but the menu
  entry is currently omitted from the System tab: the daemon has repeatedly
  degraded SystemUI/input dispatch after extended runtime across multiple
  rewrites, so it's parked here in case a different approach is worth
  revisiting rather than exposed as a working feature today.

## Extending

- For stateless root-command modules, add a `core`-style controller in
  `modules/`, following the pattern of `KeyRemapController` /
  `WirelessAdbController` / `BtIdleController` (persist via
  `AssetInstaller`, live-apply via `RootShell.run`). If it runs as a boot
  daemon, use the PID+cmdline lock pattern from
  `extra_dim_schedule_template.sh` (not a bare `kill -0`, which can
  false-positive on a reused PID) and launch it via `nohup setsid sh ...`
  (a bare `nohup ... &` can die when the invoking root shell session is
  later recycled).
- For features that need to observe ongoing state (window/IME visibility,
  key events) rather than just fire a command, that observation has to
  happen inside `Q25AccessibilityService` - root has no API for "tell me
  when X happens," only for executing commands. Add the logic there, store
  settings as SharedPreferences booleans/ints in the `q25tweaks` prefs file,
  and write the corresponding screen to read/write those same keys directly
  rather than going through `AssetInstaller`.
- Either way, add a corresponding screen in `ui/` (following e.g.
  `KeyRemapScreen.kt` for the simple case or `ImeBlockScreen.kt` for the
  prefs-based case, all built on the shared `ScreenScaffold`), and wire it
  into `DetailHost` plus the section lists in `ui/HomeScreen.kt` and
  `ui/Screen.kt`.
- For something that belongs on the Settings tab instead of a module screen
  (account-like state, app-level info) follow `settings/SettingsScreen.kt`'s
  pattern instead of the `Screen`/`DetailHost` navigation used everywhere
  else.
- Drop any new boot scripts in `app/src/main/assets/`.
- New user-facing strings go in `res/values/strings.xml` first; the other 7
  locale files (`values-ca/de/es/fr/it/nl/pt`) can lag behind as a follow-up
  translation pass rather than blocking the feature.
