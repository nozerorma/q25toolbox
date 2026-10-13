package com.kgr.q25toolbox.modules

import android.content.Context
import android.provider.Settings
import androidx.annotation.StringRes
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.service.Q25AccessibilityService

/**
 * Forces the screen rotation to a fixed state, globally and per app.
 *
 * Mechanism: `accelerometer_rotation=0` + `user_rotation=N` (the same pair the quick-settings
 * "auto-rotate" tile writes), applied as root because WRITE_SETTINGS is not held. That alone
 * only wins over apps that do not declare an orientation; an app with `screenOrientation=
 * landscape` still overrides it. So the optional "override app requests" switch also sets
 * `wm set-ignore-orientation-request true` (Android 12L+; silently skipped where the
 * command does not exist), which makes the display follow the forced rotation and letterbox
 * the app instead.
 *
 * The service picks a target for the foreground app (per-app entry, else the general state)
 * and calls [apply]. When the target is [Mode.SYSTEM] everything we changed is put back.
 * The pre-takeover values are kept in the prefs rather than in memory, so a service that was
 * killed while forcing still restores the user's own settings on its next start.
 */
object RotationController {

    /** [value] is the `user_rotation` constant (Surface.ROTATION_*); null where it is not a lock. */
    enum class Mode(val key: String, val value: Int?, @StringRes val labelRes: Int) {
        SYSTEM("sys", null, R.string.rot_mode_system),
        AUTO("auto", null, R.string.rot_mode_auto),
        R0("0", 0, R.string.rot_mode_0),
        R90("90", 1, R.string.rot_mode_90),
        R180("180", 2, R.string.rot_mode_180),
        R270("270", 3, R.string.rot_mode_270);

        companion object {
            fun fromKey(k: String?): Mode? = entries.firstOrNull { it.key == k }
        }
    }

    private const val KEY_GENERAL = "rotation_general"        // Mode.key
    private const val KEY_APPS = "rotation_apps"              // StringSet "pkg=Mode.key"
    private const val KEY_OVERRIDE = "rotation_override_app"  // ignore the apps' orientation requests
    // Values to restore, present only while we hold the rotation (survives a killed service).
    private const val KEY_SAVED_ACCEL = "rotation_saved_accel"
    private const val KEY_SAVED_USER = "rotation_saved_user"
    private const val KEY_SAVED_IGNORE = "rotation_saved_ignore"  // 1 / 0 / -1 unsupported

    private fun prefs(context: Context) =
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)

    /** Prefs keys the service must react to. */
    fun isRotationKey(key: String) = key == KEY_GENERAL || key == KEY_APPS || key == KEY_OVERRIDE

    fun general(context: Context): Mode =
        Mode.fromKey(prefs(context).getString(KEY_GENERAL, null)) ?: Mode.SYSTEM

    fun setGeneral(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_GENERAL, mode.key).apply()
    }

    fun overrideAppRequests(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERRIDE, true)

    fun setOverrideAppRequests(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERRIDE, on).apply()
    }

    /** Per-app overrides. SYSTEM means "follow the general state" and is never stored. */
    fun entries(context: Context): Map<String, Mode> {
        val raw = prefs(context).getStringSet(KEY_APPS, emptySet()) ?: emptySet()
        return raw.mapNotNull { s ->
            val i = s.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val mode = Mode.fromKey(s.substring(i + 1))?.takeIf { it != Mode.SYSTEM }
                ?: return@mapNotNull null
            s.substring(0, i) to mode
        }.toMap()
    }

    fun setMode(context: Context, pkg: String, mode: Mode) {
        val map = entries(context).toMutableMap()
        if (mode == Mode.SYSTEM) map.remove(pkg) else map[pkg] = mode
        prefs(context).edit()
            .putStringSet(KEY_APPS, map.map { "${it.key}=${it.value.key}" }.toSet())
            .apply()
    }

    /** Target for [pkg] (null = no foreground app known): its own entry, else the general state. */
    fun desired(context: Context, pkg: String?): Mode =
        pkg?.let { entries(context)[it] } ?: general(context)

    /**
     * Pushes [mode] to the system. Blocks on root: call from a worker thread, never the main one.
     * Idempotent, and cheap (no root call) when there is nothing to do.
     */
    fun apply(context: Context, mode: Mode) {
        val p = prefs(context)
        val holding = p.contains(KEY_SAVED_ACCEL)

        if (mode == Mode.SYSTEM) {
            if (holding) restore(context)
            return
        }

        if (!holding) {
            // First takeover: remember what the user had. Reads need no root.
            val cr = context.contentResolver
            val ignore = readIgnoreOrientation()
            p.edit()
                .putInt(KEY_SAVED_ACCEL, Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1))
                .putInt(KEY_SAVED_USER, Settings.System.getInt(cr, Settings.System.USER_ROTATION, 0))
                .putInt(KEY_SAVED_IGNORE, ignore)
                .apply()
        }

        val cmd = StringBuilder()
        if (mode == Mode.AUTO) {
            cmd.append("settings put system accelerometer_rotation 1")
        } else {
            // user_rotation first, so the lock engages on the target and never flashes the old one.
            cmd.append("settings put system user_rotation ${mode.value} ; ")
            cmd.append("settings put system accelerometer_rotation 0")
        }
        val saved = p.getInt(KEY_SAVED_IGNORE, -1)
        if (saved >= 0) { // -1 = this Android has no such command
            // Auto-rotate has nothing to force, so it never needs the app's request overridden.
            val ignore = mode != Mode.AUTO && overrideAppRequests(context)
            cmd.append(" ; wm set-ignore-orientation-request ${ignore}")
        }
        RootShell.run(cmd.toString())
    }

    private fun restore(context: Context) {
        val p = prefs(context)
        val accel = p.getInt(KEY_SAVED_ACCEL, 1)
        val user = p.getInt(KEY_SAVED_USER, 0)
        val ignore = p.getInt(KEY_SAVED_IGNORE, -1)
        val cmd = StringBuilder("settings put system user_rotation $user ; settings put system accelerometer_rotation $accel")
        if (ignore >= 0) cmd.append(" ; wm set-ignore-orientation-request ${ignore == 1}")
        val ok = RootShell.run(cmd.toString()).success
        // Keep the saved values if root failed, so the next reconcile retries instead of forgetting them.
        if (ok) p.edit().remove(KEY_SAVED_ACCEL).remove(KEY_SAVED_USER).remove(KEY_SAVED_IGNORE).apply()
    }

    /** 1 / 0 for the current value, -1 when `wm get-ignore-orientation-request` is unavailable. */
    private fun readIgnoreOrientation(): Int {
        val r = RootShell.run("wm get-ignore-orientation-request")
        if (!r.success) return -1
        val s = r.outString.lowercase()
        return when {
            "true" in s -> 1
            "false" in s -> 0
            else -> -1
        }
    }
}
