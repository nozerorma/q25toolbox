# Q25 Toolbox

The all-in-one companion utility and tweak suite for the **Zinwa Q25** (MediaTek physical QWERTY smartphone). Built with Material You, it brings physical keyboard productivity, visual navigation, aspect ratio scaling, and system enhancements to **BenOS, ZinwaOS, and LineageOS 23**.

| Grid Recents | Masonry Recents |
| :---: | :---: |
| ![Grid Recents](docs/recents-grid.png) | ![Masonry Recents](docs/recents-masonry.png) |

---

## Quick Navigation

- [Requirements](#requirements)
- [Installation & Updates](#installation--updates)
- [Features Overview](#features-overview)
  - [⌨️ Keyboard & Shortcuts](#️-keyboard--shortcuts)
  - [🔍 Circle to Search](#-circle-to-search)
  - [🎫 QR / Ticket Enlarger](#-qr--ticket-enlarger)
  - [📱 Screen, Aspect Ratio & Recents](#-screen-aspect-ratio--recents)
  - [⚡ System Enhancements](#-system-enhancements)
  - [🛡️ Privacy & Network](#️-privacy--network)
- [How to Map Keys (Key Mapper & Shortcuts)](#how-to-map-keys)
- [Backup & Restore](#backup--restore)
- [Technical Documentation](#technical-documentation)
- [Credits](#credits)

---

## Requirements

| Requirement | Purpose | Notes |
| :--- | :--- | :--- |
| **Root** | System tweaks, hotfixes, modules | Tested on KernelSU, KernelSU Next, APatch, and Magisk. |
| **Accessibility Service** | Key remapping, overlays, gestures | Enable in **Settings > Accessibility > Q25 Toolbox**. Prompted on first launch. |
| **Zygisk** *(Optional)* | Circle to Search, Detach | Handled autonomously by the app when toggling features. |

*Supports Android 9 (API 28) through Android 16 (API 34+).*

---

## Installation & Updates

1. **Direct Download:** Download the latest release `.apk` from [GitHub Releases](https://github.com/nozerorma/q25toolbox/releases).
2. **In-App Updater:** Check and update anytime directly in **Settings > Updates** (installs automatically with root).
3. **Obtainium:** Fully compatible with [Obtainium](https://github.com/ImranR98/Obtainium) using the GitHub repo URL.

*All releases share the same signing key, allowing seamless in-place updates without losing settings.*

---

## Features Overview

### ⌨️ Keyboard & Shortcuts
- **Centralized Keyboard Shortcuts:** Map physical keys directly to any system action (Recents, Aspect Ratio, Rotation, Flashlight, Notifications, Extra Dim, Circle to Search, QR Enlarger, and more).
- **Physical Keyboard PIN Unlock:** Unlock your lockscreen using your physical QWERTY keys like a numpad (`W E R` = 1 2 3, `S D F` = 4 5 6, `Z X C` = 7 8 9, `Q` = 0).
- **Lockscreen Enter Pad:** Pressing Enter or keypad Enter on the lockscreen automatically opens the PIN pad. Pocket-safe mode blocks accidental emergency dials.
- **Physical Keylayout Remapping:** Remap miswired or unused keys (Currency key, Right Shift, or Recents) to Right Ctrl.
- **Chat & Input Fixes:** Enter sends in messaging apps (Alt/Shift+Enter for newline); candidate strip shortcuts (Ctrl+W/E/R); dialpad digit shortcuts in phone calls.

---

### 🔍 Circle to Search
- **Zero-Setup Visual Search:** Enjoy Google's Circle to Search without needing external LSPosed modules or companion APKs.
- **Autonomous Zygisk Backend:** Toggling the feature deploys a lightweight native module to `/data/adb/modules/q25_cts` spoofing Pixel 8 Pro system features for the Google App, and configures launcher flags automatically.
- **Instant Trigger:** Trigger Circle to Search via physical key shortcut, Key Mapper, or test button via direct system IPC (`service call contextual_search 3 i32 1`).

---

### 🎫 QR / Ticket Enlarger
- **Instant Full-Screen Display:** Quickly enlarge QR codes, boarding passes, and barcodes (e.g., Renfe, train, transit tickets) visible on screen with a single key press or gesture.
- **Scanner-Ready Brightness:** Automatically cranks screen brightness to maximum and bypasses Extra Dim for quick scanning at turnstiles.
- **Automatic Restoration:** Restores your previous brightness level and Extra Dim settings the moment you dismiss the overlay.

---

### 📱 Screen, Aspect Ratio & Recents
- **Per-App Display Scaling:** Override display resolution per app (720x720, 720x960, 720x1280, etc.) to adapt square screens to standard phone app layouts.
- **Resolution Hotkey:** Cycle between your favorite screen resolutions live with a hardware shortcut.
- **Forced Screen Rotation:** Force any app or system orientation to portrait, landscape, or auto-rotation.
- **Custom Recents Layouts:** Choose between *Slim List*, *Masonry*, and *Grid* multi-column multitasking layouts (available as standalone overlays or native launcher hooks).
- **Edge Gestures:** Smooth, customizable swipe strips on the screen edges for Back, Home, Recents, and shortcuts — perfect for ROMs without native gesture navigation.

---

### ⚡ System Enhancements
- **Double-Tap to Wake (DT2W):** Wake the device with a quick double tap on the screen when turned off (LineageOS).
- **Call Proximity Sleep:** Reliable sensor sleep during phone calls to prevent cheek touches.
- **Ticker Notifications:** Elegant scrolling notification text in the status bar instead of intrusive heads-up popups.
- **GMS Sensor Guard (LineageOS):** Stop Google Play services from hammering the accelerometer (50 Hz bursts) and ambient-light sensor in the background via a UID-level activity-recognition block; re-asserted at boot. Includes toggles for background Wi-Fi/Bluetooth scanning and guidance for the manual Location Accuracy settings.
- **Advanced System Hotfixes:** Fix 50MP camera capture, AlwaysStrong KeyMint, and Bluetooth audio playback on custom ROMs.

---

### 🛡️ Privacy & Network
- **Systemless AdBlock:** Built-in hosts-based ad blocker with custom whitelists and blacklists.
- **Per-App Telemetry Block:** Turn off Firebase, Crashlytics, and Analytics tracking for chosen apps.
- **Zygisk Detach:** Detach specific apps from the Google Play Store to stop unwanted updates.
- **Battery Optimization:** Auto-turn off Bluetooth and Location after an idle period; persist Wireless ADB across boots.

---

## How to Map Keys

You can configure keyboard shortcuts in two easy ways:

### 1. Directly in Q25 Toolbox
Open **Keyboard > Keyboard Shortcuts**, pick an action (e.g. *Circle to Search* or *QR Enlarger*), and assign a physical key.

### 2. Via Key Mapper (or third-party tools)
To map complex combinations, long presses, or double taps in [Key Mapper](https://github.com/keymapperorg/KeyMapper):
1. Create a new trigger in Key Mapper for your chosen key.
2. Add an action: **Send intent** -> **Broadcast**.
3. Set the fields:
   - **Action:** `com.kgr.q25toolbox.action.RUN`
   - **Package:** `com.kgr.q25toolbox`
   - **Component:** `com.kgr.q25toolbox/.service.RunActionReceiver`
   - **Extra string key:** `action`
   - **Extra string value:** One of:
     - `CIRCLE_TO_SEARCH` — Circle to Search
     - `QR_ENLARGER` — QR / Barcode Enlarger
     - `RECENTS` — Recents overlay
     - `ASPECT_RATIO` — Cycle aspect ratios
     - `ROTATION` — Toggle rotation
     - `FLASHLIGHT` — Toggle torch
     - `NOTIFICATIONS` — Expand notification shade
     - `EXTRA_DIM` — Toggle extra dim

---

## Backup & Restore

Easily backup your configuration in **Settings > Backup & Restore**. Exports your settings to a portable JSON file that you can restore anytime or migrate to a fresh ROM install.

---

## Technical Documentation

For developers, kernel documentation, and deep low-level details:
- **[Technical Notes & Architecture](docs/TECHNICAL_NOTES.md)** covers:
  - LineageOS 23 kernel driver safety (`bbqX0kbd.ko`)
  - Binder IPC transactions & mount namespace isolation
  - Zygisk spoofer mechanics & launcher hooks
  - Custom overlay window managers and event routing

---

## Credits & Thanks

- Based on and inspired by [Key2 Toolbox](https://github.com/kgr17/Key2Toolbox) by kgr17.
- Input helpers inspired by [smh786/q25-input-helper](https://github.com/smh786/q25-input-helper).
- Display scaling presets inspired by [duc1607/q25-res-changer](https://github.com/duc1607/q25-res-changer).
- Ad blocking based on [gloeyisk/systemless-hosts](https://github.com/gloeyisk/systemless-hosts).
- Double-Tap to Wake research from [nozerorma/q25-double-tap-wake](https://github.com/nozerorma/q25-double-tap-wake).

History and version notes are available in [CHANGELOG.md](CHANGELOG.md).
