package com.kgr.q25toolbox.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class Row(val label: String, val value: String)

/**
 * Fetched once from [HomeScreen] (which survives tab switches), rather than
 * inside this composable - re-fetching (and flashing back to empty cards)
 * every time the user merely revisits the Info tab was the "wonky, pops up"
 * symptom, since `when(tab)`'s branches are disposed/recreated on every
 * switch and lose their own `remember` state.
 */
internal class InfoState {
    var device: List<Row> by mutableStateOf(emptyList())
    var battery: List<Row> by mutableStateOf(emptyList())
}

internal suspend fun InfoState.refresh(context: Context) {
    battery = readBatteryRows(context)
    withContext(Dispatchers.IO) {
        // buildDeviceRows() shells out to getprop via Runtime.exec, which blocks
        // for real process-fork time - keep it (and the root sysfs read) off the
        // main thread rather than in the calling LaunchedEffect's default dispatcher.
        val deviceRows = buildDeviceRows(context)
        val health = readBatteryHealthRows(context)
        withContext(Dispatchers.Main) {
            device = deviceRows
            if (health.isNotEmpty()) battery = battery + health
        }
    }
}

@Composable
internal fun InfoScreen(state: InfoState, scrollState: ScrollState, onOpenBatteryUsage: () -> Unit) {
    val device = state.device
    val battery = state.battery

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        InfoCard(stringResource(R.string.info_device)) { device.forEach { LabelValue(it) } }

        InfoCard(stringResource(R.string.info_battery)) {
            val levelLabel = stringResource(R.string.info_battery_level)
            battery.forEach { row ->
                LabelValue(row)
                if (row.label == levelLabel) {
                    BatteryUsageEntryRow(onClick = onOpenBatteryUsage)
                }
            }
        }
    }
}

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
private fun BatteryUsageEntryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.QueryStats, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.info_battery_usage_row), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.info_battery_usage_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

/**
 * Capacity-based health from sysfs (needs root). Cycle count is deliberately not
 * read here - this MTK gauge driver reports a static, non-incrementing value
 * (stuck at 1 regardless of actual usage) with no alternate source on this
 * hardware, so displaying it would just be misleading.
 */
private fun readBatteryHealthRows(context: Context): List<Row> {
    val out = RootShell.run(
        "cat /sys/class/power_supply/battery/charge_full " +
            "/sys/class/power_supply/battery/charge_full_design 2>/dev/null"
    ).out.map { it.trim() }
    val full = out.getOrNull(0)?.toLongOrNull()
    val design = out.getOrNull(1)?.toLongOrNull()

    val rows = mutableListOf<Row>()
    if (full != null && design != null && design > 0) {
        val normDesign = if (design < 1000000) design * 10 else design
        val pct = full * 100 / normDesign
        rows += Row(context.getString(R.string.info_battery_capacity), "${full / 1000} / ${normDesign / 1000} mAh  ($pct%)")
    }
    return rows
}
