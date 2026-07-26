package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.TelemetryController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TelemetryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var totalApps by remember { mutableIntStateOf(0) }
    var blockedApps by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshCounts() {
        scope.launch(Dispatchers.IO) {
            totalApps = TelemetryController.totalAffectedApps()
            blockedApps = TelemetryController.totalBlockedApps()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = TelemetryController.isPersisted()
            totalApps = TelemetryController.totalAffectedApps()
            blockedApps = TelemetryController.totalBlockedApps()
            // Same class of bug as Extra Dim's schedule daemon: the watchdog can die
            // mid-session (e.g. the root shell that launched it got recycled), or be
            // alive but running a stale script from an older app build (which a bare
            // "is it running" check can't see) - self-heal on either case instead of
            // leaving telemetry silently unblocked/outdated until the next reboot.
            if (enabled && !TelemetryController.isHealthy(context)) {
                TelemetryController.setEnabled(context, true)
            }
        }
    }

    ScreenScaffold(title = Screen.Telemetry.title, onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.telemetry_protection_status), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (blockedApps > 0 && blockedApps == totalApps)
                        stringResource(R.string.telemetry_status_full)
                    else if (blockedApps > 0)
                        stringResource(R.string.telemetry_status_partial)
                    else
                        stringResource(R.string.telemetry_status_none),
                    color = if (blockedApps > 0 && blockedApps == totalApps)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Text(stringResource(R.string.telemetry_detected_apps, totalApps))
                Text(stringResource(R.string.telemetry_blocked_apps, blockedApps))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.telemetry_enable_at_boot))
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = {
                    enabled = it
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        TelemetryController.setEnabled(context, it)
                        busy = false
                        statusMessage = if (it)
                            context.getString(R.string.telemetry_autoblock_on)
                        else
                            context.getString(R.string.telemetry_autoblock_off)
                    }
                }
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        TelemetryController.applyLive()
                        refreshCounts()
                        busy = false
                        statusMessage = context.getString(R.string.telemetry_block_now_status)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.telemetry_block_now_button))
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.telemetry_desc),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
