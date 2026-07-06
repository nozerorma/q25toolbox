package com.kgr.q25toolbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.kgr.q25toolbox.modules.ExtraDimController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "%02d:%02d".format(h, m)
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
    var startMinutes by remember { mutableIntStateOf(ExtraDimController.DEFAULT_START_MINUTES) }
    var endMinutes by remember { mutableIntStateOf(ExtraDimController.DEFAULT_END_MINUTES) }
    var scheduleBusy by remember { mutableStateOf(false) }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

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
                startMinutes = ExtraDimController.persistedStartMinutes()
                endMinutes = ExtraDimController.persistedEndMinutes()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        // The dial needs more width than Compose's "platform default" dialog width
        // budgets for, which was clipping the right side of the clock face -
        // this is the standard fix for wide dialog content like TimePicker/DatePicker.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(IntrinsicSize.Min),
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
