package com.kgr.q25toolbox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import com.kgr.q25toolbox.R
import android.os.BatteryManager
import android.util.Log
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.core.RomProfile
import com.kgr.q25toolbox.modules.Dt2wController
import com.kgr.q25toolbox.modules.GestureSettings
import com.kgr.q25toolbox.modules.ImeCompat
import com.kgr.q25toolbox.modules.KeyRemapController
import com.kgr.q25toolbox.modules.NativeBottomGesture
import com.kgr.q25toolbox.modules.RecentsTweaksController
import com.kgr.q25toolbox.modules.SlimRecentsController
import com.kgr.q25toolbox.inputfix.CalculatorInputFix
import com.kgr.q25toolbox.inputfix.ComposerEnterKeyHandler
import com.kgr.q25toolbox.modules.AppScalingController
import com.kgr.q25toolbox.modules.RotationController
import com.kgr.q25toolbox.modules.AutoFocusController
import com.kgr.q25toolbox.modules.BatteryUsageController
import com.kgr.q25toolbox.modules.TickerController
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
 * - **Forced Rotation**: lock the screen rotation to a fixed state, globally or per app.
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
        const val KEY_CALL_PROXIMITY_SLEEP = "call_proximity_sleep_enabled" // screen off at the ear during calls
        const val KEY_LOCKSCREEN_ENTER_OPENS_PIN = "lockscreen_enter_opens_pin" // Enter / pad centre -> open the PIN pad
        const val KEY_LOCKSCREEN_NAV_BLOCK = "lockscreen_nav_block_enabled" // swallow D-pad/Enter/Space/Tab while keyguard is up
        const val KEY_CALL_SCREEN_RECOVERY = "call_screen_recovery_enabled" // force-wake if still dark after a call ends


        // Our do-nothing IME: while it's active, physical key presses go straight
        // to the app instead of being intercepted/translated by the normal keyboard.
        const val PASSTHRU_IME = "com.kgr.q25toolbox/.service.Q25PassthroughIme"

        // How long an auto-focus injection waits for the field it just asked to focus to
        // actually report input focus, and how long it then lets that focus settle.
        private const val AUTO_FOCUS_FOCUS_TIMEOUT_MS = 1000L
        private const val AUTO_FOCUS_SETTLE_MS = 150L
        // How long a "this window has no editable field" result stays trusted before the tree
        // is walked again. Long enough to cover typing a word, short enough that a screen that
        // gains an input field without a window event is picked up almost immediately.
        private const val NO_EDITABLE_CACHE_MS = 1500L

        // The live service instance, so other in-process code (TickerOverlayController)
        // can add a TYPE_ACCESSIBILITY_OVERLAY window - that window type is only usable
        // via a WindowManager obtained from a running AccessibilityService's own Context,
        // not just any app Context.
        var instance: Q25AccessibilityService? = null
            private set

        // Every localized label Google Dialer uses for these three in-call action-bar buttons
        // (string/incall_label_speaker, .../incall_label_mute, .../incall_label_dialpad),
        // pulled directly from the installed Dialer APK's own resources.xml across all its
        // shipped locales. Matching English substrings like "speaker"/"mute"/"dial" only worked
        // on English-locale devices - a Spanish-locale device shows "Altavoz"/"Silenciar"/
        // "Teclado" instead, which don't contain those substrings, so every shortcut silently
        // no-op'd. Exact (trimmed, case-insensitive) match against these sets instead of a
        // substring check, since several of these strings are short enough that substring
        // matching could false-positive against something unrelated.
        // Google Dialer, plus LineageOS' own Dialer (com.android.dialer, same code base:
        // id/digits confirmed in its APK; its in-call buttons are not verified yet).
        private const val AOSP_DIALER = "com.android.dialer"
        private val AOSP_INCALL_BUTTON = Regex(""".*:id/incall_(first|second|third|fourth|fifth|sixth)_button""")
        private val DIALER_PKGS = listOf("com.google.android.dialer", "com.google.android.apps.dialer", "com.android.dialer")
        private val SPEAKER_LABELS = setOf("altaveu", "altavoz", "altifalante", "alto-falante", "altofalante", "altoparlanti", "bocina", "bozgorailua", "difuzor", "dinamik", "garsiakalbis", "głośnik", "hangszóró", "haut-parleur", "hoparlör", "hátalari", "högtalare", "højttaler", "høyttaler", "isipikha", "kaiutin", "karnay", "kõlar", "lautsprecher", "loa", "luidspreker", "pmbsr suara", "reproduktor", "skaļrunis", "speaker", "spika", "vivavoce", "zvočnik", "zvučnik", "ηχείο", "високогов.", "динамик", "динамік", "дынамік", "звучник", "катуу сүйлөткүч", "чанга яригч", "բարձրախոս", "רמקול", "اسپیکر", "بلندگو", "مكبر الصوت", "स्पिकर", "स्पीकर", "स्‍पीकर", "স্পিকার", "স্পীকাৰ", "ਸਪੀਕਰ", "સ્પીકર", "ସ୍ପିକର୍‌", "ஸ்பீக்கர்", "స్పీకర్", "ಸ್ಪೀಕರ್‌", "സ്പീക്കർ", "ස්පීකරය", "ลำโพง", "ລຳໂພງ", "စပီကာ", "სპიკერი", "የድምጽ ማጉያ", "ឧបករណ៍​បំពង​សំឡេង", "スピーカー", "免提", "喇叭", "擴音", "스피커")
        private val MUTE_LABELS = setOf("bisukan", "couper le son", "couper micro", "demp", "dempen", "desakt. audioa", "desativ. som", "hiqi zërin", "hljóð af", "i-mute", "isklj. zvuk", "isključi zvuk", "izklopi zvok", "izslēgt", "kutt lyden", "ljud av", "mute", "mykistä", "nutildyti", "némítás", "ovozsiz", "redam", "sesi kapat", "silencia", "silenciar", "silenzia", "silențios", "sluk mikrofon", "stumm", "susdurun", "thulisa", "tắt tiếng", "vaigista", "vypnúť zvuk", "wycisz", "zima maikrofoni", "ztlumit", "σίγαση", "без звука", "выкл. гук", "дууг хаах", "дыбысын өшіру", "заглушаване", "исклучи звук", "искључи звук", "мікрофон", "үнүн өчүрүү", "անջատել", "השתקה", "خاموش کریں", "صامت کردن", "كتم", "म्युट गर्नुहोस्", "म्यूट करा", "म्यूट करें", "মিউট করুন", "মিউট কৰক", "ਮਿਊਟ ਕਰੋ", "મ્યૂટ કરો", "ମ୍ୟୁଟ୍ କର", "ஒலியடக்கு", "మ్యూట్", "ಮ್ಯೂಟ್‌", "മ്യൂട്ടുചെയ്യുക", "නිහඬ කරන්න", "ปิดเสียง", "ປີດສຽງ", "အသံပိတ်ရန်", "დადუმება", "ድምፀ-ከል አድርግ", "បិទ​សំឡេង", "ミュート", "静音", "靜音", "음소거")
        private val DIALPAD_LABELS = setOf("billentyűzet", "blloku i tasteve", "bàn phím", "cipartast.", "clavier", "ikhiphedi", "keypad", "klaviatura", "klaviatuur", "klaviatūra", "klawiatura", "klávesnice", "knappsats", "nommerblad", "näppäimistö", "pad kekunci", "talnaborð", "tastatur", "tastatura", "tastatură", "tastenfeld", "tastierino", "teclado", "teclat", "teklatua", "telefonska tastatura", "tipkovnica", "toetsenblok", "tuş takımı", "vitufe vya simu", "číselník", "πληκτρολόγιο", "клавиа­тура", "клавиатура", "клавіатура", "клавіятура", "ном. тергич", "пернетақта", "тастатура", "товчлуур", "թվաշար", "לוח חיוג", "صفحه کلید", "لوحة المفاتيح", "کی پیڈ", "किप्याड", "कीपॅड", "कीपैड", "কীপেড", "কীপ্যাড", "ਕੀਪੈਡ", "કીપેડ", "କୀ’ପେଡ", "கீபேட்", "కీప్యాడ్", "ಕೀಪ್ಯಾಡ್‌", "കീപാഡ്", "යතුරු පුවරුව", "ปุ่มกด", "ແປ້ນກົດ", "ခလုတ်ခုံ", "კლავიატურა", "ቁልፍ ሰሌዳ", "ផ្ទាំងចុចលេខ", "キーパッド", "拨号键盘", "撥號鍵盤", "키패드")
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    // IME switching (e.g. entering the per-app keyboard block) is latency-critical - the user
    // can start typing the moment the new app appears - so it gets its own executor rather than
    // sharing `worker` with slower, poll-based tasks (auto-focus's focus-and-verify loop, the
    // in-call dialpad-open poll). Those can legitimately take over a second, and queuing behind
    // one on the same single thread was delaying the IME switch by that much, which is exactly
    // what caused the "first keypress after switching apps doesn't register" symptom.
    private val imeWorker: ExecutorService = Executors.newSingleThreadExecutor()
    // Auto-focus injection gets its own thread for the same reason, and now more urgently: it
    // holds the keystream (every key typed while it runs is queued for it - see
    // autoFocusInjecting), so letting it sit behind `wm size` from a per-app scaling switch, or
    // behind the 5s sleep in scheduleCallEndScreenRecovery, would stall the typed characters
    // for as long as that took instead of just the injection itself.
    private val autoFocusWorker: ExecutorService = Executors.newSingleThreadExecutor()

    // Resets resolution to native when the screen turns off (lock button).
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            // Lock state changes with these three: the keyguard-only key features follow it.
            // The keyguard can lag the screen change by a moment, so look again shortly after.
            mainHandler.post { refreshKeyFilter() }
            mainHandler.postDelayed({ refreshKeyFilter() }, 1000)
            if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                // Edge strips: rebuilt once the screen is on and the keyguard is gone (a no-op while locked).
                GestureStripsController.reconcile(this@Q25AccessibilityService)
            }
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                // DT2W's listener exists only while the screen is off (root shell: never on the main thread).
                worker.execute { try { Dt2wController.onScreenOn() } catch (_: Throwable) { } }
            }
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // An accessibility-overlay window can sit above the keyguard; never leave it up.
                RecentsOverlays.hide(animate = false)
                com.kgr.q25toolbox.modules.QrEnlargerController.hide()
                GestureStripsController.hide()
                foregroundPkg = null
                reconcileScaling()
                reconcileRotation()
                worker.execute { try { Dt2wController.onScreenOff(this@Q25AccessibilityService) } catch (_: Throwable) { } }
            }
        }
    }

    // True once the configured reset threshold has been crossed for the current plug-in
    // session, so a single crossing only triggers one reset (not one per broadcast) until the
    // level drops back below the threshold (e.g. unplugged, or a fresh charge from lower).
    private var batteryThresholdArmed = false

    // Registered in onServiceConnected (see there for why it needs to be dynamic); unregistered in onDestroy.
    private var runActionReceiver: RunActionReceiver? = null

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
    @Volatile private var callActive = false // true while the actual in-call action bar is up
    // Keycodes whose ACTION_DOWN auto-focus consumed, so their matching ACTION_UP is
    // consumed too. A set rather than a single keycode because a whole burst of keys can be
    // consumed while one injection is in flight (see autoFocusInjecting).
    private val consumedAutofocusKeys = mutableSetOf<Int>()
    // Set right before a focus-and-type attempt starts waiting, so onAccessibilityEvent can
    // wake it the instant the target field actually gets input focus (see onKeyEvent / onAccessibilityEvent).
    @Volatile private var focusLatch: CountDownLatch? = null
    // True from the moment auto-focus consumes a key until its text has actually been written
    // into the field. Everything typed in that window is consumed and appended to
    // [pendingAutoFocusKeys] instead of being left to the IME: the injection can take up to a
    // second (waiting for focus to land), and letting the IME handle keys 2..n in the meantime
    // meant our ACTION_SET_TEXT later overwrote the field with a snapshot taken before them -
    // the "letters come out doubled / in the wrong order right after switching apps" symptom.
    @Volatile private var autoFocusInjecting = false
    // (keycode, character typed) pairs, in press order. The keycode is kept alongside the
    // character because the dialer's number field wants the key's phone-keypad digit instead
    // (F -> 6), and which of the two applies is only known once we see the field we landed in.
    private val pendingAutoFocusKeys = mutableListOf<Pair<Int, Char>>()
    private val autoFocusLock = Any()
    // Window id + timestamp of the last tree walk that found no editable field, so a long
    // burst of typing into a screen that has nothing to focus doesn't re-walk the whole node
    // tree on the main thread for every single keystroke. Deliberately time-bounded rather
    // than held until the next window change: content can gain an input field without any
    // window event (a browser navigating within the same window, for one).
    private var noEditableWindowId = -1
    private var noEditableAtMs = 0L
    private var prefs: SharedPreferences? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (key == KEY_IME_BLOCK || key == KEY_IME_BLOCK_APPS) {
            reconcileImeBlock()
        }
        if (key == KEY_SCALING_APPS) {
            reconcileScaling()
        }
        if (key == com.kgr.q25toolbox.modules.ResolutionHotkey.KEY_ENABLED &&
            !com.kgr.q25toolbox.modules.ResolutionHotkey.isEnabled(this)) {
            hotkeyResolution = null // switched off while a hotkey resolution is active: go back
            reconcileScaling()
        }
        if (RotationController.isRotationKey(key)) reconcileRotation()
        if (key == KEY_CALL_PROXIMITY_SLEEP) reconcileCallProximity()
        refreshKeyFilter()
        if (key == KEY_PIN_INPUT || key == KEY_LOCKSCREEN_ENTER_OPENS_PIN) reconcilePinKeysRoot()
        if (key.startsWith(GestureSettings.KEY_PREFIX)) {
            GestureStripsController.reconcile(this)
            syncNativeBottomGesture()
        }
    }

    /** Mirrors "custom bottom strip on" into the Settings.Global flag the launcher hook reads (root, off the main thread). */
    private fun syncNativeBottomGesture() {
        worker.execute { try { NativeBottomGesture.sync(this) } catch (_: Throwable) { } }
    }

    // Whether the key filter is currently requested from the system (the manifest config asks for it by default).
    private var keyFilterOn = true

    /**
     * Asks the system to route key events through [onKeyEvent] only while something here can use them.
     * With the filter on, EVERY key press in EVERY app waits for a binder round trip into this process (up to
     * ~500 ms when it is slow), and under CPU pressure in a heavy app that delay is what makes keys stick or
     * repeat. So it is on only for: features that act anywhere (resolution hotkey, Recents overlay key, IME
     * suggestion shortcuts), features tied to the keyguard (while locked), and package-scoped fixes (while their
     * app is in front). Re-evaluated whenever one of those inputs changes.
     */
    private fun isSysUiOrCredentialFocused(): Boolean {
        if (foregroundPkg == "com.android.systemui" || foregroundPkg == "com.android.settings") return true
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return false
        } catch (_: Exception) {
            return false
        }
        for (w in windowList) {
            val root = w.root ?: continue
            try {
                val pkg = root.packageName?.toString()
                if (pkg == "com.android.systemui" || pkg == "com.android.settings" ||
                    pkg == "com.google.android.packageinstaller" || pkg == "com.android.packageinstaller") return true
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun findNodeByPredicate(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return AccessibilityNodeInfo.obtain(root)
        val count = root.childCount
        for (i in 0 until count) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByPredicate(child, predicate)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun hasPinPromptNodes(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString()
        if (pkg != "com.android.systemui" && pkg != "com.android.settings" &&
            pkg != "com.google.android.packageinstaller" && pkg != "com.android.packageinstaller") {
            return false
        }
        val match = findNodeByPredicate(root) { node ->
            val id = node.viewIdResourceName
            id == "com.android.systemui:id/key_enter" ||
            id == "com.android.systemui:id/delete_button" ||
            id == "com.android.systemui:id/keyguard_pin_view" ||
            id == "com.android.systemui:id/compose_credential_view" ||
            id == "com.android.systemui:id/cred_pin_pad" ||
            id == "cred_pin_pad"
        }
        val found = (match != null)
        match?.recycle()
        return found
    }

    private fun isPinPromptShowing(): Boolean {
        if (isDeviceLocked()) return true
        val root = rootInActiveWindow
        if (root != null) {
            try {
                if (hasPinPromptNodes(root)) return true
            } finally {
                root.recycle()
            }
        }
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return false
        } catch (_: Exception) {
            return false
        }
        for (w in windowList) {
            val wRoot = w.root ?: continue
            try {
                if (hasPinPromptNodes(wRoot)) return true
            } finally {
                wRoot.recycle()
            }
        }
        return false
    }

    private fun refreshKeyFilter(force: Boolean = false) {
        val p = prefs ?: return
        val pkg = foregroundPkg
        val anywhere = com.kgr.q25toolbox.modules.ResolutionHotkey.isEnabled(this) ||
            imeSuggestionsEnabled() ||
            pinInputEnabled() ||
            com.kgr.q25toolbox.modules.QrEnlargerController.isShowing() ||
            (!recentsKeyExternal() && com.kgr.q25toolbox.modules.RecentsTweaksController.getOverlayMode(this).isOverlay)
        val lockFeatures = lockscreenNavBlockEnabled() || lockscreenEnterOpensPinEnabled()
        val scoped = pkg != null && (
            (calculatorEnabled() && CalculatorInputFix.isCalculatorPackage(pkg)) ||
            (chatComposerEnabled() && composerHandler.supportsPackage(pkg)) ||
            (inCallShortcutsEnabled() && pkg in DIALER_PKGS) ||
            isAutoFocusEnabledForForeground())
        val want = anywhere || scoped || (lockFeatures && isDeviceLocked())
        if (!force && want == keyFilterOn) return
        val info = serviceInfo ?: return
        info.flags = if (want) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        serviceInfo = info
        keyFilterOn = want
    }

    /** Root daemon for the lockscreen keys (see PinKeysRoot); root shell, so off the main thread. */
    private fun reconcilePinKeysRoot() {
        val p = prefs ?: return
        worker.execute { try { com.kgr.q25toolbox.modules.PinKeysRoot.reconcile(this, p) } catch (_: Throwable) { } }
    }

    // Created only on Android 12+ (needs AudioManager.OnModeChangedListener); null elsewhere.
    private var callProximity: CallProximitySleep? = null

    private fun reconcileCallProximity() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val want = prefs?.getBoolean(KEY_CALL_PROXIMITY_SLEEP, false) ?: false
        if (want && callProximity == null) {
            callProximity = CallProximitySleep(this, worker).also { it.start() }
        } else if (!want) {
            callProximity?.stop()
            callProximity = null
        }
    }

    override fun onServiceConnected() {
        worker.execute {
            enterOpensPinDefault = RomProfile.autoDetectedLineage()
            onLineage = enterOpensPinDefault
            if (onLineage) try { ImeCompat.apply(this) } catch (_: Throwable) { }
            // Grid (auto): re-check whether the LSPosed hook works with the launcher that is installed now.
            try { RecentsTweaksController.reconcileGrid(this) } catch (t: Throwable) { Log.e("Q25Toolbox", "reconcileGrid failed", t) }
        }
        super.onServiceConnected()
        instance = this
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(prefListener)

        serviceInfo?.let { info ->
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }
        refreshKeyFilter(force = true)
        reconcileRotation() // the general state applies from the start, not only after the next app switch

        imeWorker.execute {
            val curIme = RootShell.run("settings get secure default_input_method")
                .outString.trim()
            imeBlockApplied = (curIme == PASSTHRU_IME)
        }

        // Re-assert what the ticker module needs from the system (assistant access, heads-up
        // left enabled). The "no accessibility service means no ticker, so don't suppress
        // anything" case is handled where the suppression decision is actually made, in
        // TickerNotificationAssistantService, which checks for a live instance per
        // notification - no global state to leave behind if this service dies.
        worker.execute { TickerController.syncSystemState(this) }

        reconcileCallProximity()
        reconcilePinKeysRoot()

        registerReceiver(screenOffReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        GestureStripsController.reconcile(this)
        syncNativeBottomGesture()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // RunActionReceiver: also registered dynamically, because since Android 8 a manifest receiver only
        // gets broadcasts that explicitly target this app (setPackage/setComponent). An action-only
        // "Send intent" broadcast from Key Mapper (and `am broadcast -a ...` without -p) is implicit and
        // never reaches the manifest receiver on its own; the dynamic one receives it. A delivery can
        // match both registrations when the intent does target the app; RunActionReceiver debounces that.
        runActionReceiver = RunActionReceiver().also { r ->
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, IntentFilter(RunActionReceiver.ACTION_RUN), android.content.Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(r, IntentFilter(RunActionReceiver.ACTION_RUN))
            }
        }
    }

    private fun pinInputEnabled() = prefs?.getBoolean(KEY_PIN_INPUT, true) ?: true
    private fun chatComposerEnabled() = prefs?.getBoolean(KEY_CHAT_COMPOSER, false) ?: false
    private fun calculatorEnabled() = prefs?.getBoolean(KEY_CALCULATOR, false) ?: false
    private fun imeSuggestionsEnabled() = prefs?.getBoolean(KEY_IME_SUGGESTIONS, false) ?: false
    // LineageOS only; resolved off the main thread in onServiceConnected (it reads build props).
    @Volatile private var onLineage = false
    private fun recentsKeyExternal() = onLineage && (prefs?.getBoolean(KeyRemapController.KEY_RECENTS_KEY_EXTERNAL, false) ?: false)
    private fun imeBlockEnabled() = prefs?.getBoolean(KEY_IME_BLOCK, false) ?: false
    private fun imeBlockApps(): Set<String> =
        prefs?.getStringSet(KEY_IME_BLOCK_APPS, emptySet()) ?: emptySet()
    private fun inCallShortcutsEnabled() = prefs?.getBoolean(KEY_IN_CALL_SHORTCUTS, false) ?: false
    /**
     * Default for "Enter / pad opens the PIN": on only where it was verified (LineageOS); an explicit user choice
     * always wins. Resolved off the main thread in onServiceConnected (it reads build props), hence the cached flag.
     */
    @Volatile private var enterOpensPinDefault = false
    private fun lockscreenEnterOpensPinEnabled() =
        prefs?.getBoolean(KEY_LOCKSCREEN_ENTER_OPENS_PIN, enterOpensPinDefault) ?: enterOpensPinDefault
    private fun lockscreenNavBlockEnabled() = prefs?.getBoolean(KEY_LOCKSCREEN_NAV_BLOCK, false) ?: false
    private fun callScreenRecoveryEnabled() = prefs?.getBoolean(KEY_CALL_SCREEN_RECOVERY, true) ?: true


    // ------------------------------------------------------- Foreground tracking

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Only re-derive the foreground app when a window actually changed. This used to run
        // for every event, including the typeViewFocused ones that fire continuously while
        // typing or scrolling - and each call is a windows() query plus a root-node fetch per
        // window, i.e. several synchronous binder round-trips on the main looper, which is the
        // same thread onKeyEvent is delivered on. A window event whose package we're already
        // in can't have changed the foreground app either, so that early-outs for free.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            refreshKeyFilter()
            val eventPkg = event.packageName?.toString()
            if (eventPkg == null || eventPkg != foregroundPkg) {
                val pkg = foregroundAppPackage()
                if (pkg != null && pkg != foregroundPkg) {
                    foregroundPkg = pkg
                    GestureStripsController.onForegroundChanged(this, pkg)
                    SlimRecentsOverlayController.onForegroundChanged(pkg)
                    noEditableWindowId = -1
                    reconcileImeBlock()
                    reconcileScaling()
                    reconcileRotation()
                    refreshKeyFilter()
                }
            }
        }

        if (inCallShortcutsEnabled() && isGoogleDialerForeground()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                autoOpenDialpad()
            }
        }

        // Track call start/end for the post-call screen recovery below, independently of
        // in-call shortcuts (someone might want the recovery without the mute/speaker/dialpad
        // shortcuts, or vice versa).
        if (callScreenRecoveryEnabled() && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val inCallUiVisible = isGoogleDialerForeground() && isInCallActionBarUp()
            if (inCallUiVisible && !callActive) {
                callActive = true
            } else if (!inCallUiVisible && callActive) {
                // The in-call screen isn't visible right now, but that alone doesn't mean the
                // call ended - the user may have just backgrounded it (switched to another app
                // mid-call), which looks identical from here to a real hangup. Confirm against
                // the actual telecom call state before treating this as a real end and running
                // recovery: a false positive here previously fired the keyboard i2c respawn
                // WHILE a call was still live, racing the real proximity-driven display
                // transition and crashing the kernel (bbqX0kbd_disp_notifier_callback null
                // deref) instead of preventing anything.
                worker.execute {
                    val stillOnCall = RootShell.run("dumpsys telecom").outString.let { out ->
                        out.contains("state=ACTIVE") || out.contains("state=DIALING") || out.contains("state=RINGING")
                    }
                    if (!stillOnCall) {
                        callActive = false
                        scheduleCallEndScreenRecovery()
                    }
                }
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
        return pkg in DIALER_PKGS
    }

    /**
     * True only for the Dialpad tab's actual phone-number EditText, not other dialer-app
     * screens (Contacts search, Favorites/Home, Recents) that share the same foreground
     * package. Confirmed via uiautomator dump: resource-id "com.google.android.dialer:id/digits".
     */
    private fun isDialpadDigitsField(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: return false
        return DIALER_PKGS.any { id == "$it:id/digits" }
    }

    private fun isAutoFocusEnabledForForeground(): Boolean {
        val prefs = prefs ?: return false
        if (!AutoFocusController.isEnabled(prefs)) return false
        val pkg = foregroundPkg ?: return false
        return pkg in AutoFocusController.getSelectedApps(prefs)
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
    // Resolution chosen with the global hotkey (null = none). It wins over the per-app targets and survives the screen
    // turning off, until the hotkey steps back to the default; teardown still resets it (restoreScaling).
    @Volatile private var hotkeyResolution: AppScalingController.Res? = null
    // Key of a hotkey press we consumed, so its release and auto-repeats are swallowed too.
    private var hotkeyKeyDown = -1
    private var hotkeyLongFired = false
    private val hotkeyLongPress = Runnable { if (hotkeyKeyDown != -1) { hotkeyLongFired = true; cycleResolution() } }

    private fun reconcileScaling() {
        val desired = hotkeyResolution
            ?: foregroundPkg?.let { AppScalingController.entries(this)[it] }
            ?: AppScalingController.NATIVE
        Log.d("Q25Toolbox", "reconcileScaling: fg=$foregroundPkg desired=${desired.encode()} current=$currentScaleKey busy=${resolvingScale.get()}")
        if (desired.encode() == currentScaleKey) return
        if (!resolvingScale.compareAndSet(false, true)) {
            // A change is in flight: try again shortly instead of losing this one (a hotkey press must not be dropped).
            mainHandler.postDelayed({ reconcileScaling() }, 250)
            return
        }
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
    /** Hotkey pressed: go to the next resolution of the list, or back to the default after the last one. */
    private fun cycleResolution() {
        val next = com.kgr.q25toolbox.modules.ResolutionHotkey.next(
            com.kgr.q25toolbox.modules.ResolutionHotkey.list(this), hotkeyResolution)
        hotkeyResolution = next
        reconcileScaling()
        if (com.kgr.q25toolbox.modules.ResolutionHotkey.vibrate(this)) {
            val v = com.kgr.q25toolbox.modules.GestureSettings.vibration(this)
            GestureStripsController.vibrate(this, v.actionMs, v.strengthPct)
        }
        android.widget.Toast.makeText(
            this,
            getString(com.kgr.q25toolbox.R.string.res_hotkey_toast, next?.let { "${it.w}x${it.h}" } ?: getString(com.kgr.q25toolbox.R.string.res_hotkey_default)),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun restoreScaling() {
        mainHandler.removeCallbacks(hotkeyLongPress)
        hotkeyResolution = null
        if (currentScaleKey == AppScalingController.NATIVE.encode()) return
        currentScaleKey = AppScalingController.NATIVE.encode()
        resolvingScale.set(true)
        try {
            AppScalingController.applyResolution(AppScalingController.NATIVE)
        } finally {
            resolvingScale.set(false)
        }
    }

    // ---------------------------------------------------------- Forced rotation

    // What we last queued (mode + override flag), so identical reconciles do not touch root.
    // Null until the first one, so a killed-and-restarted service still restores saved values.
    private var currentRotationKey: String? = null

    /**
     * Rotation target for the foreground app (its own entry, else the general state). Queued on
     * the single-thread [worker], so changes run in order and the main looper never waits on root.
     * While the screen is off no app is foreground, so the general state applies.
     */
    private fun reconcileRotation() {
        val mode = RotationController.desired(this, foregroundPkg)
        val key = "${mode.key}/${RotationController.overrideAppRequests(this)}"
        if (key == currentRotationKey) return
        currentRotationKey = key
        worker.execute {
            try { RotationController.apply(this, mode) } catch (t: Throwable) { Log.e("Q25Toolbox", "rotation apply failed", t) }
        }
    }

    /** Give the rotation back to the user, run synchronously on teardown. */
    private fun restoreRotation() {
        currentRotationKey = null
        try { RotationController.apply(this, RotationController.Mode.SYSTEM) } catch (_: Throwable) { }
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

    /** Runs the action assigned to an edge gesture. Back/Home first close our Recents overlay if it is up, like the keys do. */
    fun performEdgeAction(a: GestureSettings.Action) {
        when (a) {
            GestureSettings.Action.NONE -> {}
            GestureSettings.Action.BACK ->
                if (RecentsOverlays.isShowing()) RecentsOverlays.hide() else performGlobalAction(GLOBAL_ACTION_BACK)
            GestureSettings.Action.HOME -> {
                if (RecentsOverlays.isShowing()) RecentsOverlays.hide(expandTaskId = null)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            GestureSettings.Action.RECENTS -> openRecents()
            GestureSettings.Action.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            GestureSettings.Action.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            GestureSettings.Action.LOCK_SCREEN -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            GestureSettings.Action.SCREENSHOT -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            GestureSettings.Action.POWER_MENU -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            GestureSettings.Action.SPLIT_SCREEN -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            GestureSettings.Action.FLASHLIGHT -> TorchToggle.toggle(this)
            GestureSettings.Action.QR_ENLARGER -> com.kgr.q25toolbox.modules.QrEnlargerController.trigger(this)
            GestureSettings.Action.PREVIOUS_APP -> {
                if (RecentsOverlays.isShowing()) RecentsOverlays.hide(animate = false)
                val front = foregroundPkg
                worker.execute { try { switchToPreviousApp(front) } catch (t: Throwable) { Log.e("Q25Toolbox", "previous app failed", t) } }
            }
            GestureSettings.Action.ASPECT_RATIO -> cycleResolution()
            GestureSettings.Action.ROTATION -> toggleRotation()
            GestureSettings.Action.EXTRA_DIM -> toggleExtraDim()
            GestureSettings.Action.GESTURES -> toggleGestures()
            GestureSettings.Action.BLUETOOTH -> toggleBluetooth()
            GestureSettings.Action.LOCATION -> toggleLocation()
            GestureSettings.Action.CIRCLE_TO_SEARCH -> com.kgr.q25toolbox.modules.CircleToSearchController.trigger(this)
        }
    }

    private fun toggleRotation() {
        worker.execute {
            try {
                val current = com.kgr.q25toolbox.modules.RotationController.general(this)
                val target = if (current == com.kgr.q25toolbox.modules.RotationController.Mode.SYSTEM) {
                    val cr = contentResolver
                    val auto = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1)
                    if (auto == 1) com.kgr.q25toolbox.modules.RotationController.Mode.R0 else com.kgr.q25toolbox.modules.RotationController.Mode.AUTO
                } else {
                    com.kgr.q25toolbox.modules.RotationController.Mode.SYSTEM
                }
                com.kgr.q25toolbox.modules.RotationController.setGeneral(this, target)
                com.kgr.q25toolbox.modules.RotationController.apply(this, target)
                val msg = when (target) {
                    com.kgr.q25toolbox.modules.RotationController.Mode.SYSTEM -> getString(R.string.rot_mode_system)
                    com.kgr.q25toolbox.modules.RotationController.Mode.AUTO -> getString(R.string.rot_mode_auto)
                    com.kgr.q25toolbox.modules.RotationController.Mode.R0 -> getString(R.string.rot_mode_0)
                    else -> target.name
                }
                mainHandler.post { android.widget.Toast.makeText(this, getString(R.string.toast_rotation_format, msg), android.widget.Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "toggleRotation failed", t)
            }
        }
    }

    private fun toggleExtraDim() {
        worker.execute {
            try {
                val active = com.kgr.q25toolbox.modules.ExtraDimController.isActivated()
                com.kgr.q25toolbox.modules.ExtraDimController.setActivated(!active)
                mainHandler.post {
                    android.widget.Toast.makeText(this, if (!active) getString(R.string.toast_extra_dim_on) else getString(R.string.toast_extra_dim_off), android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "toggleExtraDim failed", t)
            }
        }
    }

    private fun toggleGestures() {
        mainHandler.post {
            try {
                val left = com.kgr.q25toolbox.modules.GestureSettings.get(this, com.kgr.q25toolbox.modules.GestureSettings.Zone.LEFT)
                val right = com.kgr.q25toolbox.modules.GestureSettings.get(this, com.kgr.q25toolbox.modules.GestureSettings.Zone.RIGHT)
                val bottom = com.kgr.q25toolbox.modules.GestureSettings.get(this, com.kgr.q25toolbox.modules.GestureSettings.Zone.BOTTOM)
                val anyOn = left.mode == com.kgr.q25toolbox.modules.GestureSettings.Mode.CUSTOM ||
                            right.mode == com.kgr.q25toolbox.modules.GestureSettings.Mode.CUSTOM ||
                            bottom.mode == com.kgr.q25toolbox.modules.GestureSettings.Mode.CUSTOM
                val newMode = if (anyOn) com.kgr.q25toolbox.modules.GestureSettings.Mode.OFF else com.kgr.q25toolbox.modules.GestureSettings.Mode.CUSTOM
                com.kgr.q25toolbox.modules.GestureSettings.set(this, com.kgr.q25toolbox.modules.GestureSettings.Zone.LEFT, left.copy(mode = newMode))
                com.kgr.q25toolbox.modules.GestureSettings.set(this, com.kgr.q25toolbox.modules.GestureSettings.Zone.RIGHT, right.copy(mode = newMode))
                com.kgr.q25toolbox.modules.GestureSettings.set(this, com.kgr.q25toolbox.modules.GestureSettings.Zone.BOTTOM, bottom.copy(mode = newMode))
                GestureStripsController.reconcile(this)
                android.widget.Toast.makeText(this, if (newMode == com.kgr.q25toolbox.modules.GestureSettings.Mode.CUSTOM) getString(R.string.toast_gestures_on) else getString(R.string.toast_gestures_off), android.widget.Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "toggleGestures failed", t)
            }
        }
    }

    private fun toggleBluetooth() {
        worker.execute {
            try {
                val cr = contentResolver
                val on = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.BLUETOOTH_ON, 0) == 1
                val cmd = if (on) "cmd bluetooth_manager disable" else "cmd bluetooth_manager enable"
                RootShell.run(cmd)
                mainHandler.post {
                    android.widget.Toast.makeText(this, if (!on) getString(R.string.toast_bluetooth_on) else getString(R.string.toast_bluetooth_off), android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "toggleBluetooth failed", t)
            }
        }
    }

    private fun toggleLocation() {
        worker.execute {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val on = lm.isLocationEnabled
                val cmd = if (on) "cmd location set-location-enabled false" else "cmd location set-location-enabled true"
                RootShell.run(cmd)
                mainHandler.post {
                    android.widget.Toast.makeText(this, if (!on) getString(R.string.toast_location_on) else getString(R.string.toast_location_off), android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "toggleLocation failed", t)
            }
        }
    }

    /**
     * Resumes the app used before the one in front. [SlimRecentsController.listTasks] is newest first and leaves the
     * home launcher out: if its first task is the app in front, the previous one is the second; if the front app is
     * the launcher (or unknown to us), the first task is the one to go back to. Root dumpsys, so off the main thread.
     */
    private fun switchToPreviousApp(front: String?) {
        val tasks = SlimRecentsController.listTasks(this)
        val target = if (front != null && tasks.firstOrNull()?.packageName == front) tasks.getOrNull(1) else tasks.firstOrNull()
        target?.let { SlimRecentsController.resumeTask(it) }
    }

    /**
     * Builds the Slim List / Masonry-quilt overlay. Task listing, snapshots and the live screenshot all
     * run root shell commands, so everything happens on [worker]; the window itself is added on the
     * main thread. In quilt mode the window comes up at once with placeholders and the snapshots
     * stream in afterwards.
     */
    private fun openRecents() {
        val mode = RecentsTweaksController.getOverlayMode(this)
        if (!mode.isOverlay) { performGlobalAction(GLOBAL_ACTION_RECENTS); return }
        // Read now, on the main thread and before our own window exists, so the overlay can tell which card is
        // the app already in front (see SlimRecentsOverlayController.wireRow).
        val frontPkg = foregroundPkg
        worker.execute {
            try {
                val grid = mode == RecentsTweaksController.LayoutMode.GRID_OVERLAY
                // Quilt and grid show snapshots (and a live shot of the app in front); the vertical list does not.
                val cards = grid || mode == RecentsTweaksController.LayoutMode.QUILT
                val tasks = SlimRecentsController.listTasks(this)
                SlimRecentsController.primeBannerColors(tasks)
                // The foreground app has no fresh stored snapshot (those are taken when a task goes
                // to the background), so its tile gets a live screenshot, taken before our own window
                // goes up so the scrim is not in the shot.
                val liveTop = if (cards && tasks.isNotEmpty()) captureForRecents() else null
                val topId = tasks.firstOrNull()?.taskId
                mainHandler.post {
                    if (grid) GridRecentsOverlayController.show(this, tasks, frontPkg)
                    else SlimRecentsOverlayController.show(this, tasks, cards, frontPkg)
                    if (liveTop != null && topId != null) {
                        val live = mapOf(topId to liveTop)
                        if (grid) GridRecentsOverlayController.fillSnapshots(live)
                        else SlimRecentsOverlayController.fillSnapshots(live)
                    }
                }
                if (cards && tasks.isNotEmpty()) {
                    val ids = if (liveTop != null) tasks.drop(1).map { it.taskId } else tasks.map { it.taskId }
                    val snaps = SlimRecentsController.loadSnapshots(ids)
                    mainHandler.post {
                        if (grid) GridRecentsOverlayController.fillSnapshots(snaps)
                        else SlimRecentsOverlayController.fillSnapshots(snaps)
                    }
                }
            } catch (t: Throwable) {
                Log.e("Q25Toolbox", "openRecents failed", t)
            }
        }
    }

    /** Live screenshot for the quilt's newest tile, minus the status bar. Blocking (root screencap). */
    private fun captureForRecents(): Bitmap? {
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val top = if (sbId > 0) resources.getDimensionPixelSize(sbId) else 0
        return SlimRecentsController.captureScreen(top, 0)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (com.kgr.q25toolbox.modules.QrEnlargerController.onKeyEvent(event)) return true
        if (com.kgr.q25toolbox.modules.KeyboardShortcutsController.onKeyEvent(this, event)) return true
        val kc = event.keyCode

        // Global resolution hotkey. Swallows the press, its auto-repeats and its release. Not on the lockscreen.
        // Only the physical keyboard: events we inject ourselves (below) arrive with a negative device id.
        if (event.deviceId >= 0 && kc == hotkeyKeyDown) {
            if (event.action == KeyEvent.ACTION_UP) {
                hotkeyKeyDown = -1
                mainHandler.removeCallbacks(hotkeyLongPress)
                val hk = com.kgr.q25toolbox.modules.ResolutionHotkey
                // Released before the long press: type the key after all, if asked to (the press was swallowed).
                if (!hotkeyLongFired && hk.press(this) == com.kgr.q25toolbox.modules.ResolutionHotkey.Press.LONG && hk.tapPass(this)) {
                    val cmd = hk.reinjectCommand(hk.combo(this))
                    worker.execute { try { RootShell.run(cmd) } catch (_: Throwable) { } }
                }
            }
            return true
        }
        if (event.deviceId >= 0 && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            com.kgr.q25toolbox.modules.ResolutionHotkey.isEnabled(this) && !isDeviceLocked() &&
            com.kgr.q25toolbox.modules.ResolutionHotkey.combo(this).matches(event.metaState, kc)
        ) {
            hotkeyKeyDown = kc
            hotkeyLongFired = false
            // SHORT acts at once. LONG waits for the key to be held. The press is swallowed either way; a quick tap in LONG
            // mode is typed again on release when the user asked for that (see above).
            if (com.kgr.q25toolbox.modules.ResolutionHotkey.press(this) == com.kgr.q25toolbox.modules.ResolutionHotkey.Press.SHORT) {
                cycleResolution()
            } else {
                mainHandler.postDelayed(hotkeyLongPress, com.kgr.q25toolbox.modules.ResolutionHotkey.LONG_MS)
            }
            return true
        }

        // The Recents key is left to another app (Key Mapper): do nothing with it here. It still arrives as PROG_RED
        // (the keylayout remap keeps the system from opening its Overview), and every accessibility service sees it.
        if ((kc == KeyEvent.KEYCODE_PROG_RED || kc == KeyEvent.KEYCODE_APP_SWITCH) && recentsKeyExternal()) return false

        // Recents overlay (Slim List / Masonry quilt). While it is showing, Back/Home/Recents close
        // or refresh it unconditionally, before any other feature gets a look at the key: the
        // overlay is FLAG_NOT_FOCUSABLE, so it can never receive keys itself.
        if (RecentsOverlays.isShowing()) {
            when (kc) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) RecentsOverlays.hide()
                    return true
                }
                KeyEvent.KEYCODE_HOME -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        RecentsOverlays.hide(expandTaskId = null)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                    return true
                }
                KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_PROG_RED -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openRecents()
                    return true
                }
            }
        } else if ((kc == KeyEvent.KEYCODE_PROG_RED || kc == KeyEvent.KEYCODE_APP_SWITCH) && !isDeviceLocked() &&
            RecentsTweaksController.getOverlayMode(this).isOverlay
        ) {
            // PROG_RED is what KeyRemapController turns the physical Recents key into while an overlay
            // mode is selected, so the system never sees it (it would open its own Overview alongside
            // ours). APP_SWITCH still lands here from sources that bypass the remap (e.g. USB keyboards).
            // The on-screen nav button and gesture go straight to the launcher and cannot be intercepted.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openRecents()
            return true
        }

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
                                // Short press currency key -> Toggle Speaker. Found by its actual
                                // label rather than assumed index 2: a different dialer build/OEM
                                // customization can reorder or add to this action bar, and blindly
                                // clicking "whatever's 3rd" risked hitting an unrelated toggle -
                                // reported as Airplane Mode turning on by itself during calls.
                                if (event.action == KeyEvent.ACTION_UP) {
                                    findCheckableByLabel(checkables, SPEAKER_LABELS)?.performAction(
                                        AccessibilityNodeInfo.ACTION_CLICK
                                    )
                                }
                                return true // Consume currency key event
                            }

                            if (isMKey) {
                                // Press M key -> Toggle Mute, same label-based lookup as above.
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    findCheckableByLabel(checkables, MUTE_LABELS)?.performAction(
                                        AccessibilityNodeInfo.ACTION_CLICK
                                    )
                                }
                                return true // Consume M key event
                            }

                            val injectKc = getDialerKeycode(kc)
                            if (injectKc != null) {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    val keypadNode = findCheckableByLabel(checkables, DIALPAD_LABELS)
                                    if (keypadNode == null) {
                                        // Couldn't identify the keypad toggle by label - do nothing
                                        // rather than guess at a differently-ordered button.
                                    } else if (isKeypadToggleOn(keypadNode, root)) {
                                        // Common case: autoOpenDialpad() already opened it earlier,
                                        // so the digits field should already exist - insert straight away.
                                        sendDialerDigit(root, kc)
                                    } else {
                                        // Dialpad hasn't visibly opened yet - autoOpenDialpad()'s
                                        // click is async and can lose this race for the very first
                                        // digit. Poll for the digits field to actually exist instead
                                        // of guessing a fixed delay or falling back to "input keyevent"
                                        // (unreliable this soon after a focus/visibility transition -
                                        // same reason auto-focus below uses ACTION_SET_TEXT instead).
                                        keypadNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        worker.execute {
                                            for (attempt in 1..10) {
                                                Thread.sleep(50)
                                                val sent = rootInActiveWindow?.let { r ->
                                                    try {
                                                        sendDialerDigit(r, kc)
                                                    } finally {
                                                        r.recycle()
                                                    }
                                                } ?: false
                                                if (sent) break
                                            }
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
                    // An injection is already in flight: claim this key too and hand it to that
                    // injection, so keys 2..n land in the same ACTION_SET_TEXT, in order, instead
                    // of racing it through the IME. Cheap enough for the main thread - the worker
                    // only ever holds this lock to copy the list, never across a binder call.
                    val queued = synchronized(autoFocusLock) {
                        if (autoFocusInjecting) {
                            pendingAutoFocusKeys.add(kc to unicodeChar.toChar())
                            true
                        } else {
                            false
                        }
                    }
                    if (queued) {
                        consumedAutofocusKeys.add(kc)
                        return true
                    }

                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            val alreadyFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            val alreadyFocusedIsTextField = alreadyFocused?.let { AutoFocusController.isEditableTextField(it) } ?: false
                            alreadyFocused?.recycle()
                            if (!alreadyFocusedIsTextField && !recentlyFoundNoEditableField(root)) {
                                val inputNode = AutoFocusController.findFirstEditableNode(root)
                                if (inputNode == null) {
                                    // Nothing to focus on this screen. Remember that briefly so a
                                    // whole typed word doesn't re-walk the entire node tree - on
                                    // the main thread, ahead of the key filter - once per letter.
                                    rememberNoEditableField(root)
                                } else {
                                    try {
                                        // Some search boxes (Maps, Gmail) actually activate via
                                        // ACTION_CLICK (opening a full search overlay/activity),
                                        // ignoring ACTION_FOCUS entirely - so don't gate on its
                                        // return value, just fire both and wait for real input
                                        // focus to land before injecting the triggering key.
                                        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        consumedAutofocusKeys.add(kc)
                                        synchronized(autoFocusLock) {
                                            pendingAutoFocusKeys.clear()
                                            pendingAutoFocusKeys.add(kc to unicodeChar.toChar())
                                            autoFocusInjecting = true
                                        }
                                        // onAccessibilityEvent counts this down the instant the target field
                                        // actually receives input focus, so the common case wakes in a few ms
                                        // instead of waiting out a fixed poll interval. The 1s budget below is
                                        // only a safety net for apps where that never cleanly fires (matches
                                        // the previous worst-case wait, just no longer the typical one).
                                        val latch = CountDownLatch(1)
                                        focusLatch = latch
                                        autoFocusWorker.execute { runAutoFocusInjection(latch) }
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
                if (consumedAutofocusKeys.remove(kc)) {
                    return true // Consume corresponding key release event
                }
            }
        }
        // Ported q25-input-helper fixes. Calculator claims digit/operator keys; chat composer
        // claims Enter - disjoint, so order between them doesn't matter. Both are pre-filtered
        // against the foreground package we already track: their own package check reads it off
        // getRootInActiveWindow(), so without this they each cost a binder round-trip on the
        // main thread for every digit (calculator) or Enter (composer) typed in any app at all.
        val fgPkg = foregroundPkg
        if (calculatorEnabled() && CalculatorInputFix.isCalculatorPackage(fgPkg) &&
            calculatorFix.onKeyEvent(this, event)
        ) return true
        if (chatComposerEnabled() && composerHandler.supportsPackage(fgPkg) &&
            composerHandler.onKeyEvent(this, event)
        ) return true

        // Enter / pad centre on the plain lockscreen: open the PIN pad, deterministically. Left to the system,
        // these keys activate whatever happens to hold focus (unlock, the network tile, the newest
        // notification), which changes with earlier key presses. Only when SystemUI's own keyguard is the
        // active window and no bouncer is up: over-lockscreen apps (an incoming call, say) and the PIN pad
        // itself keep their keys.
        if (isOpenPinKey(kc) && lockscreenEnterOpensPinEnabled() && isDeviceLocked() && isPlainLockscreen()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                // Root shell: never on the main thread (this callback has a ~500 ms budget).
                worker.execute { RootShell.run("wm dismiss-keyguard") }
            }
            return true
        }

        // Lockscreen navigation block: on the keyguard, D-pad/Tab/Space/Enter move focus and click
        // the focused control, which can reach the emergency-call button with no touch at all
        // (e.g. keyboard pressed in a pocket). Event-driven on purpose (ported idea from
        // Key2Toolbox's "Lockscreen Keyboard Lock", which polls dumpsys and chmods the input node).
        // Enter/D-pad-center stay live while PIN-on-keyboard is on, since the PIN pad needs them.
        if (lockscreenNavBlockEnabled() && isLockscreenNavKey(kc, pinInputEnabled()) && isDeviceLocked()) return true

        // PIN Input: map physical keys to the lockscreen PIN pad. With root available the daemon types the letter
        // digits (it also works before the first unlock), so those keys are left to it here: doing both would type
        // twice. Raw digits, Enter and Delete stay on this path, which the daemon does not cover.
        val pinEnabled = pinInputEnabled()
        val pinShowing = isPinPromptShowing()
        val locked = isDeviceLocked()
        val rootActive = com.kgr.q25toolbox.modules.PinKeysRoot.isActive(prefs)
        val isLetter = com.kgr.q25toolbox.modules.PinKeysRoot.isLetterDigit(kc)
        if (!pinEnabled) return false
        if (!pinShowing) return false
        if (locked && rootActive && isLetter && !com.kgr.q25toolbox.core.RomProfile.autoDetectedLineage()) return false
        val input = toPinInput(kc) ?: return false
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false

        Log.d("Q25Toolbox", "onKeyEvent PIN: kc=$kc, input=$input, locked=${isDeviceLocked()}")
        return clickPinButton(input, event)
    }

    private fun clickPinButton(input: PinInput, event: KeyEvent): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val button = findPinButton(root, input)
                if (button != null) {
                    try {
                        if (!button.isClickable) {
                            Log.w("Q25Toolbox", "findPinButton: button not clickable")
                            return false
                        }
                        if (event.repeatCount > 0) return true
                        val res = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d("Q25Toolbox", "clickPinButton root: input=$input, res=$res")
                        return res
                    } finally {
                        button.recycle()
                    }
                }
            } finally {
                root.recycle()
            }
        }

        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return false
        } catch (_: Exception) {
            return false
        }
        for (w in windowList) {
            val wRoot = w.root ?: continue
            try {
                val button = findPinButton(wRoot, input)
                if (button != null) {
                    try {
                        if (!button.isClickable) return false
                        if (event.repeatCount > 0) return true
                        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        button.recycle()
                    }
                }
            } finally {
                wRoot.recycle()
            }
        }
        return false
    }

    // ------------------------------------------------------- AutoFocus injection

    private fun recentlyFoundNoEditableField(root: AccessibilityNodeInfo): Boolean =
        root.windowId == noEditableWindowId &&
            (SystemClock.uptimeMillis() - noEditableAtMs) < NO_EDITABLE_CACHE_MS

    private fun rememberNoEditableField(root: AccessibilityNodeInfo) {
        noEditableWindowId = root.windowId
        noEditableAtMs = SystemClock.uptimeMillis()
    }

    /**
     * Waits for the field auto-focus just asked for to actually take input focus, then writes
     * every key consumed since (see [autoFocusInjecting]) into it in one go, draining any that
     * arrived while the write itself was in flight.
     *
     * Runs on [worker]; only the list handoff is synchronized, never the accessibility calls.
     */
    private fun runAutoFocusInjection(latch: CountDownLatch) {
        try {
            val landedInTime = try {
                latch.await(AUTO_FOCUS_FOCUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                false
            }
            focusLatch = null
            // The field can report focus a beat before its input connection is actually live -
            // injecting into that gap drops the text. Only worth waiting out when focus did land;
            // if it never did, the insert below will just fail its editability check and bail.
            if (landedInTime) Thread.sleep(AUTO_FOCUS_SETTLE_MS)

            while (true) {
                val batch = synchronized(autoFocusLock) {
                    val copy = pendingAutoFocusKeys.toList()
                    pendingAutoFocusKeys.clear()
                    if (copy.isEmpty()) autoFocusInjecting = false
                    copy
                }
                if (batch.isEmpty()) return
                if (!insertAutoFocusText(batch)) {
                    Log.d("Q25Toolbox", "autoFocus: no editable field took focus, dropped ${batch.size} key(s)")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("Q25Toolbox", "autoFocus injection failed", e)
        } finally {
            synchronized(autoFocusLock) {
                pendingAutoFocusKeys.clear()
                autoFocusInjecting = false
            }
        }
    }

    /**
     * Appends [batch] to whatever editable field currently holds input focus, returning false
     * (writing nothing) if that isn't an editable text field.
     *
     * Re-injecting via "input keyevent" was tried here first and is unreliable: it reports
     * shell-level success, but confirmed via logging that the dialer's phone-number field's text
     * never actually changes - the synthetic event is silently dropped (and doesn't even re-enter
     * this filter, unlike a real keypress). Setting the text directly through the accessibility
     * API instead - the same mechanism assistive typing tools are meant to use - sidesteps
     * IME/input-connection timing entirely rather than fighting it.
     */
    private fun insertAutoFocusText(batch: List<Pair<Int, Char>>): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } finally {
            root.recycle()
        }
        val target = focused ?: return false
        try {
            // Must specifically be the editable field, not just any focus holder - Gmail's search
            // transition (a full overlay/activity, unlike Maps' inline omnibox) briefly hands input
            // focus to intermediate widgets (e.g. the overlay's toolbar/back button) before the real
            // search box gets it, and writing to one of those loses the keystroke entirely.
            if (!AutoFocusController.isEditableTextField(target)) return false
            // In the dialer, the physical letter keys are meant to type their phone-keypad digit
            // (F -> 6), not the raw letter the key produces - but only on the actual Dialpad
            // number-entry field. isGoogleDialerForeground() alone can't tell the Dialpad tab apart
            // from Contacts search / Favorites within the same app, which would otherwise turn
            // contact-name searches into digits too.
            val asDialpad = isGoogleDialerForeground() && isDialpadDigitsField(target)
            val addition = buildString {
                for ((keycode, typed) in batch) {
                    append(if (asDialpad) (dialerDigitChar(keycode) ?: typed) else typed)
                }
            }
            val current = if (target.isShowingHintText) "" else (target.text?.toString() ?: "")
            val updated = current + addition
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            setCaretToEnd(target, updated.length)
            return true
        } finally {
            target.recycle()
        }
    }

    /**
     * Puts the caret after the text we just wrote and, more to the point, tells the IME about it.
     *
     * Without this the IME is left believing the cursor is wherever it was before the
     * ACTION_SET_TEXT (position 0 on a field it thinks is still empty), so the next physical key
     * it does handle gets inserted at the front and auto-capitalized - the caps-mode lookup at
     * offset 0 reports "start of sentence". That is exactly the "first letter after switching apps
     * comes out capitalized, or the letters come out doubled" behaviour: not the keyboard, but a
     * cursor the keyboard was never told had moved.
     */
    private fun setCaretToEnd(target: AccessibilityNodeInfo, end: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun findNodeByViewIdFirst(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return null
        val first = nodes.firstOrNull()
        for (i in 1 until nodes.size) nodes[i].recycle()
        return first
    }

    private fun isLockscreenNavKey(kc: Int, pinActive: Boolean): Boolean = when (kc) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB -> true
        KeyEvent.KEYCODE_SPACE -> !pinActive || !isBouncerShowing()
        // Enter / pad centre open the PIN pad natively from the plain lockscreen (verified on LineageOS 23),
        // so they must pass until the bouncer is up. Once it is, they can activate the focused control (for
        // example the emergency-call button), so they are blocked - unless PIN-on-keyboard needs them.
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
            !pinActive && isBouncerShowing()
        else -> false
    }

    private fun isOpenPinKey(kc: Int) =
        kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER

    /** SystemUI's keyguard is the active window and its PIN pad is not showing. */
    private fun isPlainLockscreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            root.packageName == "com.android.systemui" &&
                findNodeByViewIdFirst(root, "com.android.systemui:id/keyguard_bouncer_container")?.also { it.recycle() } == null
        } finally {
            root.recycle()
        }
    }

    /** True while the lockscreen's PIN/password/pattern pad (the "bouncer") is on screen. */
    private fun isBouncerShowing(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            findNodeByViewIdFirst(root, "com.android.systemui:id/keyguard_bouncer_container")?.also { it.recycle() } != null
        } finally {
            root.recycle()
        }
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
        for (id in DIALER_PKGS.map { "$it:id/digits" }) {
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
                    val keypadNode = findCheckableByLabel(checkables, DIALPAD_LABELS)
                    if (keypadNode != null && !isKeypadToggleOn(keypadNode, root)) {
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

    /** Same "is this actually the in-call action bar" signal [autoOpenDialpad] uses. */
    private fun isInCallActionBarUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val checkables = mutableListOf<AccessibilityNodeInfo>()
            findCheckables(root, checkables)
            try {
                return checkables.size >= 3
            } finally {
                checkables.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * If the screen is still off a few seconds after a call genuinely ends, force it back on.
     * Harmless no-op if the screen already came back on its own.
     *
     * Deliberately does NOT also respawn the keyboard's i2c binding here anymore: an unbind/
     * rebind re-registers the keyboard driver's display notifier, and pairing that with a
     * forced wake (its own display transition) right next to it is exactly the kind of
     * collision that crashed the kernel (bbqX0kbd_disp_notifier_callback null deref) when this
     * used to fire automatically - including, before the telecom-state check above existed, on
     * every mid-call app switch, not just real hangups. "Respawn keyboard" stays available as a
     * deliberate, standalone manual action instead.
     */
    private fun scheduleCallEndScreenRecovery() {
        worker.execute {
            Thread.sleep(5000)
            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && !pm.isInteractive) {
                Log.d("Q25Toolbox", "Screen still off 5s after call end - forcing wake")
                RootShell.run("input keyevent KEYCODE_WAKEUP")
            }
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
        if (node.isCheckable || isAospInCallButton(node)) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findCheckables(child, list)
            child.recycle()
        }
    }

    /**
     * LineageOS' own Dialer (com.android.dialer) builds its in-call actions as plain clickable
     * LinearLayouts (`incall_first_button` .. `incall_sixth_button`), not checkable toggles, so
     * [findCheckables] would otherwise see none of them. Layout confirmed with uiautomator on
     * LineageOS 23; their labels are child TextViews, which [nodeSubtreeContainsLabel] reads.
     */
    private fun isAospInCallButton(node: AccessibilityNodeInfo): Boolean {
        if (node.packageName != AOSP_DIALER) return false
        val id = node.viewIdResourceName ?: return false
        return AOSP_INCALL_BUTTON.matches(id)
    }

    /** The AOSP dialer exposes no checked state for its Keypad button; the DTMF pad existing is the signal. */
    private fun isKeypadToggleOn(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable) return node.isChecked
        val pad = root.findAccessibilityNodeInfosByViewId("$AOSP_DIALER:id/dtmf_twelve_key_dialer_view")
        return !pad.isNullOrEmpty()
    }

    /**
     * Types a mapped digit into the in-call dialpad. The AOSP dialer sends DTMF from its key
     * views, so click the key (zero..nine) rather than set text; Google's keeps the SET_TEXT path.
     * Returns false if the target isn't there yet (caller may retry).
     */
    private fun sendDialerDigit(root: AccessibilityNodeInfo, kc: Int): Boolean {
        val digit = dialerDigitChar(kc) ?: return false
        if (foregroundPkg == AOSP_DIALER) {
            val name = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")[digit - '0']
            val keys = root.findAccessibilityNodeInfosByViewId("$AOSP_DIALER:id/$name")
            if (keys.isNullOrEmpty()) return false
            val ok = keys[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            keys.forEach { it.recycle() }
            return ok
        }
        val field = findDialerDigitsField(root) ?: return false
        insertDialerDigit(field, kc)
        return true
    }

    /** Matches by content-description or text (case-insensitive) instead of a fixed index,
     * since the in-call action bar's button order/count isn't guaranteed across dialer
     * builds/OEM customizations. Returns null (no click) rather than guessing on a miss.
     *
     * Checks the checkable node's own subtree, not just the node itself: these buttons are
     * commonly an icon + a separate label as children of the checkable container, with the
     * checkable node itself carrying neither text nor a content-description - matching only
     * the node directly found nothing and silently broke every shortcut. */
    private fun findCheckableByLabel(checkables: List<AccessibilityNodeInfo>, labels: Set<String>): AccessibilityNodeInfo? =
        checkables.firstOrNull { node -> nodeSubtreeContainsLabel(node, labels) }

    private fun nodeSubtreeContainsLabel(node: AccessibilityNodeInfo, labels: Set<String>): Boolean {
        if (node.contentDescription?.toString()?.trim()?.lowercase() in labels ||
            node.text?.toString()?.trim()?.lowercase() in labels
        ) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (nodeSubtreeContainsLabel(child, labels)) return true
            } finally {
                child.recycle()
            }
        }
        return false
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
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_SPACE -> PinInput.DIGIT_0
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
            PinInput.DELETE -> listOf("delete", "backspace", "suprimeix", "borrar", "eliminar")
            PinInput.ENTER -> listOf("enter", "confirm", "ok", "retorn", "intro", "continuar")
        }
    }


    private fun findPinButton(root: AccessibilityNodeInfo, input: PinInput): AccessibilityNodeInfo? {
        val buttonId = pinButtonId(input)
        findNodeByPredicate(root) { it.viewIdResourceName == buttonId }?.let { return it }

        val pad = findNodeByPredicate(root) {
            it.viewIdResourceName == "com.android.systemui:id/cred_pin_pad" || it.viewIdResourceName == "cred_pin_pad"
        }
        if (pad != null) {
            try {
                val targetText = when (input) {
                    PinInput.DIGIT_0 -> "0"
                    PinInput.DIGIT_1 -> "1"
                    PinInput.DIGIT_2 -> "2"
                    PinInput.DIGIT_3 -> "3"
                    PinInput.DIGIT_4 -> "4"
                    PinInput.DIGIT_5 -> "5"
                    PinInput.DIGIT_6 -> "6"
                    PinInput.DIGIT_7 -> "7"
                    PinInput.DIGIT_8 -> "8"
                    PinInput.DIGIT_9 -> "9"
                    else -> null
                }
                for (i in 0 until pad.childCount) {
                    val child = pad.getChild(i) ?: continue
                    val isMatch = when {
                        targetText != null -> {
                            val textNode = findNodeByPredicate(child) { it.text?.toString()?.trim() == targetText }
                            val matched = (textNode != null)
                            textNode?.recycle()
                            matched
                        }
                        input == PinInput.DELETE -> {
                            val delNode = findNodeByPredicate(child) {
                                it.viewIdResourceName == "com.android.systemui:id/delete_button" ||
                                it.contentDescription?.toString()?.trim()?.lowercase() in listOf("delete", "backspace", "suprimeix", "borrar", "eliminar")
                            }
                            val matched = (delNode != null)
                            delNode?.recycle()
                            matched
                        }
                        input == PinInput.ENTER -> {
                            val enterNode = findNodeByPredicate(child) {
                                it.viewIdResourceName == "com.android.systemui:id/key_enter" ||
                                it.contentDescription?.toString()?.trim()?.lowercase() in listOf("enter", "confirm", "ok", "retorn", "intro", "continuar")
                            }
                            val matched = (enterNode != null)
                            enterNode?.recycle()
                            matched
                        }
                        else -> false
                    }
                    if (isMatch) {
                        if (child.isClickable) {
                            return child
                        }
                        val clickableChild = findNodeByPredicate(child) { it.isClickable }
                        child.recycle()
                        if (clickableChild != null) return clickableChild
                    } else {
                        child.recycle()
                    }
                }
            } finally {
                pad.recycle()
            }
        }

        val labels = pinButtonFallbackLabels(input)
        return findClickableNodeForLabels(root, labels)
    }

    private fun findClickableNodeForLabels(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val lowerLabels = labels.map { it.lowercase() }
        val rect = android.graphics.Rect()

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.trim()?.lowercase()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase()
            val matches = (text != null && text in lowerLabels) || (desc != null && desc in lowerLabels)

            if (matches) {
                node.getBoundsInScreen(rect)
                if (node.isClickable && rect.width() in 1..400 && rect.height() in 1..400) {
                    return AccessibilityNodeInfo.obtain(node)
                }
                val parent = node.parent
                if (parent != null) {
                    parent.getBoundsInScreen(rect)
                    if (parent.isClickable && rect.width() in 1..400 && rect.height() in 1..400) {
                        return parent
                    }
                    parent.recycle()
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    val found = search(child)
                    if (found != null) return found
                } finally {
                    child.recycle()
                }
            }
            return null
        }

        return search(root)
    }

    // ------------------------------------------------------------- Lifecycle

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        GestureStripsController.hide()
        com.kgr.q25toolbox.modules.QrEnlargerController.hide()
        restoreImeBlock()  // never leave the soft keyboard globally suppressed
        restoreScaling()   // never leave the screen stuck at a scaled resolution
        restoreRotation()  // nor stuck in a forced rotation
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** Re-enable the soft keyboard if we'd suppressed it, run synchronously on teardown. */
    private fun restoreImeBlock() {
        if (!imeBlockApplied) return
        imeBlockApplied = false
        applyImeBlock(false)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        GestureStripsController.hide()
        com.kgr.q25toolbox.modules.QrEnlargerController.hide()
        RecentsOverlays.hide(animate = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) callProximity?.stop()
        restoreImeBlock()
        restoreScaling()
        restoreRotation()
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        runActionReceiver?.let { r -> try { unregisterReceiver(r) } catch (_: Exception) {} }
        runActionReceiver = null
        worker.shutdown()
        imeWorker.shutdown()
        autoFocusWorker.shutdown()
        super.onDestroy()
    }

}
