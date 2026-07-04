package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.modules.ExtraDimController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatHour(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

@Composable
fun ExtraDimScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activated by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(50f) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var scheduleEnabled by remember { mutableStateOf(false) }
    var scheduleRunning by remember { mutableStateOf(false) }
    var startHour by remember { mutableIntStateOf(ExtraDimController.DEFAULT_START_HOUR) }
    var endHour by remember { mutableIntStateOf(ExtraDimController.DEFAULT_END_HOUR) }
    var scheduleBusy by remember { mutableStateOf(false) }

    fun applySchedule(newEnabled: Boolean, newStart: Int, newEnd: Int) {
        scheduleBusy = true
        scope.launch(Dispatchers.IO) {
            ExtraDimController.setScheduleEnabled(context, newEnabled, newStart, newEnd)
            scheduleEnabled = ExtraDimController.isScheduleEnabled()
            scheduleRunning = ExtraDimController.isScheduleRunning()
            scheduleBusy = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            activated = ExtraDimController.isActivated()
            level = ExtraDimController.getDimmingLevel().toFloat()
            scheduleEnabled = ExtraDimController.isScheduleEnabled()
            scheduleRunning = ExtraDimController.isScheduleRunning()
            if (scheduleEnabled) {
                startHour = ExtraDimController.persistedStartHour()
                endHour = ExtraDimController.persistedEndHour()
            }
        }
    }

    ScreenScaffold(title = Screen.ExtraDim.title, onBack = onBack) {
        Text(
            "Reduces the screen brightness below the system's standard minimum level. " +
            "Perfect for reading in low light and saving battery at night.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Extra Dim Activated")
            Switch(
                checked = activated,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        ExtraDimController.setActivated(enable)
                        activated = ExtraDimController.isActivated()
                        busy = false
                        statusMessage = if (enable) "Extra Dimming activated." else "Extra Dimming deactivated."
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Dimming Intensity: ${level.toInt()}%", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = level,
                enabled = !busy,
                valueRange = 0f..100f,
                onValueChange = { level = it },
                onValueChangeFinished = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        ExtraDimController.setDimmingLevel(level.toInt())
                        level = ExtraDimController.getDimmingLevel().toFloat()
                        busy = false
                        statusMessage = "Dimming intensity set to ${level.toInt()}%"
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Auto Night Dim",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "Automatically turns Extra Dim on at the start time and off at the " +
            "end time every day, without needing the app open.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Schedule: ${if (scheduleEnabled) "On" else "Off"}" +
                if (scheduleEnabled && !scheduleRunning) " (starts at next boot)" else ""
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enabled")
            Switch(
                checked = scheduleEnabled,
                enabled = !scheduleBusy,
                onCheckedChange = { applySchedule(it, startHour, endHour) }
            )
        }

        Text("Starts at", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            ExtraDimController.START_HOUR_OPTIONS.forEach { hour ->
                FilterChip(
                    selected = startHour == hour,
                    enabled = !scheduleBusy,
                    onClick = {
                        startHour = hour
                        if (scheduleEnabled) applySchedule(true, hour, endHour)
                    },
                    label = { Text(formatHour(hour)) }
                )
            }
        }

        Text("Ends at", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            ExtraDimController.END_HOUR_OPTIONS.forEach { hour ->
                FilterChip(
                    selected = endHour == hour,
                    enabled = !scheduleBusy,
                    onClick = {
                        endHour = hour
                        if (scheduleEnabled) applySchedule(true, startHour, hour)
                    },
                    label = { Text(formatHour(hour)) }
                )
            }
        }
    }
}
