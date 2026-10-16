package com.kgr.q25toolbox.modules

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.service.Q25AccessibilityService

/**
 * Controller for flexible in-app keyboard shortcuts and remaps.
 * Supports arbitrary key capture (single keys, modifier alone, or combos),
 * with short press, long press (~500ms) and double press detection,
 * optional single-tap pass-through / reinjection, and centralized action dispatch.
 */
object KeyboardShortcutsController {

    private const val TAG = "KeyboardShortcuts"
    private const val PREF_PREFIX = "kb_sc_"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    enum class ClickType(@StringRes val labelRes: Int) {
        SHORT(R.string.shortcut_click_short),
        LONG(R.string.shortcut_click_long),
        DOUBLE(R.string.shortcut_click_double);

        companion object {
            fun parse(s: String?): ClickType =
                entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: SHORT
        }
    }

    data class ShortcutConfig(
        val action: GestureSettings.Action,
        val enabled: Boolean = false,
        val keyCode: Int = 0,
        val modifiers: Int = 0,
        val clickType: ClickType = ClickType.SHORT,
        val tapPass: Boolean = true,
        val vibrate: Boolean = true
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)

    // --- Persistence ---

    fun getShortcut(context: Context, action: GestureSettings.Action): ShortcutConfig {
        val p = prefs(context)
        val act = action.name.lowercase()
        val enabledKey = "${PREF_PREFIX}${act}_enabled"

        if (!p.contains(enabledKey)) {
            // Backwards compatibility fallback for ASPECT_RATIO
            if (action == GestureSettings.Action.ASPECT_RATIO && ResolutionHotkey.isEnabled(context)) {
                val combo = ResolutionHotkey.combo(context)
                val press = ResolutionHotkey.press(context)
                return ShortcutConfig(
                    action = action,
                    enabled = true,
                    keyCode = combo.keyCode,
                    modifiers = combo.mods,
                    clickType = if (press == ResolutionHotkey.Press.LONG) ClickType.LONG else ClickType.SHORT,
                    tapPass = ResolutionHotkey.tapPass(context),
                    vibrate = ResolutionHotkey.vibrate(context)
                )
            }
            return ShortcutConfig(action = action)
        }

        return ShortcutConfig(
            action = action,
            enabled = p.getBoolean(enabledKey, false),
            keyCode = p.getInt("${PREF_PREFIX}${act}_keycode", 0),
            modifiers = p.getInt("${PREF_PREFIX}${act}_mods", 0),
            clickType = ClickType.parse(p.getString("${PREF_PREFIX}${act}_click", null)),
            tapPass = p.getBoolean("${PREF_PREFIX}${act}_tappass", true),
            vibrate = p.getBoolean("${PREF_PREFIX}${act}_vibrate", true)
        )
    }

    fun setShortcut(context: Context, config: ShortcutConfig) {
        val p = prefs(context)
        val act = config.action.name.lowercase()
        p.edit()
            .putBoolean("${PREF_PREFIX}${act}_enabled", config.enabled)
            .putInt("${PREF_PREFIX}${act}_keycode", config.keyCode)
            .putInt("${PREF_PREFIX}${act}_mods", config.modifiers)
            .putString("${PREF_PREFIX}${act}_click", config.clickType.name)
            .putBoolean("${PREF_PREFIX}${act}_tappass", config.tapPass)
            .putBoolean("${PREF_PREFIX}${act}_vibrate", config.vibrate)
            .apply()

        // Keep ResolutionHotkey in sync if updating ASPECT_RATIO
        if (config.action == GestureSettings.Action.ASPECT_RATIO) {
            ResolutionHotkey.setEnabled(context, config.enabled)
            if (config.keyCode != 0) {
                ResolutionHotkey.setCombo(context, ResolutionHotkey.Combo(config.modifiers, config.keyCode))
            }
            ResolutionHotkey.setPress(
                context,
                if (config.clickType == ClickType.LONG) ResolutionHotkey.Press.LONG else ResolutionHotkey.Press.SHORT
            )
            ResolutionHotkey.setTapPass(context, config.tapPass)
            ResolutionHotkey.setVibrate(context, config.vibrate)
        }
    }

