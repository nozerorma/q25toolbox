package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.BtIdleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BtIdleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var minutes by remember { mutableIntStateOf(BtIdleController.DEFAULT_TIMEOUT) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun apply(newEnabled: Boolean, newMinutes: Int) {
        busy = true
        scope.launch(Dispatchers.IO) {
            BtIdleController.setEnabled(context, newEnabled, newMinutes)
            enabled = BtIdleController.isPersisted()
            running = BtIdleController.isRunning()
            busy = false
            statusMessage = if (newEnabled)
                context.getString(R.string.bt_idle_status_on, newMinutes)
            else context.getString(R.string.bt_idle_status_off)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = BtIdleController.isPersisted()
            running = BtIdleController.isRunning()
            BtIdleController.persistedTimeout()?.let { minutes = it }
            // Self-heal a dead OR stale (content-mismatched) daemon - see
            // ExtraDimController/ExtraDimScreen for why a bare "is it running"
            // check isn't enough.
            if (enabled && !BtIdleController.isHealthy(context, minutes)) {
                BtIdleController.setEnabled(context, true, minutes)
                running = BtIdleController.isRunning()
            }
        }
    }

    ScreenScaffold(title = Screen.BtIdle.title, onBack = onBack) {
        val state = (if (enabled) stringResource(R.string.bt_idle_on) else stringResource(R.string.bt_idle_off)) +
            if (enabled && !running) stringResource(R.string.bt_idle_boot_notice) else ""
        Text(stringResource(R.string.bt_idle_state, state))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bt_idle_enabled_label))
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { apply(it, minutes) }
            )
        }

        Text(stringResource(R.string.bt_idle_turn_off_after), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtIdleController.TIMEOUT_OPTIONS.forEach { opt ->
                FilterChip(
                    selected = minutes == opt,
                    enabled = !busy,
                    onClick = {
                        minutes = opt
                        if (enabled) apply(true, opt)
                    },
                    label = {
                        Text(
                            if (opt >= 60) stringResource(R.string.bt_idle_hours, opt / 60)
                            else stringResource(R.string.bt_idle_minutes, opt)
                        )
                    }
                )
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.bt_idle_desc),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
