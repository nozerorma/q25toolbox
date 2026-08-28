package com.kgr.q25toolbox.modules

import android.content.Context
import android.os.Process
import com.kgr.q25toolbox.core.ApkAligner
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.OnDeviceApkSigner
import com.kgr.q25toolbox.core.RootShell
import java.io.File

/**
 * Recents/Overview layout for BenOS.
 *
 * The grid / masonry layout itself now runs as an LSPosed module
 * ([com.kgr.q25toolbox.xposed.RecentsHookInit]); this controller only owns the
 * two `Settings.Global` keys the hook reads and the launcher restart that
 * applies a change. The old mechanism - bind-mounting a 28 MB pre-patched copy
 * of `SearchLauncherQuickStep.apk` - was locked to one exact BenOS build and
 * could take Recents down entirely on an OTA mismatch; it is gone.
 *
 * What remains APK-level is [repairRecentsProvider]: a recovery tool for the
 * "no Recents/Overview after a BenOS OTA" corruption, where the update ships
 * `SearchLauncherQuickStep.apk` with `resources.arsc` stored-but-not-4-byte-
 * aligned and PackageManager silently drops the launcher at its boot-time scan.
 * It re-aligns and re-signs *the device's own* installed launcher (no bundled
 * asset, so no version lock) and bind-mounts the fixed copy, persisted via a
 * service.d boot script.
 */
object RecentsTweaksController {

    data class RepairResult(val mounted: Boolean, val needsReboot: Boolean)

    private const val LAYOUT_MODE_KEY = "bb_recents_layout_mode"
    private const val SCRIM_ALPHA_KEY = "q25_recents_scrim_alpha"

    private const val LAUNCHER_PKG = "com.android.launcher3"

    private const val TARGET_APK = "/system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk"
    private const val FIXED_APK_PATH = "/data/adb/q25toolbox/SearchLauncherQuickStep_fixed.apk"

    private const val BOOT_SCRIPT_TARGET = "/data/adb/service.d/recents_grid_patch.sh"
    private const val BOOT_SCRIPT_ASSET = "recents_grid_patch_template.sh"

    // Pre-v3 artifacts: the grid patch shipped a 28 MB pre-patched launcher and
    // bind-mounted it, persisted via a KernelSU module + this same boot script.
    // v3 moves grid/masonry to an LSPosed hook, so these must be torn down or
    // they keep re-mounting the stale patched apk on every boot.
    private const val LEGACY_MODULE_DIR = "/data/adb/modules/q25_recents"
    private const val LEGACY_PATCHED_APK = "/data/adb/q25toolbox/SearchLauncherQuickStep_patched.apk"

    enum class LayoutMode(val value: Int) {
        STOCK(0),
        GRID(1),

        /** Grid layout plus staggered per-tile heights (see RecentsHookInit). */
        MASONRY(2);

        companion object {
            fun fromValue(v: Int?): LayoutMode = entries.firstOrNull { it.value == v } ?: STOCK
        }
    }

    /**
     * Overridden to return true by [com.kgr.q25toolbox.xposed.RecentsHookInit]
     * when it loads in our own process, so the UI can tell the user whether the
     * LSPosed module is actually enabled. Keep the body a plain `return false`.
     */
    @JvmStatic
    fun isXposedActive(): Boolean = false

    fun getLayoutMode(): LayoutMode = LayoutMode.fromValue(
        RootShell.run("settings get global $LAYOUT_MODE_KEY").outString.trim().toIntOrNull()
    )

    fun setLayoutMode(mode: LayoutMode) {
        RootShell.run("settings put global $LAYOUT_MODE_KEY ${mode.value}")
        restartLauncher()
    }

    /** Recents background scrim opacity (0f = fully transparent, 1f = fully opaque). */
    fun getScrimAlpha(): Float =
        RootShell.run("settings get global $SCRIM_ALPHA_KEY").outString.trim().toFloatOrNull() ?: 1f

    fun setScrimAlpha(alpha: Float) {
        RootShell.run("settings put global $SCRIM_ALPHA_KEY ${alpha.coerceIn(0f, 1f)}")
    }

    fun restartLauncher(): Boolean = killProcess(LAUNCHER_PKG)
    fun restartSystemUi(): Boolean = killProcess("com.android.systemui")

    /**
     * One-time removal of the pre-v3 bind-mounted grid patch: the KernelSU
     * module, the 28 MB patched apk, the boot script, and the live mount.
     * Idempotent and cheap - a no-op once nothing is left. Runs from
     * [com.kgr.q25toolbox.modules.DaemonMaintenance] on every launch so an
     * update from 2.x cleans up even if the user never opens the Recents screen.
     */
    fun cleanupLegacyGridPatch() {
        val hasLegacy = RootShell.run(
            "if [ -e '$LEGACY_MODULE_DIR' ] || [ -e '$LEGACY_PATCHED_APK' ]; then echo yes; fi"
        ).outString.trim() == "yes"
        if (!hasLegacy) return

        RootShell.run("rm -rf '$LEGACY_MODULE_DIR'")
        RootShell.run("rm -f '$LEGACY_PATCHED_APK'")
        AssetInstaller.removeFile(BOOT_SCRIPT_TARGET)
        RootShell.run(inGlobalNs("umount -l '$TARGET_APK' 2>/dev/null ; umount '$TARGET_APK' 2>/dev/null"))
        killProcess(LAUNCHER_PKG)
    }

