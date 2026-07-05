package com.kgr.q25toolbox.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Row(val label: String, val value: String)

@Composable
fun InfoScreen() {
    val context = LocalContext.current

    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var a11yOk by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<List<Row>>(emptyList()) }
    var battery by remember { mutableStateOf<List<Row>>(emptyList()) }

    LaunchedEffect(Unit) {
        a11yOk = isQ25AccessibilityServiceEnabled(context)
        device = buildDeviceRows(context)
        battery = readBatteryRows(context)
        withContext(Dispatchers.IO) {
            val r = RootShell.isRootAvailable()
            val health = readBatteryHealthRows(context)
            withContext(Dispatchers.Main) {
                rootOk = r
                if (health.isNotEmpty()) battery = battery + health
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        InfoCard(stringResource(R.string.info_access)) {
            StatusRow(
                stringResource(R.string.info_root_access),
                when (rootOk) {
                    null -> stringResource(R.string.info_root_checking)
                    true -> stringResource(R.string.info_root_granted)
                    else -> stringResource(R.string.info_root_not_granted)
                },
                when (rootOk) { null -> NEUTRAL; true -> OK; else -> BAD }
            )
            StatusRow(
                stringResource(R.string.info_a11y_service),
                if (a11yOk) stringResource(R.string.info_enabled) else stringResource(R.string.info_disabled),
                if (a11yOk) OK else BAD
            )
        }

        InfoCard(stringResource(R.string.info_device)) { device.forEach { LabelValue(it) } }

        InfoCard(stringResource(R.string.info_battery)) { battery.forEach { LabelValue(it) } }
    }
}

private val OK = Color(0xFF81C784)
private val BAD = Color(0xFFE57373)
private val NEUTRAL = Color(0xFFB0B0B0)

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabelValue(row: Row) {
    Column {
        Text(row.label, style = MaterialTheme.typography.labelMedium, color = NEUTRAL)
        Text(row.value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NEUTRAL)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun getprop(key: String): String = try {
    Runtime.getRuntime().exec(arrayOf("getprop", key))
        .inputStream.bufferedReader().readText().trim()
} catch (_: Exception) {
    ""
}

private fun buildDeviceRows(context: Context): List<Row> {
    val rows = mutableListOf(
        Row(context.getString(R.string.info_model), "${Build.MANUFACTURER} ${Build.MODEL}"),
        Row(context.getString(R.string.info_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
    )
    getprop("ro.lineage.version").takeIf { it.isNotEmpty() }?.let {
        rows += Row(context.getString(R.string.info_lineageos), it)
    }
    rows += Row(context.getString(R.string.info_build), Build.DISPLAY)
    Build.VERSION.SECURITY_PATCH.takeIf { it.isNotEmpty() }?.let {
        rows += Row(context.getString(R.string.info_security_patch), it)
    }
    System.getProperty("os.version")?.takeIf { it.isNotEmpty() }?.let {
        rows += Row(context.getString(R.string.info_kernel), it)
    }
    return rows
}

private fun readBatteryRows(context: Context): List<Row> {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return emptyList()
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> context.getString(R.string.info_battery_charging)
        BatteryManager.BATTERY_STATUS_DISCHARGING -> context.getString(R.string.info_battery_discharging)
        BatteryManager.BATTERY_STATUS_FULL -> context.getString(R.string.info_battery_full)
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> context.getString(R.string.info_battery_not_charging)
        else -> context.getString(R.string.info_battery_unknown)
    }
    val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> context.getString(R.string.info_battery_health_good)
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> context.getString(R.string.info_battery_health_overheat)
        BatteryManager.BATTERY_HEALTH_DEAD -> context.getString(R.string.info_battery_health_dead)
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> context.getString(R.string.info_battery_health_over_voltage)
        BatteryManager.BATTERY_HEALTH_COLD -> context.getString(R.string.info_battery_health_cold)
        else -> context.getString(R.string.info_battery_unknown)
    }
    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
    val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""

    val rows = mutableListOf<Row>()
    if (pct >= 0) rows += Row(context.getString(R.string.info_battery_level), "$pct%  ($status)")
    rows += Row(context.getString(R.string.info_battery_health), health)
    if (temp > 0) rows += Row(context.getString(R.string.info_battery_temp), String.format("%.1f °C", temp / 10.0))
    if (volt > 0) rows += Row(context.getString(R.string.info_battery_volt), String.format("%.3f V", volt / 1000.0))
    if (tech.isNotEmpty()) rows += Row(context.getString(R.string.info_battery_tech), tech)
    return rows
}

/** Capacity-based health and cycle count from sysfs (needs root). */
private fun readBatteryHealthRows(context: Context): List<Row> {
    val out = RootShell.run(
        "cat /sys/class/power_supply/battery/charge_full " +
            "/sys/class/power_supply/battery/charge_full_design " +
            "/sys/class/power_supply/battery/cycle_count 2>/dev/null"
    ).out.map { it.trim() }
    val full = out.getOrNull(0)?.toLongOrNull()
    val design = out.getOrNull(1)?.toLongOrNull()
    val cycles = out.getOrNull(2)?.toIntOrNull()

    val rows = mutableListOf<Row>()
    if (full != null && design != null && design > 0) {
        val normDesign = if (design < 1000000) design * 10 else design
        val pct = full * 100 / normDesign
        rows += Row(context.getString(R.string.info_battery_capacity), "${full / 1000} / ${normDesign / 1000} mAh  ($pct%)")
    }
    if (cycles != null) rows += Row(context.getString(R.string.info_battery_cycles), cycles.toString())
    return rows
}
