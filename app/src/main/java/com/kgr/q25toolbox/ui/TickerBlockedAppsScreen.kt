package com.kgr.q25toolbox.ui

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
import com.kgr.q25toolbox.modules.TickerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Per-app blacklist for the ticker: apps picked here never get a ticker banner,
 * regardless of the other filters. Same picker pattern as [ImeBlockScreen].
 */
@Composable
fun TickerBlockedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var showSystemApps by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(TickerSettings.blockedApps(context)) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(showSystemApps) {
        apps = null
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context, showSystemApps, selected) }
    }

    fun persist(newSet: Set<String>) {
        selected = newSet
        TickerSettings.setBlockedApps(context, newSet)
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
                            title = stringResource(R.string.ticker_blocked_apps_label),
                            onBack = onBack,
                            showSystemApps = showSystemApps,
                            onToggleShowSystemApps = { showSystemApps = it },
                        )
                    }
                    item(key = "_count") {
                        Text(
                            stringResource(R.string.ticker_blocked_apps_of_total, selected.size, list.size),
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
                }
            }
        }
    }
}
