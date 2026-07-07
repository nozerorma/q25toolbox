package com.kgr.q25toolbox.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.kgr.q25toolbox.modules.AppScalingController
import com.kgr.q25toolbox.modules.AppScalingController.Res
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Per-app display scaling. Pick a target resolution per app; while that app is
 * in the foreground the accessibility service switches the global `wm size` to
 * it (and resets to native on exit) - the only display knob this ROM honours.
 */
@Composable
fun AppScalingScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var serviceEnabled by remember { mutableStateOf(false) }
    var resById by remember { mutableStateOf(AppScalingController.entries(context)) }
    var showSystemApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var customFor by remember { mutableStateOf<String?>(null) } // pkg awaiting a custom resolution

    LaunchedEffect(Unit) {
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
    }
    LaunchedEffect(showSystemApps) {
        apps = null
        apps = withContext(Dispatchers.IO) {
            loadInstalledApps(context, showSystemApps, resById.keys)
        }
    }

    fun setRes(pkg: String, res: Res) {
        AppScalingController.setRes(context, pkg, res)
        resById = AppScalingController.entries(context)
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        val list = apps ?: emptyList()
        if (q.isEmpty()) list
        else list.filter { it.label.lowercase(Locale.ROOT).contains(q) || it.pkg.contains(q) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Only the search field stays pinned while scrolling - the header and description
        // scroll away with the list so more of it is visible at once.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search apps") }
        )

        when (val list = apps) {
            null -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item(key = "_topbar") {
                        AppListTopBar(
                            title = Screen.AppScaling.title,
                            onBack = onBack,
                            showSystemApps = showSystemApps,
                            onToggleShowSystemApps = { showSystemApps = it },
                        )
                    }
                    item(key = "_banner") { AccessibilityServiceBanner(serviceEnabled) }
                    item(key = "_count") {
                        Text(
                            "${resById.size} scaled of ${list.size} apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(filtered, key = { it.pkg }) { app ->
                        ScalingRow(
                            app = app,
                            res = resById[app.pkg] ?: AppScalingController.NATIVE,
                            onPresetChange = { setRes(app.pkg, it) },
                            onCustom = { customFor = app.pkg }
                        )
                    }
                    item(key = "_desc") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DescriptionDivider()
                            Text(
                                "Give an app a different resolution and the screen switches to " +
                                    "it while that app is open, then back to native when you " +
                                    "leave. Taller portrait sizes help apps that look cramped on " +
                                    "the 720×720 screen. The whole screen briefly relayouts on " +
                                    "entry/exit. Needs root.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    customFor?.let { pkg ->
        CustomResolutionDialog(
            initial = resById[pkg] ?: AppScalingController.NATIVE,
            onDismiss = { customFor = null },
            onConfirm = { res ->
                setRes(pkg, res)
                customFor = null
            }
        )
    }
}

@Composable
private fun ScalingRow(
    app: InstalledApp,
    res: Res,
    onPresetChange: (Res) -> Unit,
    onCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val scaled = !res.isNative

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            Text(
                if (scaled) AppScalingController.label(res) else "Native",
                style = MaterialTheme.typography.labelMedium,
                color = if (scaled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppScalingController.PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                AppScalingController.label(preset),
                                color = if (preset == res) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            expanded = false
                            if (preset != res) onPresetChange(preset)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Custom…") },
                    onClick = {
                        expanded = false
                        onCustom()
                    }
                )
            }
        }
    }
}

@Composable
private fun CustomResolutionDialog(
    initial: Res,
    onDismiss: () -> Unit,
    onConfirm: (Res) -> Unit,
) {
    var w by remember { mutableStateOf(initial.w.toString()) }
    var h by remember { mutableStateOf(initial.h.toString()) }
    val wInt = w.trim().toIntOrNull()
    val hInt = h.trim().toIntOrNull()
    val valid = wInt != null && hInt != null && wInt in 240..2000 && hInt in 240..2000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom resolution") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Width × height in pixels (240–2000). The Q25 is 720×720 native; " +
                        "taller heights give a portrait aspect.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = w,
                        onValueChange = { w = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Width") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = h,
                        onValueChange = { h = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Height") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { if (valid) onConfirm(Res(wInt!!, hInt!!)) }
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
