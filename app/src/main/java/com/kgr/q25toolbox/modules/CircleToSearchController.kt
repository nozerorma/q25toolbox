package com.kgr.q25toolbox.modules

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.service.Q25AccessibilityService

/**
 * Controller for Circle to Search (CTS) management.
 *
 * The spoofing itself lives in the LSPosed hook
 * [com.kgr.q25toolbox.xposed.CircleToSearchHookInit], scoped to the Google app only: it
 * reports the Pixel 2023 Experience feature and spoofs the Pixel 8 Pro Build fields inside
 * com.google.android.googlequicksearchbox. This controller owns the rest: the master toggle,
 * the launcher device_config gesture flags, the world-readable Settings.Global key the hook
 * reads, and the hook handshake health check.
 */
object CircleToSearchController {

    private const val TAG = "Q25-CTS-Controller"
    private const val KEY_CTS_ENABLED = "cts_master_enabled"

    const val GAPP_PKG = "com.google.android.googlequicksearchbox"

    /** World-readable Settings.Global key the hook reads; 1 = spoof active in the Google app. */
    const val HOOK_ENABLED_KEY = "q25_cts_hook_enabled"

    /** Written by the hook inside the Google app's files dir; read here as root. */
    private const val HOOK_STATE_FILE = "files/q25toolbox_cts_hook.state"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CTS_ENABLED, false)

    /**
     * Overridden to return true by [com.kgr.q25toolbox.xposed.CircleToSearchHookInit] when it
     * loads in our own process, so the UI can tell whether the LSPosed module is actually
     * enabled. Keep the body a plain `return false`.
     */
    @JvmStatic
    fun isXposedActive(): Boolean = false

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CTS_ENABLED, enabled).apply()

        // 1. Launcher gesture flags
        applyLauncherConfig(enabled)

        // 2. The hook's gate
        RootShell.run("settings put global $HOOK_ENABLED_KEY ${if (enabled) 1 else 0}")

        // 3. Restart the Google app so the hook re-evaluates on a fresh process
        RootShell.run("am force-stop $GAPP_PKG")
    }

    /**
     * Reads the state file the hook leaves in the Google app: OK only if the hooks installed
     * AND recorded the versionCode of the Google app installed now. A missing or stale file is
     * UNKNOWN (the hook has not run on this build yet); a file saying a hook failed is BROKEN.
     * Blocking (root).
     */
    fun hookHealth(): RecentsTweaksController.HookHealth {
        val res = RootShell.run(
            RecentsTweaksController.inGlobalNs("cat /data/user/0/$GAPP_PKG/$HOOK_STATE_FILE 2>/dev/null")
        )
        val state = res.outString
        Log.i(TAG, "hookHealth: state=${state.replace("\n", "|")}")
        if (state.isBlank()) return RecentsTweaksController.HookHealth.UNKNOWN
        val installed = RecentsTweaksController.installedVersionCode(GAPP_PKG)
        return RecentsTweaksController.parseHandshake(state, installed, "gapp")
    }

    fun applyLauncherConfig(enabled: Boolean) {
        val boolStr = if (enabled) "true" else "false"
        val syncStr = if (enabled) "persistent" else "none"

        val cmds = listOf(
            "cmd device_config put launcher long_press_home_button_to_search $boolStr",
            "cmd device_config put launcher long_press_home_button_to_search_mpr $boolStr",
            "cmd device_config put launcher press_hold_nav_handle_to_search $boolStr",
            "cmd device_config put launcher press_hold_nav_handle_to_search_mpr $boolStr",
            "cmd device_config put launcher ENABLE_SETTINGS_OSE_CUSTOMIZATIONS $boolStr",
            "cmd device_config put launcher ENABLE_LONG_PRESS_NAV_HANDLE $boolStr",
            "cmd device_config put launcher ENABLE_LONG_PRESS_NAV_HANDLE_MPR $boolStr",
            "cmd device_config put launcher INVOKE_OMNI_LPH $boolStr",
            "cmd device_config put launcher INVOKE_OMNI_LPH_MPR $boolStr",
            "cmd device_config set_sync_disabled_for_tests $syncStr"
        )

        val fullCmd = cmds.joinToString(" && ")
        RootShell.run(fullCmd)
        Log.d(TAG, "Applied launcher device_config: enabled=$enabled")
    }

    /**
     * Directly triggers Circle to Search over whatever is currently on screen
     * using the Android system ContextualSearchManager binder IPC.
     */
    fun trigger(context: Context) {
        // Haptic feedback
        vibrate(context)

        // Invoke Contextual Search via Android framework service:
        // Transaction 3 is TRANSACTION_startContextualSearch in IContextualSearchManager.
        // Entrypoint 1 is ENTRYPOINT_LONG_PRESS_NAV_HANDLE.
        val result = RootShell.run("service call contextual_search 3 i32 1")
        if (!result.success) {
            RootShell.run("service call contextual_search 3 i32 0")
        }
        Log.d(TAG, "Triggered Contextual Search via system service: success=${result.success}")
    }

    private fun vibrate(context: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), attr)
            } else {
                vibrator.vibrate(longArrayOf(0, 30), -1, attr)
            }
        }.onFailure { e ->
            Log.w(TAG, "Vibration failed", e)
        }
    }
}
