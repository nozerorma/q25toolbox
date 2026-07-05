package com.kgr.q25toolbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R

/**
 * Top-level navigation: a bottom bar with Info / Keyboard / System sections.
 * Info is the landing page (device status); the other two list their modules,
 * and tapping one opens its detail screen.
 */
@Composable
fun HomeScreen() {
    var tab by remember { mutableStateOf(AppTab.Info) }
    var detail by remember { mutableStateOf<Screen?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.Info && detail == null,
                    onClick = { tab = AppTab.Info; detail = null },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(AppTab.Info.labelRes)) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Keyboard,
                    onClick = { tab = AppTab.Keyboard; detail = null },
                    icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                    label = { Text(stringResource(AppTab.Keyboard.labelRes)) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.System,
                    onClick = { tab = AppTab.System; detail = null },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text(stringResource(AppTab.System.labelRes)) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Network,
                    onClick = { tab = AppTab.Network; detail = null },
                    icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                    label = { Text(stringResource(AppTab.Network.labelRes)) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val current = detail
            if (current != null) {
                DetailHost(current) { detail = null }
            } else when (tab) {
                AppTab.Info -> InfoScreen()
                AppTab.Keyboard -> CategoryMenu(stringResource(R.string.tab_keyboard), keyboardScreens) { detail = it }
                AppTab.System -> CategoryMenu(stringResource(R.string.tab_system), systemScreens) { detail = it }
                AppTab.Network -> CategoryMenu(stringResource(R.string.tab_network), networkScreens) { detail = it }
            }
        }
    }
}

/** Routes a [Screen] to its detail composable. */
@Composable
private fun DetailHost(screen: Screen, onBack: () -> Unit) {
    when (screen) {
        Screen.KeyRemap -> KeyRemapScreen(onBack)
        Screen.WirelessAdb -> WirelessAdbScreen(onBack)
        Screen.Dt2w -> Dt2wScreen(onBack)
        Screen.PinKeyboard -> PinKeyboardScreen(onBack)
        Screen.ImeBlock -> ImeBlockScreen(onBack)
        Screen.ChatComposer -> ChatComposerScreen(onBack)
        Screen.CalculatorInput -> CalculatorInputScreen(onBack)
        Screen.BtIdle -> BtIdleScreen(onBack)
        Screen.Telemetry -> TelemetryScreen(onBack)
        Screen.ExtraDim -> ExtraDimScreen(onBack)
        Screen.AppScaling -> AppScalingScreen(onBack)
        Screen.AutoFocus -> AutoFocusScreen(onBack)
        Screen.InCallShortcuts -> InCallShortcutsScreen(onBack)
    }
}

@Composable
private fun CategoryMenu(title: String, screens: List<Screen>, onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        screens.forEach { screen ->
            MenuEntry(screen) { onNavigate(screen) }
        }
    }
}

@Composable
private fun MenuEntry(screen: Screen, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(stringResource(screen.titleRes), style = MaterialTheme.typography.titleMedium)
            if (screen.subtitleRes != 0) {
                Text(
                    stringResource(screen.subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
