package com.kgr.q25toolbox.modules

import android.content.Context
import com.kgr.q25toolbox.service.EdgeSwipe
import com.kgr.q25toolbox.service.Q25AccessibilityService

/**
 * Persisted configuration for the custom edge gestures. Stored in the shared "q25tweaks" prefs (the service
 * already listens to that file, so a change is applied live) under the "gest_" prefix.
 *
 * Three zones are configured independently: LEFT, RIGHT and BOTTOM. Each zone is Off or Custom. LEFT and RIGHT
 * fall back to the single side setup of earlier versions (`gest_lat_*`) for any value not yet saved, so an
 * upgrade keeps what the user had on both sides. A "Native" mode is deliberately absent: on the Q25's LineageOS the system gesture navigation
 * cannot be switched on (see the lineage-support notes), so there is nothing native to hand over to.
 */
object GestureSettings {

    enum class Zone(val prefix: String, val legacyPrefix: String? = null) {
        LEFT("gest_left_", "gest_lat_"), RIGHT("gest_right_", "gest_lat_"), BOTTOM("gest_bot_");
        val isSide get() = this != BOTTOM
    }
    enum class Mode { OFF, CUSTOM }

    const val KEY_PREFIX = "gest_"
    const val KEY_SHOW_STRIPS = "gest_show_strips"
    private const val K_MODE = "mode"
    private const val K_THICKNESS = "thickness_dp"
    private const val K_LENGTH = "length_pct"
    private const val K_DISTANCE = "distance_dp"
    private const val K_HAPTIC = "haptic"
    private const val K_ARROW = "arrow"

    // Ranges are in dp / percent so they scale with the display density.
    val THICKNESS_RANGE = 6f..32f
    val LENGTH_RANGE = 20f..100f
    /** Swipe distance that triggers the action: small = sensitive. */
    val DISTANCE_RANGE = 8f..80f

    /** How long a bottom swipe must be held after crossing the threshold to count as "up and hold" (Recents). */
    const val HOLD_MS = 350L

    data class Config(
        val mode: Mode,
        val thicknessDp: Int,
        val lengthPct: Int,
        val distanceDp: Int,
        val haptic: Boolean,
        /** Draw an arrow overlay that follows the finger during the swipe. */
        val arrow: Boolean = false,
    )

    private fun defaults(zone: Zone) = when (zone) {
        // Side strips: thin, covering the middle 60% of the height (keeps clear of the corners and status bar).
        Zone.LEFT, Zone.RIGHT -> Config(Mode.OFF, 14, 60, 28, true)
        // Bottom strip: thin so it takes little of the keyboard/app UI; 50% of the width, centred.
        Zone.BOTTOM -> Config(Mode.OFF, 12, 50, 32, true)
    }

    /** Value of [suffix] for [zone]: its own key, else the legacy side key (LEFT/RIGHT only), else [def]. Pure, for tests. */
    internal fun <T> pick(zone: Zone, suffix: String, lookup: (String) -> T?, def: T): T =
        lookup(zone.prefix + suffix) ?: zone.legacyPrefix?.let { lookup(it + suffix) } ?: def

    fun get(context: Context, zone: Zone): Config {
        val p = context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
        val d = defaults(zone)
        fun str(k: String) = if (p.contains(k)) p.getString(k, null) else null
        fun int(k: String) = if (p.contains(k)) p.getInt(k, 0) else null
        fun bool(k: String) = if (p.contains(k)) p.getBoolean(k, false) else null
        val mode = runCatching { Mode.valueOf(pick(zone, K_MODE, ::str, d.mode.name)) }.getOrDefault(d.mode)
        return Config(
            mode = mode,
            thicknessDp = pick(zone, K_THICKNESS, ::int, d.thicknessDp).coerceIn(THICKNESS_RANGE.start.toInt(), THICKNESS_RANGE.endInclusive.toInt()),
            lengthPct = pick(zone, K_LENGTH, ::int, d.lengthPct).coerceIn(LENGTH_RANGE.start.toInt(), LENGTH_RANGE.endInclusive.toInt()),
            distanceDp = pick(zone, K_DISTANCE, ::int, d.distanceDp).coerceIn(DISTANCE_RANGE.start.toInt(), DISTANCE_RANGE.endInclusive.toInt()),
            haptic = pick(zone, K_HAPTIC, ::bool, d.haptic),
            arrow = pick(zone, K_ARROW, ::bool, d.arrow),
        )
    }

