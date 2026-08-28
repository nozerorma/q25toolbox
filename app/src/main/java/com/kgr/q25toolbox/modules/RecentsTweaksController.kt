package com.kgr.q25toolbox.modules

import android.content.Context
import android.os.Process
import com.kgr.q25toolbox.core.ApkAligner
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.OnDeviceApkSigner
import com.kgr.q25toolbox.core.RootShell
import java.io.File

/**
 * Controller for the surgical RecentsView/BaseActivityInterface grid patch: forces the
 * two-row Grid Recents overview at native DPI by bind-mounting a patched copy of
 * SearchLauncherQuickStep.apk over the real one, without touching DeviceProfile's
 * tablet status for the workspace/hotseat or changing screen density.
 */
object RecentsTweaksController {

    data class RecentsStatus(val isNativePatchActive: Boolean = false)

    data class RepairResult(val mounted: Boolean, val needsReboot: Boolean)

    private const val TARGET_APK = "/system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk"
    private const val PATCHED_APK_PATH = "/data/adb/q25toolbox/SearchLauncherQuickStep_patched.apk"
    private const val PATCHED_APK_ASSET = "SearchLauncherQuickStep_patched.apk"

    // KernelSU / Magisk module path (for root managers with metamodule / overlay support).
    // The module structure (/data/adb/modules/q25_recents) is always populated so that
    // metamodules (like Mountify or magic_mount_rs) will automatically mount it at boot.
    private const val MODULE_ID = "q25_recents"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val MODULE_TARGET_APK = "$MODULE_DIR/system/system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk"
    private const val MODULE_PROP = "$MODULE_DIR/module.prop"

    // Boot script in service.d that re-applies the mount at startup on all root environments.
    // NOTE: In your SU Manager (KernelSU / APatch / Magisk), ensure com.android.launcher3
    // is granted root and NOT placed on the Zygisk DenyList / unmount list. If DenyList
    // is enforced on Launcher3, Zygisk actively strips the bind-mount from its namespace.
    private const val BOOT_SCRIPT_NAME = "recents_grid_patch.sh"
    private const val BOOT_SCRIPT_TARGET = "/data/adb/service.d/$BOOT_SCRIPT_NAME"
    private const val BOOT_SCRIPT_ASSET = "recents_grid_patch_template.sh"

    // Every app process (including this one) gets its own private mount namespace on this ROM
    // so every mount/umount/status check here must run inside PID 1's (the real, global) namespace.
    private fun inGlobalNs(cmd: String): String =
        "nsenter --mount=/proc/1/ns/mnt -- sh -c '${cmd.replace("'", "'\\''")}'"

    private fun isBindMounted(): Boolean =
        RootShell.run(inGlobalNs("mount | grep -F '$TARGET_APK'")).out.isNotEmpty()

    // `am force-stop` is a no-op for persistent apps like com.android.systemui.
    // A hard kill -9 on the actual PID always works and lets the system respawn it fresh.
    private fun killProcess(pkg: String): Boolean {
        val pid = RootShell.run("pidof $pkg").outString.trim()
        if (pid.isEmpty()) return false
        return RootShell.run("kill -9 $pid").success
    }

    fun queryStatus(): RecentsStatus = RecentsStatus(isNativePatchActive = isBindMounted())

    /** Realigns+re-signs whatever is at [input] (see [ApkAligner]/[OnDeviceApkSigner]) and
     * returns the result, or null if it couldn't be repaired this way. */
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

