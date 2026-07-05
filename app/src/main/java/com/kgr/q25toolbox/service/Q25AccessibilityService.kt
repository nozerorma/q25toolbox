package com.kgr.q25toolbox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.inputfix.CalculatorInputFix
import com.kgr.q25toolbox.inputfix.ComposerEnterKeyHandler
import com.kgr.q25toolbox.modules.AppScalingController
import com.kgr.q25toolbox.modules.KeyRemapController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's single accessibility service - the home for every feature that has
 * to observe ongoing window/IME/foreground state or intercept physical keys,
 * since root can execute commands but can't be told "notify me when X happens".
 *
 * It drives:
 * - **Lockscreen PIN**: maps physical-keyboard presses to taps on the SystemUI
 *   PIN pad so the PIN can be typed on the hardware keyboard.
 * - **Chat Enter-to-Send / Calculator Keys**: physical-key fixes ported from
 *   nozerorma/q25-input-helper (dispatched in onKeyEvent).
 * - **Per-App Keyboard Block**: in selected apps, switch the default IME to a
 *   do-nothing passthrough keyboard so physical keys reach the app raw.
 * - **Per-App Display Scaling**: switch the global `wm size` to a per-app target
 *   resolution while that app is foreground, resetting on exit.
 *
 * Each feature has independent settings in SharedPreferences ("q25tweaks").
 * Root commands go through RootShell (libsu) to share one root path with the
 * rest of the app.
 */
