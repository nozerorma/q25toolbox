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
import java.io.File
import java.io.FileOutputStream

/**
 * Controller for Circle to Search (CTS) management.
 * Operates via a lightweight standalone Zygisk module + system permission XML deployed
 * to /data/adb/modules/q25_cts to spoof Pixel 8 Pro system features and properties for
 * Google Search (com.google.android.googlequicksearchbox), alongside launcher device_config
 * flags and system contextual_search IPC trigger.
 */
object CircleToSearchController {

    private const val TAG = "Q25-CTS-Controller"
    private const val KEY_CTS_ENABLED = "cts_master_enabled"

    const val MODULE_DIR = "/data/adb/modules/q25_cts"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CTS_ENABLED, false)

    fun isModuleInstalled(): Boolean {
        val cmd = """
            if [ -f '$MODULE_DIR/module.prop' ] && [ -f '$MODULE_DIR/zygisk/arm64-v8a.so' ] && [ ! -f '$MODULE_DIR/disable' ]; then
                echo yes
            else
                echo no
            fi
        """.trimIndent()
        val result = RootShell.run(cmd).outString.trim()
        val installed = result.contains("yes")
        Log.d(TAG, "isModuleInstalled: $installed (raw='$result')")
        return installed
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CTS_ENABLED, enabled).apply()

        // 1. Device config flags for launcher
        applyLauncherConfig(enabled)

        // 2. Manage Zygisk module
        if (enabled) {
            deployZygiskModule(context)
            RootShell.run("am force-stop com.google.android.googlequicksearchbox")
        } else {
            removeZygiskModule()
            RootShell.run("am force-stop com.google.android.googlequicksearchbox")
        }
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

    fun deployZygiskModule(context: Context): Boolean {
        return runCatching {
            val cacheDir = context.cacheDir
            val soFile = File(cacheDir, "arm64-v8a.so")
            val xmlFile = File(cacheDir, "pixel_2023_experience.xml")
            val propFile = File(cacheDir, "module.prop")

            context.assets.open("cts/arm64-v8a.so").use { input ->
                FileOutputStream(soFile).use { output -> input.copyTo(output) }
            }
            context.assets.open("cts/pixel_2023_experience.xml").use { input ->
                FileOutputStream(xmlFile).use { output -> input.copyTo(output) }
            }
            context.assets.open("cts/module.prop").use { input ->
                FileOutputStream(propFile).use { output -> input.copyTo(output) }
            }

            val installCmd = """
                mkdir -p $MODULE_DIR/zygisk
                mkdir -p $MODULE_DIR/system/etc/permissions
                cp ${propFile.absolutePath} $MODULE_DIR/module.prop
                cp ${soFile.absolutePath} $MODULE_DIR/zygisk/arm64-v8a.so
                cp ${xmlFile.absolutePath} $MODULE_DIR/system/etc/permissions/pixel_2023_experience.xml
                chmod 755 $MODULE_DIR
                chmod 755 $MODULE_DIR/zygisk
                chmod 755 $MODULE_DIR/system
                chmod 755 $MODULE_DIR/system/etc
                chmod 755 $MODULE_DIR/system/etc/permissions
                chmod 644 $MODULE_DIR/module.prop
                chmod 755 $MODULE_DIR/zygisk/arm64-v8a.so
                chmod 644 $MODULE_DIR/system/etc/permissions/pixel_2023_experience.xml
                rm -f $MODULE_DIR/disable
            """.trimIndent().replace("\n", " && ")

            RootShell.run(installCmd)
            Log.d(TAG, "Deployed Zygisk module to $MODULE_DIR")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed to deploy Zygisk module", e)
            false
        }
    }

    fun removeZygiskModule(): Boolean {
        val result = RootShell.run("rm -rf $MODULE_DIR")
        Log.d(TAG, "Removed Zygisk module from $MODULE_DIR: success=${result.success}")
        return result.success
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
