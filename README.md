# Q25 Toolbox

A root app for the Zinwa Q25 (KernelSU, Qualcomm-based, physical QWERTY
keyboard) that bundles a set of tweaks into one UI, organised into four
bottom-bar sections:

- **Info** — device status landing page with build, battery, and current
  root/accessibility/IME state.
- **Keyboard** — key remapping, auto-focus input, in-call shortcuts, PIN entry
  on the physical keyboard, IME blocking, Enter-to-send, and calculator-key
  routing.
- **System** — double-tap-to-wake, extra dimming, and per-app display scaling.
- **Network** — telemetry blocking, wireless ADB, and Bluetooth auto-disable.

Ported from [Key2 Toolbox](../Key2Toolbox), the same app built for the
BlackBerry Key2. Most of the UI and architecture carried over unchanged; the
hardware underneath several modules did not, so a few Key2-only features were
left out of this Q25 build.

The UI follows Material You (Monet), in light or dark to match the system.
Most modules are stateless: they fire root commands on demand and persist by
installing a script to `/data/adb/service.d/`. PIN keyboard, the input fixes,
Per-App Keyboard Block and Per-App Display Scaling instead depend on a
long-lived `Q25AccessibilityService` that watches window/IME/foreground state
and intercepts physical key events, since none of that is observable from a
one-shot root command. Their settings live in a `q25tweaks` SharedPreferences
file rather than going through `AssetInstaller`.

The accessibility-service modules only work once **Q25 Toolbox** is enabled
under Settings → Accessibility - each of their screens shows a banner with a
direct link there if it isn't. Reinstalling the app resets this, so it needs
re-enabling after every fresh install.

## Signing with your existing keystore

`app/build.gradle.kts` has a commented-out `signingConfigs { create("release") {...} }`
block referencing `kgr_signing.keystore` / alias `kgr`. Uncomment it, point
`storeFile` at your keystore path, and wire `signingConfig = signingConfigs.getByName("release")`
into the `release` build type. Consider passing the password via
`gradle.properties` (gitignored) or an env var rather than committing it in
the build script.

## How each module works

### Key Remapper (`KeyRemapController`)
- Remaps one of the currently supported source keys to Right Ctrl at the
  hardware keylayout level: the **Currency key** (the firmware-miswired key
  next to "M") or **Right Shift**. It is recommended to map currency symbol
  to double tap using KeyMapper app.
- The implementation currently targets the Q25's existing keyboard layout file
  (`Q25_keyboard.kl`) by rewriting the relevant scancode entry and reloading
  the i2c keyboard driver so the change applies live and persists across
  reboots. The supported source-key choices are the Currency key (`41`,
  `GRAVE`) and Right Shift (`54`, `SHIFT_RIGHT`).
- The keyboard resolves its **layout** to `Generic.kl` and its **char map** to
  `Generic.kcm`, both under read-only `/system` (the `.kl` fallback was
  confirmed via `dumpsys input`). A generated boot script
  (`service.d/key_remap.sh`) `umount`s any previous bind, rewrites both files
  (scancode → target keycode in the `.kl`; the GRAVE `base:` char in the `.kcm`
  for the currency symbol - toybox-`sed` POSIX form, literal UTF-8 glyph),
  bind-mounts the copies over the originals, and unbinds/rebinds the i2c
  keyboard driver (`/sys/bus/i2c/drivers/Q25_keyboard/{un,}bind`, `6-001f`) so
  EventHub reloads. The same script runs live on save, so no reboot is needed.
- Settings live in a `q25keyremap` prefs file. Clearing all remaps (everything
  back to default) removes the script and undoes the mounts.

### Auto-Focus Input (`AutoFocusController`)
- Uses the accessibility service to focus the first editable field in selected
  apps once a key is pressed, which makes the Google Phone dialer and other
  text-entry screens ready for immediate keyboard input.
- The per-app target list is stored in the same `q25tweaks` prefs set used by
  the other accessibility-service modules, so the behavior can be enabled or
  disabled independently for each app.

### In-Call Shortcuts (`InCallShortcutsController`)
- Adds a few lightweight phone-call shortcuts for the Google Phone app, such as
  muting/unmuting with M, toggling the speaker with $, and bringing up the
  dialer/keypad flow for direct numeric entry.
- Like the other accessibility-service features, it runs through the shared
  service layer so it can react to the foreground app and ongoing call UI.

