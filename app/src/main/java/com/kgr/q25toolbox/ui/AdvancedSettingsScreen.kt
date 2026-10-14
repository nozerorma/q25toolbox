package com.kgr.q25toolbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.modules.hotfix.HotfixController
import com.kgr.q25toolbox.modules.hotfix.HotfixId
import com.kgr.q25toolbox.modules.hotfix.HotfixItem
import com.kgr.q25toolbox.modules.hotfix.HotfixStatus
import com.kgr.q25toolbox.modules.hotfix.VfsManager
import com.kgr.q25toolbox.modules.hotfix.VfsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hotfixes by remember { mutableStateOf<List<HotfixItem>>(emptyList()) }
    var vfsStatus by remember { mutableStateOf(VfsStatus.NOT_AVAILABLE) }
    var isHmInstalled by remember { mutableStateOf(false) }
    var vfsRuleCount by remember { mutableStateOf(0) }
    var pendingMountCount by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var isInspecting by remember { mutableStateOf(true) }
    var showAll by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var showHmWarningDialog by remember { mutableStateOf(false) }
    var showKeymintWarningDialog by remember { mutableStateOf(false) }
    var pendingHotfixToEnable by remember { mutableStateOf<HotfixItem?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reloadData(showInspection: Boolean = false) {
        if (showInspection) isInspecting = true
        withContext(Dispatchers.IO) {
            isHmInstalled = VfsManager.isHybridMountInstalled()
            vfsStatus = VfsManager.getStatus()
            vfsRuleCount = VfsManager.getActiveRules().size
            pendingMountCount = if (vfsStatus == VfsStatus.ACTIVE_VFS) VfsManager.getPendingBindMountCount() else 0
            hotfixes = HotfixController.getAllHotfixes(context)
        }
        if (showInspection) {
            delay(1200) // Ensure speech bubble is comfortably readable
            isInspecting = false
        }
    }

    LaunchedEffect(Unit) {
        reloadData(showInspection = true)
    }

    fun executeToggle(item: HotfixItem, enable: Boolean) {
        busy = true
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                HotfixController.toggleHotfix(context, item.id, enable)
            }
            reloadData(showInspection = false)
            busy = false
            if (success) {
                if (item.requiresReboot) {
                    showRebootDialog = true
                }
            } else {
                statusMessage = context.getString(R.string.hotfix_operation_failed)
            }
        }
    }

    fun handleToggleRequest(item: HotfixItem, enable: Boolean) {
        if (enable && (item.id == HotfixId.BT_AUDIO || item.id == HotfixId.CAMERA_50MP) && vfsStatus != VfsStatus.ACTIVE_VFS) {
            pendingHotfixToEnable = item
            showHmWarningDialog = true
        } else if (enable && item.id == HotfixId.KEYMINT_TEE) {
            pendingHotfixToEnable = item
            showKeymintWarningDialog = true
        } else {
            executeToggle(item, enable)
        }
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text(stringResource(R.string.hotfix_reboot_dialog_title)) },
            text = { Text(stringResource(R.string.hotfix_reboot_dialog_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        showRebootDialog = false
                        scope.launch(Dispatchers.IO) {
                            RootShell.run("reboot")
                        }
                    }
                ) {
                    Text(stringResource(R.string.hotfix_reboot_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(stringResource(R.string.hotfix_reboot_later))
                }
            }
        )
    }

    if (showHmWarningDialog && pendingHotfixToEnable != null) {
        val targetItem = pendingHotfixToEnable!!
        AlertDialog(
            onDismissRequest = {
                showHmWarningDialog = false
                pendingHotfixToEnable = null
            },
            title = { Text(stringResource(R.string.vfs_warn_dialog_title)) },
            text = { Text(stringResource(R.string.vfs_warn_dialog_text)) },
            confirmButton = {
                if (!isHmInstalled) {
                    Button(
                        onClick = {
                            showHmWarningDialog = false
                            pendingHotfixToEnable = null
                            busy = true
                            statusMessage = context.getString(R.string.vfs_installing_hm)
                            scope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    VfsManager.installHybridMountModule(context)
                                }
                                reloadData(showInspection = false)
                                busy = false
                                if (success) {
                                    showRebootDialog = true
                                    statusMessage = context.getString(R.string.vfs_installed_hm_success)
                                } else {
                                    statusMessage = context.getString(R.string.hotfix_operation_failed)
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.vfs_btn_install_hm))
                    }
                } else {
                    Button(
                        onClick = {
                            showHmWarningDialog = false
                            pendingHotfixToEnable = null
                            executeToggle(targetItem, true)
                        }
                    ) {
                        Text(stringResource(R.string.vfs_warn_continue_classic))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!isHmInstalled) {
                            showHmWarningDialog = false
                            pendingHotfixToEnable = null
                            executeToggle(targetItem, true)
                        } else {
                            showHmWarningDialog = false
                            pendingHotfixToEnable = null
                        }
                    }
                ) {
                    Text(
                        if (!isHmInstalled) stringResource(R.string.vfs_warn_continue_classic)
                        else stringResource(android.R.string.cancel)
                    )
                }
            }
        )
    }

    if (showKeymintWarningDialog && pendingHotfixToEnable != null) {
        val targetItem = pendingHotfixToEnable!!
        AlertDialog(
            onDismissRequest = {
                showKeymintWarningDialog = false
                pendingHotfixToEnable = null
            },
            title = { Text(stringResource(R.string.hotfix_keymint_warn_title)) },
            text = { Text(stringResource(R.string.hotfix_keymint_warn_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        showKeymintWarningDialog = false
                        pendingHotfixToEnable = null
                        executeToggle(targetItem, true)
                    }
                ) {
                    Text(stringResource(R.string.hotfix_keymint_warn_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showKeymintWarningDialog = false
                        pendingHotfixToEnable = null
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    ScreenScaffold(title = stringResource(Screen.AdvancedSettings.titleRes), onBack = onBack) {
        // VFS status card
        VfsEngineCard(
            vfsStatus = vfsStatus,
            isHmInstalled = isHmInstalled,
            ruleCount = vfsRuleCount,
            pendingMountCount = pendingMountCount,
            busy = busy,
            onMigrateClick = {
                busy = true
                scope.launch {
                    val count = withContext(Dispatchers.IO) {
                        VfsManager.migrateBindMountsToVfs()
                    }
                    reloadData(showInspection = false)
                    busy = false
                    statusMessage = if (count > 0) {
                        context.getString(R.string.vfs_migrated_count, count)
                    } else {
                        context.getString(R.string.vfs_already_clean)
                    }
                }
            },
            onInstallHmClick = {
                busy = true
                statusMessage = context.getString(R.string.vfs_installing_hm)
                scope.launch {
                    val success = withContext(Dispatchers.IO) {
                        VfsManager.installHybridMountModule(context)
                    }
                    reloadData(showInspection = false)
                    busy = false
                    if (success) {
                        showRebootDialog = true
                        statusMessage = context.getString(R.string.vfs_installed_hm_success)
                    } else {
                        statusMessage = context.getString(R.string.hotfix_operation_failed)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (isInspecting) {
            // Inspection speech bubble with clock
            InspectionSpeechBubble()
        } else {
            val applicableHotfixes = remember(hotfixes) {
                hotfixes.filter { it.status == HotfixStatus.AVAILABLE || it.status == HotfixStatus.ACTIVE }
            }
            val otherHotfixes = remember(hotfixes, applicableHotfixes) {
                hotfixes.filter { it !in applicableHotfixes }
            }

            if (applicableHotfixes.isEmpty()) {
                // Silk state: "Tu Q25 va como la seda. Aquí no hay nada para ti."
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = stringResource(R.string.hotfix_nothing_needed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.hotfix_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                applicableHotfixes.forEach { item ->
                    HotfixCard(
                        item = item,
                        busy = busy,
                        onToggle = { enable -> handleToggleRequest(item, enable) }
                    )
                }
            }

            // If there are other hotfixes (incompatible or not applicable to this ROM/hardware), allow expanding them
            if (otherHotfixes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (showAll) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (showAll) stringResource(R.string.hotfix_hide_inapplicable)
                        else stringResource(R.string.hotfix_show_all, otherHotfixes.size)
                    )
                }

                if (showAll) {
                    otherHotfixes.forEach { item ->
                        HotfixCard(
                            item = item,
                            busy = busy,
                            onToggle = { enable -> handleToggleRequest(item, enable) }
                        )
                    }
                }
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun InspectionSpeechBubble() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = stringResource(R.string.hotfix_checking_bubble),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun VfsEngineCard(
    vfsStatus: VfsStatus,
    isHmInstalled: Boolean,
    ruleCount: Int,
    pendingMountCount: Int,
    busy: Boolean,
    onMigrateClick: () -> Unit,
    onInstallHmClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.vfs_engine_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            val statusText = when (vfsStatus) {
                VfsStatus.ACTIVE_VFS -> stringResource(R.string.vfs_status_active, ruleCount)
                VfsStatus.FALLBACK_BIND -> stringResource(R.string.vfs_status_fallback)
                VfsStatus.NOT_AVAILABLE -> stringResource(R.string.vfs_status_not_available)
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium)

            if (vfsStatus == VfsStatus.ACTIVE_VFS && pendingMountCount == 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.vfs_all_migrated),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if ((vfsStatus == VfsStatus.ACTIVE_VFS && pendingMountCount > 0) || (!isHmInstalled)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (vfsStatus == VfsStatus.ACTIVE_VFS && pendingMountCount > 0) {
                        OutlinedButton(
                            onClick = onMigrateClick,
                            enabled = !busy
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text(stringResource(R.string.vfs_btn_migrate_pending, pendingMountCount))
                        }
                    } else if (!isHmInstalled) {
                        Button(
                            onClick = onInstallHmClick,
                            enabled = !busy
                        ) {
                            Text(stringResource(R.string.vfs_btn_install_hm))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HotfixCard(
    item: HotfixItem,
    busy: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(item.status)
            }

            Text(
                text = stringResource(item.descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            item.statusDetail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (item.requiresReboot) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 2.dp)
                        )
                        Text(
                            text = stringResource(R.string.hotfix_requires_reboot),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 2.dp)
                        )
                        Text(
                            text = stringResource(R.string.hotfix_live_no_reboot),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                val canToggle = item.status == HotfixStatus.ACTIVE || item.status == HotfixStatus.AVAILABLE
                val isChecked = item.isEnabled || item.isLiveApplied

                Switch(
                    checked = isChecked,
                    enabled = !busy && canToggle,
                    onCheckedChange = onToggle
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: HotfixStatus) {
    val (bgColor, textColor, textRes) = when (status) {
        HotfixStatus.ACTIVE -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            R.string.hotfix_badge_active
        )
        HotfixStatus.AVAILABLE -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            R.string.hotfix_badge_available
        )
        HotfixStatus.NOT_APPLICABLE -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            R.string.hotfix_badge_not_needed
        )
        HotfixStatus.PREREQUISITE_MISSING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            R.string.hotfix_badge_missing_prereq
        )
        HotfixStatus.INCOMPATIBLE -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            R.string.hotfix_badge_incompatible
        )
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}
