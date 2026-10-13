package com.kgr.q25toolbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.kgr.q25toolbox.modules.PinKeysRoot
import com.kgr.q25toolbox.core.RomProfile
import com.kgr.q25toolbox.service.Q25AccessibilityService
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled

@Composable
fun PinKeyboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    val scope = rememberCoroutineScope()
    // The root daemon follows these two switches; the service's own listener does too, but it may not be running.
    fun syncDaemon() { scope.launch(Dispatchers.IO) { PinKeysRoot.reconcile(context, prefs) } }

    var serviceEnabled by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(Q25AccessibilityService.KEY_PIN_INPUT, true)) }

    var enterOpens by remember { mutableStateOf(prefs.getBoolean(Q25AccessibilityService.KEY_LOCKSCREEN_ENTER_OPENS_PIN, false)) }
    var navBlock by remember { mutableStateOf(prefs.getBoolean(Q25AccessibilityService.KEY_LOCKSCREEN_NAV_BLOCK, false)) }

    LaunchedEffect(Unit) {
        // Same default the service uses: on only on LineageOS, unless the user already chose. Reads build props.
        enterOpens = withContext(Dispatchers.IO) {
            prefs.getBoolean(Q25AccessibilityService.KEY_LOCKSCREEN_ENTER_OPENS_PIN, RomProfile.autoDetectedLineage())
        }
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
    }

    ScreenScaffold(title = stringResource(Screen.PinKeyboard.titleRes), onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generic_enabled))
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    prefs.edit().putBoolean(Q25AccessibilityService.KEY_PIN_INPUT, checked).apply()
                    syncDaemon()
                }
            )
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.pin_desc),
            style = MaterialTheme.typography.bodySmall
        )

        DescriptionDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lockscreen_enter_opens_pin_label))
            Switch(
                checked = enterOpens,
                onCheckedChange = { checked ->
                    enterOpens = checked
                    prefs.edit().putBoolean(Q25AccessibilityService.KEY_LOCKSCREEN_ENTER_OPENS_PIN, checked).apply()
                    syncDaemon()
                }
            )
        }
        Text(
            stringResource(R.string.lockscreen_enter_opens_pin_desc),
            style = MaterialTheme.typography.bodySmall
        )

        DescriptionDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lockscreen_nav_block_label))
            Switch(
                checked = navBlock,
                onCheckedChange = { checked ->
                    navBlock = checked
                    prefs.edit().putBoolean(Q25AccessibilityService.KEY_LOCKSCREEN_NAV_BLOCK, checked).apply()
                }
            )
        }
        Text(
            stringResource(R.string.lockscreen_nav_block_desc),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
