# Q25 Toolbox

A root app for the Zinwa Q25 (KernelSU, Qualcomm-based, physical QWERTY
keyboard) that bundles a set of tweaks into one UI, organised into four
bottom-bar sections:

**Info** - device status landing page: build (model, Android, LineageOS,
security patch, kernel), battery (level, health, temperature, voltage,
technology, capacity-health % and charge cycles from sysfs), and root +
accessibility-service status.

**Keyboard**
- **Key Remapper** - remap the 5 modifier keys (Left Alt, Left/Right Shift,
  Right SYM, Currency) and personalise what the currency key types
- **Lockscreen PIN on Keyboard** - type your PIN on the physical keyboard
- **Per-App Keyboard Block** - in chosen apps, route physical keys straight
  to the app (for games) by switching to a passthrough IME
- **Chat Enter-to-Send** - in messaging apps, Enter sends and Alt/Shift+Enter
  inserts a newline
- **Calculator Keys** - route physical number and operator keys to the
  AOSP/Google Calculator

**System**
- **Double-Tap to Wake** (DT2W) - software watchdog: double-tap the off screen
  to wake it
- **Extra Dimming** - reduce brightness below the system's standard minimum,
  with an optional nightly auto-on/off schedule
- **Adaptive Brightness (One-Shot)** - measure ambient light once per screen
  wake and then hold it steady, instead of continuously adapting
- **Per-App Display Scaling** - switch to a smaller/portrait resolution while a
  chosen app is open (the only display-scaling knob this ROM honours)
- **ZRAM** - compressed-swap size, compression algorithm and swappiness
- **Key2 App Spoof** - install a module that spoofs the device as a BlackBerry
  KEY2 so Key2-only apps install and run

**Network**
- **Global Telemetry Block** - disable Firebase Crashlytics collection across
  all installed apps at boot
- **Persistent wireless ADB** on a user-chosen static port
- **Wearable Power Saver** - put any GMS-paired wearable into Dormant mode so
  out-of-range devices don't trigger constant Bluetooth reconnect alarms
- **Bluetooth Auto-Disable** - watchdog daemon that turns Bluetooth off after a
  configurable idle timeout (no device connected), preventing
  `hal_bluetooth_lock` from blocking deep sleep overnight

