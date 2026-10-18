package com.kgr.q25toolbox.modules

import android.content.Context
import android.util.Log
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.ShellResult

/**
 * Block Google Play services' background sensor hammering (LineageOS).
 *
 * GMS's location library (com.google.android.location.collectionlib) keeps the
 * accelerometer registered at 50 Hz - plus the ambient-light sensor - in
 * short bursts that cycle every few seconds, even when no app is asking for a
 * location right then. On a device with a sensor like the icm4n607_acc that
 * shows up in battery stats as constant drain "by Google Play services".
 *
 * Revoking GMS's ACTIVITY_RECOGNITION appop (measured, on-device: the sensor
 * bursts stop while network/GPS location requests still get served) kills
 * the scanning without breaking location. State is read back from the OS
 * (`cmd appops get --uid`), never from a local pref, so the UI always shows
 * what the device actually enforces even if it was changed via adb.
 *
 * ROM quirk this exists for: on LineageOS the package-level form
 * `cmd appops set com.google.android.gms ...` is accepted (rc=0) but never
 * applied - the mode silently stays "allow". Only the UID form works, so the
 * UID is resolved at runtime on every call: it is NOT stable across devices
 * (10091 on this Q25, 10098 on a KEY2, anything elsewhere).
 *
 * The appop would normally be expected to persist across reboots (appops live
 * in /data/system/appops.xml) - observed on-device, it does NOT: after a reboot
 * `cmd appops get --uid` reports "No UID for ACTIVITY_RECOGNITION" and the
 * bursts resume. So the one-shot boot script
 * (/data/adb/service.d/gms_guard_boot.sh) is required, not just
 * belt-and-braces - it also covers "Reset app preferences", a GMS reinstall,
 * or ROM appops sanitization. See the template for why it resolves the UID
 * itself.
 */
object GmsSensorGuardController {

    private const val TAG = "Q25Toolbox"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val SCRIPT_NAME = "gms_guard_boot.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "gms_guard_boot_template.sh"

    /**
     * GMS's runtime UID, resolved live via `pm list packages -U`. The exact-name
     * regex is required: -U prefix-matches, so it also returns sibling packages
     * (com.google.android.gms.supervision et al.) with their own UIDs. Null when
     * GMS is missing or the parse fails - callers must treat that as "can't do
     * anything" rather than falling back to a baked-in UID.
     */
    fun gmsUid(): String? {
        val out = RootShell.run("pm list packages -U $GMS_PACKAGE").outString
        val uid = Regex("""package:$GMS_PACKAGE uid:(\d+)""").find(out)?.groupValues?.get(1)
        Log.i(TAG, "gmsUid: raw=[${out.trim().replace("\n", " | ")}] parsed=$uid")
        return uid
    }

    /** Whether the ACTIVITY_RECOGNITION UID mode is currently "ignore". */
    fun isBlocked(uid: String?): Boolean {
        if (uid == null) return false
        return RootShell.run("cmd appops get --uid $uid ACTIVITY_RECOGNITION")
            .outString.contains("ignore")
    }

    /** Whether the boot re-assert script is installed. */
    fun isBootGuardPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /**
     * Whether the installed boot script is byte-identical to today's template
     * - a leftover from an older app build keeps "working" while re-asserting
     * with whatever logic that version shipped. See AssetInstaller.matchesAsset.
     */
    fun isBootGuardCurrent(context: Context): Boolean =
        AssetInstaller.matchesAsset(context, TEMPLATE_ASSET, TARGET)

    /**
     * Applies or removes the block.
     *
     * force-stopping GMS both releases already-running activity-recognition
     * listeners (the appop mode change alone leaves the "(running)" state
     * attached until the process dies) and makes a removal take effect
     * without waiting for the next GMS restart. GMS comes back on its own
     * within seconds; nothing on this ROM breaks from the restart itself.
     */
    fun setBlocked(context: Context, blocked: Boolean): ShellResult {
        val uid = gmsUid() ?: run {
            Log.w(TAG, "setBlocked($blocked): GMS uid not resolvable")
            return ShellResult(false, emptyList())
        }
        val mode = if (blocked) "ignore" else "allow"
        val result = RootShell.run("cmd appops set --uid $uid ACTIVITY_RECOGNITION $mode")
        Log.i(TAG, "setBlocked($blocked): appops rc=${result.success} out=[${result.outString.trim()}]")
        RootShell.run("am force-stop $GMS_PACKAGE")
        return if (blocked) {
            // One-shot script: no daemon to launch, service.d runs it at boot.
            if (result.success) {
                val install = AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET)
                Log.i(TAG, "boot guard install rc=${install.success} out=[${install.outString.trim()}]")
                install
            } else result
        } else {
            AssetInstaller.removeFile(TARGET)
            result
        }
    }

    /** Re-installs the boot script if missing or stale (DaemonMaintenance / screen self-heal). */
    fun ensureBootGuard(context: Context) {
        AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET)
    }

    /*
     * Secondary toggles - the two Settings > Location > Location services
     * scan switches, the only location-related knobs exposed via `settings`.
     * Whether each is worth disabling is device-dependent (see the screen's
     * description), so they are presented independently of the main block.
     */

    private fun globalGet(key: String): Boolean =
        RootShell.run("settings get global $key").outString.trim() == "1"

    private fun globalPut(key: String, value: Boolean): ShellResult =
        RootShell.run("settings put global $key ${if (value) "1" else "0"}")

    /** Settings > Location > Wi-Fi scanning ("Cerca de xarxes Wi-Fi"). */
    fun isWifiScanAlways(): Boolean = globalGet("wifi_scan_always_enabled")
    fun setWifiScanAlways(enabled: Boolean) = globalPut("wifi_scan_always_enabled", enabled)

    /** Settings > Location > Bluetooth scanning ("Cerca de dispositius Bluetooth"). */
    fun isBleScanAlways(): Boolean = globalGet("ble_scan_always_enabled")
    fun setBleScanAlways(enabled: Boolean) = globalPut("ble_scan_always_enabled", enabled)
}
