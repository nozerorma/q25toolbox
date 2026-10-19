package com.kgr.q25toolbox.modules

import android.content.Context
import android.provider.Settings
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.service.Q25AccessibilityService

/**
 * Recents layout: which implementation draws the Overview, and the settings that go with it.
 *
 * Three ways to draw it exist:
 *  - the launcher's own Overview (stock), optionally forced into the two-row tablet Grid by the LSPosed
 *    module [com.kgr.q25toolbox.xposed.RecentsHookInit];
 *  - our own overlay windows (Slim List, Masonry quilt, standalone Grid), which need no hook at all.
 *
 * The user-facing "Grid" is [LayoutMode.GRID_AUTO]: use the LSPosed Grid when the hook is demonstrably working
 * in the launcher on this device, otherwise the standalone Grid. That removes the old per-ROM / per-launcher
 * build dance (v3): a launcher the hook does not fit simply falls back instead of breaking Overview.
 * "Demonstrably working" is a handshake: the hook writes a small state file inside the launcher's data dir
 * after trying to install its hooks, and this controller reads it (as root) and compares the launcher's
 * versionCode, so a launcher update invalidates a stale "ok" until the hook has run on the new build.
 *
 * Settings.Global keys (world-readable, written with root):
 *  - `bb_recents_layout_mode`: what the LSPosed hook reads (0 stock, 1 grid, 2 hooked masonry);
 *  - `q25_recents_overlay_mode`: the user's choice for the modes that are not the hooked ones;
 *  - `q25_recents_hook_ok`: the outcome of [reconcileGrid] (1 = Grid runs on the hook), read by the service.
 *
 * The pre-v3 mechanism (bind-mounting a bundled, pre-patched launcher APK) is gone, and so is the v3 "Recents
 * Provider Repair" tool: the overlay modes do not depend on the launcher's Recents provider at all.
 */
object RecentsTweaksController {

    private const val LAYOUT_MODE_KEY = "bb_recents_layout_mode"
    private const val SCRIM_ALPHA_KEY = "q25_recents_scrim_alpha"

    private const val LAUNCHER_PKG = "com.android.launcher3"

    /** Launcher packages the hook is scoped to (see RecentsHookInit.TARGET_PACKAGES). */
    internal val HOOK_PACKAGES = listOf("com.android.launcher3", "org.lineageos.trebuchet")

    /** Written by the hook inside the launcher's files dir; read here as root. */
    private const val HOOK_STATE_FILE = "files/q25toolbox_hook.state"

    // Kept only for [cleanupLegacyGridPatch]: pre-v3 and v3-repair artifacts that an update must still be able to
    // tear down. Nothing creates them any more.
    private const val TARGET_APK = "/system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk"
    private const val BOOT_SCRIPT_TARGET = "/data/adb/service.d/recents_grid_patch.sh"
    private const val LEGACY_MODULE_DIR = "/data/adb/modules/q25_recents"
    private const val LEGACY_PATCHED_APK = "/data/adb/q25toolbox/SearchLauncherQuickStep_patched.apk"

    /** Overlay modes and "Grid (auto)" live under their own key; see [setLayoutMode]. */
    private const val OVERLAY_MODE_KEY = "q25_recents_overlay_mode"

    /** 1 when Grid (auto) currently runs on the LSPosed hook; maintained by [reconcileGrid]. */
    private const val HOOK_OK_KEY = "q25_recents_hook_ok"

    enum class LayoutMode(val value: Int) {
        STOCK(0),

        /** The LSPosed Grid forced on the launcher. Only ever set by [reconcileGrid] (it is not a user choice any more). */
        GRID(1),

        /** Grid layout plus staggered per-tile heights (see RecentsHookInit). */
        MASONRY(2),

        /** Standalone vertical task list drawn as our own overlay; no Xposed, no launcher hook. */
        SLIM_LIST(3),

        /** Standalone snapshot quilt drawn as our own overlay; no Xposed, no launcher hook. */
        QUILT(4),

        /** Standalone two-row grid (Launcher3 tablet look) drawn by
         *  [com.kgr.q25toolbox.service.GridRecentsOverlayController]; never uses the LSPosed hook. */
        GRID_OVERLAY(5),

        /** "Grid": the LSPosed Grid if the hook works on this launcher, otherwise [GRID_OVERLAY]. */
        GRID_AUTO(6);

        /** True for the choices that can end up drawn by one of our own overlay windows (settings apply to them). */
        val isOverlay: Boolean get() = this == SLIM_LIST || this == QUILT || this == GRID_OVERLAY || this == GRID_AUTO

        companion object {
            fun fromValue(v: Int?): LayoutMode = entries.firstOrNull { it.value == v } ?: STOCK
        }
    }

    /** Outcome of the hook handshake for the launcher build that is installed right now. */
    enum class HookHealth { OK, BROKEN, UNKNOWN }