    /** Installs [source] to PATCHED_APK_PATH, bind-mounts it over TARGET_APK in PID 1,
     * mirrors it to /data/adb/modules/q25_recents, and persists via service.d boot script. */
    private fun mountPatchedApk(context: Context, source: File): Boolean {
        // 1. Prepare legacy storage
        RootShell.run("mkdir -p /data/adb/q25toolbox && chmod 755 /data/adb/q25toolbox")
        RootShell.run("cp '${source.absolutePath}' '$PATCHED_APK_PATH'")
        RootShell.run("chown root:root '$PATCHED_APK_PATH' && chmod 644 '$PATCHED_APK_PATH' && chcon u:object_r:system_file:s0 '$PATCHED_APK_PATH'")

        // 2. Mirror into Magisk / KernelSU module structure
        val modulePrivAppDir = File(MODULE_TARGET_APK).parentFile?.absolutePath ?: "$MODULE_DIR/system/system_ext/priv-app/SearchLauncherQuickStep"
        RootShell.run("mkdir -p '$modulePrivAppDir'")
        RootShell.run("cp '${source.absolutePath}' '$MODULE_TARGET_APK'")
        RootShell.run("ln -sf system/system_ext '$MODULE_DIR/system_ext' 2>/dev/null")
        RootShell.run("touch '$MODULE_DIR/auto_mount'")
        RootShell.run(
            "cat << 'EOF' > '$MODULE_PROP'\n" +
                "id=$MODULE_ID\n" +
                "name=Q25 Toolbox Recents Grid Patch\n" +
                "version=1.0\n" +
                "versionCode=1\n" +
                "author=Q25Toolbox\n" +
                "description=Native Grid Recents patch for SearchLauncherQuickStep\n" +
                "EOF"
        )
        RootShell.run("chown -R root:root '$MODULE_DIR' && chmod -R 755 '$MODULE_DIR' && chmod 644 '$MODULE_TARGET_APK' && chcon -R u:object_r:system_file:s0 '$MODULE_DIR'")

        // 3. Apply live bind mount to PID 1 global namespace
        RootShell.run(inGlobalNs("umount -l '$TARGET_APK' 2>/dev/null ; mount -o bind '$PATCHED_APK_PATH' '$TARGET_APK'"))

        // 4. Install the service.d boot script that re-applies it on startup
        AssetInstaller.installFromAsset(context, BOOT_SCRIPT_ASSET, BOOT_SCRIPT_TARGET)

        // 5. Restart Launcher3 to pick up the mounted file
        killProcess("com.android.launcher3")
        return isBindMounted()
    }

    fun setNativeGridPatch(context: Context, enable: Boolean): Boolean {
        return if (enable) {
            val staging = File(context.cacheDir, "grid_patch_asset.apk")
            context.assets.open(PATCHED_APK_ASSET).use { input -> staging.outputStream().use { input.copyTo(it) } }
            val signed = alignAndSign(staging)
            staging.delete()
            if (signed == null) return false
            val mounted = mountPatchedApk(context, signed)
            signed.delete()
            mounted
        } else {
            // Remove module directory & boot script
            RootShell.run("rm -rf '$MODULE_DIR'")
            AssetInstaller.removeFile(BOOT_SCRIPT_TARGET)

            // Unmount bind mount in PID 1
            var attempts = 0
            while (isBindMounted() && attempts < 3) {
                RootShell.run(inGlobalNs("umount -l $TARGET_APK 2>/dev/null ; umount $TARGET_APK 2>/dev/null"))
                attempts++
            }
            killProcess("com.android.launcher3")
            !isBindMounted()
        }
    }

    fun isRecentsProviderInstalled(): Boolean =
        RootShell.run("pm path com.android.launcher3").success

    fun repairRecentsProvider(context: Context): RepairResult {
        if (!repairFromLiveApk(context)) {
            setNativeGridPatch(context, true)
        }
        return RepairResult(mounted = isBindMounted(), needsReboot = !isRecentsProviderInstalled())
    }

    private fun repairFromLiveApk(context: Context): Boolean {
        val live = copyLiveApkToAppStorage(context) ?: return false
        val signed = alignAndSign(live)
        live.delete()
        if (signed == null) return false
        val mounted = mountPatchedApk(context, signed)
        signed.delete()
        return mounted
    }

    /** Root-copies TARGET_APK into this app's own cache dir and hands ownership
     * back to the app's uid (+ restorecon) so plain File I/O can read it. */
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

    /** Whether the boot-time re-mount script is installed. */
    fun isPersisted(): Boolean = AssetInstaller.fileExists(BOOT_SCRIPT_TARGET)

    /** Persisted AND the mount it maintains is currently active. */
    fun isHealthy(context: Context): Boolean =
        isBindMounted() && AssetInstaller.matchesAsset(context, BOOT_SCRIPT_ASSET, BOOT_SCRIPT_TARGET)

    fun restartLauncher(): Boolean = killProcess("com.android.launcher3")

    fun restartSystemUi(): Boolean = killProcess("com.android.systemui")

    private const val SCRIM_ALPHA_KEY = "q25_recents_scrim_alpha"

    /** Recents background scrim opacity (0f = fully transparent, 1f = fully opaque). */
    fun getScrimAlpha(): Float =
        RootShell.run("settings get global $SCRIM_ALPHA_KEY").outString.trim().toFloatOrNull() ?: 1f

    fun setScrimAlpha(alpha: Float) {
        RootShell.run("settings put global $SCRIM_ALPHA_KEY ${alpha.coerceIn(0f, 1f)}")
    }
}
