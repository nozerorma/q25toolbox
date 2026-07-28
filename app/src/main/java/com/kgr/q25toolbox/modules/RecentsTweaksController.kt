package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell

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

    fun setNativeGridPatch(context: Context, enable: Boolean): Boolean {
        return if (enable) {
            AssetInstaller.installAsset(context, PATCHED_APK_ASSET, PATCHED_APK_PATH, mode = "644")
            RootShell.run("chown root:root $PATCHED_APK_PATH && chcon u:object_r:system_file:s0 $PATCHED_APK_PATH")
            RootShell.run(inGlobalNs("umount -l $TARGET_APK 2>/dev/null ; mount -o bind $PATCHED_APK_PATH $TARGET_APK"))
            // The mount itself is kernel state and won't survive a reboot on its own -
            // install the boot script that re-applies it on every startup.
            AssetInstaller.installFromAsset(context, BOOT_SCRIPT_ASSET, BOOT_SCRIPT_TARGET)
            killProcess("com.android.launcher3")
            isBindMounted()
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