    /**
     * Overridden to return true by [com.kgr.q25toolbox.xposed.RecentsHookInit]
     * when it loads in our own process, so the UI can tell the user whether the
     * LSPosed module is actually enabled. Keep the body a plain `return false`.
     */
    @JvmStatic
    fun isXposedActive(): Boolean = false

    private fun globalInt(key: String): Int? =
        RootShell.run("settings get global $key").outString.trim().toIntOrNull()

    /** The user's choice (what the radio list shows). */
    fun getLayoutMode(): LayoutMode {
        val overlay = LayoutMode.fromValue(globalInt(OVERLAY_MODE_KEY))
        if (overlay.isOverlay) return overlay
        return LayoutMode.fromValue(globalInt(LAYOUT_MODE_KEY))
    }

    /**
     * What the accessibility service should do when Recents is opened (non-root read of world-readable keys).
     * STOCK means "let the system open its Overview" (stock, or the LSPosed Grid); anything else is one of our
     * overlays. [LayoutMode.GRID_AUTO] is resolved here, through the outcome [reconcileGrid] stored.
     */
    fun getOverlayMode(context: Context): LayoutMode {
        val raw = LayoutMode.fromValue(
            runCatching { Settings.Global.getInt(context.contentResolver, OVERLAY_MODE_KEY) }.getOrNull()
        )
        if (raw == LayoutMode.GRID_AUTO) {
            val hookOk = runCatching { Settings.Global.getInt(context.contentResolver, HOOK_OK_KEY) }.getOrNull() == 1
            return if (hookOk) LayoutMode.STOCK else LayoutMode.GRID_OVERLAY
        }
        return raw.takeIf { it.isOverlay } ?: LayoutMode.STOCK
    }

    /** Whether Grid (auto) is currently running on the LSPosed hook. Root read; for the settings screen. */
    fun gridUsesHook(): Boolean = getLayoutMode() == LayoutMode.GRID_AUTO && globalInt(HOOK_OK_KEY) == 1

    /**
     * Writes the layout choice. The overlay choices write only [OVERLAY_MODE_KEY] and force the hook's key to
     * STOCK, so the Xposed module (which treats every non-zero value as a grid) never sees them. [LayoutMode.GRID_AUTO]
     * also only writes its own key; the hook's key is then decided by [reconcileGrid]. The launcher is restarted
     * only when the hook-controlled mode actually changes.
     */
    fun setLayoutMode(mode: LayoutMode) {
        val hookBefore = globalInt(LAYOUT_MODE_KEY) ?: 0
        val hookAfter = when {
            mode == LayoutMode.GRID_AUTO -> hookBefore // reconcileGrid decides
            mode.isOverlay -> LayoutMode.STOCK.value
            else -> mode.value
        }
        RootShell.run(
            "settings put global $OVERLAY_MODE_KEY ${if (mode.isOverlay) mode.value else 0}" +
                " ; settings put global $LAYOUT_MODE_KEY $hookAfter"
        )
        if (hookBefore != hookAfter) restartLauncher()
    }

    /**
     * The whole "user picked a layout" operation: stores the choice, resolves Grid (auto), and makes sure the
     * physical Recents key is remapped exactly when one of our overlays is what will open. Blocking.
     */
    fun applyMode(context: Context, mode: LayoutMode) {
        setLayoutMode(mode)
        // Grid (auto) settles hook-or-overlay itself, remap included.
        if (mode == LayoutMode.GRID_AUTO) { reconcileGrid(context); return }
        RootShell.run("settings put global $HOOK_OK_KEY 0")
        val sp = context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
        KeyRemapController.setRecentsOverlayRemap(sp, mode.isOverlay)
        KeyRemapController.applySettings(sp)
    }

    // --- LSPosed hook handshake ------------------------------------------------

    internal fun installedVersionCode(pkg: String): String? =
        RootShell.run("dumpsys package $pkg 2>/dev/null | grep -m1 -o 'versionCode=[0-9]*'")
            .outString.substringAfter("=", "").trim().ifEmpty { null }

    /**
     * Reads the state file the hook leaves in the launcher: OK only if the hook installed every hook Grid needs AND
     * recorded the versionCode of the launcher that is installed now. A missing file, or one from another launcher
     * build, is UNKNOWN (the hook has not run on this build yet); a file saying a required hook failed is BROKEN.
     * Blocking (root).
     */
    fun hookHealth(): HookHealth {
        for (pkg in HOOK_PACKAGES) {
            // Through PID 1's mount namespace: the app's own root shell inherits Android's per-app data isolation and
            // cannot see another app's data dir (an `adb root` shell can, which is why this passed by hand).
            val res = RootShell.run(inGlobalNs("cat /data/user/0/$pkg/$HOOK_STATE_FILE 2>/dev/null"))
            val state = res.outString
            android.util.Log.i("Q25Toolbox", "hookHealth[$pkg]: success=${res.success} state=${state.replace("\n", "|")}")
            if (state.isBlank()) continue
            val installed = installedVersionCode(pkg)
            val h = parseHandshake(state, installed)
            android.util.Log.i("Q25Toolbox", "hookHealth[$pkg]: installed=$installed -> $h")
            return h
        }
        return HookHealth.UNKNOWN
    }

