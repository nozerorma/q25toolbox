package com.kgr.q25toolbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.modules.KeyRemapController
import com.kgr.q25toolbox.service.Q25AccessibilityService
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled

@Composable
fun KeyRemapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(KeyRemapController.isEnabled(prefs)) }
    var sourceKey by remember { mutableStateOf(KeyRemapController.getSourceKey(prefs)) }

    LaunchedEffect(Unit) {
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
    }

    ScreenScaffold(title = Screen.KeyRemap.title, onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Text(
            "Intercepts a spare physical key and replaces it with Right Ctrl. " +
            "Injection uses the hardware event device so all apps — terminals, " +
            "editors, SSH clients — receive a proper Ctrl modifier.",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        Text("Source key", style = MaterialTheme.typography.titleSmall)

        Column(modifier = Modifier.selectableGroup()) {
            KeyRemapController.SourceKey.entries.forEach { key ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = sourceKey == key,
                            onClick = {
                                sourceKey = key
                                KeyRemapController.setSourceKey(prefs, key)
                            },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = sourceKey == key,
                        onClick = null,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(key.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            key.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Enabled")
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    KeyRemapController.setEnabled(prefs, checked)
                }
            )
        }
    }
}
