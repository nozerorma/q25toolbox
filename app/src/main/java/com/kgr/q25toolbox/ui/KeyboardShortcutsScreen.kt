package com.kgr.q25toolbox.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.AppScalingController
import com.kgr.q25toolbox.modules.AppScalingController.Res
import com.kgr.q25toolbox.modules.GestureSettings
import com.kgr.q25toolbox.modules.KeyboardShortcutsController
import com.kgr.q25toolbox.modules.KeyboardShortcutsController.ClickType
import com.kgr.q25toolbox.modules.KeyboardShortcutsController.ShortcutConfig
import com.kgr.q25toolbox.modules.ResolutionHotkey
import kotlinx.coroutines.delay

@Composable
fun KeyboardShortcutsScreen(
    onBack: () -> Unit,
    onNavigate: ((Screen) -> Unit)? = null
) {
    val context = LocalContext.current
    var recordingAction by remember { mutableStateOf<GestureSettings.Action?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var showCustomResDialog by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(Screen.KeyboardShortcuts.titleRes),
        onBack = onBack
    ) {
        Text(
            text = stringResource(R.string.keyboard_shortcuts_header_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        KeyboardShortcutsController.SUPPORTED_ACTIONS.forEach { action ->
            var config by remember(refreshTrigger) {
                mutableStateOf(KeyboardShortcutsController.getShortcut(context, action))
            }

            ActionShortcutCard(
                action = action,
                config = config,
                onConfigChanged = { updated ->
                    config = updated
                    KeyboardShortcutsController.setShortcut(context, updated)
                },
                onStartRecord = {
                    recordingAction = action
                },
                onOpenCustomRes = if (action == GestureSettings.Action.ASPECT_RATIO) {
                    { showCustomResDialog = true }
                } else null
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    // Key recording modal dialog
    recordingAction?.let { act ->
        KeyRecordDialog(
            actionName = stringResource(actionTitleRes(act)),
            onDismiss = {
                recordingAction = null
            },
            onKeyRecorded = { keyCode, mods ->
                val current = KeyboardShortcutsController.getShortcut(context, act)
                val updated = current.copy(
                    enabled = true,
                    keyCode = keyCode,
                    modifiers = mods
                )
                KeyboardShortcutsController.setShortcut(context, updated)
                recordingAction = null
                refreshTrigger++
            },
            onClear = {
                val current = KeyboardShortcutsController.getShortcut(context, act)
                val updated = current.copy(
                    enabled = false,
                    keyCode = 0,
                    modifiers = 0
                )
                KeyboardShortcutsController.setShortcut(context, updated)
                recordingAction = null
                refreshTrigger++
            }
        )
    }

    // Custom Resolution W x H Dialog (for ASPECT_RATIO)
    if (showCustomResDialog) {
        CustomResolutionDialog(
            onDismiss = { showCustomResDialog = false },
            onAdd = { w, h ->
                val current = ResolutionHotkey.list(context)
                ResolutionHotkey.setList(context, current + Res(w, h))
                refreshTrigger++
                showCustomResDialog = false
            }
        )
    }
}

/**
 * Clean Action Card offering two distinct bocadillos/pills:
 * [ Mapeo in-app ] vs [ Mapear con Key Mapper ]
 */
@Composable
fun ActionShortcutCard(
    action: GestureSettings.Action,
    config: ShortcutConfig,
    onConfigChanged: (ShortcutConfig) -> Unit,
    onStartRecord: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCustomRes: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = In-app, 1 = Key Mapper

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Icon + Title + Description
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = actionIcon(action),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(actionTitleRes(action)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(actionDescRes(action)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Two bocadillos / pills to avoid clutter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = {
                        Text(
                            text = stringResource(R.string.shortcut_tab_in_app),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (selectedTab == 0) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )

                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = {
                        Text(
                            text = stringResource(R.string.shortcut_tab_key_mapper),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (selectedTab == 1) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }

            // Tab Content
            if (selectedTab == 0) {
                // In-App mapping panel
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.shortcut_enable_in_app),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { onConfigChanged(config.copy(enabled = it)) }
                        )
                    }

                    if (config.enabled) {
                        // Key Trigger row
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.shortcut_trigger_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = KeyboardShortcutsController.formatKeyLabel(
                                            context,
                                            config.keyCode,
                                            config.modifiers
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (config.keyCode != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }

                                FilledTonalButton(
                                    onClick = onStartRecord,
                                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                                ) {
                                    Icon(
                                        Icons.Default.Keyboard,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.shortcut_record_button))
                                }
                            }
                        }

                        // Click Type selector (Corta, Larga, Doble)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.shortcut_press_type),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ClickType.entries.forEach { ct ->
                                    FilterChip(
                                        selected = config.clickType == ct,
                                        onClick = { onConfigChanged(config.copy(clickType = ct)) },
                                        label = { Text(stringResource(ct.labelRes)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Single tap pass-through switch for Long / Double press
                        if (config.clickType != ClickType.SHORT) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.shortcut_tap_pass),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.shortcut_tap_pass_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = config.tapPass,
                                    onCheckedChange = { onConfigChanged(config.copy(tapPass = it)) }
                                )
                            }
                        }

                        // Vibration feedback toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.shortcut_vibrate),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = config.vibrate,
                                onCheckedChange = { onConfigChanged(config.copy(vibrate = it)) }
                            )
                        }

                        // Extra configuration for ASPECT_RATIO (Resolution cycle list)
                        if (action == GestureSettings.Action.ASPECT_RATIO && onOpenCustomRes != null) {
                            ResolutionCycleConfig(onOpenCustomRes)
                        }
                    }
                }
            } else {
                // Key Mapper instruction panel
                KeyMapperCard(
                    actionValue = action.name,
                    customTitle = stringResource(R.string.key_mapper_title)
                )
            }
        }
    }
}



