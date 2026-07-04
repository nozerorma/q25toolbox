package com.kgr.q25toolbox.ui

sealed class Screen(val title: String, val subtitle: String = "") {
    data object WirelessAdb : Screen("Persistent Wireless ADB", "Static port, survives reboot")
    data object Dt2w : Screen("Double-Tap to Wake", "Wake screen with a double tap")
    data object PinKeyboard : Screen("Lockscreen PIN on Keyboard", "Type your PIN on hardware keys")
    data object ImeBlock : Screen("Per-App Keyboard Block", "Send keys straight to chosen apps")
    data object ChatComposer : Screen("Chat Enter-to-Send", "Enter sends, Alt+Enter newline in chat apps")
    data object CalculatorInput : Screen("Calculator Keys", "Route number & operator keys to the calculator")
    data object BtIdle : Screen("Auto-disable Bluetooth", "Turn off Bluetooth when idle")
    data object Telemetry : Screen("Global Telemetry Block", "Disable Firebase Crashlytics system-wide")
    data object ExtraDim : Screen("Extra Dimming", "Reduce brightness below system minimum")
    data object AppScaling : Screen("Per-App Display Scaling", "Force phone layout on misbehaving apps")
}

/** Bottom-bar sections. */
enum class AppTab(val label: String) {
    Info("Info"),
    Keyboard("Keyboard"),
    System("System"),
    Network("Network"),
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
