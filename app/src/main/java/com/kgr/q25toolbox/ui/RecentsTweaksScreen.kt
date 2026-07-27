package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.RecentsTweaksController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecentsTweaksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(RecentsTweaksController.RecentsStatus()) }
    val scope = rememberCoroutineScope()

    fun updateStatusAsync() {
        scope.launch(Dispatchers.IO) {
            val newStatus = RecentsTweaksController.queryStatus()
            withContext(Dispatchers.Main) {
                status = newStatus
            }
        }
    }

    fun toggleNativePatchAsync(enable: Boolean) {
        scope.launch(Dispatchers.IO) {
            RecentsTweaksController.setNativeGridPatch(context, enable)
            delay(200)
            val newStatus = RecentsTweaksController.queryStatus()
            withContext(Dispatchers.Main) {
                status = newStatus
            }
        }
    }

    LaunchedEffect(Unit) {
        updateStatusAsync()
    }

    ScreenScaffold(
        title = stringResource(R.string.title_recents_tweaks),
        onBack = onBack
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.recents_patch_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.recents_patch_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = status.isNativePatchActive,
                    onCheckedChange = { checked ->
                        toggleNativePatchAsync(checked)
                    }
                )
            }
        }

        // Restart Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        RecentsTweaksController.restartLauncher()
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.recents_restart_launcher))
            }

            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        RecentsTweaksController.restartSystemUi()
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.recents_restart_systemui))
            }
        }

        DescriptionDivider()

        Text(
            text = stringResource(R.string.subtitle_recents_tweaks),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
