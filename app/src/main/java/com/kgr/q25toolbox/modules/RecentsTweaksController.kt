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
 *
 * The patch itself forces `isTablet` true (and `ENABLE_GRID_ONLY_OVERVIEW`) at every
 * read site scoped to Recents/Overview code (RecentsView, BaseActivityInterface, TaskView,
 * and their supporting classes) - never in DeviceProfile itself - plus backfills the
 * grid-mode dimens (row spacing, side margins, grid icon size) that are 0 on the phone
 * resource bucket. See app assets/SearchLauncherQuickStep_patched.apk.
 */
object RecentsTweaksController {

    data class RecentsStatus(val isNativePatchActive: Boolean = false)

    data class RepairResult(val mounted: Boolean, val needsReboot: Boolean)

    private const val TARGET_APK = "/system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk"
    private const val PATCHED_APK_PATH = "/data/adb/q25toolbox/SearchLauncherQuickStep_patched.apk"
    private const val PATCHED_APK_ASSET = "SearchLauncherQuickStep_patched.apk"

    // Bind mounts are kernel state, not persisted - without this, the patch silently
    // reverted on every reboot even though the toggle still showed "on" in the app's
    // own persisted intent, since queryStatus() (correctly) reads the live mount, not
    // a stored preference. This boot script re-applies the mount at startup, matching
    // every other persisted module in this app.
    private const val BOOT_SCRIPT_NAME = "recents_grid_patch.sh"
    private const val BOOT_SCRIPT_TARGET = "/data/adb/service.d/$BOOT_SCRIPT_NAME"
    private const val BOOT_SCRIPT_ASSET = "recents_grid_patch_template.sh"

    // Every app process (including this one) gets its own private mount namespace on this ROM
    // (same reason TelemetryController has to nsenter for /data/data visibility). A bind mount
    // done in this app's own root-shell namespace is invisible to com.android.launcher3's
    // namespace, and this app's own `mount` readback would only ever see its own phantom copy -
    // so every mount/umount/status check here must run inside PID 1's (the real, global) namespace.
    private fun inGlobalNs(cmd: String): String =
        "nsenter --mount=/proc/1/ns/mnt -- sh -c '${cmd.replace("'", "'\\''")}'"

    private fun isBindMounted(): Boolean =
        RootShell.run(inGlobalNs("mount | grep -F '$TARGET_APK'")).out.isNotEmpty()

    // `am force-stop` is a no-op for persistent apps like com.android.systemui (verified on-device:
    // same PID before/after). A hard kill -9 on the actual PID always works and lets the system
    // respawn it fresh - use this everywhere instead of am force-stop for a real restart.
    private fun killProcess(pkg: String): Boolean {
        val pid = RootShell.run("pidof $pkg").outString.trim()
        if (pid.isEmpty()) return false
        return RootShell.run("kill -9 $pid").success
    }

    fun queryStatus(): RecentsStatus = RecentsStatus(isNativePatchActive = isBindMounted())

    /** Realigns+re-signs whatever is at [input] (see [ApkAligner]/[OnDeviceApkSigner]) and
     * returns the result, or null if it couldn't be repaired this way (input missing, or a
     * ZIP layout ApkAligner doesn't handle) - callers decide what "can't fix it" means for
     * them rather than this throwing past them. [input] itself is left untouched either way;
     * the caller owns cleanup of both [input] and the returned file. */
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

    /** Installs [source] to PATCHED_APK_PATH, bind-mounts it over TARGET_APK, and persists
     * the mount across reboots via the boot script - the common back half of both the grid
     * patch and the live-apk repair, which differ only in where [source] comes from. */
    private fun mountPatchedApk(context: Context, source: File): Boolean {
        // On a clean install /data/adb/q25toolbox/ doesn't exist yet, so this and PATCHED_APK_PATH's
        // own writes fail ("No such file or directory") and the mount below silently binds nothing.
        // Reported by a user who traced it exactly to this (v2.0.5, clean install, no prior root data).
        RootShell.run("mkdir -p /data/adb/q25toolbox && chmod 755 /data/adb/q25toolbox")
        RootShell.run("cp '${source.absolutePath}' '$PATCHED_APK_PATH'")
        RootShell.run("chown root:root '$PATCHED_APK_PATH' && chcon u:object_r:system_file:s0 '$PATCHED_APK_PATH'")
        RootShell.run(inGlobalNs("umount -l '$TARGET_APK' 2>/dev/null ; mount -o bind '$PATCHED_APK_PATH' '$TARGET_APK'"))
        // The mount itself is kernel state and won't survive a reboot on its own -
        // install the boot script that re-applies it on every startup.
        AssetInstaller.installFromAsset(context, BOOT_SCRIPT_ASSET, BOOT_SCRIPT_TARGET)
        killProcess("com.android.launcher3")
        return isBindMounted()
    }

