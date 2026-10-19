package com.kgr.q25toolbox.xposed

import android.content.Context
import android.provider.Settings
import com.kgr.q25toolbox.modules.CircleToSearchController
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed hook that enables Circle to Search on the Q25 without shipping a companion app.
 *
 * Inside `com.google.android.googlequicksearchbox` only, it does the same two things the old
 * `google.cts.enabler` module did (FeatureSpoofer + DeviceSpoofer):
 *  - reports `com.google.android.feature.PIXEL_2023_EXPERIENCE` from
 *    `ApplicationPackageManager.hasSystemFeature` (both overloads);
 *  - spoofs the Pixel 8 Pro (husky) `android.os.Build` fields.
 * The launcher gesture flags are set separately by
 * [com.kgr.q25toolbox.modules.CircleToSearchController] via device_config.
 *
 * Everything is gated on the world-readable `Settings.Global` key
 * [CircleToSearchController.HOOK_ENABLED_KEY], written with root by the controller when the
 * user toggles the feature in the app; the Google app is force-stopped on toggle so the hook
 * re-evaluates on a fresh process.
 *
 * Handshake: the first hook callback with a Context writes `files/q25toolbox_cts_hook.state`
 * (ok= + the Google app versionCode) so the app can show whether the hooks installed in THIS
 * build of the Google app. Never allowed to throw into the hooked app.
 *
 * Debug on device: /data/adb/lspd/log/modules_*.log (tag Q25Toolbox-Xposed)
 */
class CircleToSearchHookInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "Q25Toolbox-Xposed"
        private const val SELF_PKG = "com.kgr.q25toolbox"
        private const val TARGET_PKG = "com.google.android.googlequicksearchbox"

        /** The feature the Google app checks to offer Circle to Search. */
        private const val FEATURE_PIXEL_2023 = "com.google.android.feature.PIXEL_2023_EXPERIENCE"

        /** Pixel 8 Pro (husky) Build fields — same values the old google.cts.enabler spoofed. */
        private val BUILD_PROPS = listOf(
            "BRAND" to "google",
            "MANUFACTURER" to "Google",
            "DEVICE" to "husky",
            "PRODUCT" to "husky",
            "MODEL" to "Pixel 8 Pro",
            "FINGERPRINT" to "google/husky/husky:14/UQ1A.240205.004/11269751:user/release-keys"
        )

        private const val HANDSHAKE_FILE = "q25toolbox_cts_hook.state"

        /** Whether the required hooks installed in this process. */
        @Volatile private var hooksOk = false
        @Volatile private var handshakeWritten = false
        @Volatile private var buildSpoofed = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            SELF_PKG -> selfProbe(lpparam.classLoader)
            TARGET_PKG -> installHooks(lpparam)
        }
    }

    private fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        XposedBridge.log("[$TAG] loaded in ${lpparam.packageName}, installing Circle to Search hooks")
        val feature = hookHasSystemFeature(cl)
        val attach = hookAttachBaseContext(cl)
        hooksOk = feature && attach
        XposedBridge.log("[$TAG] CTS hooks installed: hasSystemFeature=$feature attachBaseContext=$attach")
    }

    /**
     * `ApplicationPackageManager.hasSystemFeature(...)` -> true for the Pixel 2023 Experience
     * feature, only while the app-side toggle is on. The same hook is also the fallback that
     * applies the Build spoof / writes the handshake if attachBaseContext was never reached.
     */
    private fun hookHasSystemFeature(cl: ClassLoader): Boolean {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (param.args.isEmpty()) return
                    if (param.args[0]?.toString() != FEATURE_PIXEL_2023) return
                    val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                        ?: return
                    if (!enabled(ctx)) return
                    param.result = true
                    applyBuildSpoofOnce(cl, ctx)
                    writeHandshake(ctx)
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] hasSystemFeature hook error: ${t.message}")
                }
            }
        }
        var ok = true
        ok = ok && try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager", cl, "hasSystemFeature",
                String::class.java, hook
            )
            XposedBridge.log("[$TAG] hooked hasSystemFeature(String)")
            true
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hasSystemFeature(String) hook failed: ${t.message}")
            false
        }
        ok = ok && try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager", cl, "hasSystemFeature",
                String::class.java, Integer.TYPE, hook
            )
            XposedBridge.log("[$TAG] hooked hasSystemFeature(String, int)")
            true
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hasSystemFeature(String, int) hook failed: ${t.message}")
            false
        }
        return ok
    }

    /**
     * `attachBaseContext` runs before any app code: the earliest point with a Context, so the
     * Build spoof and the handshake happen before the Google app evaluates its Circle to Search
     * eligibility. Legacy target is Application; on LineageOS 24 (SDK 37) the declaration lives
     * on ContextWrapper and Xposed's exact lookup does not walk superclasses — see HookTargets.
     */
    private fun hookAttachBaseContext(cl: ClassLoader): Boolean {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val ctx = param.args.getOrNull(0) as? Context ?: return
                    if (!enabled(ctx)) return
                    applyBuildSpoofOnce(cl, ctx)
                    writeHandshake(ctx)
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] attachBaseContext hook error: ${t.message}")
                }
            }
        }
        return HookTargets.hookAny(
            cl,
            legacy = HookTargets.Candidate(
                "android.app.Application", "attachBaseContext",
                arrayOf<Any>("android.content.Context"), hook
            ),
            new = HookTargets.Candidate(
                "android.content.ContextWrapper", "attachBaseContext",
                arrayOf<Any>("android.content.Context"), hook
            )
        )
    }

    private fun enabled(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, CircleToSearchController.HOOK_ENABLED_KEY, 0) == 1
    } catch (t: Throwable) {
        false
    }

    private fun applyBuildSpoofOnce(cl: ClassLoader, ctx: Context) {
        if (buildSpoofed) return
        buildSpoofed = true
        val build = try {
            XposedHelpers.findClass("android.os.Build", cl)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] android.os.Build not found: ${t.message}")
            null
        }
        var applied = 0
        if (build != null) {
            for ((field, value) in BUILD_PROPS) {
                try {
                    XposedHelpers.setStaticObjectField(build, field, value)
                    applied++
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] Build.$field spoof failed: ${t.message}")
                }
            }
        }
        XposedBridge.log("[$TAG] Build props spoofed: $applied/${BUILD_PROPS.size} (enabled=${enabled(ctx)})")
    }

    /**
     * Tells the app, once per Google app process, whether the hooks installed, together with the
     * app's versionCode so the verdict cannot outlive an app update.
     */
    private fun writeHandshake(ctx: Context) {
        if (handshakeWritten) return
        handshakeWritten = true
        try {
            @Suppress("DEPRECATION")
            val versionCode = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
            val f = java.io.File(ctx.filesDir, HANDSHAKE_FILE)
            f.parentFile?.mkdirs()
            f.writeText("ok=${if (hooksOk) 1 else 0}\ngapp=$versionCode\nbuild=${if (buildSpoofed) 1 else 0}\n")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] handshake write failed: ${t.message}")
        }
    }

    private fun selfProbe(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.kgr.q25toolbox.modules.CircleToSearchController", cl, "isXposedActive",
                XC_MethodReplacement.returnConstant(true)
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] CTS self-probe hook failed: ${t.message}")
        }
    }
}