    // `am force-stop` is a no-op for persistent apps like com.android.systemui.
    // A hard kill -9 on the actual PID always works and lets the system respawn it.
    private fun killProcess(pkg: String): Boolean {
        val pid = RootShell.run("pidof $pkg").outString.trim()
        if (pid.isEmpty()) return false
        return RootShell.run("kill -9 $pid").success
    }

    // --- OTA "no Recents provider" recovery -----------------------------

    // Every app process gets its own private mount namespace on this ROM, so
    // every mount/umount/status check must run inside PID 1's global namespace.
    private fun inGlobalNs(cmd: String): String =
        "nsenter --mount=/proc/1/ns/mnt -- sh -c '${cmd.replace("'", "'\\''")}'"

    fun isBindMounted(): Boolean =
        RootShell.run(inGlobalNs("mount | grep -F '$TARGET_APK'")).out.isNotEmpty()

    fun isRecentsProviderInstalled(): Boolean =
        RootShell.run("pm path $LAUNCHER_PKG").success

    /** Whether the boot-time re-mount script is installed. */
    fun isPersisted(): Boolean = AssetInstaller.fileExists(BOOT_SCRIPT_TARGET)

    /** Persisted AND the mount it maintains is currently active. */
    fun isHealthy(context: Context): Boolean =
        isBindMounted() && AssetInstaller.matchesAsset(context, BOOT_SCRIPT_ASSET, BOOT_SCRIPT_TARGET)

    /**
     * Re-aligns and re-signs the device's own installed launcher and bind-mounts
     * the fixed copy, persisted via a service.d boot script. Reports
     * [RepairResult.needsReboot] when the provider still is not registered (a
     * fresh case of the bug needs PackageManager's next boot-time scan).
     */
    fun repairRecentsProvider(context: Context): RepairResult {
        repairFromLiveApk(context)
        return RepairResult(
            mounted = isBindMounted(),
            needsReboot = !isRecentsProviderInstalled()
        )
    }

    private fun repairFromLiveApk(context: Context): Boolean {
        val live = copyLiveApkToAppStorage(context) ?: return false
        val signed = alignAndSign(live)
        live.delete()
        if (signed == null) return false
        val mounted = mountApk(context, signed)
        signed.delete()
        return mounted
    }

    /** Realigns + re-signs [input]; returns the result or null if unrepairable. */
    private fun alignAndSign(input: File): File? {
        val aligned = File(input.parentFile, "${input.nameWithoutExtension}_aligned.apk")
        val signed = File(input.parentFile, "${input.nameWithoutExtension}_signed.apk")
        return try {
            ApkAligner.align(input, aligned)
            if (OnDeviceApkSigner.sign(aligned, signed)) signed else null
        } catch (e: ApkAligner.UnsupportedZipLayoutException) {
            null
        } finally {
            aligned.delete()
        }
    }

    /** Installs [source] to FIXED_APK_PATH, bind-mounts it over TARGET_APK in
     * PID 1, and persists via the service.d boot script. */
    private fun mountApk(context: Context, source: File): Boolean {
        RootShell.run("mkdir -p /data/adb/q25toolbox && chmod 755 /data/adb/q25toolbox")
        RootShell.run("cp '${source.absolutePath}' '$FIXED_APK_PATH'")
        RootShell.run("chown root:root '$FIXED_APK_PATH' && chmod 644 '$FIXED_APK_PATH' && chcon u:object_r:system_file:s0 '$FIXED_APK_PATH'")
        RootShell.run(inGlobalNs("umount -l '$TARGET_APK' 2>/dev/null ; mount -o bind '$FIXED_APK_PATH' '$TARGET_APK'"))
        AssetInstaller.installFromAsset(context, BOOT_SCRIPT_ASSET, BOOT_SCRIPT_TARGET)
        killProcess(LAUNCHER_PKG)
        return isBindMounted()
    }

    fun removeRepair(): Boolean {
        AssetInstaller.removeFile(BOOT_SCRIPT_TARGET)
        var attempts = 0
        while (isBindMounted() && attempts < 3) {
            RootShell.run(inGlobalNs("umount -l $TARGET_APK 2>/dev/null ; umount $TARGET_APK 2>/dev/null"))
            attempts++
        }
        RootShell.run("rm -f '$FIXED_APK_PATH'")
        killProcess(LAUNCHER_PKG)
        return !isBindMounted()
    }

    /** Root-copies TARGET_APK into this app's cache and hands ownership back to
     * the app's uid (+ restorecon) so plain File I/O can read it. */
    private fun copyLiveApkToAppStorage(context: Context): File? {
        val staging = File(context.cacheDir, "launcher3_live.apk")
        staging.delete()
        val uid = Process.myUid()
        val result = RootShell.run(
            "cp '$TARGET_APK' '${staging.absolutePath}' && " +
                "chown $uid:$uid '${staging.absolutePath}' && " +
                "restorecon '${staging.absolutePath}'"
        )
        return if (result.success && staging.exists()) staging else null
    }
}
