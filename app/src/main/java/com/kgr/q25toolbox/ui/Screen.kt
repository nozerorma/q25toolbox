package com.kgr.q25toolbox.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kgr.q25toolbox.R

sealed class Screen(@StringRes val titleRes: Int, @StringRes val subtitleRes: Int = 0) {
    data object WirelessAdb : Screen(R.string.title_wireless_adb, R.string.subtitle_wireless_adb)
    data object Dt2w : Screen(R.string.title_dt2w, R.string.subtitle_dt2w)
    data object PinKeyboard : Screen(R.string.title_pin_keyboard, R.string.subtitle_pin_keyboard)
    data object ImeBlock : Screen(R.string.title_ime_block, R.string.subtitle_ime_block)
    data object ChatComposer : Screen(R.string.title_chat_composer, R.string.subtitle_chat_composer)
    data object CalculatorInput : Screen(R.string.title_calculator_input, R.string.subtitle_calculator_input)
    data object BtIdle : Screen(R.string.title_bt_idle, R.string.subtitle_bt_idle)
    data object Telemetry : Screen(R.string.title_telemetry, R.string.subtitle_telemetry)
    data object ExtraDim : Screen(R.string.title_extra_dim, R.string.subtitle_extra_dim)
    data object AppScaling : Screen(R.string.title_app_scaling, R.string.subtitle_app_scaling)

    val title: String @Composable get() = stringResource(titleRes)
    val subtitle: String @Composable get() = if (subtitleRes != 0) stringResource(subtitleRes) else ""
}

/** Bottom-bar sections. */
enum class AppTab(@StringRes val labelRes: Int) {
    Info(R.string.tab_info),
    Keyboard(R.string.tab_keyboard),
    System(R.string.tab_system),
    Network(R.string.tab_network),
}

/** Screens listed under the Keyboard tab. */
val keyboardScreens = listOf(
    Screen.PinKeyboard,
    Screen.ImeBlock,
    Screen.ChatComposer,
    Screen.CalculatorInput,
)

/** Screens listed under the System tab. */
val systemScreens = listOf(
    Screen.Dt2w,
    Screen.ExtraDim,
    Screen.AppScaling,
)

/** Screens listed under the Network tab. */
val networkScreens = listOf(
    Screen.Telemetry,
    Screen.WirelessAdb,
    Screen.BtIdle,
)
