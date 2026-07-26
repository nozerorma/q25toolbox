package com.kgr.q25toolbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.service.Q25AccessibilityService
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Per-app keyboard block: pick the apps where physical key presses should reach
 * the app directly. The accessibility service watches the foreground app and, in
 * a selected app, switches the default IME to a do-nothing passthrough keyboard
 * (restoring the previous one on exit).
 */
@Composable
fun ImeBlockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(Q25AccessibilityService.KEY_IME_BLOCK, false))
    }
    var selected by remember {
        mutableStateOf(
            prefs.getStringSet(Q25AccessibilityService.KEY_IME_BLOCK_APPS, emptySet())
                ?.toSet() ?: emptySet()
        )
    }
    var showSystemApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
    }
    LaunchedEffect(showSystemApps) {
        apps = null
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context, showSystemApps, selected) }
    }

    fun persist(newSet: Set<String>) {
        selected = newSet
        prefs.edit().putStringSet(Q25AccessibilityService.KEY_IME_BLOCK_APPS, newSet).apply()
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
        // Only the search field stays pinned while scrolling - the header, switch, and
        // description scroll away with the list so more of it is visible at once.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.common_search_apps)) }
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
                            title = Screen.ImeBlock.title,
                            onBack = onBack,
                            showSystemApps = showSystemApps,
                            onToggleShowSystemApps = { showSystemApps = it },
                        )
                    }
                    item(key = "_switch") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ime_block_enable_switch_label), modifier = Modifier.weight(1f))
                            Switch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    enabled = checked
                                    prefs.edit().putBoolean(Q25AccessibilityService.KEY_IME_BLOCK, checked).apply()
                                }
                            )
                        }
                    }
                    item(key = "_banner") { AccessibilityServiceBanner(serviceEnabled) }
                    item(key = "_count") {
                        Text(
                            stringResource(R.string.ime_block_selected_count, selected.size, list.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(filtered, key = { it.pkg }) { app ->
                        val checked = app.pkg in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    onValueChange = { on ->
                                        persist(if (on) selected + app.pkg else selected - app.pkg)
                                    }
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(modifier = Modifier.fillMaxWidth()) {
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
                        }
                    }
                    item(key = "_desc") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DescriptionDivider()
                            Text(
                                stringResource(R.string.ime_block_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
