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
import com.kgr.q25toolbox.modules.SlimRecentsController
import androidx.compose.ui.res.stringArrayResource
import android.view.WindowManager
import android.os.Build
import com.kgr.q25toolbox.service.Q25AccessibilityService
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled
import com.kgr.q25toolbox.modules.GestureSettings
import com.kgr.q25toolbox.modules.KeyboardShortcutsController
import android.content.Context
import com.kgr.q25toolbox.core.Rom
import com.kgr.q25toolbox.modules.RecentsTweaksController
import com.kgr.q25toolbox.modules.RecentsTweaksController.HookHealth
import com.kgr.q25toolbox.modules.RecentsTweaksController.LayoutMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecentsTweaksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var xposedActive by remember { mutableStateOf(RecentsTweaksController.isXposedActive()) }
    var mode by remember { mutableStateOf(LayoutMode.STOCK) }
    var scrimAlpha by remember { mutableFloatStateOf(1f) }
    var serviceEnabled by remember { mutableStateOf(true) }
    var health by remember { mutableStateOf(HookHealth.UNKNOWN) }
    var hookInUse by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { serviceEnabled = isQ25AccessibilityServiceEnabled(context) }
    val onLineage = rememberRom().value == Rom.LINEAGE

    // Reads everything the screen shows from the device (root); also used after any change.
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val m = RecentsTweaksController.getLayoutMode()
            val a = RecentsTweaksController.getScrimAlpha()
            val h = RecentsTweaksController.hookHealth()
            val inUse = RecentsTweaksController.gridUsesHook()
            withContext(Dispatchers.Main) {
                mode = m
                scrimAlpha = a
                health = h
                hookInUse = inUse
                xposedActive = RecentsTweaksController.isXposedActive()
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun setModeAsync(newMode: LayoutMode) {
        mode = newMode
        scope.launch(Dispatchers.IO) {
            // Stores the choice, settles hook-or-overlay for Grid (auto), and remaps the physical Recents key
            // exactly when one of our overlays is what will open (see KeyRemapController).
            RecentsTweaksController.applyMode(context, newMode)
            refresh()
        }
    }

    fun recheckAsync() {
        scope.launch(Dispatchers.IO) {
            RecentsTweaksController.reconcileGrid(context)
            refresh()
        }
    }

    fun commitScrimAlphaAsync(alpha: Float) {
        scope.launch(Dispatchers.IO) {
            RecentsTweaksController.setScrimAlpha(alpha)
            RecentsTweaksController.restartLauncher()
        }
    }

    // A v3.x install stores the hooked Grid as GRID until the first launch of v4 adopts it as Grid (auto).
    val shownMode = if (mode == LayoutMode.GRID) LayoutMode.GRID_AUTO else mode
    // One of our own windows will open (needs the accessibility service and the remapped Recents key).
    val overlayInUse = shownMode.isOverlay && !(shownMode == LayoutMode.GRID_AUTO && hookInUse)

    ScreenScaffold(
        title = stringResource(R.string.title_recents_tweaks),
        onBack = onBack
    ) {
        Text(
            stringResource(R.string.recents_intro),
            style = MaterialTheme.typography.bodySmall
        )
        if (overlayInUse) AccessibilityServiceBanner(serviceEnabled)

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
                // What the launcher itself reports: has the hook installed everything Grid needs in THIS build?
                Text(
                    stringResource(
                        when (health) {
                            HookHealth.OK -> R.string.recents_hook_status_ok
                            HookHealth.BROKEN -> R.string.recents_hook_status_broken
                            HookHealth.UNKNOWN -> R.string.recents_hook_status_unknown
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (shownMode == LayoutMode.GRID_AUTO) {
                    Text(
                        stringResource(
                            if (hookInUse) R.string.recents_grid_using_hook else R.string.recents_grid_using_overlay
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedButton(
                    onClick = { recheckAsync() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) { Text(stringResource(R.string.recents_hook_recheck)) }
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
                // "Grid" picks the LSPosed Grid when the hook works in this launcher and the standalone one otherwise;
                // "Grid, standalone" never touches the hook. The hooked Masonry is offered on BenOS/ZinwaOS only.
                val options = if (onLineage) listOf(
                    LayoutMode.STOCK to R.string.recents_mode_stock,
                    LayoutMode.SLIM_LIST to R.string.recents_mode_slim,
                    LayoutMode.QUILT to R.string.recents_mode_quilt,
                    LayoutMode.GRID_AUTO to R.string.recents_mode_grid_auto,
                    LayoutMode.GRID_OVERLAY to R.string.recents_mode_grid_standalone
                ) else listOf(
                    LayoutMode.STOCK to R.string.recents_mode_stock,
                    LayoutMode.GRID_AUTO to R.string.recents_mode_grid_auto,
                    LayoutMode.MASONRY to R.string.recents_mode_masonry,
                    LayoutMode.SLIM_LIST to R.string.recents_mode_slim,
                    LayoutMode.QUILT to R.string.recents_mode_quilt_standalone,
                    LayoutMode.GRID_OVERLAY to R.string.recents_mode_grid_standalone
                )
                options.forEach { (value, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setModeAsync(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = shownMode == value, onClick = { setModeAsync(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (overlayInUse) {
            Text(
                stringResource(R.string.recents_overlay_trigger_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (shownMode.isOverlay) OverlayAppearanceCard(shownMode)

        // The scrim slider is a launcher property: only the hooked layouts use it.
        if (shownMode == LayoutMode.MASONRY || (shownMode == LayoutMode.GRID_AUTO && hookInUse)) {
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

        var recentsConfig by remember {
            mutableStateOf(KeyboardShortcutsController.getShortcut(context, GestureSettings.Action.RECENTS))
        }
        var recordingRecentsKey by remember { mutableStateOf(false) }

        ActionShortcutCard(
            action = GestureSettings.Action.RECENTS,
            config = recentsConfig,
            onConfigChanged = {
                recentsConfig = it
                KeyboardShortcutsController.setShortcut(context, it)
            },
            onStartRecord = { recordingRecentsKey = true }
        )

        if (recordingRecentsKey) {
            KeyRecordDialog(
                actionName = stringResource(R.string.shortcut_action_recents),
                onDismiss = { recordingRecentsKey = false },
                onKeyRecorded = { kc, mods ->
                    val updated = recentsConfig.copy(enabled = true, keyCode = kc, modifiers = mods)
                    recentsConfig = updated
                    KeyboardShortcutsController.setShortcut(context, updated)
                    recordingRecentsKey = false
                },
                onClear = {
                    val updated = recentsConfig.copy(enabled = false, keyCode = 0, modifiers = 0)
                    recentsConfig = updated
                    KeyboardShortcutsController.setShortcut(context, updated)
                    recordingRecentsKey = false
                }
            )
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.subtitle_recents_tweaks),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Look of the standalone overlays (Slim List / Masonry): background colour (dark or Material You),
 * opacity and blur. Saved straight to the shared prefs; the overlay reads them each time it opens.
 */
@Composable
private fun OverlayAppearanceCard(mode: LayoutMode) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE) }
    var colorMode by remember { mutableStateOf(SlimRecentsController.scrimColorMode(prefs)) }
    var opacity by remember { mutableFloatStateOf(SlimRecentsController.scrimOpacityPercent(prefs).toFloat()) }
    var blur by remember { mutableFloatStateOf(SlimRecentsController.scrimBlurPercent(prefs).toFloat()) }
    var animPct by remember { mutableFloatStateOf(SlimRecentsController.animDurationPercent(prefs).toFloat()) }
    var gridCorner by remember { mutableFloatStateOf(SlimRecentsController.gridCornerDp(prefs).toFloat()) }
    var quiltCorner by remember { mutableFloatStateOf(SlimRecentsController.quiltCornerDp(prefs).toFloat()) }
    // Cross-window blur can be unavailable (battery saver, unsupported GPU path): say so instead of a dead slider.
    val blurSupported = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.recents_slim_appearance_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text(stringResource(R.string.recents_slim_scrim_color), style = MaterialTheme.typography.bodyMedium)
            val labels = stringArrayResource(R.array.recents_slim_scrim_color_modes)
            listOf(0, 1).forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        colorMode = m
                        prefs.edit().putInt(SlimRecentsController.KEY_SCRIM_COLOR_MODE, m).apply()
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = colorMode == m, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(labels.getOrElse(m) { "$m" }, style = MaterialTheme.typography.bodyMedium)
                }
            }

            AppearanceSlider(
                label = stringResource(R.string.recents_slim_scrim_opacity), value = opacity, range = 15f..100f,
                onChange = { opacity = it },
                onCommit = { prefs.edit().putInt(SlimRecentsController.KEY_SCRIM_OPACITY, opacity.toInt()).apply() }
            )
            AppearanceSlider(
                label = stringResource(R.string.recents_slim_scrim_blur), value = blur, range = 0f..100f,
                enabled = blurSupported,
                onChange = { blur = it },
                onCommit = { prefs.edit().putInt(SlimRecentsController.KEY_SCRIM_BLUR, blur.toInt()).apply() }
            )
            // Corner radius only applies to the tile layouts; the vertical list has its own fixed pills.
            if (mode == LayoutMode.GRID_OVERLAY || mode == LayoutMode.GRID_AUTO) {
                AppearanceSlider(
                    label = stringResource(R.string.recents_slim_corner_grid), value = gridCorner,
                    range = 0f..SlimRecentsController.MAX_CORNER_DP.toFloat(), unit = " dp",
                    offLabel = stringResource(R.string.recents_slim_corner_square),
                    onChange = { gridCorner = it },
                    onCommit = { prefs.edit().putInt(SlimRecentsController.KEY_GRID_CORNER_DP, gridCorner.toInt()).apply() }
                )
            }
            if (mode == LayoutMode.QUILT) {
                AppearanceSlider(
                    label = stringResource(R.string.recents_slim_corner_quilt), value = quiltCorner,
                    range = 0f..SlimRecentsController.MAX_CORNER_DP.toFloat(), unit = " dp",
                    offLabel = stringResource(R.string.recents_slim_corner_square),
                    onChange = { quiltCorner = it },
                    onCommit = { prefs.edit().putInt(SlimRecentsController.KEY_QUILT_CORNER_DP, quiltCorner.toInt()).apply() }
                )
            }
            AppearanceSlider(
                label = stringResource(R.string.recents_slim_anim_duration), value = animPct, range = 0f..200f,
                offLabel = stringResource(R.string.recents_slim_anim_off),
                onChange = { animPct = it },
                onCommit = { prefs.edit().putInt(SlimRecentsController.KEY_ANIM_DURATION, animPct.toInt()).apply() }
            )
            Text(
                stringResource(R.string.recents_slim_anim_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!blurSupported) {
                Text(
                    stringResource(R.string.recents_slim_blur_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppearanceSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true, offLabel: String? = null, unit: String = "%", onChange: (Float) -> Unit, onCommit: () -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(if (value.toInt() == 0 && offLabel != null) offLabel else "${value.toInt()}$unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onCommit, valueRange = range, enabled = enabled)
    }
}