class Q25AccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "q25tweaks"
        const val KEY_PIN_INPUT = "pin_input_enabled"
        const val KEY_CHAT_COMPOSER = "chat_composer_enabled" // Enter-to-send in chat apps
        const val KEY_CALCULATOR = "calculator_enabled"       // route number/operator keys to calculators
        const val KEY_IME_BLOCK = "ime_block_enabled"     // bypass IME in selected apps
        const val KEY_IME_BLOCK_APPS = "ime_block_apps"   // StringSet of package names
        const val KEY_IME_SAVED = "ime_block_saved_ime"   // IME to restore when leaving a blocked app
        const val KEY_SCALING_APPS = "scaling_apps"       // StringSet "pkg=width" for per-app resolution

        // Our do-nothing IME: while it's active, physical key presses go straight
        // to the app instead of being intercepted/translated by the normal keyboard.
        const val PASSTHRU_IME = "com.kgr.q25toolbox/.service.Q25PassthroughIme"
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debounceForegroundRunnable = Runnable { checkForeground() }

    // Resets resolution to native when the screen turns off (lock button).
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                foregroundPkg = null
                reconcileScaling()
            }
        }
    }

    // Ported physical-key fixes from nozerorma/q25-input-helper. Each inspects
    // the foreground app itself and no-ops outside its target apps.
    private val composerHandler = ComposerEnterKeyHandler(
        ComposerEnterKeyHandler.defaultSupportedPackages(),
        ComposerEnterKeyHandler.defaultSendButtonMatchers()
    )
    private val calculatorFix = CalculatorInputFix()

    // Per-app display scaling: last global resolution we pushed via `wm size`,
    // as its encoded "WxH" key ("" = unknown, so the first reconcile applies).
    @Volatile private var currentScaleKey = ""
    // True while a `wm size` command is still running on the worker thread;
    // a second resolution change is skipped if one is already in-flight so we
    // don't get stuck waiting on the single-thread executor.
    private val resolvingScale = AtomicBoolean(false)

    @Volatile private var imeBlockApplied = false // last show_ime value we pushed (true = suppressed)
    @Volatile private var foregroundPkg: String? = null // last seen foreground app package
    private var prefs: SharedPreferences? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (key == KEY_IME_BLOCK || key == KEY_IME_BLOCK_APPS) {
            reconcileImeBlock()
        }
        if (key == KEY_SCALING_APPS) {
            reconcileScaling()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(prefListener)

        serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        }

        worker.execute {
            val curIme = RootShell.run("settings get secure default_input_method")
                .outString.trim()
            imeBlockApplied = (curIme == PASSTHRU_IME)
        }

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    private fun pinInputEnabled() = prefs?.getBoolean(KEY_PIN_INPUT, true) ?: true
    private fun chatComposerEnabled() = prefs?.getBoolean(KEY_CHAT_COMPOSER, false) ?: false
    private fun calculatorEnabled() = prefs?.getBoolean(KEY_CALCULATOR, false) ?: false
    private fun imeBlockEnabled() = prefs?.getBoolean(KEY_IME_BLOCK, false) ?: false
    private fun imeBlockApps(): Set<String> =
        prefs?.getStringSet(KEY_IME_BLOCK_APPS, emptySet()) ?: emptySet()

    // ------------------------------------------------------- Foreground tracking

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = foregroundAppPackage()
        if (pkg != null && pkg != foregroundPkg) {
            foregroundPkg = pkg
            reconcileImeBlock()
            reconcileScaling()
        }
    }

    private fun checkForeground() {
        val pkg = foregroundAppPackage()
        if (pkg != foregroundPkg) {
            foregroundPkg = pkg
            reconcileImeBlock()
            reconcileScaling()
        }
    }

    /**
     * The package of the focused/active TYPE_APPLICATION window - i.e. the app
     * behind any keyboard. Reading the application window (not the event source)
     * keeps this stable while the IME window comes and goes.
     */
    private fun foregroundAppPackage(): String? {
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return null
        } catch (_: Exception) {
            return null
        }
        for (w in windowList) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString()
                root.recycle()
                if (pkg != null) return pkg
            }
        }
        return null
    }

    // --------------------------------------------------------- Per-app scaling

    /**
     * Apply the target global resolution for the current foreground app (or the
     * native size when it has no scaling entry), if it differs from what we last
     * pushed. Runs `wm size` on the worker thread.
     */
    private fun reconcileScaling() {
        val desired = foregroundPkg
            ?.let { AppScalingController.entries(this)[it] }
            ?: AppScalingController.NATIVE
        if (desired.encode() == currentScaleKey) return
        if (!resolvingScale.compareAndSet(false, true)) return // already in-flight, skip
        currentScaleKey = desired.encode()
        worker.execute {
            try {
                AppScalingController.applyResolution(desired)
            } finally {
                resolvingScale.set(false)
            }
        }
    }

    /** Restore the native resolution, run synchronously on teardown. */
    private fun restoreScaling() {
        if (currentScaleKey == AppScalingController.NATIVE.encode()) return
        currentScaleKey = AppScalingController.NATIVE.encode()
        resolvingScale.set(true)
        try {
            AppScalingController.applyResolution(AppScalingController.NATIVE)
        } finally {
            resolvingScale.set(false)
        }
    }

    // --------------------------------------------------------------- IME Block

    /** Switch to / from the passthrough IME based on the current foreground app. */
    private fun reconcileImeBlock() {
        val desired = imeBlockEnabled() && foregroundPkg?.let { it in imeBlockApps() } == true
        if (desired == imeBlockApplied) return
        imeBlockApplied = desired
        worker.execute { applyImeBlock(desired) }
    }

    /**
     * Bypass the keyboard for selected apps by switching the default input method
     * to a do-nothing passthrough IME, so physical key presses reach the app raw
     * instead of being intercepted/translated by the normal keyboard. Restores
     * the previously active IME on the way out (or the system default if we have
     * nothing saved).
     */
    private fun applyImeBlock(bypass: Boolean) {
        try {
            val current = RootShell.run("settings get secure default_input_method")
                .outString.trim()
            if (bypass) {
                if (current != PASSTHRU_IME) {
                    if (current.isNotEmpty() && current != "null") {
                        prefs?.edit()?.putString(KEY_IME_SAVED, current)?.apply()
                    }
                    RootShell.run("ime enable $PASSTHRU_IME ; ime set $PASSTHRU_IME")
                }
            } else if (current == PASSTHRU_IME) {
                val saved = prefs?.getString(KEY_IME_SAVED, null)
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                if (saved != null) {
                    RootShell.run("ime set $saved")
                } else {
                    RootShell.run("ime reset") // no saved IME: fall back to the system default
                }
            }
            Log.d("Q25Toolbox", "applyImeBlock: bypass=$bypass, was=$current")
        } catch (e: Exception) {
            Log.e("Q25Toolbox", "applyImeBlock failed for bypass=$bypass", e)
        }
    }

    // -------------------------------------------------- Physical key handling

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val kc = event.keyCode

        // Key remap: intercept the configured source key and inject CTRL_RIGHT into
        // the hardware event device so all apps receive a proper modifier event.
        if (prefs?.getBoolean(KeyRemapController.KEY_REMAP_ENABLED, false) == true) {
            val sourceKc = KeyRemapController.getSourceKey(prefs!!).androidKeycode
            if (kc == sourceKc) {
                // Inject only on the first down and on up; consume repeats silently.
                if (event.action == KeyEvent.ACTION_UP ||
                    (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)) {
                    val action = if (event.action == KeyEvent.ACTION_DOWN) 1 else 0
                    worker.execute { KeyRemapController.injectCtrlRight(action) }
                }
                return true
            }
        }
        // Ported q25-input-helper fixes. Each checks the foreground app itself,
        // so they're safe to call for every key and no-op elsewhere. Calculator
        // claims digit/operator keys; chat composer claims Enter - disjoint, so
        // order between them doesn't matter.
        if (calculatorEnabled() && calculatorFix.onKeyEvent(this, event)) return true
        if (chatComposerEnabled() && composerHandler.onKeyEvent(this, event)) return true

        // PIN Input: map physical keys to the lockscreen PIN pad.
        if (!pinInputEnabled()) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!isDeviceLocked()) return false

        val input = toPinInput(kc) ?: return false

        val root = rootInActiveWindow ?: return false
        try {
            val pinView = findSingleNode(root, "com.android.systemui:id/keyguard_pin_view") ?: return false
            try {
                val pkgName = pinView.packageName?.toString()
                if (pkgName != "com.android.systemui") {
                    return false
                }

                val buttonId = pinButtonId(input)
                val button = findSingleNodeInTree(pinView, buttonId, pinButtonFallbackLabels(input)) ?: return false
                try {
                    if (!button.isClickable) return false
                    if (event.repeatCount > 0) return true
                    return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } finally {
                    button.recycle()
                }
            } finally {
                pinView.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    enum class PinInput { DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9, ENTER, DELETE }

    private fun toPinInput(kc: Int): PinInput? {
        return when (kc) {
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_1 -> PinInput.DIGIT_1
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_2 -> PinInput.DIGIT_2
            KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_3 -> PinInput.DIGIT_3
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_4 -> PinInput.DIGIT_4
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_5 -> PinInput.DIGIT_5
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_6 -> PinInput.DIGIT_6
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_7 -> PinInput.DIGIT_7
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_8 -> PinInput.DIGIT_8
            KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_9 -> PinInput.DIGIT_9
            KeyEvent.KEYCODE_0 -> PinInput.DIGIT_0
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> PinInput.ENTER
            KeyEvent.KEYCODE_DEL -> PinInput.DELETE
            else -> null
        }
    }

    private fun pinButtonId(input: PinInput): String {
        return when (input) {
            PinInput.DIGIT_0 -> "com.android.systemui:id/key0"
            PinInput.DIGIT_1 -> "com.android.systemui:id/key1"
            PinInput.DIGIT_2 -> "com.android.systemui:id/key2"
            PinInput.DIGIT_3 -> "com.android.systemui:id/key3"
            PinInput.DIGIT_4 -> "com.android.systemui:id/key4"
            PinInput.DIGIT_5 -> "com.android.systemui:id/key5"
            PinInput.DIGIT_6 -> "com.android.systemui:id/key6"
            PinInput.DIGIT_7 -> "com.android.systemui:id/key7"
            PinInput.DIGIT_8 -> "com.android.systemui:id/key8"
            PinInput.DIGIT_9 -> "com.android.systemui:id/key9"
            PinInput.ENTER -> "com.android.systemui:id/key_enter"
            PinInput.DELETE -> "com.android.systemui:id/delete_button"
        }
    }

    private fun pinButtonFallbackLabels(input: PinInput): List<String> {
        return when (input) {
            PinInput.DIGIT_0 -> listOf("0")
            PinInput.DIGIT_1 -> listOf("1")
            PinInput.DIGIT_2 -> listOf("2")
            PinInput.DIGIT_3 -> listOf("3")
            PinInput.DIGIT_4 -> listOf("4")
            PinInput.DIGIT_5 -> listOf("5")
            PinInput.DIGIT_6 -> listOf("6")
            PinInput.DIGIT_7 -> listOf("7")
            PinInput.DIGIT_8 -> listOf("8")
            PinInput.DIGIT_9 -> listOf("9")
            PinInput.DELETE -> listOf("delete", "backspace")
            PinInput.ENTER -> listOf("enter", "confirm", "ok")
        }
    }

    private fun findSingleNode(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return null
        if (nodes.size != 1) {
            for (node in nodes) node.recycle()
            return null
        }
        val result = nodes[0]
        for (i in 1 until nodes.size) {
            nodes[i].recycle()
        }
        return result
    }

    private fun findSingleNodeInTree(
        root: AccessibilityNodeInfo?,
        viewId: String,
        fallbackTexts: List<CharSequence>
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        var match: AccessibilityNodeInfo? = null
        val rootViewId = root.viewIdResourceName
        if (rootViewId != null && viewId.contentEquals(rootViewId)) {
            match = AccessibilityNodeInfo.obtain(root)
        } else if (isActionableMatch(root, fallbackTexts)) {
            match = AccessibilityNodeInfo.obtain(root)
        }

        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            try {
                val childMatch = findSingleNodeInTree(child, viewId, fallbackTexts)
                if (childMatch != null) {
                    if (match != null) {
                        match.recycle()
                        childMatch.recycle()
                        return null
                    }
                    match = childMatch
                }
            } finally {
                child.recycle()
            }
        }

        return match
    }

    private fun isActionableMatch(node: AccessibilityNodeInfo?, expectedTexts: List<CharSequence>): Boolean {
        if (node == null || expectedTexts.isEmpty()) return false
        return node.isClickable && hasTextInTree(node, expectedTexts)
    }

    private fun hasTextInTree(node: AccessibilityNodeInfo?, expectedTexts: List<CharSequence>): Boolean {
        if (node == null) return false
        val contentDescription = node.contentDescription
        if (contentDescription != null && expectedTexts.any { it.toString() == contentDescription.toString() }) return true

        val nodeText = node.text
        if (nodeText != null && expectedTexts.any { it.toString() == nodeText.toString() }) return true

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (hasTextInTree(child, expectedTexts)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    // ------------------------------------------------------------- Lifecycle

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        restoreImeBlock()  // never leave the soft keyboard globally suppressed
        restoreScaling()   // never leave the screen stuck at a scaled resolution
        return super.onUnbind(intent)
    }

    /** Re-enable the soft keyboard if we'd suppressed it, run synchronously on teardown. */
    private fun restoreImeBlock() {
        if (!imeBlockApplied) return
        imeBlockApplied = false
        applyImeBlock(false)
    }

    override fun onDestroy() {
        restoreImeBlock()
        restoreScaling()
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        worker.shutdown()
        super.onDestroy()
    }
}
