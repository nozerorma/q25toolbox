package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.kgr.q25toolbox.modules.CircleToSearchController
import com.kgr.q25toolbox.modules.RecentsTweaksController.HookHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CircleToSearchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isEnabled by remember { mutableStateOf(CircleToSearchController.isEnabled(context)) }
    var xposedActive by remember { mutableStateOf(CircleToSearchController.isXposedActive()) }
    var health by remember { mutableStateOf(HookHealth.UNKNOWN) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(isEnabled, refreshKey) {
        withContext(Dispatchers.IO) {
            val xa = CircleToSearchController.isXposedActive()
            val h = CircleToSearchController.hookHealth()
            withContext(Dispatchers.Main) {
                xposedActive = xa
                health = h
            }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.title_circle_to_search),
        onBack = onBack
    ) {
        // Master Activation Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.cts_master_switch_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.cts_master_switch_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            isEnabled = checked
                            coroutineScope.launch(Dispatchers.IO) {
                                CircleToSearchController.setEnabled(context, checked)
                                withContext(Dispatchers.Main) {
                                    refreshKey++
                                }
                            }
                        }
                    )
                }

                if (isEnabled) {
                    // LSPosed module status
                    Text(
                        text = stringResource(
                            if (xposedActive) R.string.cts_xposed_ok else R.string.cts_xposed_missing
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (xposedActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!xposedActive) {
                        Text(
                            text = stringResource(R.string.cts_xposed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // What the Google app itself reports: did the hook install in THIS build?
                    Text(
                        text = stringResource(
                            when (health) {
                                HookHealth.OK -> R.string.cts_hook_status_ok
                                HookHealth.BROKEN -> R.string.cts_hook_status_broken
                                HookHealth.UNKNOWN -> R.string.cts_hook_status_unknown
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.cts_status_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Test Button
                    FilledTonalButton(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                CircleToSearchController.trigger(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cts_test_trigger_button))
                    }
                }
            }
        }

        // Central notice directing user to Keyboard Shortcuts / Key Mapper
        ShortcutMappingNotice()
    }
}
