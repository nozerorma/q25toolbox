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
import androidx.compose.material3.Slider
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
    var scrimAlpha by remember { mutableStateOf(1f) }
    var repairMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun updateStatusAsync() {
        scope.launch(Dispatchers.IO) {
            val newStatus = RecentsTweaksController.queryStatus()
            val alpha = RecentsTweaksController.getScrimAlpha()
            withContext(Dispatchers.Main) {
                status = newStatus
                scrimAlpha = alpha
            }
        }
    }

    fun commitScrimAlphaAsync(alpha: Float) {
        scope.launch(Dispatchers.IO) {
            RecentsTweaksController.setScrimAlpha(alpha)
            RecentsTweaksController.restartLauncher()
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

    fun repairRecentsProviderAsync() {
        scope.launch(Dispatchers.IO) {
            val result = RecentsTweaksController.repairRecentsProvider(context)
            delay(200)
            val newStatus = RecentsTweaksController.queryStatus()
            val message = when {
                result.needsReboot -> context.getString(R.string.recents_repair_needs_reboot)
                result.mounted -> context.getString(R.string.recents_repair_success)
                else -> context.getString(R.string.recents_repair_failed)
            }
            withContext(Dispatchers.Main) {
                status = newStatus
                repairMessage = message
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

        Text(
            text = stringResource(R.string.recents_ota_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.recents_transparency_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${(scrimAlpha * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(R.string.recents_transparency_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = scrimAlpha,
                    onValueChange = { scrimAlpha = it },
                    onValueChangeFinished = { commitScrimAlphaAsync(scrimAlpha) },
                    valueRange = 0f..1f
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.recents_repair_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.recents_repair_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        repairMessage = null
                        repairRecentsProviderAsync()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.recents_repair_button))
                }
                repairMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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
