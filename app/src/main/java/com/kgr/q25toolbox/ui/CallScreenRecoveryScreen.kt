package com.kgr.q25toolbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.KeyRemapController
import com.kgr.q25toolbox.service.Q25AccessibilityService
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CallScreenRecoveryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var screenRecovery by remember {
        mutableStateOf(prefs.getBoolean(Q25AccessibilityService.KEY_CALL_SCREEN_RECOVERY, true))
    }
    var respawning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
    }

    ScreenScaffold(title = stringResource(Screen.CallScreenRecovery.titleRes), onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.call_recovery_auto_recover_label))
            Switch(
                checked = screenRecovery,
                onCheckedChange = { checked ->
                    screenRecovery = checked
                    prefs.edit().putBoolean(Q25AccessibilityService.KEY_CALL_SCREEN_RECOVERY, checked).apply()
                }
            )
        }
        Text(
            stringResource(R.string.call_recovery_auto_recover_desc),
            style = MaterialTheme.typography.bodySmall
        )

        DescriptionDivider()

        Button(
            enabled = !respawning,
            onClick = {
                respawning = true
                statusMessage = null
                scope.launch(Dispatchers.IO) {
                    val result = KeyRemapController.respawnKeyboard()
                    respawning = false
                    statusMessage = if (result.success) {
                        context.getString(R.string.call_recovery_respawn_success)
                    } else {
                        context.getString(R.string.call_recovery_respawn_failed)
                    }
                }
            }
        ) {
            Text(stringResource(R.string.call_recovery_respawn_button))
        }
        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.call_recovery_desc),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