    /** Pure part of [hookHealth]: a handshake file and the launcher versionCode installed now. */
    internal fun parseHandshake(
        state: String,
        installedVersion: String?,
        versionKey: String = "launcher"
    ): HookHealth {
        val ok = Regex("""ok=(\d)""").find(state)?.groupValues?.get(1) == "1"
        val recorded = Regex("""$versionKey=(\d+)""").find(state)?.groupValues?.get(1)
        if (recorded == null || installedVersion == null || recorded != installedVersion) return HookHealth.UNKNOWN
        return if (ok) HookHealth.OK else HookHealth.BROKEN
    }

    /** Pure part of [reconcileGrid]: should Grid (auto) run on the hook? See there for the UNKNOWN rule. */
    internal fun shouldUseHook(health: HookHealth, hookKeyOn: Boolean): Boolean =
        health == HookHealth.OK || (health == HookHealth.UNKNOWN && hookKeyOn)

    /**
     * Decides, for a user who chose Grid (auto), whether Grid runs on the LSPosed hook right now, and puts the
     * settings in that state: the hook's key (1 or 0), the outcome key the service reads, and a launcher restart when
     * the hook's key changed. Returns true when the hook is used.
     *
     * An UNKNOWN handshake keeps whatever already works: someone updating from v3, whose hook is still the old build
     * that never wrote the state file, keeps their LSPosed Grid until the new hook has run once. A new selection with
     * an UNKNOWN handshake starts on the standalone Grid. BROKEN always means the standalone Grid.
     * No-op (returns false) unless the choice is Grid (auto). Blocking (root).
     */
    fun reconcileGrid(context: Context): Boolean {
        val choice = globalInt(OVERLAY_MODE_KEY)
        if (LayoutMode.fromValue(choice) != LayoutMode.GRID_AUTO) {
            android.util.Log.i("Q25Toolbox", "reconcileGrid: skipped, overlay choice=$choice")
            return false
        }
        val hookKeyNow = (globalInt(LAYOUT_MODE_KEY) ?: 0) == LayoutMode.GRID.value
        val health = hookHealth()
        val useHook = shouldUseHook(health, hookKeyNow)
        android.util.Log.i("Q25Toolbox", "reconcileGrid: health=$health hookKeyNow=$hookKeyNow -> useHook=$useHook")
        val recorded = globalInt(HOOK_OK_KEY) == 1

        if (useHook != recorded || useHook != hookKeyNow) {
            RootShell.run(
                "settings put global $HOOK_OK_KEY ${if (useHook) 1 else 0}" +
                    " ; settings put global $LAYOUT_MODE_KEY ${if (useHook) LayoutMode.GRID.value else 0}"
            )
            if (useHook != hookKeyNow) restartLauncher()
        }
        // The Recents key is remapped exactly when an overlay is what opens. Skip the (root, keyboard-reloading)
        // re-apply when it is already right.
        val sp = context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
        if (KeyRemapController.isRecentsOverlayRemap(sp) == useHook) {
            KeyRemapController.setRecentsOverlayRemap(sp, !useHook)
            KeyRemapController.applySettings(sp)
        }
        return useHook
    }

    /**
     * One-time adoption of installs from v3.x, where "Grid" was the hook itself (`bb_recents_layout_mode` = 1 and no
     * overlay choice): it becomes Grid (auto), keeping the hook for as long as it keeps working. Cheap when there is
     * nothing to do. Blocking (root).
     */
    fun migrateLegacyGrid(context: Context) {
        if (globalInt(OVERLAY_MODE_KEY).let { it != null && it != 0 }) return
        if (globalInt(LAYOUT_MODE_KEY) != LayoutMode.GRID.value) return
        RootShell.run("settings put global $OVERLAY_MODE_KEY ${LayoutMode.GRID_AUTO.value} ; settings put global $HOOK_OK_KEY 1")
        reconcileGrid(context)
    }

    // --- misc -----------------------------------------------------------------

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
     * (A bind mount left by the removed v3 "Recents Provider Repair" is deliberately left alone: it was fixing
     * a launcher the user needed, and nothing here can tell whether it is still wanted.)
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

    // Every app process gets its own private mount namespace on this ROM, so every mount/umount/status check
    // must run inside PID 1's global namespace.
    internal fun inGlobalNs(cmd: String): String =
        "nsenter --mount=/proc/1/ns/mnt -- sh -c '${cmd.replace("'", "'\\''")}'"
}
