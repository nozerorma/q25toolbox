package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.AdBlockController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdBlockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var needsReboot by remember { mutableStateOf(false) }
    var entryCount by remember { mutableStateOf(0) }
    var whitelist by remember { mutableStateOf<List<String>>(emptyList()) }
    var sources by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var updateStatus by remember { mutableStateOf("none") }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var searchTerm by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var newDomain by remember { mutableStateOf("") }
    var newWhitelistEntry by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("") }
    var showResetConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val isInst = AdBlockController.isInstalled()
                AdBlockSnapshot(
                    installed = isInst,
                    enabled = if (isInst) AdBlockController.isEnabled() else false,
                    needsReboot = AdBlockController.requiresReboot(),
                    entryCount = if (isInst) AdBlockController.entryCount() else 0,
                    whitelist = if (isInst) AdBlockController.whitelistList() else emptyList(),
                    sources = if (isInst) AdBlockController.sourceList() else emptyList(),
                    updateStatus = if (isInst) AdBlockController.updateStatus() else "none"
                )
            }
            installed = snapshot.installed
            enabled = snapshot.enabled
            needsReboot = snapshot.needsReboot
            entryCount = snapshot.entryCount
            whitelist = snapshot.whitelist
            sources = snapshot.sources
            updateStatus = snapshot.updateStatus
        }
    }

    fun runAction(block: () -> com.kgr.q25toolbox.core.ShellResult, onDone: () -> Unit = {}) {
        scope.launch {
            busy = true
            error = null
            val result = withContext(Dispatchers.IO) { block() }
            busy = false
            if (result.success) {
                onDone()
                refresh()
            } else {
                error = result.outString.ifBlank { context.getString(R.string.adblock_command_failed) }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }


    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.adblock_reset_dialog_title)) },
            text = { Text(stringResource(R.string.adblock_reset_dialog_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    runAction({ AdBlockController.resetToDefaults() }) {
                        message = context.getString(R.string.adblock_reset_done)
                    }
                }) { Text(stringResource(R.string.adblock_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.generic_cancel)) } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.adblock_cd_back))
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.title_adblock), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            if (!installed) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.adblock_not_installed_desc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            enabled = !busy,
                            onClick = {
                                runAction({ AdBlockController.install(context) }) {
                                    message = context.getString(R.string.adblock_installed_message)
                                }
                            }
                        ) { Text(stringResource(R.string.adblock_install)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                return@Column
            }

            if (needsReboot) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.adblock_reboot_required),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Status / enable toggle
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.adblock_filtering_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.adblock_blocked_entries, entryCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        enabled = !busy,
                        onCheckedChange = { checked ->
                            runAction({ AdBlockController.setEnabled(checked) })
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.adblock_section_manage_entries))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchTerm,
                            onValueChange = {
                                searchTerm = it
                                scope.launch {
                                    searchResults = if (it.isBlank()) emptyList() else withContext(Dispatchers.IO) {
                                        AdBlockController.searchEntries(it)
                                    }
                                }
                            },
                            label = { Text(stringResource(R.string.adblock_search_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (searchResults.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(searchResults) { domain ->
                                EntryRow(domain) {
                                    runAction({ AdBlockController.removeEntry(domain) }) {
                                        searchResults = searchResults - domain
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newDomain,
                            onValueChange = { newDomain = it },
                            label = { Text(stringResource(R.string.adblock_add_domain_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !busy && newDomain.isNotBlank(),
                            onClick = {
                                val domain = newDomain.trim()
                                runAction({ AdBlockController.addEntry(domain) }) { newDomain = "" }
                            }
                        ) { Text(stringResource(R.string.adblock_add)) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.adblock_section_whitelist))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.adblock_whitelist_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    whitelist.forEach { domain ->
                        EntryRow(domain) {
                            runAction({ AdBlockController.whitelistRemove(domain) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newWhitelistEntry,
                            onValueChange = { newWhitelistEntry = it },
                            label = { Text(stringResource(R.string.adblock_whitelist_input_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !busy && newWhitelistEntry.isNotBlank(),
                            onClick = {
                                val domain = newWhitelistEntry.trim()
                                runAction({ AdBlockController.whitelistAdd(domain) }) { newWhitelistEntry = "" }
                            }
                        ) { Text(stringResource(R.string.adblock_add)) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.adblock_section_sources))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    sources.forEach { (lineNumber, url) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(url, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { runAction({ AdBlockController.sourceRemove(lineNumber) }) },
                                enabled = !busy
                            ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.adblock_cd_remove_source)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newSourceUrl,
                            onValueChange = { newSourceUrl = it },
                            label = { Text(stringResource(R.string.adblock_source_url_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !busy && newSourceUrl.isNotBlank(),
                            onClick = {
                                val url = newSourceUrl.trim()
                                runAction({ AdBlockController.sourceAdd(url) }) { newSourceUrl = "" }
                            }
                        ) { Text(stringResource(R.string.adblock_add)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !busy && !updateStatus.startsWith("running"),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val triggered = withContext(Dispatchers.IO) { AdBlockController.triggerUpdate() }
                                    if (!triggered.success) {
                                        busy = false
                                        error = triggered.outString
                                        return@launch
                                    }
                                    updateStatus = "running"
                                    // Poll until the backend reports done/error, however long the fetch takes -
                                    // hosts_ctl.sh's status string doesn't change while still running, so we
                                    // can't rely on the value itself to know when to keep checking.
                                    while (true) {
                                        delay(2000)
                                        val status = withContext(Dispatchers.IO) { AdBlockController.updateStatus() }
                                        updateStatus = status
                                        if (!status.startsWith("running")) break
                                    }
                                    busy = false
                                    refresh()
                                }
                            }
                        ) { Text(stringResource(R.string.adblock_update_sources_now)) }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            updateStatusLabel(context, updateStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.adblock_section_danger_zone))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { showResetConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.adblock_reset_button)) }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class AdBlockSnapshot(
    val installed: Boolean,
    val enabled: Boolean,
    val needsReboot: Boolean,
    val entryCount: Int,
    val whitelist: List<String>,
    val sources: List<Pair<Int, String>>,
    val updateStatus: String
)

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun EntryRow(domain: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(domain, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.adblock_cd_remove))
        }
    }
}

private fun updateStatusLabel(context: android.content.Context, status: String): String = when {
    status == "none" -> ""
    status.startsWith("running") -> context.getString(R.string.adblock_update_status_updating)
    status.startsWith("done") -> context.getString(R.string.adblock_update_status_done)
    status.startsWith("error") -> context.getString(
        R.string.adblock_update_status_error,
        status.removePrefix("error:").substringBeforeLast(':')
    )
    else -> status
}
