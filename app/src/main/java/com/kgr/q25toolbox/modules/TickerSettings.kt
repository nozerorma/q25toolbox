package com.kgr.q25toolbox.modules

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.kgr.q25toolbox.R

/**
 * Persisted configuration for the Ticker Notifications module - kept in its own
 * SharedPreferences file (separate from [Q25AccessibilityService.PREFS]) since none of
 * this depends on the accessibility service.
 */
object TickerSettings {

    private const val PREFS = "ticker_notifications"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_TAP_TO_OPEN = "tap_to_open"
    private const val KEY_MIN_IMPORTANCE = "min_importance"
    private const val KEY_INCLUDE_ONGOING = "include_ongoing"
    private const val KEY_MAX_BODY_LINES = "max_body_lines"
    private const val KEY_SCROLL_SPEED = "scroll_speed_dp_per_sec"
    private const val KEY_START_DELAY = "start_delay_ms"
    private const val KEY_BLOCKED_APPS = "blocked_apps"
    private const val KEY_BLOCKED_CATEGORIES = "blocked_categories"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_FIXED_COLOR = "fixed_color"

    const val DEFAULT_MIN_IMPORTANCE = NotificationManager.IMPORTANCE_DEFAULT
    const val DEFAULT_MAX_BODY_LINES = 1
    const val DEFAULT_SCROLL_SPEED_DP = 100
    const val DEFAULT_START_DELAY_MS = 1500
    const val DEFAULT_FIXED_COLOR = 0xFF000000.toInt()

    /** How the ticker banner's background color is chosen. */
    enum class ColorMode { FIXED, APP_ICON, MONET }

    /** Preset swatches offered for [ColorMode.FIXED]. */
    val PRESET_COLORS = listOf(
        0xFF000000.toInt(), // black
        0xFF212121.toInt(), // dark grey
        0xFF1A237E.toInt(), // indigo
        0xFF01579B.toInt(), // deep blue
        0xFF004D40.toInt(), // teal
        0xFF1B5E20.toInt(), // green
        0xFF4A148C.toInt(), // purple
        0xFFB71C1C.toInt(), // red
        0xFFE65100.toInt(), // orange
        0xFF263238.toInt(), // blue grey
    )

    val BODY_LINES_OPTIONS = listOf(1, 2, 3)
    val SCROLL_SPEED_RANGE = 20f..150f
    val START_DELAY_RANGE = 0f..3000f

    /** (importance, label resource) pairs for the min-priority chip row, low to high. */
    val IMPORTANCE_OPTIONS = listOf(
        NotificationManager.IMPORTANCE_MIN to R.string.ticker_priority_min,
        NotificationManager.IMPORTANCE_LOW to R.string.ticker_priority_low,
        NotificationManager.IMPORTANCE_DEFAULT to R.string.ticker_priority_default,
        NotificationManager.IMPORTANCE_HIGH to R.string.ticker_priority_high,
    )

    /** (category constant, friendly label resource) pairs for the blocked-categories checklist. */
    val CATEGORY_OPTIONS: List<Pair<String, Int>> = listOf(
        Notification.CATEGORY_CALL to R.string.ticker_category_calls,
        Notification.CATEGORY_MESSAGE to R.string.ticker_category_messages,
        Notification.CATEGORY_EMAIL to R.string.ticker_category_email,
        Notification.CATEGORY_EVENT to R.string.ticker_category_events,
        Notification.CATEGORY_PROMO to R.string.ticker_category_promo,
        Notification.CATEGORY_ALARM to R.string.ticker_category_alarms,
        Notification.CATEGORY_PROGRESS to R.string.ticker_category_progress,
        Notification.CATEGORY_SOCIAL to R.string.ticker_category_social,
        Notification.CATEGORY_ERROR to R.string.ticker_category_errors,
        Notification.CATEGORY_TRANSPORT to R.string.ticker_category_transport,
        Notification.CATEGORY_SYSTEM to R.string.ticker_category_system,
        Notification.CATEGORY_SERVICE to R.string.ticker_category_service,
        Notification.CATEGORY_REMINDER to R.string.ticker_category_reminder,
        Notification.CATEGORY_RECOMMENDATION to R.string.ticker_category_recommendation,
        Notification.CATEGORY_MISSED_CALL to R.string.ticker_category_missed_call,
        Notification.CATEGORY_NAVIGATION to R.string.ticker_category_navigation,
        Notification.CATEGORY_STOPWATCH to R.string.ticker_category_stopwatch,
        Notification.CATEGORY_WORKOUT to R.string.ticker_category_workout,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabledFlag(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isTapToOpen(context: Context): Boolean = prefs(context).getBoolean(KEY_TAP_TO_OPEN, true)
    fun setTapToOpen(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TAP_TO_OPEN, value).apply()
    }

    fun minImportance(context: Context): Int = prefs(context).getInt(KEY_MIN_IMPORTANCE, DEFAULT_MIN_IMPORTANCE)
    fun setMinImportance(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MIN_IMPORTANCE, value).apply()
    }

    fun includeOngoing(context: Context): Boolean = prefs(context).getBoolean(KEY_INCLUDE_ONGOING, false)
    fun setIncludeOngoing(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_ONGOING, value).apply()
    }

    fun maxBodyLines(context: Context): Int = prefs(context).getInt(KEY_MAX_BODY_LINES, DEFAULT_MAX_BODY_LINES)
    fun setMaxBodyLines(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MAX_BODY_LINES, value).apply()
    }

    fun scrollSpeedDpPerSec(context: Context): Int = prefs(context).getInt(KEY_SCROLL_SPEED, DEFAULT_SCROLL_SPEED_DP)
    fun setScrollSpeedDpPerSec(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SCROLL_SPEED, value).apply()
    }

    fun startDelayMs(context: Context): Int = prefs(context).getInt(KEY_START_DELAY, DEFAULT_START_DELAY_MS)
    fun setStartDelayMs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_START_DELAY, value).apply()
    }

    fun blockedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
    fun setBlockedApps(context: Context, value: Set<String>) {
        prefs(context).edit().putStringSet(KEY_BLOCKED_APPS, value).apply()
    }

    fun blockedCategories(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCKED_CATEGORIES, emptySet()) ?: emptySet()
    fun setBlockedCategories(context: Context, value: Set<String>) {
        prefs(context).edit().putStringSet(KEY_BLOCKED_CATEGORIES, value).apply()
    }

    fun colorMode(context: Context): ColorMode {
        val name = prefs(context).getString(KEY_COLOR_MODE, null)
        return name?.let { runCatching { ColorMode.valueOf(it) }.getOrNull() } ?: ColorMode.FIXED
    }
    fun setColorMode(context: Context, mode: ColorMode) {
        prefs(context).edit().putString(KEY_COLOR_MODE, mode.name).apply()
    }

    fun fixedColor(context: Context): Int = prefs(context).getInt(KEY_FIXED_COLOR, DEFAULT_FIXED_COLOR)
    fun setFixedColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_FIXED_COLOR, color).apply()
    }
}
