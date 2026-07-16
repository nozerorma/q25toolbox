package com.kgr.q25toolbox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import android.os.Bundle
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
import com.kgr.q25toolbox.modules.AutoFocusController
import com.kgr.q25toolbox.modules.BatteryUsageController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        const val KEY_IN_CALL_SHORTCUTS = "in_call_shortcuts_enabled"
        const val KEY_IME_SUGGESTIONS = "ime_suggestions_enabled" // Ctrl+W/E/R picks IME suggestion 1/2/3


        // Our do-nothing IME: while it's active, physical key presses go straight
        // to the app instead of being intercepted/translated by the normal keyboard.
        const val PASSTHRU_IME = "com.kgr.q25toolbox/.service.Q25PassthroughIme"
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    // IME switching (e.g. entering the per-app keyboard block) is latency-critical - the user
    // can start typing the moment the new app appears - so it gets its own executor rather than
    // sharing `worker` with slower, poll-based tasks (auto-focus's focus-and-verify loop, the
    // in-call dialpad-open poll). Those can legitimately take over a second, and queuing behind
    // one on the same single thread was delaying the IME switch by that much, which is exactly
    // what caused the "first keypress after switching apps doesn't register" symptom.
    private val imeWorker: ExecutorService = Executors.newSingleThreadExecutor()
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

    // True once the configured reset threshold has been crossed for the current plug-in
    // session, so a single crossing only triggers one reset (not one per broadcast) until the
    // level drops back below the threshold (e.g. unplugged, or a fresh charge from lower).
    private var batteryThresholdArmed = false

    // Auto-resets battery usage stats once the level reaches the configured threshold while
    // charging - a substitute for BATTERY_STATUS_FULL, which this device's charging driver
    // never reports (see BatteryUsageController.resetStats doc).
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val percent = level * 100 / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val threshold = BatteryUsageController.getResetThreshold(this@Q25AccessibilityService)

            if (charging && percent >= threshold) {
                if (!batteryThresholdArmed) {
                    batteryThresholdArmed = true
                    worker.execute { BatteryUsageController.resetStats() }
                }
            } else if (percent < threshold) {
                batteryThresholdArmed = false
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
    private var consumedAutofocusKeycode = -1
    // Set right before a focus-and-type attempt starts waiting, so onAccessibilityEvent can
    // wake it the instant the target field actually gets input focus (see onKeyEvent / onAccessibilityEvent).
    @Volatile private var focusLatch: CountDownLatch? = null
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

        imeWorker.execute {
            val curIme = RootShell.run("settings get secure default_input_method")
                .outString.trim()
            imeBlockApplied = (curIme == PASSTHRU_IME)
        }

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun pinInputEnabled() = prefs?.getBoolean(KEY_PIN_INPUT, true) ?: true
    private fun chatComposerEnabled() = prefs?.getBoolean(KEY_CHAT_COMPOSER, false) ?: false
    private fun calculatorEnabled() = prefs?.getBoolean(KEY_CALCULATOR, false) ?: false
    private fun imeSuggestionsEnabled() = prefs?.getBoolean(KEY_IME_SUGGESTIONS, false) ?: false
    private fun imeBlockEnabled() = prefs?.getBoolean(KEY_IME_BLOCK, false) ?: false
    private fun imeBlockApps(): Set<String> =
        prefs?.getStringSet(KEY_IME_BLOCK_APPS, emptySet()) ?: emptySet()
    private fun inCallShortcutsEnabled() = prefs?.getBoolean(KEY_IN_CALL_SHORTCUTS, false) ?: false


    // ------------------------------------------------------- Foreground tracking

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = foregroundAppPackage()
        if (pkg != null && pkg != foregroundPkg) {
            foregroundPkg = pkg
            reconcileImeBlock()
            reconcileScaling()
        }

        if (inCallShortcutsEnabled() && isGoogleDialerForeground()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                autoOpenDialpad()
            }
        }

        // Wake up a pending auto-focus wait (see onKeyEvent) as soon as the field it's
        // waiting on actually receives input focus, instead of it finding out only on
        // its next poll tick.
        focusLatch?.let { latch ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                rootInActiveWindow?.let { r ->
                    try {
                        val focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        val isEditable = focused?.let { AutoFocusController.isEditableTextField(it) } ?: false
                        focused?.recycle()
                        if (isEditable) latch.countDown()
                    } finally {
                        r.recycle()
                    }
                }
            }
        }
    }

    private fun isGoogleDialerForeground(): Boolean {
        val pkg = foregroundPkg ?: return false
        return pkg == "com.google.android.dialer" || pkg == "com.google.android.apps.dialer"
    }

    /**
     * True only for the Dialpad tab's actual phone-number EditText, not other dialer-app
     * screens (Contacts search, Favorites/Home, Recents) that share the same foreground
     * package. Confirmed via uiautomator dump: resource-id "com.google.android.dialer:id/digits".
     */
    private fun isDialpadDigitsField(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: return false
        return id == "com.google.android.dialer:id/digits" || id == "com.google.android.apps.dialer:id/digits"
    }

    private fun isAutoFocusEnabledForForeground(): Boolean {
        val prefs = prefs ?: return false
        if (!AutoFocusController.isEnabled(prefs)) return false
        val pkg = foregroundPkg ?: return false
        return pkg in AutoFocusController.getSelectedApps(prefs)
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
        imeWorker.execute { applyImeBlock(desired) }
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

        // IME suggestion shortcuts: Ctrl+W/E/R picks suggestion 1/2/3 from the keyboard's
        // candidate strip. Only consumes the key if a suggestion was actually found and
        // clicked, so Ctrl+W/E/R still behaves normally (e.g. closing a browser tab) when
        // no suggestions are showing.
        if (imeSuggestionsEnabled() && event.isCtrlPressed && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val suggestionIndex = when (kc) {
                KeyEvent.KEYCODE_W -> 0
                KeyEvent.KEYCODE_E -> 1
                KeyEvent.KEYCODE_R -> 2
                else -> -1
            }
            if (suggestionIndex >= 0 && clickImeSuggestion(suggestionIndex)) {
                return true
            }
        }

        // In-call shortcuts for Google Phone / Dialer: currency key (Speaker), M key (Mute), digits (Dialpad).
        // Must run BEFORE auto-focus below: both gate only on foreground package (dialer), and
        // auto-focus's printable-key check (M, digits, the currency key's '`' are all printable)
        // would otherwise steal the key first whenever the in-call screen has any unfocused
        // editable node in its tree, silently swallowing mute/speaker/dialpad presses instead of
        // acting on them. This block already scopes itself tightly to the actual in-call action
        // bar via the checkables.size >= 3 guard, so it naturally no-ops (and falls through to
        // auto-focus) on other dialer screens like the pre-call dial-a-number or contact search.
        if (inCallShortcutsEnabled() && isGoogleDialerForeground()) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val checkables = mutableListOf<AccessibilityNodeInfo>()
                    findCheckables(root, checkables)
                    try {
                        // Require the full expected in-call toggle set (keypad, mute, speaker) -
                        // the standalone pre-call dial-a-number screen isn't guaranteed to have
                        // zero checkables, and matching on just "any" let this block misfire
                        // there, double-handling keys that auto-focus's own generic search-box
                        // logic was already correctly handling for that screen.
                        if (checkables.size >= 3) {
                            val isCurrencyKey = kc == KeyEvent.KEYCODE_CTRL_RIGHT || kc == KeyEvent.KEYCODE_GRAVE
                            val isMKey = kc == KeyEvent.KEYCODE_M

                            if (isCurrencyKey) {
                                // Short press currency key -> Toggle Speaker (index 2)
                                if (event.action == KeyEvent.ACTION_UP) {
                                    if (checkables.size > 2) {
                                        checkables[2].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    }
                                }
                                return true // Consume currency key event
                            }

                            if (isMKey) {
                                // Press M key -> Toggle Mute (index 1)
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    if (checkables.size > 1) {
                                        checkables[1].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    }
                                }
                                return true // Consume M key event
                            }

                            val injectKc = getDialerKeycode(kc)
                            if (injectKc != null) {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    val keypadNode = checkables[0]
                                    if (keypadNode.isChecked) {
                                        // Common case: autoOpenDialpad() already opened it earlier,
                                        // so the digits field should already exist - insert straight away.
                                        findDialerDigitsField(root)?.let { insertDialerDigit(it, kc) }
                                    } else {
                                        // Dialpad hasn't visibly opened yet - autoOpenDialpad()'s
                                        // click is async and can lose this race for the very first
                                        // digit. Poll for the digits field to actually exist instead
                                        // of guessing a fixed delay or falling back to "input keyevent"
                                        // (unreliable this soon after a focus/visibility transition -
                                        // same reason auto-focus below uses ACTION_SET_TEXT instead).
                                        keypadNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        worker.execute {
                                            var digits: AccessibilityNodeInfo? = null
                                            for (attempt in 1..10) {
                                                Thread.sleep(50)
                                                digits = rootInActiveWindow?.let { r ->
                                                    try {
                                                        findDialerDigitsField(r)
                                                    } finally {
                                                        r.recycle()
                                                    }
                                                }
                                                if (digits != null) break
                                            }
                                            digits?.let { insertDialerDigit(it, kc) }
                                        }
                                    }
                                }
                                return true // Consume dialpad digit events
                            }
                        }
                    } finally {
                        checkables.forEach { it.recycle() }
                    }
                } finally {
                    root.recycle()
                }
            }
        }

        // Key-triggered AutoFocus: Focus input field and type key once any printable key is pressed
        // on an unfocused field. Gated on a cheap, native-backed "does anything already have input
        // focus?" check rather than a per-app-session "have we tried already" flag - the latter
        // (autoFocusDone) got stuck once focus was lost mid-session (e.g. tapping a back arrow or
        // the screen elsewhere in the same app), since nothing re-armed it without an app change.
        // Checking live focus state instead means it naturally re-attempts whenever focus is
        // actually gone, while still skipping the expensive tree search whenever a field is
        // already focused (the common case while continuing to type).
        if (isAutoFocusEnabledForForeground()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val unicodeChar = event.unicodeChar
                if (unicodeChar > 0 && event.repeatCount == 0 && !event.isAltPressed && !event.isCtrlPressed) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            val alreadyFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            val alreadyFocusedIsTextField = alreadyFocused?.let { AutoFocusController.isEditableTextField(it) } ?: false
                            alreadyFocused?.recycle()
                            if (!alreadyFocusedIsTextField) {
                                val inputNode = AutoFocusController.findFirstEditableNode(root)
                                if (inputNode != null) {
                                    try {
                                        // Some search boxes (Maps, Gmail) actually activate via
                                        // ACTION_CLICK (opening a full search overlay/activity),
                                        // ignoring ACTION_FOCUS entirely - so don't gate on its
                                        // return value, just fire both and wait for real input
                                        // focus to land before injecting the triggering key.
                                        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        consumedAutofocusKeycode = kc
                                        // onAccessibilityEvent counts this down the instant the target field
                                        // actually receives input focus, so the common case wakes in a few ms
                                        // instead of waiting out a fixed poll interval. The 1s budget below is
                                        // only a safety net for apps where that never cleanly fires (matches
                                        // the previous worst-case wait, just no longer the typical one).
                                        val latch = CountDownLatch(1)
                                        focusLatch = latch
                                        worker.execute {
                                            val landedInTime = try {
                                                latch.await(1000, TimeUnit.MILLISECONDS)
                                            } catch (_: InterruptedException) {
                                                false
                                            }
                                            focusLatch = null
                                            // Must specifically be the editable field, not just any focus
                                            // holder - Gmail's search transition (a full overlay/activity,
                                            // unlike Maps' inline omnibox) briefly hands input focus to
                                            // intermediate widgets (e.g. the overlay's toolbar/back button)
                                            // before the real search box gets it, and injecting too early
                                            // against one of those drops the keystroke entirely.
                                            val hasInputFocus = landedInTime && (rootInActiveWindow?.let { r ->
                                                try {
                                                    val focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                                    val isEditable = focused?.let { AutoFocusController.isEditableTextField(it) } ?: false
                                                    focused?.recycle()
                                                    isEditable
                                                } finally {
                                                    r.recycle()
                                                }
                                            } ?: false)
                                            if (hasInputFocus) Thread.sleep(150)
                                            // Re-injecting via "input keyevent" turned out unreliable
                                            // here: it reports shell-level success, but confirmed via
                                            // logging that the dialer's phone-number field's text never
                                            // actually changes - the synthetic event is silently dropped
                                            // (and doesn't even re-enter this filter, unlike a real
                                            // keypress). Setting the text directly through the
                                            // accessibility API instead - the same mechanism assistive
                                            // typing tools are meant to use - sidesteps IME/input-
                                            // connection timing entirely rather than fighting it.
                                            val target = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                            if (target != null) {
                                                try {
                                                    val current = if (target.isShowingHintText) "" else (target.text?.toString() ?: "")
                                                    // In the dialer, the physical letter keys are meant to
                                                    // type their phone-keypad digit (F -> 6), not the raw
                                                    // letter the key produces - but only on the actual
                                                    // Dialpad number-entry field. isGoogleDialerForeground()
                                                    // alone can't tell the Dialpad tab apart from Contacts
                                                    // search / Favorites within the same app, which would
                                                    // otherwise turn contact-name searches into digits too.
                                                    val insertedChar = if (isGoogleDialerForeground() && isDialpadDigitsField(target)) {
                                                        dialerDigitChar(kc) ?: unicodeChar.toChar()
                                                    } else {
                                                        unicodeChar.toChar()
                                                    }
                                                    val args = Bundle().apply {
                                                        putCharSequence(
                                                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                                            current + insertedChar
                                                        )
                                                    }
                                                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                                } finally {
                                                    target.recycle()
                                                }
                                            }
                                        }
                                        return true // Consume original press event
                                    } finally {
                                        inputNode.recycle()
                                    }
                                }
                            }
                        } finally {
                            root.recycle()
                        }
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (kc == consumedAutofocusKeycode) {
                    consumedAutofocusKeycode = -1
                    return true // Consume corresponding key release event
                }
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

        // Straight from root to the button by resource id - a single native,
        // index-backed lookup (same approach as the original q25pininput,
        // which was noticeably snappier than scoping through an intermediate
        // keyguard_pin_view container first via a manual tree walk). Only
        // falls back to walking the tree by label if the id lookup misses
        // (e.g. a SystemUI version using different ids).
        val root = rootInActiveWindow ?: return false
        try {
            val buttonId = pinButtonId(input)
            val button = findNodeByViewIdFirst(root, buttonId)
                ?: findByFallbackTextUnique(root, pinButtonFallbackLabels(input))
                ?: return false
            try {
                if (!button.isClickable) return false
                if (event.repeatCount > 0) return true
                return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                button.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findNodeByViewIdFirst(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return null
        val first = nodes.firstOrNull()
        for (i in 1 until nodes.size) nodes[i].recycle()
        return first
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    private fun getDialerKeycode(kc: Int): Int? {
        return when (kc) {
            KeyEvent.KEYCODE_W -> KeyEvent.KEYCODE_1
            KeyEvent.KEYCODE_E -> KeyEvent.KEYCODE_2
            KeyEvent.KEYCODE_R -> KeyEvent.KEYCODE_3
            KeyEvent.KEYCODE_S -> KeyEvent.KEYCODE_4
            KeyEvent.KEYCODE_D -> KeyEvent.KEYCODE_5
            KeyEvent.KEYCODE_F -> KeyEvent.KEYCODE_6
            KeyEvent.KEYCODE_Z -> KeyEvent.KEYCODE_7
            KeyEvent.KEYCODE_X -> KeyEvent.KEYCODE_8
            KeyEvent.KEYCODE_C -> KeyEvent.KEYCODE_9
            KeyEvent.KEYCODE_0 -> KeyEvent.KEYCODE_0
            else -> null
        }
    }

    /**
     * Same W/E/R/S/D/F/Z/X/C/0 -> phone-digit mapping as [getDialerKeycode], as a character
     * instead of a keycode - used when the generic auto-focus text-insertion path (which
     * otherwise just inserts the raw unicode character the key produces) needs to insert into
     * the dialer's number field specifically, where the physical letter keys are meant to type
     * their corresponding phone-keypad digit, not the letter itself.
     */
    private fun dialerDigitChar(kc: Int): Char? = when (getDialerKeycode(kc)) {
        KeyEvent.KEYCODE_0 -> '0'
        KeyEvent.KEYCODE_1 -> '1'
        KeyEvent.KEYCODE_2 -> '2'
        KeyEvent.KEYCODE_3 -> '3'
        KeyEvent.KEYCODE_4 -> '4'
        KeyEvent.KEYCODE_5 -> '5'
        KeyEvent.KEYCODE_6 -> '6'
        KeyEvent.KEYCODE_7 -> '7'
        KeyEvent.KEYCODE_8 -> '8'
        KeyEvent.KEYCODE_9 -> '9'
        else -> null
    }

    /**
     * Finds the in-call dialpad's actual phone-number EditText, the same node
     * [isDialpadDigitsField] checks for. Caller must recycle the returned node.
     */
    private fun findDialerDigitsField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in arrayOf("com.google.android.dialer:id/digits", "com.google.android.apps.dialer:id/digits")) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) {
                for (i in 1 until nodes.size) nodes[i].recycle()
                return nodes[0]
            }
        }
        return null
    }

    /**
     * Appends [kc]'s mapped digit to the dialpad's number field directly through the
     * accessibility API, recycling [target] when done. "input keyevent" was tried here first
     * but is unreliable this soon after the dialpad's open/visibility transition - the same
     * reason auto-focus's own text insertion below uses ACTION_SET_TEXT instead of key injection.
     */
    private fun insertDialerDigit(target: AccessibilityNodeInfo, kc: Int) {
        try {
            val digit = dialerDigitChar(kc) ?: return
            val current = if (target.isShowingHintText) "" else (target.text?.toString() ?: "")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + digit)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            target.recycle()
        }
    }

    private fun performDialerAction(index: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val checkables = mutableListOf<AccessibilityNodeInfo>()
            findCheckables(root, checkables)
            try {
                if (checkables.size > index) {
                    val node = checkables[index]
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (success) {
                        Log.d("Q25Toolbox", "Successfully clicked dialer button index $index")
                    }
                    return success
                }
            } finally {
                checkables.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
        return false
    }

    private fun autoOpenDialpad() {
        val root = rootInActiveWindow ?: return
        try {
            val checkables = mutableListOf<AccessibilityNodeInfo>()
            findCheckables(root, checkables)
            try {
                // Same guard as the key-handling block: only the actual in-call screen has
                // the full keypad+mute+speaker toggle set, so this can't misfire on the
                // pre-call dial-a-number screen's own (unrelated) checkables.
                if (checkables.size >= 3) {
                    val keypadNode = checkables[0]
                    if (!keypadNode.isChecked) {
                        keypadNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d("Q25Toolbox", "Auto-opened dialpad on call screen load")
                    }
                }
            } finally {
                checkables.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }


    /**
     * Clicks the Nth clickable TextView in the IME window (its suggestion strip) - confirmed on
     * BlackBerry Keyboard, where the candidate strip is exactly a row of clickable TextViews
     * alongside an unrelated ImageButton (the quick-modes toggle), which the TextView classname
     * check filters out. Assumes other BlackBerry-derived keyboards (e.g. Harpocrat) use a
     * similar structure; if a given keyboard doesn't, this just finds nothing and no-ops.
     */
    private fun clickImeSuggestion(index: Int): Boolean {
        val imeRoot = windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root ?: return false
        try {
            val suggestions = mutableListOf<AccessibilityNodeInfo>()
            findClickableTextViews(imeRoot, suggestions)
            try {
                if (suggestions.size <= index) return false
                return suggestions[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                suggestions.forEach { it.recycle() }
            }
        } finally {
            imeRoot.recycle()
        }
    }

    private fun findClickableTextViews(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable && node.className?.contains("TextView") == true) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableTextViews(child, list)
            child.recycle()
        }
    }

    private fun findCheckables(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isCheckable) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findCheckables(child, list)
            child.recycle()
        }
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


    private fun findByFallbackTextUnique(
        root: AccessibilityNodeInfo?,
        fallbackTexts: List<CharSequence>
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        var match: AccessibilityNodeInfo? = null
        if (isActionableMatch(root, fallbackTexts)) {
            match = AccessibilityNodeInfo.obtain(root)
        }

        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            try {
                val childMatch = findByFallbackTextUnique(child, fallbackTexts)
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
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        worker.shutdown()
        super.onDestroy()
    }
}