/**
 * Key Record Dialog: listens for physical key events with a 5-second countdown timer.
 */
@Composable
fun KeyRecordDialog(
    actionName: String,
    onDismiss: () -> Unit,
    onKeyRecorded: (keyCode: Int, modifiers: Int) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var secondsLeft by remember { mutableIntStateOf(5) }
    var liveKey by remember { mutableStateOf<Int?>(null) }
    var liveMods by remember { mutableIntStateOf(0) }

    // Start controller recording
    DisposableEffect(Unit) {
        KeyboardShortcutsController.startRecording { kc, mods ->
            onKeyRecorded(kc, mods)
        }
        onDispose {
            KeyboardShortcutsController.stopRecording()
        }
    }

    // Countdown timer
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent: androidx.compose.ui.input.key.KeyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    val kc = native.keyCode
                    if (kc == KeyEvent.KEYCODE_BACK) {
                        onDismiss()
                        return@onKeyEvent true
                    }
                    val mods = ResolutionHotkey.modsOf(native.metaState)
                    val isMod = KeyboardShortcutsController.isModifierKey(kc)

                    if (native.action == KeyEvent.ACTION_DOWN) {
                        if (!isMod) {
                            liveKey = kc
                            liveMods = mods
                        } else {
                            liveMods = mods
                        }
                    } else if (native.action == KeyEvent.ACTION_UP) {
                        val finalKey = liveKey ?: (if (isMod) kc else null)
                        val finalMods = if (liveKey != null) liveMods else 0
                        if (finalKey != null) {
                            onKeyRecorded(finalKey, finalMods)
                        }
                    }
                    true
                },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.shortcut_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.shortcut_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Visual Key Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val label = if (liveKey != null) {
                            KeyboardShortcutsController.formatKeyLabel(context, liveKey!!, liveMods)
                        } else if (liveMods != 0) {
                            ResolutionHotkey.MODIFIERS.filter { liveMods and it.first != 0 }.joinToString(" + ") { it.second }
                        } else {
                            stringResource(R.string.shortcut_dialog_waiting)
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Countdown indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { secondsLeft / 5f },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp
                    )
                    Text(
                        text = "${secondsLeft}s",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onClear,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.shortcut_dialog_clear))
                    }

                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.shortcut_dialog_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomResolutionDialog(
    onDismiss: () -> Unit,
    onAdd: (w: Int, h: Int) -> Unit
) {
    var width by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom resolution") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter { c -> c.isDigit() } },
                    label = { Text("Width (px)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter { c -> c.isDigit() } },
                    label = { Text("Height (px)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val w = width.toIntOrNull()
                    val h = height.toIntOrNull()
                    if (w != null && h != null && w in 200..3840 && h in 200..3840) {
                        onAdd(w, h)
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shortcut_dialog_cancel))
            }
        }
    )
}

private fun actionIcon(a: GestureSettings.Action): ImageVector = when (a) {
    GestureSettings.Action.RECENTS -> Icons.Outlined.GridView
    GestureSettings.Action.QR_ENLARGER -> Icons.Outlined.QrCodeScanner
    GestureSettings.Action.ASPECT_RATIO -> Icons.Outlined.AspectRatio
    GestureSettings.Action.ROTATION -> Icons.Outlined.ScreenRotation
    GestureSettings.Action.EXTRA_DIM -> Icons.Outlined.Brightness4
    GestureSettings.Action.GESTURES -> Icons.Outlined.Swipe
    else -> Icons.Default.Keyboard
}

private fun actionTitleRes(a: GestureSettings.Action): Int = when (a) {
    GestureSettings.Action.RECENTS -> R.string.shortcut_action_recents
    GestureSettings.Action.QR_ENLARGER -> R.string.shortcut_action_qr
    GestureSettings.Action.ASPECT_RATIO -> R.string.shortcut_action_aspect_ratio
    GestureSettings.Action.ROTATION -> R.string.shortcut_action_rotation
    GestureSettings.Action.EXTRA_DIM -> R.string.shortcut_action_extra_dim
    GestureSettings.Action.GESTURES -> R.string.shortcut_action_gestures
    GestureSettings.Action.CIRCLE_TO_SEARCH -> R.string.title_circle_to_search
    else -> R.string.title_keyboard_shortcuts
}

private fun actionDescRes(a: GestureSettings.Action): Int = when (a) {
    GestureSettings.Action.RECENTS -> R.string.shortcut_action_recents_desc
    GestureSettings.Action.QR_ENLARGER -> R.string.shortcut_action_qr_desc
    GestureSettings.Action.ASPECT_RATIO -> R.string.shortcut_action_aspect_ratio_desc
    GestureSettings.Action.ROTATION -> R.string.shortcut_action_rotation_desc
    GestureSettings.Action.EXTRA_DIM -> R.string.shortcut_action_extra_dim_desc
    GestureSettings.Action.GESTURES -> R.string.shortcut_action_gestures_desc
    GestureSettings.Action.CIRCLE_TO_SEARCH -> R.string.subtitle_circle_to_search
    else -> R.string.subtitle_keyboard_shortcuts
}
