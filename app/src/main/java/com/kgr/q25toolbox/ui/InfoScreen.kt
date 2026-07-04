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
import androidx.compose.ui.unit.dp
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
        device = buildDeviceRows()
        battery = readBatteryRows(context)
        withContext(Dispatchers.IO) {
            val r = RootShell.isRootAvailable()
            val health = readBatteryHealthRows()
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
        Text("Q25 Toolbox", style = MaterialTheme.typography.headlineMedium)

        InfoCard("Access") {
            StatusRow(
                "Root access",
                when (rootOk) { null -> "Checking…"; true -> "Granted"; else -> "Not granted" },
                when (rootOk) { null -> NEUTRAL; true -> OK; else -> BAD }
            )
            StatusRow(
                "Accessibility service",
                if (a11yOk) "Enabled" else "Disabled",
                if (a11yOk) OK else BAD
            )
        }

        InfoCard("Device") { device.forEach { LabelValue(it) } }

        InfoCard("Battery") { battery.forEach { LabelValue(it) } }
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

private fun buildDeviceRows(): List<Row> {
    val rows = mutableListOf(
        Row("Model", "${Build.MANUFACTURER} ${Build.MODEL}"),
        Row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
    )
    getprop("ro.lineage.version").takeIf { it.isNotEmpty() }?.let { rows += Row("LineageOS", it) }
    rows += Row("Build", Build.DISPLAY)
    Build.VERSION.SECURITY_PATCH.takeIf { it.isNotEmpty() }?.let { rows += Row("Security patch", it) }
    System.getProperty("os.version")?.takeIf { it.isNotEmpty() }?.let { rows += Row("Kernel", it) }
    return rows
}

private fun readBatteryRows(context: Context): List<Row> {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return emptyList()
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }
    val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }
    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
    val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""

    val rows = mutableListOf<Row>()
    if (pct >= 0) rows += Row("Level", "$pct%  ($status)")
    rows += Row("Health", health)
    if (temp > 0) rows += Row("Temperature", String.format("%.1f °C", temp / 10.0))
    if (volt > 0) rows += Row("Voltage", String.format("%.3f V", volt / 1000.0))
    if (tech.isNotEmpty()) rows += Row("Technology", tech)
    return rows
}

/** Capacity-based health and cycle count from sysfs (needs root). */
private fun readBatteryHealthRows(): List<Row> {
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
        rows += Row("Capacity", "${full / 1000} / ${normDesign / 1000} mAh  ($pct%)")
    }
    if (cycles != null) rows += Row("Charge cycles", cycles.toString())
    return rows
}
