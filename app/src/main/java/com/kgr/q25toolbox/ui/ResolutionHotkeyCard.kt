package com.kgr.q25toolbox.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.AppScalingController
import com.kgr.q25toolbox.modules.AppScalingController.Res
import com.kgr.q25toolbox.modules.ResolutionHotkey

/**
 * Global Resolution Modes & Shortcut Notice Card.
 * Displays the ordered list of global resolutions cycled by the system shortcut,
 * and shows an informative banner directing the user to Keyboard Shortcuts for key mapping.
 */
@Composable
fun ResolutionHotkeyCard(onCustom: () -> Unit, listVersion: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.shortcut_action_aspect_ratio),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.shortcut_action_aspect_ratio_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ResolutionCycleConfig(onCustom)

            ShortcutMappingNotice()
        }
    }
}

@Composable
fun ResolutionCycleConfig(onOpenCustomRes: () -> Unit) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(ResolutionHotkey.list(context)) }
    var menuOpen by remember { mutableStateOf(false) }

    fun saveList(l: List<Res>) {
        list = l
        ResolutionHotkey.setList(context, l)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.res_hotkey_list),
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            list.forEachIndexed { i, r ->
                InputChip(
                    selected = false,
                    onClick = { saveList(list - r) },
                    label = { Text("${i + 1}. ${r.w}×${r.h} ✕") }
                )
            }
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(stringResource(R.string.res_hotkey_add))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    AppScalingController.PRESETS.filter { !it.isNative && it !in list }.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(AppScalingController.label(p)) },
                            onClick = {
                                menuOpen = false
                                saveList(list + p)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Custom…") },
                        onClick = {
                            menuOpen = false
                            onOpenCustomRes()
                        }
                    )
                }
            }
        }
    }
}
