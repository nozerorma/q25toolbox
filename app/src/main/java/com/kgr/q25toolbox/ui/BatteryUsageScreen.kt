package com.kgr.q25toolbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.AppPowerUsage
import com.kgr.q25toolbox.modules.BatteryUsageController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Cycled by row index so each app gets a stable, distinct pie-slice / legend-dot color. */
private val SLICE_COLORS = listOf(
    Color(0xFF4C8BF5), Color(0xFFEA6C4C), Color(0xFF34A853), Color(0xFFF2B705),
    Color(0xFF9B59B6), Color(0xFF16A6A0), Color(0xFFE0559A), Color(0xFF8D6E63),
    Color(0xFF7986CB), Color(0xFFC0CA33),
)

private fun sliceColor(index: Int): Color = SLICE_COLORS[index % SLICE_COLORS.size]

@Composable
fun BatteryUsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<List<AppPowerUsage>?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var showSystemApps by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var editingThreshold by remember { mutableStateOf(false) }
    var resetThreshold by remember { mutableIntStateOf(BatteryUsageController.getResetThreshold(context)) }

    LaunchedEffect(refreshToken) {
        usage = null
        usage = withContext(Dispatchers.IO) { BatteryUsageController.readUsage(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                stringResource(R.string.title_battery_usage),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { BatteryUsageController.resetStats() }
                    refreshToken++
                }
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.battery_usage_reset))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.battery_usage_more_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.battery_usage_set_threshold)) },
                        onClick = {
                            menuExpanded = false
                            editingThreshold = true
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.battery_usage_show_system),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
        }

        when (val list = usage) {
            null -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            else -> {
                val totalMah = list.sumOf { it.mAh }
                val visible = if (showSystemApps) list else list.filterNot { it.isSystemApp }
                Text(
                    stringResource(R.string.battery_usage_total, formatMah(totalMah)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    BatteryPieChart(
                        entries = visible,
                        modifier = Modifier
                            .size(120.dp)
                            .align(Alignment.Top)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(visible, key = { _, app -> app.uid }) { index, app ->
                            AppUsageRow(app, sliceColor(index))
                        }
                        item(key = "_desc") {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                DescriptionDivider()
                                Text(
                                    stringResource(R.string.battery_usage_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingThreshold) {
        ResetThresholdDialog(
            initial = resetThreshold,
            onDismiss = { editingThreshold = false },
            onConfirm = { percent ->
                BatteryUsageController.setResetThreshold(context, percent)
                resetThreshold = percent
                editingThreshold = false
            }
        )
    }
}

@Composable
private fun ResetThresholdDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember { mutableFloatStateOf(initial.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_usage_set_threshold)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.battery_usage_threshold_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.battery_usage_threshold_value, value.toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Slider(
                    value = value,
                    valueRange = 1f..100f,
                    steps = 98,
                    onValueChange = { value = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.toInt()) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Simple pie chart, one slice per entry sized by its share of [entries]' own total (not the device-wide total). */
@Composable
private fun BatteryPieChart(entries: List<AppPowerUsage>, modifier: Modifier = Modifier) {
    val total = entries.sumOf { it.mAh }
    Canvas(modifier = modifier) {
        if (total <= 0.0) return@Canvas
        var startAngle = -90f
        entries.forEachIndexed { index, app ->
            val sweep = (app.mAh / total * 360.0).toFloat()
            drawArc(
                color = sliceColor(index),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun AppUsageRow(app: AppPowerUsage, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.packageName != null) {
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(String.format(Locale.US, "%.1f%%", app.percentOfTotal), style = MaterialTheme.typography.bodyMedium)
            Text(formatMah(app.mAh), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMah(mah: Double): String = when {
    mah >= 10 -> String.format(Locale.US, "%.1f mAh", mah)
    else -> String.format(Locale.US, "%.3f mAh", mah)
}
