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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.DaemonMaintenance
import com.kgr.q25toolbox.settings.SettingsScreen
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Top-level navigation: a bottom bar with Info / Keyboard / System sections.
 * Info is the landing page (device status); the other two list their modules,
 * and tapping one opens its detail screen.
 */
@Composable
fun HomeScreen() {
    var tabName by rememberSaveable { mutableStateOf(AppTab.Info.name) }
    var detailRoute by rememberSaveable { mutableStateOf<String?>(null) }

    val tab = remember(tabName) { AppTab.entries.firstOrNull { it.name == tabName } ?: AppTab.Info }
    val detail = remember(detailRoute) { Screen.fromRoute(detailRoute) }

    // Hoisted above the `when(tab)` branches (which are disposed/recreated on every
    // tab switch, losing their own `remember` state) so revisiting Info doesn't
    // flash back to empty cards and re-fetch every time - the "wonky, pops up" jank.
    val infoState = remember { InfoState() }
    val infoScrollState = rememberScrollState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { infoState.refresh(context) }
    // Repairs/removes daemon scripts for every module here, once per launch, instead
    // of relying on the user to open each module's own screen to trigger its self-heal
    // - see DaemonMaintenance for why that matters.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { DaemonMaintenance.sweep(context) }
    }

    val currentVersionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    BackHandler(enabled = detail != null) { detailRoute = null }

    WhatsNewDialog()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.Info && detail == null,
                    onClick = { tabName = AppTab.Info.name; detailRoute = null },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(AppTab.Info.labelRes)) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Keyboard,
                    onClick = { tabName = AppTab.Keyboard.name; detailRoute = null },
                    icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                    label = { Text(stringResource(AppTab.Keyboard.labelRes)) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Screen,
                    onClick = { tabName = AppTab.Screen.name; detailRoute = null },
                    icon = { Icon(Icons.Filled.Smartphone, contentDescription = null) },
                    label = { Text(stringResource(AppTab.Screen.labelRes)) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.System,
                    onClick = { tabName = AppTab.System.name; detailRoute = null },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text(stringResource(AppTab.System.labelRes)) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Network,
                    onClick = { tabName = AppTab.Network.name; detailRoute = null },
                    icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                    label = { Text(stringResource(AppTab.Network.labelRes)) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Settings,
                    onClick = { tabName = AppTab.Settings.name; detailRoute = null },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(AppTab.Settings.labelRes)) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val current = detail
            if (current != null) {
                DetailHost(current) { detailRoute = null }
            } else when (tab) {
                AppTab.Info -> InfoScreen(
                    state = infoState,
                    scrollState = infoScrollState,
                    onOpenBatteryUsage = { detailRoute = Screen.BatteryUsage.route }
                )
                AppTab.Keyboard -> CategoryMenu(stringResource(R.string.tab_keyboard), keyboardScreens) { detailRoute = it.route }
                AppTab.Screen -> CategoryMenu(stringResource(R.string.tab_screen), screenScreens) { detailRoute = it.route }
                AppTab.System -> CategoryMenu(stringResource(R.string.tab_system), systemScreens) { detailRoute = it.route }
                AppTab.Network -> CategoryMenu(stringResource(R.string.tab_network), networkScreens) { detailRoute = it.route }
                AppTab.Settings -> SettingsScreen(currentVersionName = currentVersionName)
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
        Screen.LocationIdle -> LocationIdleScreen(onBack)
        Screen.Telemetry -> TelemetryScreen(onBack)
        Screen.ExtraDim -> ExtraDimScreen(onBack)
        Screen.BesLoudness -> BesLoudnessScreen(onBack)
        Screen.AppScaling -> AppScalingScreen(onBack)
        Screen.AutoFocus -> AutoFocusScreen(onBack)
        Screen.InCallShortcuts -> InCallShortcutsScreen(onBack)
        Screen.CallScreenRecovery -> CallScreenRecoveryScreen(onBack)
        Screen.ImeSuggestions -> ImeSuggestionsScreen(onBack)
        Screen.BatteryUsage -> BatteryUsageScreen(onBack)
        Screen.TickerNotifications -> TickerNotificationsScreen(onBack)
        Screen.RecentsTweaks -> RecentsTweaksScreen(onBack)
        Screen.AdBlock -> AdBlockScreen(onBack)
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
