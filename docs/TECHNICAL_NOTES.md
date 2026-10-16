# Q25 Toolbox — Technical Notes & Architecture

This document contains deep low-level details, kernel-level quirks, device-specific observations, and internal architecture documentation for developers and advanced users of the Zinwa Q25.

---

## 1. LineageOS 23 / Kernel Notes

The following facts were verified on a Zinwa Q25 running LineageOS 23 (Android 16, kernel 5.10, KernelSU Next / APatch):

- **Keyboard driver safety (`Q25_keyboard`):**
  The driver `bbqX0kbd.ko` registers a display notifier but never unregisters it. Consequently, unbinding the driver while the panel turns on triggers a kernel `NULL` dereference crash. On LineageOS, the keyboard is reloaded via a uevent remove/add on its input node instead (`RomProfile.keyboardRebindUnsafe()`). BenOS still uses standard unbind/bind.
- **Vendor Keylayout:**
  The physical keylayout file is `/vendor/usr/keylayout/Q25_keyboard.kl`. Any bind-mounted replacement layout must have SELinux context `u:object_r:vendor_keylayout_file:s0`. If labelled as generic `vendor_file`, the system is denied access and silently falls back to `Generic.kl`.
- **Physical Recents Key (Scancode 580):**
  AOSP/LineageOS triggers the system Overview on scancode 580 even if an accessibility service consumes the event. When standalone Recents overlay modes are active, scancode 580 is remapped to `PROG_RED` (183), which the system ignores, allowing `Q25AccessibilityService` to consume the event and render the custom overlay.
- **Key Mapper Integration:**
  Key Mapper's built-in "Recents" action executes `GLOBAL_ACTION_RECENTS`, which always invokes the system stock Overview. To trigger Q25 Toolbox overlays or features, Key Mapper sends a broadcast intent:
  - Action: `com.kgr.q25toolbox.action.RUN`
  - Component: `com.kgr.q25toolbox/.service.RunActionReceiver`
  - Extra (string): `action` (`RECENTS`, `CIRCLE_TO_SEARCH`, `QR_ENLARGER`, `ASPECT_RATIO`, etc.)
- **System Navigation Gestures:**
  Settings does not expose "System navigation" because `WindowManager.hasNavigationBar(0)` returns `false` (the ROM treats the device as having hardware navigation keys). Writing LineageOS's `force_show_navbar=1` does not enable SystemUI edge back navigation. Therefore, **Edge Gestures** draws standalone `TYPE_ACCESSIBILITY_OVERLAY` touch strips.
- **AdBlock Mount Namespaces:**
  KernelSU Next without a metamodule does not mount module `system/` trees into PID 1. The hosts file is mounted into the system via `service.d/hosts_ctl.sh` in the global namespace (`nsenter --mount=/proc/1/ns/mnt`).
- **BlackBerry Keyboard Suggestions Strip:**
  The BlackBerry Keyboard app (`com.blackberry.keyboard`) targets SDK 27. On a 1:1 square screen, `ConfigurationContainer.applySizeOverrideIfNeeded` previously treated the display minus status bar as landscape orientation (`h597dp port`), forcing the full virtual keyboard to replace the candidate strip. Enabling `OVERRIDE_ENABLE_INSETS_DECOUPLED_CONFIGURATION` via `am compat enable` prevents this and keeps the compact suggestion strip intact.

---

## 2. Architecture & Subsystems

### Accessibility Service (`Q25AccessibilityService`)
- Runs persistently as the central event observer for foreground packages, window transitions, key events, and custom overlay rendering.
- Manages `TYPE_ACCESSIBILITY_OVERLAY` windows for Slim Recents, Masonry Recents, Grid Recents, Edge Gesture touch strips, Gesture arrow animations, and Ticker notifications.
- Reacts dynamically to configuration changes stored in SharedPreferences (`q25tweaks`).

### Root Shell & Daemons
- Stateless root commands execute via `RootShell.run(...)` (built on `libsu`).
- Mount-dependent operations inspect processes through `/proc/1/ns/mnt`.
- Background daemons run detached (`setsid`) and use PID/`cmdline` locks to prevent duplicate instances or stale loopers.
- `DaemonMaintenance.sweep()` executes on app startup to reconcile and update active scripts in `/data/adb/service.d/`.

### Circle to Search (CTS) Backend
- Deploys a standalone native Zygisk module to `/data/adb/modules/q25_cts` containing:
  - `zygisk/arm64-v8a.so`: Native library spoofing Pixel 8 Pro device fingerprints and `PIXEL_2023_EXPERIENCE` features specifically for `com.google.android.googlequicksearchbox`.
  - `system/etc/permissions/pixel_2023_experience.xml`: Declares system capability feature flags.
  - `module.prop`: Magisk/KernelSU module metadata.
- Launcher flags configured via `cmd device_config put launcher ...`.
- Triggered directly via Android Framework Binder IPC:
  `service call contextual_search 3 i32 1` (Transaction 3: `startContextualSearch`, Entrypoint 1: `ENTRYPOINT_LONG_PRESS_NAV_HANDLE`), which commands `ContextualSearchManagerService` to capture screen tokenization and open Google LensientActivity.

### Recents Overlays & LSPosed Hooks
- **Standalone Overlays:** Queries `dumpsys activity recents` for recent tasks and pulls app thumbnail snapshots from `/data/system_ce/<user>/snapshots/`. Takes an immediate `screencap` for the active foreground window.
- **LSPosed In-Memory Hooks (`RecentsHookInit`, `GestureHookInit`):**
  Scoped to `com.android.launcher3`:
  - Intercepts `DisplayController.Info.isTablet()` and `showAsGrid()` to instruct the stock launcher to lay out tasks in a multi-column grid.
  - Skips hotseat width recalculation to avoid zero-division crashes.
  - Suppresses native bottom gesture window during active edge strip navigation.

---

## 3. IPC State & Global Settings Keys

| Key | Type | Description |
| --- | --- | --- |
| `bb_recents_layout_mode` | Int | LSPosed layout mode: `0` (stock), `1` (grid), `2` (masonry) |
| `q25_recents_overlay_mode` | Int | Standalone overlay mode: `0` (none), `1` (slim list), `2` (masonry), `3` (grid) |
| `q25_recents_scrim_alpha` | Float | Background scrim opacity for Recents |
| `q25_recents_hook_ok` | Int | Handshake status for LSPosed launcher hook (`1` = active) |
| `q25_native_bottom_gesture_off` | Int | `1` instructs launcher hook to ignore native swipe-up |

---

## 4. Building & Testing

```bash
# Compile debug and release APKs (outputs in app/build/outputs/apk/)
./gradlew assembleDebug assembleRelease

# Run JVM unit tests
./gradlew testDebugUnitTest
```

Release APKs are signed with the debug key by default to ensure in-place update compatibility across installs. To sign with a private release keystore, configure the `signingConfigs` block in `app/build.gradle.kts`.