    fun setNativeGridPatch(context: Context, enable: Boolean): Boolean {
        return if (enable) {
            // The bundled asset is a fixed build - re-running it through the same align+sign
            // pipeline as repairRecentsProvider() means this never again depends on us having
            // manually zipaligned/signed it correctly ahead of shipping (that's exactly the class
            // of mistake that shipped the beta3a-era bug in the first place). If a future asset
            // update ships something ApkAligner can't parse, this fails closed rather than
            // mounting something possibly-broken.
            val staging = File(context.cacheDir, "grid_patch_asset.apk")
            context.assets.open(PATCHED_APK_ASSET).use { input -> staging.outputStream().use { input.copyTo(it) } }
            val signed = alignAndSign(staging)
            staging.delete()
            if (signed == null) return false
            val mounted = mountPatchedApk(context, signed)
            signed.delete()
            mounted
        } else {
            // Lazy unmount can take a moment to detach; retry with a plain umount before giving up,
            // so the toggle never reports success while the patched apk is still bind-mounted.
            var attempts = 0
            while (isBindMounted() && attempts < 3) {
                RootShell.run(inGlobalNs("umount -l $TARGET_APK 2>/dev/null ; umount $TARGET_APK 2>/dev/null"))
                attempts++
            }
            AssetInstaller.removeFile(BOOT_SCRIPT_TARGET)
            killProcess("com.android.launcher3")
            !isBindMounted()
        }
    }

    fun isRecentsProviderInstalled(): Boolean =
        RootShell.run("pm path com.android.launcher3").success

    // Traced on a BenOS beta3a OTA (2026-08-16): the update shipped a corrupted
    // TARGET_APK (resources.arsc stored but not 4-byte aligned), which
    // PackageManager silently refuses at its own boot-time scan - com.android.launcher3
    // never gets registered, so there's no Recents/Overview provider at all,
    // independent of whether the Grid Recents toggle is on. Circumstantial evidence
    // points at this controller's own bind mount being active *during* the OTA
    // install as the actual trigger (Custota reads/diffs the live partition
    // content; if a bind mount was substituting a different file at that exact
    // moment, the update could compute or write the wrong bytes for that entry) -
    // see the OTA disclaimer surfaced in RecentsTweaksScreen.
    //
    // The fix itself is the same bind-mount mechanism as the grid patch (both
    // need a validly zip-aligned launcher3 apk at TARGET_APK), but
    // PackageManager's alignment check only runs at its own boot-time scan - a
    // live file swap doesn't retrigger it. So if the provider was never
    // registered this boot (a fresh case of this bug), mounting a good file
    // alone isn't enough; the caller must prompt for a reboot when
    // [RepairResult.needsReboot] is true.
    //
    // Deliberately does NOT reuse the bundled PATCHED_APK_ASSET here (that's a
    // fixed build tied to one specific BenOS version). Instead it pulls
    // whatever com.android.launcher3 is actually installed on *this* device,
    // fixes only its resources.arsc alignment (ApkAligner), and re-signs it
    // (OnDeviceApkSigner) - so this generalizes across BenOS versions and other
    // ROMs, instead of needing a manual re-extract-and-rebuild from us every
    // time a new build ships a misaligned copy. Falls back to the bundled
    // asset only if the live apk can't be repaired this way at all (missing,
    // or a ZIP layout ApkAligner doesn't handle).
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
     * back to the app's uid (+ restorecon) so plain File I/O can read it - the
     * live system file itself is root-only. */
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

    /** Whether the boot-time re-mount script is installed (i.e. the patch is meant to
     * survive reboot), for [DaemonMaintenance] to self-heal against. */
    fun isPersisted(): Boolean = AssetInstaller.fileExists(BOOT_SCRIPT_TARGET)

    /** Persisted AND the mount it's supposed to maintain is actually active right now -
     * see [AssetInstaller.matchesAsset] for why checking the script exists alone isn't
     * enough (a stale script from an older build looks identical to a healthy one). */
    fun isHealthy(context: Context): Boolean =
        isBindMounted() && AssetInstaller.matchesAsset(context, BOOT_SCRIPT_ASSET, BOOT_SCRIPT_TARGET)

    fun restartLauncher(): Boolean = killProcess("com.android.launcher3")

    fun restartSystemUi(): Boolean = killProcess("com.android.systemui")

    private const val SCRIM_ALPHA_KEY = "q25_recents_scrim_alpha"

    /** Recents background scrim opacity (0f = fully transparent, 1f = fully opaque).
     * Read live by the patched RecentsState/OverviewState.getScrimColor()/
     * getWorkspaceScrimColor() - no rebuild needed, just a launcher restart to pick it up. */
    fun getScrimAlpha(): Float =
        RootShell.run("settings get global $SCRIM_ALPHA_KEY").outString.trim().toFloatOrNull() ?: 1f

    fun setScrimAlpha(alpha: Float) {
        RootShell.run("settings put global $SCRIM_ALPHA_KEY ${alpha.coerceIn(0f, 1f)}")
    }
}
