package com.kgr.q25toolbox.xposed

import android.content.Context
import android.graphics.Color
import android.provider.Settings
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module that produces the two-row grid / masonry Overview (Recents)
 * inside `com.android.launcher3` (BenOS ships Google's `SearchLauncherQuickStep`
 * under that package name).
 *
 * This replaces the old bind-mounted-patched-APK mechanism. That approach shipped
 * a 28 MB pre-patched launcher build and bind-mounted it over the system file;
 * it was locked to one exact BenOS build and could take Recents down entirely on
 * an OTA mismatch. A hook patches whatever launcher is actually running, by
 * method name, in memory - OTA-resilient, and its worst case is "grid silently
 * falls back to stock" rather than "no Overview at all".
 *
 * Verified against the decompiled `SearchLauncherQuickStep.apk` (versionName 14):
 *
 *  - Overview task geometry branches on `DeviceProfile.isTablet`, which the sole
 *    `DeviceProfile` constructor derives from
 *    `DisplayController.Info.isTablet(WindowBounds)` before computing anything.
 *    Force that -> true. [forceOverviewTablet]. `FeatureFlags.ENABLE_GRID_ONLY_
 *    OVERVIEW` is deliberately left alone - forcing it would drop the large
 *    focused task and the actions bar; `isTablet` alone gives the stock tablet
 *    grid (big first tile + two rows) with no toolbar.
 *  - Forcing `isTablet` also runs `DeviceProfile.recalculateHotseatWidthAndBorderSpace()`
 *    (skipped on a phone), which divides by zero here; no-op it - the launcher's
 *    own hotseat is never shown (third-party home launcher). [guardHotseatRecalc]
 *  - `task_thumbnail_icon_drawable_size_grid` / `overview_grid_row_spacing` /
 *    `overview_grid_side_margin` are 0 in the phone resource bucket; backfill
 *    the zeroed `DeviceProfile` fields. Also clear `isTaskbarPresent` so the
 *    forced-tablet profile does not bring the floating taskbar.
 *    [fixupOverviewDeviceProfile]
 *  - Masonry: shorten each `TaskView` box by a per-task-id factor in
 *    `TaskView.updateTaskSize()`; `updateGridProperties` re-centres it.
 *    Square tile corners via `TaskCornerRadius.get`. [mosaicTileHeights],
 *    [squareTaskCorners]
 *
 * Config (world-readable `Settings.Global`, written with root by
 * [com.kgr.q25toolbox.modules.RecentsTweaksController]):
 *   - bb_recents_layout_mode : 0 = Stock, 1 = Grid, 2 = Masonry
 *   - q25_recents_scrim_alpha : 0.0 .. 1.0  (Overview background opacity)
 *
 * Debug on device:  `adb logcat | grep Q25Toolbox-Xposed`
 */
class RecentsHookInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "Q25Toolbox-Xposed"
        private const val SELF_PKG = "com.kgr.q25toolbox"

        const val PREF_RECENTS_MODE = "bb_recents_layout_mode"
        const val PREF_SCRIM_ALPHA = "q25_recents_scrim_alpha"

        const val MODE_STOCK = 0
        const val MODE_GRID = 1
        const val MODE_MASONRY = 2

        /** Gap (dp) the Grid puts between its two rows when the launcher's own value is 0. */
        private const val ROW_SPACING_DP = 24

        /** File the app reads (as root) to learn whether the Grid hooks installed in this launcher build. */
        private const val HANDSHAKE_FILE = "q25toolbox_hook.state"

        /** Whether every hook the Grid layout needs was installed in this process. */
        @Volatile private var gridHooksOk = false
        @Volatile private var handshakeWritten = false

        /** Per-tile height multipliers for Masonry, indexed by task id modulo size. */
        private val MOSAIC_FACTORS = floatArrayOf(1.0f, 0.78f, 0.93f, 0.70f, 0.86f, 0.74f)

        private val TARGET_PACKAGES = setOf(
            "com.android.launcher3",
            "org.lineageos.trebuchet"
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == SELF_PKG) {
            selfProbe(lpparam.classLoader)
            return
        }
        if (lpparam.packageName !in TARGET_PACKAGES) return

        XposedBridge.log("[$TAG] loaded in ${lpparam.packageName}, installing Recents hooks")
        val cl = lpparam.classLoader
        // The four hooks the Grid layout cannot work without. Masonry, corners and scrim are cosmetic extras.
        // The app's "Grid (auto)" only uses this hook when all four are in (see writeHandshake).
        val tablet = forceOverviewTablet(cl)
        val hotseat = guardHotseatRecalc(cl)
        val profile = fixupOverviewDeviceProfile(cl)
        val asGrid = forceShowAsGrid(cl)
        gridHooksOk = tablet && hotseat && profile && asGrid
        mosaicTileHeights(cl)
        squareTaskCorners(cl)
        scaleOverviewScrim(cl)
    }

    // --- config ---------------------------------------------------------

    private fun mode(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, PREF_RECENTS_MODE, MODE_STOCK)
    } catch (t: Throwable) {
        MODE_STOCK
    }

    /** Grid or Masonry: both need the tablet two-row Overview path. */
    private fun gridActive(): Boolean {
        val ctx = currentApplication() ?: return false
        writeHandshake(ctx)
        return mode(ctx) != MODE_STOCK
    }

    /**
     * Tells the app, once per launcher process, whether the Grid hooks installed, together with the launcher's
     * versionCode so the verdict cannot outlive a launcher update. Done from the first hook callback because that
     * is the first moment an Application context exists; it runs at launcher start whatever the layout mode is, so
     * the app learns the answer before it ever turns the hook on. Never allowed to throw into the launcher.
     */
    private fun writeHandshake(ctx: Context) {
        if (handshakeWritten) return
        handshakeWritten = true
        try {
            @Suppress("DEPRECATION")
            val versionCode = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
            java.io.File(ctx.filesDir, HANDSHAKE_FILE)
                .writeText("ok=${if (gridHooksOk) 1 else 0}\nlauncher=$versionCode\n")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] handshake write failed: ${t.message}")
        }
    }

    private fun scrimAlpha(context: Context): Float = try {
        Settings.Global.getFloat(context.contentResolver, PREF_SCRIM_ALPHA, 1.0f)
    } catch (t: Throwable) {
        1.0f
    }

    // --- hooks ---------------------------------------------------------

    /**
     * Tablet Overview -> true while a grid mode is active.
     *
     * Legacy (LineageOS <= 23 launchers): force `DisplayController.Info.isTablet(WindowBounds)`.
     * That method computes, so ART cannot inline it and the hook is reliable.
     *
     * LineageOS 24 (launcher versionCode 37) removed that class; the grid path consults
     * `DeviceProperties.isLargeScreen()` instead — a TRIVIAL getter that ART inlines at hot call
     * sites, bypassing any method hook (a direct call inside TaskView.updateTaskSize reads the
     * real field while a reflection read sees the hook). So on LineageOS 24 the FIELD is forced
     * instead — see [forceLargeScreenFields].
     */
    private fun forceOverviewTablet(cl: ClassLoader): Boolean {
        if (HookTargets.isLineage24()) return forceLargeScreenFields(cl)
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (gridActive()) param.result = true
            }
        }
        return HookTargets.hookAny(
            cl,
            legacy = HookTargets.Candidate(
                "com.android.launcher3.util.DisplayController\$Info", "isTablet",
                arrayOf<Any>("com.android.launcher3.util.WindowBounds"), hook
            ),
            new = null
        )
    }

    /**
     * LineageOS 24: force the `isLargeScreen` field on DeviceProperties, while a grid mode is
     * active. Two injection points, because the field is read both inlined (field reads see it
     * directly) and at profile (re)build time:
     *  - every DeviceProperties construction — the profile is rebuilt on rotation/display change;
     *  - RecentsView.setInsets — it runs before updateSizeAndPadding in the same pass, covering
     *    profiles built before the mode key was visible. Its after-callback also forces
     *    mOverviewGridEnabled, in case setInsets computed it from a not-yet-DEFAULT state.
     * Never throws into the launcher.
     */
    private fun forceLargeScreenFields(cl: ClassLoader): Boolean {
        var ok = true
        try {
            val dps = XposedHelpers.findClass("com.android.launcher3.deviceprofile.DeviceProperties", cl)
            XposedBridge.hookAllConstructors(dps, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    forceLargeScreenFieldOn(param.thisObject)
                }
            })
            XposedBridge.log("[$TAG] hooked DeviceProperties constructors (isLargeScreen field force)")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] DeviceProperties ctor hook failed: ${t.message}")
            ok = false
        }
        val setInsetsHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val container = XposedHelpers.getObjectField(param.thisObject, "mContainer")
                    val dp = XposedHelpers.callMethod(container, "getDeviceProfile")
                    forceLargeScreenFieldOn(XposedHelpers.callMethod(dp, "getDeviceProperties"))
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] setInsets field force failed: ${t.message}")
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (gridActive()) {
                        XposedHelpers.setBooleanField(param.thisObject, "mOverviewGridEnabled", true)
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] setInsets gridEnabled force failed: ${t.message}")
                }
            }
        }
        ok = ok && HookTargets.hookAny(
            cl,
            legacy = null,
            new = HookTargets.Candidate(
                "com.android.quickstep.views.RecentsView", "setInsets",
                arrayOf<Any>("android.graphics.Rect"), setInsetsHook
            )
        )
        return ok
    }

    private fun forceLargeScreenFieldOn(dps: Any?) {
        if (dps == null || !gridActive()) return
        runCatching { XposedHelpers.setBooleanField(dps, "isLargeScreen", true) }
    }

    /**
     * On this older SearchLauncher build, forcing `isTablet` true makes
     * `DeviceProfile.recalculateHotseatWidthAndBorderSpace()` run (it is skipped
     * on a non-scalable-grid phone), and its `calculateHotseatBorderSpace()`
     * divides by `numShownHotseatIcons - 1` -> ArithmeticException in the
     * `DeviceProfile` constructor, which takes the whole launcher (and therefore
     * Overview) down. The hotseat of `com.android.launcher3` is never shown here
     * anyway (the home launcher is a third party one), so no-op the recalc while
     * a grid mode is active.
     */
    private fun guardHotseatRecalc(cl: ClassLoader): Boolean {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (gridActive()) param.result = null
            }
        }
        // Legacy target is the no-arg recalculateHotseatWidthAndBorderSpace(); the LineageOS 24
        // launcher takes an int and the divide-by-zero moved into HotseatProfile. No-op'ing the
        // DeviceProfile entry still cuts the whole chain, same as before. Both targets preserved,
        // ROM-ordered (HookTargets).
        return HookTargets.hookAny(
            cl,
            legacy = HookTargets.Candidate(
                "com.android.launcher3.DeviceProfile", "recalculateHotseatWidthAndBorderSpace",
                arrayOf(), hook
            ),
            new = HookTargets.Candidate(
                "com.android.launcher3.DeviceProfile", "recalculateHotseatWidthAndBorderSpace",
                arrayOf<Any>(Integer.TYPE), hook
            )
        )
    }

    /**
     * Post-construction fixups on every `DeviceProfile`, only while a grid mode
     * is active: drop the taskbar the forced-tablet profile would bring, and
     * backfill the grid dimens that are 0 in the phone resource bucket (0-size
     * task icons, no row gap otherwise).
     */
    private fun fixupOverviewDeviceProfile(cl: ClassLoader): Boolean {
        val dp = try {
            XposedHelpers.findClass("com.android.launcher3.DeviceProfile", cl)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] DeviceProfile not found: ${t.message}")
            return false
        }
        var n = 0
        for (ctor in dp.declaredConstructors) {
            try {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!gridActive()) return
                        val o = param.thisObject
                        // Density is read now, not when the hook is installed: no Application exists yet at install
                        // time, and a guessed value made "24 dp" come out as ~40 dp on a 193 dpi screen.
                        val density = currentApplication()?.resources?.displayMetrics?.density ?: 2.0f
                        fun px(dpValue: Int) = (dpValue * density).toInt()
                        runCatching { XposedHelpers.setBooleanField(o, "isTaskbarPresent", false) }
                        runCatching { XposedHelpers.setIntField(o, "taskbarHeight", 0) }

                        // The grid dimens that resolve to 0 in the phone resource bucket. Android 16's launcher moved
                        // them from DeviceProfile into a nested `overviewProfile` object (fields rowSpacing, ...);
                        // older launchers keep them on DeviceProfile itself (overviewRowSpacing, ...). Try the new
                        // layout first, then the old one.
                        val nested = runCatching { XposedHelpers.getObjectField(o, "overviewProfile") }.getOrNull()
                        val holder: Any = nested ?: o
                        val rowSpacing = if (nested != null) "rowSpacing" else "overviewRowSpacing"
                        val sideMargin = if (nested != null) "gridSideMargin" else "overviewGridSideMargin"
                        val iconGrid = if (nested != null) "taskIconDrawableSizeGridPx" else "overviewTaskIconDrawableSizeGridPx"
                        val iconPlain = if (nested != null) "taskIconDrawableSizePx" else "overviewTaskIconDrawableSizePx"
                        fun geti(name: String) = runCatching { XposedHelpers.getIntField(holder, name) }.getOrNull()
                        fun seti(name: String, v: Int) = runCatching { XposedHelpers.setIntField(holder, name, v) }

                        if ((geti(iconGrid) ?: 1) <= 0) {
                            val nonGrid = geti(iconPlain) ?: 0
                            seti(iconGrid, if (nonGrid > 0) nonGrid else px(44))
                        }
                        // Vertical gap between the two rows of tiles. Left at 0 the second row's header sits right
                        // against the first row's snapshots, so the tiles look like they overlap.
                        if ((geti(rowSpacing) ?: 1) <= 0) seti(rowSpacing, px(ROW_SPACING_DP))
                        if ((geti(sideMargin) ?: 1) <= 0) seti(sideMargin, px(12))
                        // Baked values of the freshly built profile: on LineageOS 24 the grid only
                        // renders when isLargeScreen was forced at construction time, so log it.
                        val dps = runCatching { XposedHelpers.getObjectField(o, "mDeviceProperties") }.getOrNull()
                        val ils = dps?.let { runCatching { XposedHelpers.callMethod(it, "isLargeScreen") }.getOrNull() }
                        val ar = dps?.let { runCatching { XposedHelpers.callMethod(it, "getAspectRatio") }.getOrNull() }
                        XposedBridge.log("[$TAG] DeviceProfile fixup (${if (nested != null) "overviewProfile" else "legacy"}): " +
                            "rowSpacing=${geti(rowSpacing)} side=${geti(sideMargin)} iconGrid=${geti(iconGrid)} " +
                            "isLargeScreen=$ils aspectRatio=$ar")
                    }
                })
                n++
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] DeviceProfile ctor hook failed: ${t.message}")
            }
        }
        XposedBridge.log("[$TAG] overview profile fixup on $n DeviceProfile constructor(s)")
        return n > 0
    }

    /** Pin `RecentsView.showAsGrid()` for a deterministic Stock (off) state. */
    private fun forceShowAsGrid(cl: ClassLoader): Boolean {
        return try {
            XposedHelpers.findAndHookMethod(
                "com.android.quickstep.views.RecentsView", cl, "showAsGrid",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctx = (param.thisObject as? View)?.context ?: return
                        param.result = mode(ctx) != MODE_STOCK
                    }
                }
            )
            XposedBridge.log("[$TAG] hooked RecentsView.showAsGrid")
            true
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] showAsGrid hook failed: ${t.message}")
            false
        }
    }

    /**
     * Masonry: after `TaskView.updateTaskSize()` (no-arg on this build) sizes a
     * task box to the uniform grid rect, shorten it by [MOSAIC_FACTORS] keyed on
     * the task id. `updateGridProperties()` runs afterwards and re-derives each
     * tile's vertical offset from its own `LayoutParams.height`, centring it, so
     * shorter tiles end up staggered. Width is untouched, so column positions,
     * paging and swipe-to-dismiss are unchanged. Focused / desktop tasks skipped.
     */
    private fun mosaicTileHeights(cl: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val tv = param.thisObject as? View ?: return
                if (mode(tv.context) != MODE_MASONRY) return
                if (XposedHelpers.callMethod(tv, "isFocusedTask") == true) return
                if (XposedHelpers.callMethod(tv, "isDesktopTask") == true) return
                val lp = tv.layoutParams ?: return
                if (lp.height <= 0) return
                val id = (XposedHelpers.callMethod(tv, "getTaskViewId") as? Int) ?: return
                val f = MOSAIC_FACTORS[((id % MOSAIC_FACTORS.size) + MOSAIC_FACTORS.size) % MOSAIC_FACTORS.size]
                val newH = (lp.height * f).toInt()
                if (newH <= 0 || newH == lp.height) return
                lp.height = newH
                tv.layoutParams = lp
            }
        }
        // Legacy: no-arg updateTaskSize(). The LineageOS 24 launcher (versionCode 37) takes
        // (Rect, Rect). Same callback works for both. Both targets preserved (HookTargets).
        HookTargets.hookAny(
            cl,
            legacy = HookTargets.Candidate(
                "com.android.quickstep.views.TaskView", "updateTaskSize",
                arrayOf(), hook
            ),
            new = HookTargets.Candidate(
                "com.android.quickstep.views.TaskView", "updateTaskSize",
                arrayOf<Any>("android.graphics.Rect", "android.graphics.Rect"), hook
            )
        )
    }

    /** Masonry only: square task-tile corners. */
    private fun squareTaskCorners(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.quickstep.util.TaskCornerRadius", cl, "get",
                "android.content.Context",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.getOrNull(0) as? Context ?: return
                        if (mode(ctx) == MODE_MASONRY) param.result = 0f
                    }
                }
            )
            XposedBridge.log("[$TAG] hooked TaskCornerRadius.get (masonry)")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] TaskCornerRadius hook failed: ${t.message}")
        }
    }

    /**
     * Overview background scrim opacity. Two getters on this build:
     * `OverviewState.getWorkspaceScrimColor(Launcher)` and
     * `fallback.RecentsState.getScrimColor(RecentsActivity)`; both return an
     * ARGB int whose alpha is scaled by the configured factor.
     */
    private fun scaleOverviewScrim(cl: ClassLoader) {
        val candidates = listOf(
            Triple(
                "com.android.launcher3.uioverrides.states.OverviewState",
                "getWorkspaceScrimColor", "com.android.launcher3.Launcher"
            ),
            Triple(
                "com.android.quickstep.fallback.RecentsState",
                "getScrimColor", "com.android.quickstep.RecentsActivity"
            )
        )
        for ((fqcn, method, argType) in candidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    fqcn, cl, method, argType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val ctx = param.args.getOrNull(0) as? Context ?: return
                            val factor = scrimAlpha(ctx)
                            if (factor >= 0.999f) return
                            val base = param.result as? Int ?: return
                            val a = (Color.alpha(base) * factor).toInt().coerceIn(0, 255)
                            param.result =
                                Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
                        }
                    }
                )
                XposedBridge.log("[$TAG] hooked $fqcn.$method")
            } catch (t: Throwable) {
                // not every launcher variant exposes both
            }
        }
    }

    // --- helpers ------------------------------------------------------

    private fun selfProbe(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.kgr.q25toolbox.modules.RecentsTweaksController", cl, "isXposedActive",
                XC_MethodReplacement.returnConstant(true)
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] self-probe hook failed: ${t.message}")
        }
    }

    private fun currentApplication(): Context? = try {
        val at = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentActivityThread"
        )
        XposedHelpers.callMethod(at, "getApplication") as? Context
    } catch (t: Throwable) {
        null
    }
}
