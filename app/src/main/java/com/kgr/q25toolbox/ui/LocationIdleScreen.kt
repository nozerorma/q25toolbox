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
import com.kgr.q25toolbox.modules.LocationIdleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LocationIdleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var minutes by remember { mutableIntStateOf(LocationIdleController.DEFAULT_TIMEOUT) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun apply(newEnabled: Boolean, newMinutes: Int) {
        busy = true
        scope.launch(Dispatchers.IO) {
            LocationIdleController.setEnabled(context, newEnabled, newMinutes)
            enabled = LocationIdleController.isPersisted()
            running = LocationIdleController.isRunning()
            busy = false
            statusMessage = if (newEnabled)
                "Location will turn off after $newMinutes min with no active GPS fix."
            else "Auto-disable off. Location stays as you set it."
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = LocationIdleController.isPersisted()
            running = LocationIdleController.isRunning()
            LocationIdleController.persistedTimeout()?.let { minutes = it }
            // Self-heal a dead OR stale (content-mismatched) daemon - see
            // ExtraDimController/ExtraDimScreen for why a bare "is it running"
            // check isn't enough.
            if (enabled && !LocationIdleController.isHealthy(context, minutes)) {
                LocationIdleController.setEnabled(context, true, minutes)
                running = LocationIdleController.isRunning()
            }
        }
    }

    ScreenScaffold(title = Screen.LocationIdle.title, onBack = onBack) {
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
            LocationIdleController.TIMEOUT_OPTIONS.forEach { opt ->
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
            "Turns Location off after a period with no active GPS fix (navigation, " +
                "ride-hailing pickup, camera geotag, ...). Background low-power location " +
                "checks from Google Play services don't count as \"active\" and won't keep " +
                "resetting the timer.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
