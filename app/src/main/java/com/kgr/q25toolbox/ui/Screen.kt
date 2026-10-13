package com.kgr.q25toolbox.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.Rom

sealed class Screen(val route: String, @StringRes val titleRes: Int, @StringRes val subtitleRes: Int = 0) {
    data object KeyRemap : Screen("key_remap", R.string.title_key_remap, R.string.subtitle_key_remap)
    data object WirelessAdb : Screen("wireless_adb", R.string.title_wireless_adb, R.string.subtitle_wireless_adb)
    data object Dt2w : Screen("dt2w", R.string.title_dt2w, R.string.subtitle_dt2w)
    data object PinKeyboard : Screen("pin_keyboard", R.string.title_pin_keyboard, R.string.subtitle_pin_keyboard)
    data object ImeBlock : Screen("ime_block", R.string.title_ime_block, R.string.subtitle_ime_block)
    data object ChatComposer : Screen("chat_composer", R.string.title_chat_composer, R.string.subtitle_chat_composer)
    data object CalculatorInput : Screen("calculator_input", R.string.title_calculator_input, R.string.subtitle_calculator_input)
    data object BtIdle : Screen("bt_idle", R.string.title_bt_idle, R.string.subtitle_bt_idle)
    data object LocationIdle : Screen("location_idle", R.string.title_location_idle, R.string.subtitle_location_idle)
    data object Telemetry : Screen("telemetry", R.string.title_telemetry, R.string.subtitle_telemetry)
    data object ExtraDim : Screen("extra_dim", R.string.title_extra_dim, R.string.subtitle_extra_dim)
    data object BesLoudness : Screen("besloudness", R.string.title_besloudness, R.string.subtitle_besloudness)
    data object AppScaling : Screen("app_scaling", R.string.title_app_scaling, R.string.subtitle_app_scaling)
    data object Rotation : Screen("rotation", R.string.title_rotation, R.string.subtitle_rotation)
    data object AutoFocus : Screen("auto_focus", R.string.title_auto_focus, R.string.subtitle_auto_focus)
    data object InCallShortcuts : Screen("in_call_shortcuts", R.string.title_in_call_shortcuts, R.string.subtitle_in_call_shortcuts)
    data object CallProximitySleep : Screen("call_proximity_sleep", R.string.title_call_proximity_sleep, R.string.subtitle_call_proximity_sleep)
    data object CallScreenRecovery : Screen("call_screen_recovery", R.string.title_call_screen_recovery, R.string.subtitle_call_screen_recovery)
    data object ImeSuggestions : Screen("ime_suggestions", R.string.title_ime_suggestions, R.string.subtitle_ime_suggestions)
    data object BatteryUsage : Screen("battery_usage", R.string.title_battery_usage)
    data object TickerNotifications : Screen("ticker_notifications", R.string.title_ticker_notifications, R.string.subtitle_ticker_notifications)
    data object RecentsTweaks : Screen("recents_tweaks", R.string.title_recents_tweaks, R.string.subtitle_recents_tweaks)
    data object AdBlock : Screen("adblock", R.string.title_adblock, R.string.subtitle_adblock)
    data object Gestures : Screen("gestures", R.string.title_gestures, R.string.subtitle_gestures)
    data object ImeCompat : Screen("ime_compat", R.string.title_ime_compat, R.string.subtitle_ime_compat)
    data object ZygiskDetach : Screen("zygisk_detach", R.string.title_zygisk_detach, R.string.subtitle_zygisk_detach)

    val title: String @Composable get() = stringResource(titleRes)
    val subtitle: String @Composable get() = if (subtitleRes != 0) stringResource(subtitleRes) else ""

    companion object {
        fun fromRoute(route: String?): Screen? {
            if (route == null) return null
            return allScreens.firstOrNull { it.route == route }
        }
    }
}

/** Bottom-bar sections. */
enum class AppTab(@StringRes val labelRes: Int) {
    Info(R.string.tab_info),
    Keyboard(R.string.tab_keyboard),
    Screen(R.string.tab_screen),
    System(R.string.tab_system),
    Network(R.string.tab_network),
    Settings(R.string.tab_settings),
}

/** Screens listed under the Keyboard tab. */
val keyboardScreens = listOf(
    Screen.KeyRemap,
    Screen.PinKeyboard,
    Screen.ImeBlock,
    Screen.ChatComposer,
    Screen.CalculatorInput,
    Screen.ImeSuggestions,
    Screen.ImeCompat,
    Screen.InCallShortcuts,
)

/** Screens listed under the Screen tab. */
val screenScreens = listOf(
    Screen.ExtraDim,
    Screen.AppScaling,
    Screen.Rotation,
    Screen.RecentsTweaks,
    Screen.Gestures,
)

/** Screens listed under the System tab. */
val systemScreens = listOf(
    // Dt2w intentionally omitted - the software DT2W daemon has repeatedly
    // degraded SystemUI/input dispatch after extended runtime (multiple
    // rewrites, same symptom), so the entry point is hidden. Controller/
    // screen code and the daemon script are kept in the repo in case this
    // is revisited with a different approach.
    Screen.BesLoudness,
    Screen.AutoFocus,
    Screen.CallScreenRecovery,
    Screen.CallProximitySleep,
    Screen.TickerNotifications,
    Screen.Dt2w,
)

/** Screens listed under the Network tab. */
val networkScreens = listOf(
    Screen.AdBlock,
    Screen.ZygiskDetach,
    Screen.Telemetry,
    Screen.WirelessAdb,
    Screen.BtIdle,
    Screen.LocationIdle,
)

val allScreens = keyboardScreens + screenScreens + systemScreens + networkScreens + listOf(Screen.BatteryUsage, Screen.Dt2w)

/**
 * Screens that make no sense on a given ROM are hidden from the menus (the code stays). Only
 * hardware/ROM facts belong here; BenOS and ZinwaOS keep the full list.
 *  - BesLoudness drives MediaTek's HAL parameter, which LineageOS does not expose.
 */
fun Screen.availableOn(rom: Rom?): Boolean = when (this) {
    Screen.BesLoudness -> rom != Rom.LINEAGE
    // On LineageOS the older stuck-proximity recovery is replaced by Call Proximity Sleep, which handles the
    // opposite and more likely failure there (screen staying lit at the ear). BenOS keeps its own module.
    Screen.CallScreenRecovery -> rom != Rom.LINEAGE
    Screen.CallProximitySleep -> rom == Rom.LINEAGE
    // The always-on BenOS watchdog stays hidden (it degraded SystemUI); the Lineage screen-off listener is safe to show.
    Screen.Dt2w -> rom == Rom.LINEAGE
    // A LineageOS-specific fix for the BlackBerry keyboard on the Q25 (see ImeCompat).
    Screen.ImeCompat -> rom == Rom.LINEAGE
    else -> true
}
