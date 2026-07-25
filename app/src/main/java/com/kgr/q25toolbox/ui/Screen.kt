package com.kgr.q25toolbox.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kgr.q25toolbox.R

sealed class Screen(@StringRes val titleRes: Int, @StringRes val subtitleRes: Int = 0) {
    data object KeyRemap : Screen(R.string.title_key_remap, R.string.subtitle_key_remap)
    data object WirelessAdb : Screen(R.string.title_wireless_adb, R.string.subtitle_wireless_adb)
    data object Dt2w : Screen(R.string.title_dt2w, R.string.subtitle_dt2w)
    data object PinKeyboard : Screen(R.string.title_pin_keyboard, R.string.subtitle_pin_keyboard)
    data object ImeBlock : Screen(R.string.title_ime_block, R.string.subtitle_ime_block)
    data object ChatComposer : Screen(R.string.title_chat_composer, R.string.subtitle_chat_composer)
    data object CalculatorInput : Screen(R.string.title_calculator_input, R.string.subtitle_calculator_input)
    data object BtIdle : Screen(R.string.title_bt_idle, R.string.subtitle_bt_idle)
    data object LocationIdle : Screen(R.string.title_location_idle, R.string.subtitle_location_idle)
    data object Telemetry : Screen(R.string.title_telemetry, R.string.subtitle_telemetry)
    data object ExtraDim : Screen(R.string.title_extra_dim, R.string.subtitle_extra_dim)
    data object BesLoudness : Screen(R.string.title_besloudness, R.string.subtitle_besloudness)
    data object AppScaling : Screen(R.string.title_app_scaling, R.string.subtitle_app_scaling)
    data object AutoFocus : Screen(R.string.title_auto_focus, R.string.subtitle_auto_focus)
    data object InCallShortcuts : Screen(R.string.title_in_call_shortcuts, R.string.subtitle_in_call_shortcuts)
    data object CallScreenRecovery : Screen(R.string.title_call_screen_recovery, R.string.subtitle_call_screen_recovery)
    data object ImeSuggestions : Screen(R.string.title_ime_suggestions, R.string.subtitle_ime_suggestions)
    data object BatteryUsage : Screen(R.string.title_battery_usage)

    val title: String @Composable get() = stringResource(titleRes)
    val subtitle: String @Composable get() = if (subtitleRes != 0) stringResource(subtitleRes) else ""
}

/** Bottom-bar sections. */
enum class AppTab(@StringRes val labelRes: Int) {
    Info(R.string.tab_info),
    Keyboard(R.string.tab_keyboard),
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
)

/** Screens listed under the System tab. */
val systemScreens = listOf(
    // Dt2w intentionally omitted - the software DT2W daemon has repeatedly
    // degraded SystemUI/input dispatch after extended runtime (multiple
    // rewrites, same symptom), so the entry point is hidden. Controller/
    // screen code and the daemon script are kept in the repo in case this
    // is revisited with a different approach.
    Screen.ExtraDim,
    Screen.BesLoudness,
    Screen.AppScaling,
    Screen.AutoFocus,
    Screen.InCallShortcuts,
    Screen.CallScreenRecovery,
)

/** Screens listed under the Network tab. */
val networkScreens = listOf(
    Screen.Telemetry,
    Screen.WirelessAdb,
    Screen.BtIdle,
    Screen.LocationIdle,
)
