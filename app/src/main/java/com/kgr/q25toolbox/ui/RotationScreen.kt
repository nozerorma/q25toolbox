package com.kgr.q25toolbox.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.kgr.q25toolbox.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.modules.RotationController
import com.kgr.q25toolbox.modules.RotationController.Mode
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Forced screen rotation: one general state plus per-app overrides. The accessibility service
 * applies the state of the foreground app (see [RotationController]).
 */
@Composable
fun RotationScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var serviceEnabled by remember { mutableStateOf(false) }
    var general by remember { mutableStateOf(RotationController.general(context)) }
    var override by remember { mutableStateOf(RotationController.overrideAppRequests(context)) }
    var modeById by remember { mutableStateOf(RotationController.entries(context)) }
    var showSystemApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { serviceEnabled = isQ25AccessibilityServiceEnabled(context) }
    LaunchedEffect(showSystemApps) {
        apps = null
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context, showSystemApps, modeById.keys) }
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
            label = { Text(stringResource(R.string.rot_search)) }
        )

        when (val list = apps) {
            null -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "_topbar") {
                    AppListTopBar(
                        title = Screen.Rotation.title,
                        onBack = onBack,
                        showSystemApps = showSystemApps,
                        onToggleShowSystemApps = { showSystemApps = it },
                    )
                }
                item(key = "_banner") { AccessibilityServiceBanner(serviceEnabled) }
                item(key = "_general") {
                    ModeRow(
                        title = stringResource(R.string.rot_all_apps),
                        subtitle = stringResource(R.string.rot_all_apps_sub),
                        mode = general,
                        options = Mode.entries,
                        onChange = { general = it; RotationController.setGeneral(context, it) },
                    )
                }
                item(key = "_override") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.rot_override), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.rot_override_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = override,
                            onCheckedChange = { override = it; RotationController.setOverrideAppRequests(context, it) }
                        )
                    }
                }
                item(key = "_count") {
                    Text(
                        stringResource(R.string.rot_count, modeById.size, list.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(filtered, key = { it.pkg }) { app ->
                    ModeRow(
                        title = app.label,
                        subtitle = app.pkg,
                        mode = modeById[app.pkg] ?: Mode.SYSTEM,
                        // For one app, SYSTEM reads "follow general" rather than "don't touch".
                        options = Mode.entries,
                        systemLabel = stringResource(R.string.rot_follow_general),
                        onChange = {
                            RotationController.setMode(context, app.pkg, it)
                            modeById = RotationController.entries(context)
                        },
                    )
                }
                item(key = "_desc") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DescriptionDivider()
                        Text(
                            stringResource(R.string.rot_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/** A title/subtitle row whose right side opens a dropdown of rotation states. */
@Composable
private fun ModeRow(
    title: String,
    subtitle: String,
    mode: Mode,
    options: List<Mode>,
    onChange: (Mode) -> Unit,
    systemLabel: String = stringResource(Mode.SYSTEM.labelRes),
) {
    var expanded by remember { mutableStateOf(false) }
    @Composable fun label(m: Mode) = if (m == Mode.SYSTEM) systemLabel else stringResource(m.labelRes)
    val forced = mode != Mode.SYSTEM

    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            Text(
                label(mode),
                style = MaterialTheme.typography.labelMedium,
                color = if (forced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label(m),
                                color = if (m == mode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = { expanded = false; if (m != mode) onChange(m) }
                    )
                }
            }
        }
    }
}
