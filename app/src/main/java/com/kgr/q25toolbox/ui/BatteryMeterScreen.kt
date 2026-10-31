package com.kgr.q25toolbox.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.BatteryMeterController
import kotlinx.coroutines.delay

@Composable
fun BatteryMeterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf(BatteryMeterController.read(context)) }
    var notify by remember { mutableStateOf(BatteryMeterController.isEnabled(context)) }
    var ma by remember { mutableStateOf(BatteryMeterController.usesMilliamps(context)) }

    // Live refresh while the screen is open; the loop dies with the composition.
    LaunchedEffect(ma) {
        while (true) {
            reading = BatteryMeterController.read(context)
            delay(2_000)
        }
    }

    ScreenScaffold(title = stringResource(R.string.title_battery_meter), onBack = onBack) {
        Text(stringResource(R.string.battery_meter_intro), style = MaterialTheme.typography.bodySmall)

        reading?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val sign = if (r.charging) "+" else "−"
                    Text(
                        if (r.watts > 0.005) "$sign${"%.2f".format(r.watts)} W" else "0 W",
                        style = MaterialTheme.typography.displaySmall
                    )
                    Text(
                        stringResource(
                            when {
                                r.full -> R.string.battery_meter_full
                                r.charging -> R.string.battery_meter_charging
                                else -> R.string.battery_meter_discharging
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Metric(R.string.battery_meter_level, "${r.percent} %")
                    Metric(R.string.battery_meter_current, "${"%.3f".format(r.amps)} A")
                    Metric(R.string.battery_meter_voltage, "${"%.3f".format(r.volts)} V")
                    Metric(R.string.battery_meter_temp, "${"%.1f".format(r.tempC)} °C")
                    r.energyWh?.let { Metric(R.string.battery_meter_energy, "${"%.2f".format(it)} Wh") }
                    r.timeToFullMs?.let {
                        val m = it / 60_000
                        Metric(R.string.battery_meter_to_full, "${m / 60} h ${m % 60} min")
                    }
                    r.chargingSinceMs?.let {
                        Metric(R.string.battery_meter_since, DateFormat.getTimeFormat(context).format(it))
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow(R.string.battery_meter_notify, notify) { on ->
                    notify = on
                    context.getSharedPreferences(com.kgr.q25toolbox.service.Q25AccessibilityService.PREFS, 0)
                        .edit().putBoolean(BatteryMeterController.KEY_ENABLED, on).apply()
                    BatteryMeterController.sync(context)
                }
                ToggleRow(R.string.battery_meter_ma, ma) { on -> ma = on; BatteryMeterController.setMilliamps(context, on) }
                Text(stringResource(R.string.battery_meter_ma_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Metric(labelRes: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToggleRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