    val SUPPORTED_ACTIONS = listOf(
        GestureSettings.Action.RECENTS,
        GestureSettings.Action.QR_ENLARGER,
        GestureSettings.Action.ASPECT_RATIO,
        GestureSettings.Action.ROTATION,
        GestureSettings.Action.EXTRA_DIM,
        GestureSettings.Action.GESTURES,
        GestureSettings.Action.CIRCLE_TO_SEARCH,
    )

    fun getAllShortcuts(context: Context): List<ShortcutConfig> =
        SUPPORTED_ACTIONS.map { getShortcut(context, it) }

    // --- Key Recording State ---

    @Volatile var isRecording: Boolean = false
        private set

    private var recordCallback: ((keyCode: Int, modifiers: Int) -> Unit)? = null
    private var recordedKey: Int? = null
    private var recordedMods: Int = 0

    fun startRecording(callback: (keyCode: Int, modifiers: Int) -> Unit) {
        recordCallback = callback
        recordedKey = null
        recordedMods = 0
        isRecording = true
        Log.d(TAG, "Started key recording")
    }

    fun stopRecording() {
        isRecording = false
        recordCallback = null
        recordedKey = null
        recordedMods = 0
        Log.d(TAG, "Stopped key recording")
    }

    fun dispatchRecordKeyEvent(event: KeyEvent): Boolean {
        if (!isRecording) return false
        val kc = event.keyCode
        val mods = ResolutionHotkey.modsOf(event.metaState)
        val isMod = isModifierKey(kc)

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!isMod) {
                recordedKey = kc
                recordedMods = mods
            } else {
                recordedMods = mods
            }
            return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            val finalKey = recordedKey ?: (if (isMod) kc else null)
            val finalMods = if (recordedKey != null) recordedMods else 0
            if (finalKey != null) {
                val cb = recordCallback
                stopRecording()
                cb?.invoke(finalKey, finalMods)
                return true
            }
        }
        return true
    }

    fun isModifierKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> true
        else -> false
    }

    fun formatKeyLabel(context: Context, keyCode: Int, modifiers: Int): String {
        if (keyCode == 0) return context.getString(R.string.shortcut_unassigned)
        val modNames = ResolutionHotkey.MODIFIERS
            .filter { modifiers and it.first != 0 }
            .map { it.second }

        val keyName = when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> context.getString(R.string.key_label_prog_red)
            KeyEvent.KEYCODE_APP_SWITCH -> context.getString(R.string.key_label_app_switch)
            KeyEvent.KEYCODE_BACK -> context.getString(R.string.key_label_back)
            KeyEvent.KEYCODE_HOME -> context.getString(R.string.key_label_home)
            KeyEvent.KEYCODE_GRAVE -> context.getString(R.string.key_label_grave)
            KeyEvent.KEYCODE_SYM -> "SYM"
            KeyEvent.KEYCODE_SEARCH -> context.getString(R.string.key_label_search)
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_VOLUME_UP -> "Vol +"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "Vol -"
            KeyEvent.KEYCODE_CAMERA -> context.getString(R.string.key_label_camera)
            KeyEvent.KEYCODE_CTRL_LEFT -> "Left Ctrl"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "Right Ctrl"
            KeyEvent.KEYCODE_ALT_LEFT -> "Left Alt"
            KeyEvent.KEYCODE_ALT_RIGHT -> "Right Alt"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "Left Shift"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "Right Shift"
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }

        return if (modNames.isNotEmpty()) {
            (modNames + keyName).joinToString(" + ")
        } else {
            keyName
        }
    }

    // --- Runtime Key Handling Engine (Q25AccessibilityService) ---

    private var activeLongShortcut: ShortcutConfig? = null
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        activeLongShortcut?.let { sc ->
            longPressFired = true
            currentService?.let { svc ->
                Log.d(TAG, "Long press fired for ${sc.action}")
                svc.performEdgeAction(sc.action)
                if (sc.vibrate) vibrate(svc)
            }
        }
    }

    private var lastTapTime = 0L
    private var lastTapKey = 0
    private var lastTapMods = 0
    private var pendingDoubleShortcut: ShortcutConfig? = null

    private val singleTapFallbackRunnable = Runnable {
        val sc = pendingDoubleShortcut
        pendingDoubleShortcut = null
        if (sc != null && sc.tapPass) {
            reinjectKey(sc.keyCode, sc.modifiers)
        }
    }

    private var currentService: Q25AccessibilityService? = null
    private var lastConsumedDownKey = 0

    fun onKeyEvent(service: Q25AccessibilityService, event: KeyEvent): Boolean {
        currentService = service

        // If recording dialog is active, intercept and pass to recording handler
        if (isRecording) {
            return dispatchRecordKeyEvent(event)
        }

        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (event.deviceId < 0 || km?.isKeyguardLocked == true) return false

        val kc = event.keyCode
        val mods = ResolutionHotkey.modsOf(event.metaState)

        // Handle key release for long press
        if (event.action == KeyEvent.ACTION_UP) {
            if (activeLongShortcut != null && activeLongShortcut?.keyCode == kc) {
                mainHandler.removeCallbacks(longPressRunnable)
                val sc = activeLongShortcut
                activeLongShortcut = null
                if (!longPressFired && sc != null && sc.tapPass) {
                    reinjectKey(sc.keyCode, sc.modifiers)
                }
                return true
            }
            if (lastConsumedDownKey == kc) {
                lastConsumedDownKey = 0
                return true
            }
            return false
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Match against active configured shortcuts
        val activeShortcuts = getAllShortcuts(service).filter { it.enabled && it.keyCode != 0 }
        val matched = activeShortcuts.firstOrNull { it.keyCode == kc && it.modifiers == mods } ?: return false

        lastConsumedDownKey = kc

        when (matched.clickType) {
            ClickType.SHORT -> {
                if (event.repeatCount == 0) {
                    Log.d(TAG, "Short press fired for ${matched.action}")
                    service.performEdgeAction(matched.action)
                    if (matched.vibrate) vibrate(service)
                }
                return true
            }
            ClickType.LONG -> {
                if (event.repeatCount == 0) {
                    activeLongShortcut = matched
                    longPressFired = false
                    mainHandler.postDelayed(longPressRunnable, ResolutionHotkey.LONG_MS)
                }
                return true
            }
            ClickType.DOUBLE -> {
                if (event.repeatCount == 0) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTapTime <= 350L && lastTapKey == kc && lastTapMods == mods) {
                        // Double tap triggered
                        mainHandler.removeCallbacks(singleTapFallbackRunnable)
                        lastTapTime = 0L
                        lastTapKey = 0
                        lastTapMods = 0
                        pendingDoubleShortcut = null
                        Log.d(TAG, "Double press fired for ${matched.action}")
                        service.performEdgeAction(matched.action)
                        if (matched.vibrate) vibrate(service)
                    } else {
                        // First tap of double press
                        lastTapTime = now
                        lastTapKey = kc
                        lastTapMods = mods
                        pendingDoubleShortcut = matched
                        if (matched.tapPass) {
                            mainHandler.postDelayed(singleTapFallbackRunnable, 350L)
                        }
                    }
                }
                return true
            }
        }
    }

    private fun reinjectKey(keyCode: Int, modifiers: Int) {
        val cmd = if (modifiers != 0) {
            ResolutionHotkey.reinjectCommand(ResolutionHotkey.Combo(modifiers, keyCode))
        } else {
            "input keyevent $keyCode"
        }
        Thread {
            try {
                RootShell.run(cmd)
            } catch (t: Throwable) {
                Log.w(TAG, "Reinject key failed", t)
            }
        }.start()
    }

    fun vibrate(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(35L)
            }
        } catch (_: Throwable) {
        }
    }
}
