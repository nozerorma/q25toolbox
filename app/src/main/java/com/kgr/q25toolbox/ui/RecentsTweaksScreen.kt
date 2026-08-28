package com.kgr.q25toolbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.kgr.q25toolbox.modules.RecentsTweaksController.LayoutMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecentsTweaksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var xposedActive by remember { mutableStateOf(RecentsTweaksController.isXposedActive()) }
    var mode by remember { mutableStateOf(LayoutMode.STOCK) }
    var scrimAlpha by remember { mutableFloatStateOf(1f) }
    var repairMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val m = RecentsTweaksController.getLayoutMode()
            val a = RecentsTweaksController.getScrimAlpha()
            withContext(Dispatchers.Main) {
                mode = m
                scrimAlpha = a
                xposedActive = RecentsTweaksController.isXposedActive()
            }
        }
    }

    fun setModeAsync(newMode: LayoutMode) {
        mode = newMode
        scope.launch(Dispatchers.IO) { RecentsTweaksController.setLayoutMode(newMode) }
    }

    fun commitScrimAlphaAsync(alpha: Float) {
        scope.launch(Dispatchers.IO) {
            RecentsTweaksController.setScrimAlpha(alpha)
            RecentsTweaksController.restartLauncher()
        }
    }

    fun repairRecentsProviderAsync() {
        scope.launch(Dispatchers.IO) {
            val result = RecentsTweaksController.repairRecentsProvider(context)
            delay(200)
            val message = when {
                result.needsReboot -> context.getString(R.string.recents_repair_needs_reboot)
                result.mounted -> context.getString(R.string.recents_repair_success)
                else -> context.getString(R.string.recents_repair_failed)
            }
            withContext(Dispatchers.Main) { repairMessage = message }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.title_recents_tweaks),
        onBack = onBack
    ) {
        Text(
            stringResource(R.string.recents_intro),
            style = MaterialTheme.typography.bodySmall
        )

        DescriptionDivider()
        Text(
            stringResource(R.string.recents_section_lsposed),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(
                        if (xposedActive) R.string.recents_xposed_ok
                        else R.string.recents_xposed_missing
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (xposedActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                if (!xposedActive) {
                    Text(
                        stringResource(R.string.recents_xposed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    stringResource(R.string.recents_layout_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.recents_layout_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                val options = listOf(
                    LayoutMode.STOCK to R.string.recents_mode_stock,
                    LayoutMode.GRID to R.string.recents_mode_grid,
                    LayoutMode.MASONRY to R.string.recents_mode_masonry
                )
                options.forEach { (value, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setModeAsync(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == value, onClick = { setModeAsync(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

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
                        stringResource(R.string.recents_transparency_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${(scrimAlpha * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    stringResource(R.string.recents_transparency_desc),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { scope.launch(Dispatchers.IO) { RecentsTweaksController.restartLauncher() } },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) { Text(stringResource(R.string.recents_restart_launcher)) }
            OutlinedButton(
                onClick = { scope.launch(Dispatchers.IO) { RecentsTweaksController.restartSystemUi() } },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) { Text(stringResource(R.string.recents_restart_systemui)) }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.recents_repair_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.recents_repair_desc),
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
                ) { Text(stringResource(R.string.recents_repair_button)) }
                repairMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.subtitle_recents_tweaks),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
