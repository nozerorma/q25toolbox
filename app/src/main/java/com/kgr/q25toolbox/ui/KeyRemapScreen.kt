package com.kgr.q25toolbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.KeyRemapController
import com.kgr.q25toolbox.service.Q25AccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KeyRemapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var enabled by remember { mutableStateOf(KeyRemapController.isEnabled(prefs)) }
    var sourceKey by remember { mutableStateOf(KeyRemapController.getSourceKey(prefs)) }
    var busy by remember { mutableStateOf(false) }

    ScreenScaffold(title = stringResource(Screen.KeyRemap.titleRes), onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.key_remap_desc),
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                Text(stringResource(R.string.key_remap_source_title), style = MaterialTheme.typography.titleSmall)

                Column(modifier = Modifier.selectableGroup()) {
                    KeyRemapController.SourceKey.entries.forEach { key ->
                        val label = when (key) {
                            KeyRemapController.SourceKey.GRAVE -> stringResource(R.string.key_remap_currency_label)
                            KeyRemapController.SourceKey.RSHIFT -> stringResource(R.string.key_remap_rshift_label)
                            KeyRemapController.SourceKey.RECENTS -> stringResource(R.string.key_remap_recents_label)
                        }
                        val description = when (key) {
                            KeyRemapController.SourceKey.GRAVE -> stringResource(R.string.key_remap_currency_desc)
                            KeyRemapController.SourceKey.RSHIFT -> stringResource(R.string.key_remap_rshift_desc)
                            KeyRemapController.SourceKey.RECENTS -> stringResource(R.string.key_remap_recents_desc)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = sourceKey == key,
                                    enabled = !busy,
                                    onClick = {
                                        sourceKey = key
                                        KeyRemapController.setSourceKey(prefs, key)
                                        busy = true
                                        scope.launch(Dispatchers.IO) {
                                            KeyRemapController.applySettings(prefs)
                                            withContext(Dispatchers.Main) {
                                                busy = false
                                            }
                                        }
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = sourceKey == key,
                                enabled = !busy,
                                onClick = null,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.key_remap_enabled_label))
                    Switch(
                        checked = enabled,
                        enabled = !busy,
                        onCheckedChange = { checked ->
                            enabled = checked
                            KeyRemapController.setEnabled(prefs, checked)
                            busy = true
                            scope.launch(Dispatchers.IO) {
                                KeyRemapController.applySettings(prefs)
                                withContext(Dispatchers.Main) {
                                    busy = false
                                }
                            }
                        }
                    )
                }
            }

            if (busy) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
