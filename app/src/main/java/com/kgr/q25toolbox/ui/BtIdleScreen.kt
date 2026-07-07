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
import androidx.compose.ui.unit.dp
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
                "Bluetooth will turn off after $newMinutes min with nothing connected."
            else "Auto-disable off. Bluetooth stays as you set it."
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = BtIdleController.isPersisted()
            running = BtIdleController.isRunning()
            BtIdleController.persistedTimeout()?.let { minutes = it }
        }
    }

    ScreenScaffold(title = Screen.BtIdle.title, onBack = onBack) {
        Text("State: ${if (enabled) "On" else "Off"}${if (enabled && !running) " (starts at next boot)" else ""}")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enabled")
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { apply(it, minutes) }
            )
        }

        Text("Turn off after", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtIdleController.TIMEOUT_OPTIONS.forEach { opt ->
                FilterChip(
                    selected = minutes == opt,
                    enabled = !busy,
                    onClick = {
                        minutes = opt
                        if (enabled) apply(true, opt)
                    },
                    label = { Text(if (opt >= 60) "${opt / 60} h" else "$opt min") }
                )
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        DescriptionDivider()
        Text(
            "Turns Bluetooth off after a period with no device connected, so an idle " +
                "radio can't hold the system awake overnight. Connecting earbuds, a " +
                "watch or a speaker resets the timer.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
