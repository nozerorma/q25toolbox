package com.kgr.q25toolbox.modules

import android.content.SharedPreferences
import android.view.KeyEvent
import com.kgr.q25toolbox.core.RootShell

/**
 * Maps a spare physical key to KEYCODE_CTRL_RIGHT.
 *
 * Interception happens in Q25AccessibilityService.onKeyEvent(); injection is done
 * via `sendevent` into the keyboard device node, so all apps receive a real
 * hardware Ctrl event (correct modifier flags, correct source) rather than a
 * synthetic IME event that many apps ignore.
 *
 * Linux evdev code 97 = KEY_RIGHTCTRL, which the existing Q25_keyboard.kl
 * maps to KEYCODE_CTRL_RIGHT — so the injection goes through the normal kl
 * translation pipeline.
 */
object KeyRemapController {

    const val KEY_REMAP_ENABLED = "key_remap_enabled"
    const val KEY_REMAP_SOURCE  = "key_remap_source"

    /** Q25 hardware keyboard event device node (confirmed via getevent -p). */
    private const val KB_DEV = "/dev/input/event2"

    /** Linux evdev keycode for KEY_RIGHTCTRL (97 = 0x61). */
    private const val LINUX_CTRL_RIGHT = 97

    enum class SourceKey(
        val label: String,
        val description: String,
        val androidKeycode: Int
    ) {
        GRAVE(
            "Currency key",
            "The € / £ / $ key next to Space. Currently mismapped as backtick by the firmware — " +
            "remapping it to Ctrl doesn't affect normal typing.",
            KeyEvent.KEYCODE_GRAVE
        ),
        RSHIFT(
            "Right Shift",
            "The right-hand Shift key. Left Shift still works normally for uppercase.",
            KeyEvent.KEYCODE_SHIFT_RIGHT
        )
    }

    fun isEnabled(prefs: SharedPreferences) =
        prefs.getBoolean(KEY_REMAP_ENABLED, false)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) =
        prefs.edit().putBoolean(KEY_REMAP_ENABLED, enabled).apply()

    fun getSourceKey(prefs: SharedPreferences): SourceKey {
        val raw = prefs.getString(KEY_REMAP_SOURCE, SourceKey.GRAVE.name) ?: SourceKey.GRAVE.name
        return try { SourceKey.valueOf(raw) } catch (_: Exception) { SourceKey.GRAVE }
    }

    fun setSourceKey(prefs: SharedPreferences, key: SourceKey) =
        prefs.edit().putString(KEY_REMAP_SOURCE, key.name).apply()

    /**
     * Inject a KEY_RIGHTCTRL hardware event into the keyboard device node.
     * [action]: 1 = key down, 0 = key up.
     *
     * Must be called from a worker thread — RootShell.run() blocks until done.
     */
    fun injectCtrlRight(action: Int) {
        RootShell.run(
            "sendevent $KB_DEV 1 $LINUX_CTRL_RIGHT $action" +
            " ; sendevent $KB_DEV 0 0 0"
        )
    }
}
