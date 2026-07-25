package com.kgr.q25toolbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.modules.BesLoudnessController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "%02d:%02d".format(h, m)
}

@Composable
fun BesLoudnessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var scheduleEnabled by remember { mutableStateOf(false) }
    var scheduleRunning by remember { mutableStateOf(false) }
    var startMinutes by remember { mutableIntStateOf(BesLoudnessController.DEFAULT_START_MINUTES) }
    var endMinutes by remember { mutableIntStateOf(BesLoudnessController.DEFAULT_END_MINUTES) }
    var scheduleBusy by remember { mutableStateOf(false) }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

    fun applySchedule(newEnabled: Boolean, newStart: Int, newEnd: Int) {
        scheduleBusy = true
        scope.launch(Dispatchers.IO) {
            BesLoudnessController.setScheduleEnabled(context, newEnabled, newStart, newEnd)
            scheduleEnabled = BesLoudnessController.isScheduleEnabled()
            scheduleRunning = BesLoudnessController.isScheduleRunning()
            scheduleBusy = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = BesLoudnessController.isEnabled(context)
            scheduleEnabled = BesLoudnessController.isScheduleEnabled()
            scheduleRunning = BesLoudnessController.isScheduleRunning()
            if (scheduleEnabled) {
                startMinutes = BesLoudnessController.persistedStartMinutes()
                endMinutes = BesLoudnessController.persistedEndMinutes()
                // Self-heal a dead OR stale (content-mismatched) daemon - see
                // ExtraDimController/ExtraDimScreen for why a bare "is it running"
                // check isn't enough.
                if (!BesLoudnessController.isScheduleHealthy(context, startMinutes, endMinutes)) {
                    BesLoudnessController.setScheduleEnabled(context, true, startMinutes, endMinutes)
                    scheduleRunning = BesLoudnessController.isScheduleRunning()
                }
            }
        }
    }

    ScreenScaffold(title = Screen.BesLoudness.title, onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("BesLoudness Enabled")
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        BesLoudnessController.setEnabled(context, enable)
                        enabled = BesLoudnessController.isEnabled(context)
                        busy = false
                        statusMessage = if (enable) "BesLoudness enabled." else "BesLoudness disabled."
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Auto Schedule",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "Automatically turns BesLoudness on at the start time and off at the " +
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
                onCheckedChange = { applySchedule(it, startMinutes, endMinutes) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !scheduleBusy) { editingStart = true }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Starts at", style = MaterialTheme.typography.titleSmall)
            Text(formatMinutes(startMinutes), style = MaterialTheme.typography.titleMedium)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !scheduleBusy) { editingEnd = true }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ends at", style = MaterialTheme.typography.titleSmall)
            Text(formatMinutes(endMinutes), style = MaterialTheme.typography.titleMedium)
        }

        DescriptionDivider()
        Text(
            "Toggles the vendor speaker loudness-enhancement DSP stage, the same " +
            "one behind Settings' own \"Sound Enhancement\" screen. Applies " +
            "immediately, including to whatever's already playing.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (editingStart) {
        TimePickerDialog(
            initialMinutes = startMinutes,
            onDismiss = { editingStart = false },
            onConfirm = { newMinutes ->
                startMinutes = newMinutes
                editingStart = false
                if (scheduleEnabled) applySchedule(true, newMinutes, endMinutes)
            }
        )
    }

    if (editingEnd) {
        TimePickerDialog(
            initialMinutes = endMinutes,
            onDismiss = { editingEnd = false },
            onConfirm = { newMinutes ->
                endMinutes = newMinutes
                editingEnd = false
                if (scheduleEnabled) applySchedule(true, startMinutes, newMinutes)
            }
        )
    }
}
