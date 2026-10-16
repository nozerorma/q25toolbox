package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.GestureSettings
import com.kgr.q25toolbox.modules.KeyboardShortcutsController
import com.kgr.q25toolbox.modules.QrEnlargerController
import com.kgr.q25toolbox.service.Q25AccessibilityService
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled

@Composable
fun QrEnlargerScreen(
    onBack: () -> Unit,
    onOpenGestures: () -> Unit = {}
) {
    val context = LocalContext.current
    var serviceEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
    }

    ScreenScaffold(title = stringResource(Screen.QrEnlarger.titleRes), onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Text(
            stringResource(R.string.qr_enlarger_desc),
            style = MaterialTheme.typography.bodyMedium
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.qr_enlarger_test_button),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.subtitle_qr_enlarger),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val service = Q25AccessibilityService.instance
                        if (service != null) {
                            QrEnlargerController.trigger(service)
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.a11y_banner_needed),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.qr_enlarger_test_button))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.qr_enlarger_trigger_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.qr_enlarger_trigger_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onOpenGestures,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.title_gestures))
                }
            }
        }

        ShortcutMappingNotice()
    }
}
