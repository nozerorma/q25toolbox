package com.kgr.q25toolbox.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kgr.q25toolbox.modules.AirplaneScheduler
import com.kgr.q25toolbox.modules.AirplaneScheduler.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun AirplaneSchedulerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var schedules by remember { mutableStateOf(AirplaneScheduler.load(context)) }
    var airplaneOn by remember { mutableStateOf<Boolean?>(null) }
    var next by remember { mutableStateOf<Long?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    // Re-read after every change; the engine runs on IO because it shells out as root.
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val on = AirplaneScheduler.isAirplaneOn()
            val n = AirplaneScheduler.nextTransition(context)
            val list = AirplaneScheduler.load(context)
            withContext(Dispatchers.Main) { airplaneOn = on; next = n; schedules = list }
        }
    }

    fun change(id: Long, f: (Schedule) -> Schedule?) {
        scope.launch(Dispatchers.IO) {
            AirplaneScheduler.update(context, id, f)
            tick++
        }
    }

    ScreenScaffold(title = stringResource(R.string.title_airplane_scheduler), onBack = onBack) {
        Text(stringResource(R.string.airplane_intro), style = MaterialTheme.typography.bodySmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        when (airplaneOn) {
                            true -> R.string.airplane_state_on
                            false -> R.string.airplane_state_off
                            null -> R.string.airplane_state_unknown
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                next?.let {
                    val f = DateFormat.getTimeFormat(context).format(it)
                    val d = DateFormat.getMediumDateFormat(context).format(it)
                    Text(stringResource(R.string.airplane_next, "$d $f"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        schedules.forEach { s -> ScheduleCard(s, ::change) }

        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    AirplaneScheduler.add(context, context.getString(R.string.airplane_default_name, schedules.size + 1))
                    tick++
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.airplane_add)) }
    }
}

@Composable
private fun ScheduleCard(s: Schedule, change: (Long, (Schedule) -> Schedule?) -> Unit) {
    val context = LocalContext.current
    val dayNames = remember {
        // Monday-first short names from the locale, matching the bit order used by the engine.
        val cal = Calendar.getInstance()
        (0..6).map { i ->
            cal.set(Calendar.DAY_OF_WEEK, if (i == 6) Calendar.SUNDAY else Calendar.MONDAY + i)
            DateFormat.format("EEE", cal).toString()
        }
    }

    fun pickTime(initial: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPick(h * 60 + m) }, initial / 60, initial % 60,
            DateFormat.is24HourFormat(context)).show()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = s.enabled, onCheckedChange = { on ->
                    change(s.id) {
                        // Re-arming a one-shot means "the next time that clock time comes round".
                        it.copy(enabled = on, onceStartMs = if (on && it.days == 0) AirplaneScheduler.nextOnce(it.startMin) else it.onceStartMs)
                    }
                })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pickTime(s.startMin) { m -> change(s.id) { it.copy(startMin = m, onceStartMs = if (it.days == 0) AirplaneScheduler.nextOnce(m) else 0L) } }
                }) { Text(stringResource(R.string.airplane_from, fmt(s.startMin))) }
                OutlinedButton(onClick = {
                    pickTime(s.endMin) { m -> change(s.id) { it.copy(endMin = m) } }
                }) { Text(stringResource(R.string.airplane_to, fmt(s.endMin))) }
            }

            // Seven chips don't fit one row at phone width on this narrow screen; two rows keep them tappable.
            listOf(0..3, 4..6).forEach { range ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in range) {
                        FilterChip(
                            selected = s.days and (1 shl i) != 0,
                            onClick = { change(s.id) { it.copy(days = it.days xor (1 shl i)).let { n ->
                                if (n.days == 0) n.copy(onceStartMs = AirplaneScheduler.nextOnce(n.startMin)) else n.copy(onceStartMs = 0L)
                            } } },
                            label = { Text(dayNames[i]) }
                        )
                    }
                }
            }
            Text(
                stringResource(if (s.days == 0) R.string.airplane_once else R.string.airplane_repeat),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.airplane_wifi_off), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Switch(checked = s.wifiOff, onCheckedChange = { on -> change(s.id) { it.copy(wifiOff = on) } })
            }

            val now = System.currentTimeMillis()
            val skipping = s.skipUntilMs > now
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    change(s.id) { cur ->
                        // Skip = ignore the open window (ending it now) or, if none is open, the next one.
                        // Pressing it again lifts the skip.
                        if (cur.skipUntilMs > System.currentTimeMillis()) cur.copy(skipUntilMs = 0L)
                        else cur.copy(skipUntilMs = AirplaneScheduler.skipTarget(cur, System.currentTimeMillis()) ?: 0L)
                    }
                }) { Text(stringResource(if (skipping) R.string.airplane_unskip else R.string.airplane_skip)) }
                TextButton(onClick = { change(s.id) { null } }) {
                    Text(stringResource(R.string.airplane_delete))
                }
            }
        }
    }
}
