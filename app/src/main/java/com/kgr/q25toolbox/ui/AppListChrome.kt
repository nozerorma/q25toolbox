package com.kgr.q25toolbox.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kgr.q25toolbox.R
import java.util.Locale

/** An installed app entry (label + package) for the picker lists. */
data class InstalledApp(val label: String, val pkg: String)

/**
 * Compact top bar shared by the app-list screens: the title sits inline next to
 * the Back button (rather than on its own line) to save vertical space on the
 * small square screen, with a 3-dot overflow menu on the right.
 *
 * The overflow always carries a "Show system apps" toggle (off by default);
 * [extraMenuItems] can add screen-specific entries below it.
 */
@Composable
fun AppListTopBar(
    title: String,
    onBack: () -> Unit,
    showSystemApps: Boolean,
    onToggleShowSystemApps: (Boolean) -> Unit,
    extraMenuItems: @Composable (dismiss: () -> Unit) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_show_system_apps)) },
                    onClick = { onToggleShowSystemApps(!showSystemApps) },
                    leadingIcon = { Checkbox(checked = showSystemApps, onCheckedChange = null) }
                )
                extraMenuItems { menuOpen = false }
            }
        }
    }
}

/**
 * Installed apps for the pickers. When [includeSystem] is false (the default),
 * only apps with a launcher entry are shown; when true, every installed app is
 * listed. Packages in [alwaysInclude] (e.g. already-configured ones) are kept
 * regardless so a selection can always be cleared. The app itself is excluded.
 */
fun loadInstalledApps(
    context: Context,
    includeSystem: Boolean,
    alwaysInclude: Set<String>,
): List<InstalledApp> {
    val pm = context.packageManager
    val self = context.packageName
    val result = LinkedHashMap<String, InstalledApp>()

    fun labelOf(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    if (includeSystem) {
        pm.getInstalledApplications(0).forEach { ai ->
            if (ai.packageName != self) {
                result[ai.packageName] = InstalledApp(pm.getApplicationLabel(ai).toString(), ai.packageName)
            }
        }
    } else {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).forEach { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg != self && !result.containsKey(pkg)) {
                result[pkg] = InstalledApp(labelOf(pkg), pkg)
            }
        }
        alwaysInclude.forEach { pkg ->
            if (pkg != self && !result.containsKey(pkg)) {
                result[pkg] = InstalledApp(labelOf(pkg), pkg)
            }
        }
    }

    return result.values.sortedBy { it.label.lowercase(Locale.ROOT) }
}