Ported from [Key2 Toolbox](../Key2Toolbox), the same app built for the
BlackBerry Key2. Most of the UI/architecture carried over unchanged; the
hardware underneath several modules didn't, and a few Key2-only modules were
dropped entirely - see "Ported from Key2Toolbox" below for what changed and
why.

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
- Remaps any of the five main modifier keys to a curated set of keycodes
  (Default / Ctrl / Shift / Alt / Meta / Tab), and lets the **currency key**
  type a chosen symbol (` ` ` / $ / € / £ / ¥ / ₹ / ₩ / ¢).
- The keys, by their `Generic.kl` scancode and default keycode (all confirmed
  from the [pastiera](../pastiera) Q25 map + on-device): Left Alt `56`
  (ALT_LEFT), Left Shift `42` (SHIFT_LEFT), Right Shift `54` (SHIFT_RIGHT),
  Right SYM `100` (ALT_RIGHT), Currency `41` (GRAVE).
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

### Adaptive Brightness (One-Shot) (`OneShotBrightnessController`)
- LineageOS has a "one-shot auto-brightness" option that measures ambient
  light once when the screen turns on, then stops the sensor from
  continuously driving brightness until the next screen-off. This ROM
  (BenOS/MTK) has no such framework flag, so it's reproduced in userspace by
  a root watchdog daemon (`service.d/one_shot_brightness.sh`).
- On each screen-on (detected from the `lcd-backlight` node, 0 = off), the
  daemon flips `screen_brightness_mode` to auto, waits ~2s for the framework
  to settle, reads the committed brightness float from `dumpsys display`, then
  flips back to manual with that captured value so brightness holds steady
  until the next wake.
- `screen_brightness` doesn't track the auto value on this ROM and the MTK
  backlight sysfs node is non-linear, so `dumpsys display`'s `Display
  Brightness=` float (converted to the 0-255 setting scale) is the readback
  source. The frozen value is clamped to a small floor so a dark reading can't
  blank the screen.
- Disabling the module restores normal continuous adaptive brightness (puts
  `screen_brightness_mode` back to auto) and removes the boot script.

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

### ZRAM (`ZramController`)
- Controls compressed-swap **size** (Off / 2 / 3 / 4 / 6 / 8 GB - scaled for the
  Q25's 12 GB RAM), **compression algorithm** (read live from
  `/sys/block/zram0/comp_algorithm`, so only kernel-supported ones are offered:
  `lzo`, `lzo-rle`, `lz4`, `zstd`), and **swappiness**.
- **Persist**: installs `assets/zram_template.sh` (with size/algo/swappiness
  substituted) to `/data/adb/service.d/zram_size.sh`; "Off" removes it. Unlike
  the Key2 version there's no Qualcomm post-boot wait - the Q25 is MediaTek.
- **Apply now** (behind a confirmation): `swapoff` → reset → `comp_algorithm` →
  `disksize` → `mkswap` → `swapon` → swappiness. This briefly turns swap off and
  can close background apps, so the default action is save-for-next-boot.

### Key2 App Spoof (`ProdFixController`)
- Installs wumbomumbo's **BBProdFix Lite** as a KernelSU/Magisk module (bundled
  under `assets/bbprodfix/`) into `/data/adb/modules/bb-prodfix/`. Two pieces,
  both of which only work applied at boot from a systemless overlay - which is
  why this is a module rather than a runtime tweak:
  - `system.prop` spoofs `ro.product.*` (model=KEY2, brand=blackberry,
    device=bbf100) across all partition namespaces, for apps that check
    `Build.MODEL` / brand at runtime;
  - `com.blackberry.only.jar` + its permissions XML provide the
    `com.blackberry.only` shared library that BlackBerry-only apps require via
    `<uses-library>` - without it they won't install.
- Enabling writes the module and prompts a **reboot** (required for the module
  manager to mount it and for the prop overrides to apply); disabling flags it
  for removal. The screen shows whether the spoof is merely installed or live
  (props actually reflect KEY2). Note it's a **global** spoof - every app sees
  `Build.MODEL=KEY2`, which can affect Play Integrity / banking apps.

### Persistent wireless ADB (`WirelessAdbController`)
- User enters a port; **persist** installs `assets/adb_wireless_template.sh`
  (with `__PORT__` substituted) to `/data/adb/service.d/adb_wireless.sh`,
  which waits for `sys.boot_completed` then sets `adb_wifi_enabled` and pins
  `persist.adb.tcp.port` / `service.adb.tcp.port`.
- **Live apply** sets the same properties immediately.
- The screen also shows the device's current WLAN IP and the live port, so
  you can confirm the `adb connect <ip>:<port>` target at a glance.

### Wearable Power Saver (`WatchController`)
- Reads all wearables paired through GMS from `connectionconfig.db` and
  lists them by name and MAC. Toggling a device **Dormant** sets
  `connectionEnabled = 0` in that SQLite table and force-stops GMS so it
  picks up the change immediately.
- A `service.d/wearable_dormant.sh` boot script re-applies `connectionEnabled
  = 0` for all selected MACs at boot (retrying until the data partition is
  decrypted and the DB is accessible), since GMS can reset the field during a
  cold boot before our script runs.

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

## Ported from Key2Toolbox: what changed and why

The Key2 and the Q25 share a lot of surface area (both root-friendly Android
devices with a physical keyboard), but the underlying hardware/driver stack
is different enough that several modules needed real rewrites rather than a
find-and-replace:

- **Ctrl key remap**: Key2 could remount `/vendor` read-write and `sed` the
  keylayout file in place (`stmpe.kl`, key 110, `setenforce 0` around the
  edit). On the Q25 the keyboard resolves its layout to `Generic.kl` under the
  read-only `/system`, so the working approach is a `mount -o bind` of a
  modified copy over it (key 54) plus an i2c driver unbind/rebind to reload
  live.
- **Double-Tap to Wake**: Key2 used a raw `wake_gesture` sysfs write on its
  Synaptics touch driver. The Q25 has no hardware gesture-wake at all and
  ignores the `double_tap_to_wake` setting, so DT2W is reimplemented in
  software (a `getevent` watchdog that injects `KEYCODE_WAKEUP`).
- **Extra Dimming / One-Shot Brightness**: new on Q25.
- **Per-App Display Scaling**: reworked - the Key2 approach (compat
  `DOWNSCALE_*`) is a no-op on this ROM, so it's done by foreground-switching
  the global `wm size` (the only knob that works here).

**Dropped**:
- **Keyboard Nav Lock** (Key2) - the capacitive nav buttons it locked
  (`0dbutton` sysfs node, Synaptics touchscreen) don't exist on the Q25.
- **Adaptive Keyboard Backlight** - the Q25's `bbqX0kbd` (i2c_puppet) keyboard
  exposes no host-controllable backlight (no `/sys/class/backlight` node, no
  `/dev/i2c`); brightness is set only by the firmware combo **Sym + Right-Shift
  + 1..9**.
- **Increase Volume Steps** - raising `ro.config.*_vol_steps` broke volume
  control above the default step count on this device.
- **5GHz Hotspot Workaround**, **CPU Performance Tuning** (both Key2
  hardware/ROM specific). *(ZRAM was later re-added - see above.)*

## Extending

- For stateless root-command modules, add a `core`-style controller in
  `modules/`, following the pattern of `CtrlKeyController` /
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
