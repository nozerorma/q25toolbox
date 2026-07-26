package com.kgr.q25toolbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
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
                // The daemon can die mid-session (e.g. the root shell that launched it
                // got recycled), or be alive but running a stale script left over from
                // an older app version (which a bare "is it running" check can't see -
                // it loops forever either way). Self-heal on either case instead of
                // just reporting "not running" passively.
                if (!ExtraDimController.isScheduleHealthy(context, startMinutes, endMinutes)) {
                    ExtraDimController.setScheduleEnabled(context, true, startMinutes, endMinutes)
                    scheduleRunning = ExtraDimController.isScheduleRunning()
                }
            }
        }
    }

    ScreenScaffold(title = Screen.ExtraDim.title, onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.extra_dim_activated_switch_label))
            Switch(
                checked = activated,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        ExtraDimController.setActivated(enable)
                        activated = ExtraDimController.isActivated()
                        busy = false
                        statusMessage = if (enable)
                            context.getString(R.string.extra_dim_on_status)
                        else context.getString(R.string.extra_dim_off_status)
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
            Text(stringResource(R.string.extra_dim_intensity_label, level.toInt()), style = MaterialTheme.typography.titleSmall)
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
                        statusMessage = context.getString(R.string.extra_dim_intensity_status, level.toInt())
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            stringResource(R.string.extra_dim_auto_night),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            stringResource(R.string.extra_dim_schedule_desc),
            style = MaterialTheme.typography.bodySmall
        )
        run {
            val state = (if (scheduleEnabled) stringResource(R.string.extra_dim_schedule_on) else stringResource(R.string.extra_dim_schedule_off)) +
                if (scheduleEnabled && !scheduleRunning) stringResource(R.string.extra_dim_schedule_boot_notice) else ""
            Text(stringResource(R.string.extra_dim_schedule_state, state))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.extra_dim_schedule_enabled_label))
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
            Text(stringResource(R.string.extra_dim_starts_at), style = MaterialTheme.typography.titleSmall)
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
            Text(stringResource(R.string.extra_dim_ends_at), style = MaterialTheme.typography.titleSmall)
            Text(formatMinutes(endMinutes), style = MaterialTheme.typography.titleMedium)
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.extra_dim_desc),
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
