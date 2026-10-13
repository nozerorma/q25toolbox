package com.kgr.q25toolbox.modules

import android.content.Context
import android.content.SharedPreferences
import com.kgr.q25toolbox.core.AssetInstaller
import com.kgr.q25toolbox.core.RomProfile
import com.kgr.q25toolbox.core.RootShell

/**
 * Root path for the lockscreen keyboard features (PIN digits from the Q25's letter keys, Enter opens the PIN
 * pad): a daemon in /data/adb/service.d (from pin_keys_template.sh) that reads the keyboard node and injects.
 *
 * Why it exists next to the accessibility path: the accessibility service is not direct-boot aware, so after a
 * reboot it does not run until the first unlock, which is exactly when the PIN is needed; and every key press in
 * every app costs the service a binder round trip. With root available the daemon wins and the accessibility
 * path leaves it the letter digits ([isActive], [isLetterDigit]); without root nothing changes.
 *
 * Limits: the daemon only observes (it cannot swallow a key), so it covers letter digits and "open the pad" and
 * leaves raw digits, Enter and Delete to the accessibility path.
 */
object PinKeysRoot {

    const val KEY_PIN_INPUT = "pin_input_enabled"
    const val KEY_ENTER_OPENS = "lockscreen_enter_opens_pin"
    /** Set once the daemon has been verified running; the accessibility service reads it per key (a pref read, no shell). */
    const val KEY_ROOT_ACTIVE = "pin_keys_root_active"

    private const val SCRIPT_NAME = "pin_keys.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "pin_keys_template.sh"
    private const val LOCK = "/data/adb/.pin_keys.lock"

    /** Same defaults as the accessibility path: PIN on, Enter-opens on only where it was verified (Lineage). */
    fun pinWanted(prefs: SharedPreferences) = prefs.getBoolean(KEY_PIN_INPUT, true)
    fun enterOpensWanted(prefs: SharedPreferences) =
        prefs.getBoolean(KEY_ENTER_OPENS, RomProfile.autoDetectedLineage())

    /** The letter keys the daemon turns into digits (W E R / S D F / Z X C = 1..9); the accessibility path skips them. */
    fun isLetterDigit(keyCode: Int) = when (keyCode) {
        android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_E, android.view.KeyEvent.KEYCODE_R,
        android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.KEYCODE_D, android.view.KeyEvent.KEYCODE_F,
        android.view.KeyEvent.KEYCODE_Z, android.view.KeyEvent.KEYCODE_X, android.view.KeyEvent.KEYCODE_C -> true
        else -> false
    }

    /** Whether the accessibility path must leave the letter digits to the daemon (cheap: a pref read). */
    fun isActive(prefs: SharedPreferences?) = prefs?.getBoolean(KEY_ROOT_ACTIVE, false) ?: false

    /** Pure, for tests: the script as installed for these settings. */
    internal fun render(raw: String, pin: Boolean, enterOpens: Boolean) =
        raw.replace("__PIN__", if (pin) "1" else "0").replace("__ENTER_OPENS__", if (enterOpens) "1" else "0")

    private fun isRunning(): Boolean =
        RootShell.run("pgrep -f $SCRIPT_NAME >/dev/null 2>&1 && echo yes || echo no").outString.trim() == "yes"

    /**
     * Brings the daemon in line with the prefs: installed and running when either feature is wanted, removed
     * otherwise, and restarted when the installed script is stale. Blocking (root shell): call off the main thread.
     * Also publishes [KEY_ROOT_ACTIVE]. A no-op returning false when root is unavailable.
     */
    @Synchronized
    fun reconcile(context: Context, prefs: SharedPreferences): Boolean {
        if (!RootShell.isRootAvailable()) {
            prefs.edit().putBoolean(KEY_ROOT_ACTIVE, false).apply()
            return false
        }
        val pin = pinWanted(prefs)
        val enter = enterOpensWanted(prefs)
        if (!pin && !enter) {
            RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")
            AssetInstaller.removeFile(TARGET)
            prefs.edit().putBoolean(KEY_ROOT_ACTIVE, false).apply()
            return false
        }
        val healthy = isRunning() &&
            AssetInstaller.matchesAsset(context, TEMPLATE_ASSET, TARGET) { render(it, pin, enter) }
        if (!healthy) {
            // kill + pgrep: pkill was unreliable on this toybox (see BtIdleController). setsid: survive the libsu session.
            RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")
            AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { render(it, pin, enter) }
            RootShell.run("nohup setsid sh $TARGET </dev/null >/dev/null 2>&1 &")
        }
        val up = isRunning()
        prefs.edit().putBoolean(KEY_ROOT_ACTIVE, up).apply()
        return up
    }

    /** Stops and removes the daemon (used when the whole feature is turned off). */
    fun remove(prefs: SharedPreferences) {
        RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")
        AssetInstaller.removeFile(TARGET)
        prefs.edit().putBoolean(KEY_ROOT_ACTIVE, false).apply()
    }
}