    fun set(context: Context, zone: Zone, c: Config) {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(zone.prefix + K_MODE, c.mode.name)
            .putInt(zone.prefix + K_THICKNESS, c.thicknessDp)
            .putInt(zone.prefix + K_LENGTH, c.lengthPct)
            .putInt(zone.prefix + K_DISTANCE, c.distanceDp)
            .putBoolean(zone.prefix + K_HAPTIC, c.haptic)
            .putBoolean(zone.prefix + K_ARROW, c.arrow)
            .apply()
    }

    // --- native bottom gesture (launcher swipe-up) --------------------------------------------------------

    const val KEY_NATIVE_BOTTOM_OFF = "gest_bot_native_off"

    /**
     * Whether the launcher's native swipe-up should be switched off. Until the user chooses, it follows the bottom
     * strip (off exactly while the strip is Custom), which is what earlier versions did; once chosen it is independent.
     */
    fun nativeBottomOff(context: Context): Boolean {
        val p = context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
        return if (p.contains(KEY_NATIVE_BOTTOM_OFF)) p.getBoolean(KEY_NATIVE_BOTTOM_OFF, false)
        else get(context, Zone.BOTTOM).mode == Mode.CUSTOM
    }

    fun setNativeBottomOff(context: Context, off: Boolean) {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NATIVE_BOTTOM_OFF, off).apply()
    }

    // Vibration is shared by all zones (each zone still has its own on/off switch).
    private const val KEY_VIB_TICK = "gest_vib_tick_ms"
    private const val KEY_VIB_ACTION = "gest_vib_action_ms"
    private const val KEY_VIB_STRENGTH = "gest_vib_strength_pct"
    val VIB_TICK_RANGE = 0f..80f       // 0 = no tick at the threshold
    val VIB_ACTION_RANGE = 10f..200f
    val VIB_STRENGTH_RANGE = 10f..100f // only honoured by motors with amplitude control

    data class Vibration(val tickMs: Int, val actionMs: Int, val strengthPct: Int)

    fun vibration(context: Context): Vibration {
        val p = context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
        return Vibration(p.getInt(KEY_VIB_TICK, 15), p.getInt(KEY_VIB_ACTION, 35), p.getInt(KEY_VIB_STRENGTH, 100))
    }

    fun setVibration(context: Context, v: Vibration) {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VIB_TICK, v.tickMs).putInt(KEY_VIB_ACTION, v.actionMs).putInt(KEY_VIB_STRENGTH, v.strengthPct).apply()
    }

    // --- what each gesture does -------------------------------------------------------------------------

    /** Things a gesture can do. NONE leaves the gesture unassigned. Names are stored in prefs: do not rename. */
    enum class Action {
        NONE, BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT, POWER_MENU, SPLIT_SCREEN, FLASHLIGHT, PREVIOUS_APP, QR_ENLARGER,
        ASPECT_RATIO, ROTATION, EXTRA_DIM, GESTURES, BLUETOOTH, LOCATION, CIRCLE_TO_SEARCH
    }

    /** Parses an action name from an external caller (case-insensitive); null if it is not one of ours. Pure, for tests. */
    fun parseAction(name: String?): Action? {
        val n = name?.trim()?.uppercase() ?: return null
        if (n == "ENLARGE_QR" || n == "QR") return Action.QR_ENLARGER
        if (n == "RESOLUTION" || n == "RES" || n == "ASPECT") return Action.ASPECT_RATIO
        if (n == "ROTATE" || n == "AUTO_ROTATE") return Action.ROTATION
        if (n == "DIM" || n == "EXTRA_DIMMING") return Action.EXTRA_DIM
        if (n == "GESTURE" || n == "EDGE_GESTURES" || n == "STRIPS") return Action.GESTURES
        if (n == "BT") return Action.BLUETOOTH
        if (n == "LOC" || n == "GPS") return Action.LOCATION
        if (n == "CTS" || n == "CIRCLE_TO_SEARCH" || n == "OMNI" || n == "SEARCH") return Action.CIRCLE_TO_SEARCH
        return Action.entries.firstOrNull { it.name == n }
    }

    /** Gestures offered per zone: side edges tell straight from the two diagonals, the bottom edge only swipes up. */
    fun dirsOf(zone: Zone): List<EdgeSwipe.Dir> = when (zone) {
        Zone.LEFT, Zone.RIGHT -> listOf(EdgeSwipe.Dir.STRAIGHT, EdgeSwipe.Dir.DIAG_A, EdgeSwipe.Dir.DIAG_B)
        Zone.BOTTOM -> listOf(EdgeSwipe.Dir.STRAIGHT)
    }

    private fun bindingSuffix(dir: EdgeSwipe.Dir, hold: Boolean) = "act_${dir.name.lowercase()}_${if (hold) "hold" else "swipe"}"

    private fun bindingKey(zone: Zone, dir: EdgeSwipe.Dir, hold: Boolean) = zone.prefix + bindingSuffix(dir, hold)

    /**
     * Defaults: side swipe = Back, side hold = Previous app, side diagonal down = Notifications, side diagonal up =
     * Quick settings; bottom swipe = Home, bottom hold = Recents.
     */
    fun defaultBinding(zone: Zone, dir: EdgeSwipe.Dir, hold: Boolean): Action = when {
        zone.isSide && dir == EdgeSwipe.Dir.STRAIGHT -> if (hold) Action.PREVIOUS_APP else Action.BACK
        zone.isSide && dir == EdgeSwipe.Dir.DIAG_B && !hold -> Action.NOTIFICATIONS
        zone.isSide && dir == EdgeSwipe.Dir.DIAG_A && !hold -> Action.QUICK_SETTINGS
        zone == Zone.BOTTOM && dir == EdgeSwipe.Dir.STRAIGHT -> if (hold) Action.RECENTS else Action.HOME
        else -> Action.NONE
    }

    fun binding(context: Context, zone: Zone, dir: EdgeSwipe.Dir, hold: Boolean): Action {
        val p = context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
        val d = defaultBinding(zone, dir, hold)
        val name = pick(zone, bindingSuffix(dir, hold), { k -> if (p.contains(k)) p.getString(k, null) else null }, d.name)
        return runCatching { Action.valueOf(name) }.getOrDefault(d)
    }

    /** Copies every setting and binding of [from] onto [to] (the side strips are configured separately). */
    fun copyZone(context: Context, from: Zone, to: Zone) {
        set(context, to, get(context, from))
        for (d in dirsOf(from)) for (hold in listOf(false, true)) setBinding(context, to, d, hold, binding(context, from, d, hold))
    }

    fun setBinding(context: Context, zone: Zone, dir: EdgeSwipe.Dir, hold: Boolean, a: Action) {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(bindingKey(zone, dir, hold), a.name).apply()
    }

    // --- apps where the strips are switched off ---------------------------------------------------------

    private const val KEY_EXCLUDED = "gest_excluded_apps"

    fun excludedApps(context: Context): Set<String> =
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED, emptySet())?.toSet() ?: emptySet()

    fun setExcludedApps(context: Context, apps: Set<String>) {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_EXCLUDED, apps).apply()
    }

    // --- look of the arrow overlay (shared by both zones) -----------------------------------------------

    /** Colours are opaque RGB ints; the badge opacity is separate. All sizes in dp. */
    data class ArrowStyle(
        val arrowColor: Int = 0xFFFFFFFF.toInt(),
        val inactiveColor: Int = 0xFF9E9E9E.toInt(),
        val activeColor: Int = 0xFF000000.toInt(),
        val sizeDp: Int = 22,          // badge radius
        val travelDp: Int = 50,        // how far it slides out at the threshold
        val opacityPct: Int = 70,
        val thicknessDp: Int = 3,      // chevron stroke
        val speedPct: Int = 100,       // animation speed: 200 = twice as fast
        val showBadge: Boolean = false, // false = the bare arrow
        val followTilt: Boolean = true // arrow turns with the finger's direction
    )

    val ARROW_SIZE_RANGE = 12f..40f
    val ARROW_TRAVEL_RANGE = 30f..100f
    val ARROW_OPACITY_RANGE = 30f..100f
    val ARROW_THICKNESS_RANGE = 1f..6f
    val ARROW_SPEED_RANGE = 25f..300f

    private const val A = "gest_arrow_"

    fun arrowStyle(context: Context): ArrowStyle {
        val p = context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
        val d = ArrowStyle()
        // A colour picked from the Material You palette is stored as a reference and resolved now, so it follows
        // the wallpaper; the saved int is only the fallback (and what older readers see).
        fun colour(slot: String, def: Int): Int =
            colorRef(context, slot)?.let { parseMyRef(it) }?.let { (f, t) -> myColor(context, f, t) }
                ?: (p.getInt(A + slot, def) or 0xFF000000.toInt())
        fun i(k: String, def: Int, r: ClosedFloatingPointRange<Float>) = p.getInt(A + k, def).coerceIn(r.start.toInt(), r.endInclusive.toInt())
        return ArrowStyle(
            arrowColor = colour("color", d.arrowColor),
            inactiveColor = colour("inactive", d.inactiveColor),
            activeColor = colour("active", d.activeColor),
            sizeDp = i("size", d.sizeDp, ARROW_SIZE_RANGE),
            travelDp = i("travel", d.travelDp, ARROW_TRAVEL_RANGE),
            opacityPct = i("opacity", d.opacityPct, ARROW_OPACITY_RANGE),
            thicknessDp = i("thickness", d.thicknessDp, ARROW_THICKNESS_RANGE),
            speedPct = i("speed", d.speedPct, ARROW_SPEED_RANGE),
            showBadge = p.getBoolean(A + "badge", d.showBadge),
            followTilt = p.getBoolean(A + "tilt", d.followTilt),
        )
    }

    fun setArrowStyle(context: Context, s: ArrowStyle) {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).edit()
            .putInt(A + "color", s.arrowColor).putInt(A + "inactive", s.inactiveColor).putInt(A + "active", s.activeColor)
            .putInt(A + "size", s.sizeDp).putInt(A + "travel", s.travelDp).putInt(A + "opacity", s.opacityPct)
            .putInt(A + "thickness", s.thicknessDp).putInt(A + "speed", s.speedPct)
            .putBoolean(A + "badge", s.showBadge).putBoolean(A + "tilt", s.followTilt).apply()
    }

    // --- Material You palette references (Android 12+) ---------------------------------------------------

    /** Dynamic-colour families, the framework resources `system_<family>_<tone>`, in the order ColorBlendr lists them. */
    val MY_FAMILIES = listOf("accent1", "accent2", "accent3", "neutral1", "neutral2")
    val MY_TONES = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

    /** The three arrow colours that can hold a palette reference. */
    val COLOR_SLOTS = listOf("color", "inactive", "active")

    fun myRef(family: String, tone: Int) = "$family:$tone"

    /** "accent1:500" -> (accent1, 500); null if [ref] is not a valid reference. Pure, for tests. */
    internal fun parseMyRef(ref: String?): Pair<String, Int>? {
        val parts = ref?.split(':') ?: return null
        if (parts.size != 2) return null
        val tone = parts[1].toIntOrNull() ?: return null
        return if (parts[0] in MY_FAMILIES && tone in MY_TONES) parts[0] to tone else null
    }

    /** Current value of a palette colour (it changes with the wallpaper); null below Android 12 or for an unknown name. */
    fun myColor(context: Context, family: String, tone: Int): Int? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return null
        val id = context.resources.getIdentifier("system_${family}_$tone", "color", "android")
        return if (id == 0) null else runCatching { context.getColor(id) or 0xFF000000.toInt() }.getOrNull()
    }

    fun colorRef(context: Context, slot: String): String? =
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
            .getString(A + slot + "_ref", null)?.takeIf { parseMyRef(it) != null }

    /** Sets (or, with null, clears) the palette reference of [slot]; picking any other colour clears it. */
    fun setColorRef(context: Context, slot: String, ref: String?) {
        val e = context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).edit()
        if (ref == null) e.remove(A + slot + "_ref") else e.putString(A + slot + "_ref", ref)
        e.apply()
    }

    fun clearColorRefs(context: Context) { for (slot in COLOR_SLOTS) setColorRef(context, slot, null) }

    private val ARROW_KEYS = listOf("color", "inactive", "active", "size", "travel", "opacity", "thickness", "speed", "badge", "tilt").map { A + it } +
        COLOR_SLOTS.map { A + it + "_ref" }

    /** Every pref key this module owns (for backup/restore). */
    fun allKeys(): List<String> {
        val suffixes = listOf(K_MODE, K_THICKNESS, K_LENGTH, K_DISTANCE, K_HAPTIC, K_ARROW)
        val prefixes = Zone.entries.flatMap { listOfNotNull(it.prefix, it.legacyPrefix) }.distinct()
        val dirs = EdgeSwipe.Dir.entries.flatMap { d -> listOf(false, true).map { bindingSuffix(d, it) } }
        return prefixes.flatMap { pre -> (suffixes + dirs).map { pre + it } } + KEY_NATIVE_BOTTOM_OFF + KEY_EXCLUDED +
            ARROW_KEYS + KEY_SHOW_STRIPS + listOf(KEY_VIB_TICK, KEY_VIB_ACTION, KEY_VIB_STRENGTH)
    }

    fun showStrips(context: Context): Boolean =
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_STRIPS, false)

    fun setShowStrips(context: Context, on: Boolean) {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_STRIPS, on).apply()
    }
}