### Double-Tap to Wake (`Dt2wController`)
- The Q25 touch panel has **no** hardware/driver gesture-wake, and the
  `double_tap_to_wake` secure setting isn't wired to anything on this ROM. So
  DT2W is done in software by a root watchdog daemon
  (`service.d/dt2w.sh`, adapted from
  [nozerorma/q25-double-tap-wake](https://github.com/nozerorma/q25-double-tap-wake)):
  it watches the touchscreen via `getevent` and, on a quick double-tap while
  the screen is off, injects `KEYCODE_WAKEUP`.
- Reworked from the original for stability (that version crashed the system
  after a few hours): screen state comes from the cheap `lcd-backlight` sysfs
  node instead of repeated `dumpsys power`, and it wakes with the idempotent
  `KEYCODE_WAKEUP` rather than a `KEY_POWER` `sendevent` (which toggles and
  could turn the screen back off).
- **Enable** installs the daemon to `service.d` and launches it live; a pid
  lock keeps a single instance.

### Extra Dimming (`ExtraDimController`)
- Toggles Android's built-in Accessibility "Extra Dim" feature
  (`reduce_bright_colors_activated` / `reduce_bright_colors_level` secure
  settings) directly from the toolbox, with a 0-100% intensity slider.
- The manual toggle/level need no persistence - they're standard system
  settings that survive reboot on their own.
- **Auto Night Dim schedule** (optional): a `service.d/extra_dim_schedule.sh`
  watchdog turns Extra Dim on at a chosen start hour and off at a chosen end
  hour every day (window may wrap past midnight). It only writes the setting
  on an on/off transition, so a manual override in between the two edges isn't
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
  keyboard-block module) runs `wm size <W>x<H>` when a chosen app comes to the
  foreground and `wm size reset` when leaving.
- Targets are full width×height (not just squares), so an app can be given a
  taller portrait aspect. Presets follow duc1607's
  [q25-res-changer](../q25-res-changer) (720×720 native, 720×772, 720×960,
  720×1280, 720×1440; the SystemUI-breaking 780×780 is omitted), plus a
  **Custom…** entry to type any W×H (240–2000).
- The whole screen (incl. system UI) briefly relayouts on enter/exit - inherent
  to a global resolution change. Per-app targets are stored as a `pkg=WxH`
  StringSet in the `q25tweaks` prefs; the service always resets to native on
  teardown so it can't leave the screen stuck at a scaled resolution.

The current Q25 build focuses on the modules above; older Key2-only helpers
such as ZRAM and the Key2 App Spoof module are not part of this repo snapshot.

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
  entries. A PID lock file ensures only one daemon instance runs at a time.

### Global Telemetry Block (`TelemetryController`)
- Scans `/data/data/*/com.google.firebase.crashlytics.xml` across all
  installed apps and sets `firebase_crashlytics_collection_enabled` to `false`.
- Apps rewrite that XML at runtime (Crashlytics re-enables collection on app
  start), so a one-shot pass gets undone. `service.d/block_telemetry.sh` is
  therefore a **watchdog daemon**: an initial pass ~15s after boot, then a
  re-scan every 30 minutes. It runs the scan inside the init (pid 1) mount
  namespace via `nsenter` so it sees the real `/data/data` both at boot and
  when launched live from the app. A pid lock keeps a single instance.

### Lockscreen PIN on Keyboard (`Q25AccessibilityService`)
No root needed. While the keyguard is locked, maps physical key presses to
taps on SystemUI's PIN pad via `AccessibilityNodeInfo`, so the PIN can be
entered on the hardware keyboard instead of the touchscreen. Digits map
phone-dialpad style onto QWERTY: `W E R` = `1 2 3`, `S D F` = `4 5 6`, `Z X C`
= `7 8 9`, `Q` = `0` (number row also works directly). Enter/D-pad-center
confirms, Delete/Backspace deletes. Button lookup tries known SystemUI view
IDs first (`key0`-`key9`, `delete_button`, `key_enter`, etc.) and falls back
to a recursive node search by visible text or content description if those
IDs don't match on this build.

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
Ported from [nozerorma/q25-input-helper](https://github.com/nozerorma/q25-input-helper).
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
  ID / content-description / label heuristics), while Alt+Enter and Shift+Enter
  fall through as a normal newline.
- **Calculator Keys**: in the AOSP/Google Calculator, maps digit and operator
  keys to the calculator's buttons (by view ID with a label fallback), and
  inserts `!`, `(`, `)` straight into the formula field via `ACTION_SET_TEXT`.

## Extending

- For stateless root-command modules, add a `core`-style controller in
  `modules/`, following the pattern of `KeyRemapController` /
  `WirelessAdbController` / `Dt2wController` (persist
  via `AssetInstaller`, live-apply via `RootShell.run`).
- For features that need to observe ongoing state (window/IME visibility,
  key events) rather than just fire a command, that observation has to
  happen inside `Q25AccessibilityService` - root has no API for "tell me
  when X happens," only for executing commands. Add the logic there, store
  settings as SharedPreferences booleans/ints in the `q25tweaks` prefs file,
  and write the corresponding screen to read/write those same keys directly
  rather than going through `AssetInstaller`.
- Either way, add a corresponding screen in `ui/` (following e.g.
  `CtrlKeyScreen.kt` for the simple case or `ImeBlockScreen.kt` for the
  prefs-based case, all built on the shared `ScreenScaffold`), and wire it
  into `DetailHost` plus the section lists in `ui/HomeScreen.kt` and
  `ui/Screen.kt`.
- Drop any new boot scripts in `app/src/main/assets/`.
