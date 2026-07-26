package com.kgr.q25toolbox.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kgr.q25toolbox.MainActivity
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.TickerColorResolver
import com.kgr.q25toolbox.modules.TickerController
import com.kgr.q25toolbox.modules.TickerSettings
import com.kgr.q25toolbox.service.TickerOverlayController
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Super Status Bar"-style ticker: a scrolling banner across the top of the screen
 * instead of a heads-up popup, with the same customization knobs that app offered
 * (per-app/category blacklist, minimum priority, lines shown, scroll speed/delay,
 * tap-to-open). See [TickerController] for how the master switch grants permissions
 * and kills heads-up, and [com.kgr.q25toolbox.service.TickerOverlayController] for
 * how the banner itself is drawn.
 */
@Composable
fun TickerNotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        TickerBlockedAppsScreen(onBack = { showAppPicker = false })
        return
    }

    var enabled by remember { mutableStateOf(TickerSettings.isEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var notifAccessGranted by remember { mutableStateOf(TickerController.isNotificationAccessGranted(context)) }
    var serviceEnabled by remember { mutableStateOf(isQ25AccessibilityServiceEnabled(context)) }

    var tapToOpen by remember { mutableStateOf(TickerSettings.isTapToOpen(context)) }
    var minImportance by remember { mutableIntStateOf(TickerSettings.minImportance(context)) }
    var includeOngoing by remember { mutableStateOf(TickerSettings.includeOngoing(context)) }
    var maxBodyLines by remember { mutableIntStateOf(TickerSettings.maxBodyLines(context)) }
    var scrollSpeed by remember { mutableFloatStateOf(TickerSettings.scrollSpeedDpPerSec(context).toFloat()) }
    var startDelay by remember { mutableFloatStateOf(TickerSettings.startDelayMs(context).toFloat()) }
    var blockedCategories by remember { mutableStateOf(TickerSettings.blockedCategories(context)) }
    var blockedAppCount by remember { mutableStateOf(TickerSettings.blockedApps(context).size) }
    var colorMode by remember { mutableStateOf(TickerSettings.colorMode(context)) }
    var fixedColor by remember { mutableIntStateOf(TickerSettings.fixedColor(context)) }

    LaunchedEffect(Unit) {
        notifAccessGranted = TickerController.isNotificationAccessGranted(context)
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
        blockedAppCount = TickerSettings.blockedApps(context).size
    }

    ScreenScaffold(title = Screen.TickerNotifications.title, onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enable ticker", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { checked ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        TickerController.setEnabled(context, checked)
                        enabled = TickerSettings.isEnabled(context)
                        // On this device's ROM, enabled_notification_listeners has been observed
                        // to take a moment to propagate to other processes after the root
                        // `cmd notification allow_listener` call returns - retry briefly rather
                        // than flash a false "missing" permission banner on every enable.
                        var notifGranted = TickerController.isNotificationAccessGranted(context)
                        var attempts = 0
                        while (checked && !notifGranted && attempts < 5) {
                            delay(300)
                            notifGranted = TickerController.isNotificationAccessGranted(context)
                            attempts++
                        }
                        val serviceNowEnabled = isQ25AccessibilityServiceEnabled(context)
                        withContext(Dispatchers.Main) {
                            notifAccessGranted = notifGranted
                            serviceEnabled = serviceNowEnabled
                        }
                        busy = false
                    }
                }
            )
        }

        if (enabled) {
            TickerPermissionStatus(notifAccessGranted)
        }
        AccessibilityServiceBanner(serviceEnabled)

        Text("Heads-up popups are disabled while this is on.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                enabled = serviceEnabled,
                onClick = {
                    val openAppIntent = PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    TickerOverlayController.show(
                        context = context,
                        icon = ContextCompat.getDrawable(context, R.drawable.ic_app_logo),
                        text = "Ticker Notifications test - this is how a real notification will scroll by",
                        contentIntent = if (tapToOpen) openAppIntent else null,
                        backgroundColor = TickerColorResolver.resolveBackgroundColor(context, context.packageName),
                    )
                }
            ) {
                Text("Test ticker")
            }
            if (!serviceEnabled) {
                Text(
                    "Needs the accessibility service enabled first (see below).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tap ticker to open app", modifier = Modifier.weight(1f))
            Switch(
                checked = tapToOpen,
                onCheckedChange = {
                    tapToOpen = it
                    TickerSettings.setTapToOpen(context, it)
                }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Show ongoing notifications (media, downloads)", modifier = Modifier.weight(1f))
            Switch(
                checked = includeOngoing,
                onCheckedChange = {
                    includeOngoing = it
                    TickerSettings.setIncludeOngoing(context, it)
                }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Minimum priority", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TickerSettings.IMPORTANCE_OPTIONS.forEach { (value, label) ->
                    FilterChip(
                        selected = minImportance == value,
                        onClick = {
                            minImportance = value
                            TickerSettings.setMinImportance(context, value)
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Lines of text shown", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TickerSettings.BODY_LINES_OPTIONS.forEach { opt ->
                    FilterChip(
                        selected = maxBodyLines == opt,
                        onClick = {
                            maxBodyLines = opt
                            TickerSettings.setMaxBodyLines(context, opt)
                        },
                        label = { Text("$opt") }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Scroll speed: ${scrollSpeed.toInt()} dp/s", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = scrollSpeed,
                valueRange = TickerSettings.SCROLL_SPEED_RANGE,
                onValueChange = { scrollSpeed = it },
                onValueChangeFinished = { TickerSettings.setScrollSpeedDpPerSec(context, scrollSpeed.toInt()) }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Start delay: ${startDelay.toInt()} ms", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = startDelay,
                valueRange = TickerSettings.START_DELAY_RANGE,
                onValueChange = { startDelay = it },
                onValueChangeFinished = { TickerSettings.setStartDelayMs(context, startDelay.toInt()) }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ticker color", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = colorMode == TickerSettings.ColorMode.FIXED,
                    onClick = {
                        colorMode = TickerSettings.ColorMode.FIXED
                        TickerSettings.setColorMode(context, TickerSettings.ColorMode.FIXED)
                    },
                    label = { Text("Fixed") }
                )
                FilterChip(
                    selected = colorMode == TickerSettings.ColorMode.APP_ICON,
                    onClick = {
                        colorMode = TickerSettings.ColorMode.APP_ICON
                        TickerSettings.setColorMode(context, TickerSettings.ColorMode.APP_ICON)
                    },
                    label = { Text("App icon") }
                )
                FilterChip(
                    selected = colorMode == TickerSettings.ColorMode.MONET,
                    enabled = Build.VERSION.SDK_INT >= 31,
                    onClick = {
                        colorMode = TickerSettings.ColorMode.MONET
                        TickerSettings.setColorMode(context, TickerSettings.ColorMode.MONET)
                    },
                    label = { Text("Monet") }
                )
            }
            if (colorMode == TickerSettings.ColorMode.APP_ICON) {
                Text(
                    "Uses each notifying app's own icon color, muted to a dark, readable tone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (colorMode == TickerSettings.ColorMode.MONET && Build.VERSION.SDK_INT < 31) {
                Text(
                    "Monet needs Android 12+; falls back to the fixed color on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (colorMode == TickerSettings.ColorMode.FIXED) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TickerSettings.PRESET_COLORS) { swatch ->
                        val selected = swatch == fixedColor
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                )
                                .clickable {
                                    fixedColor = swatch
                                    TickerSettings.setFixedColor(context, swatch)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = false, onValueChange = { showAppPicker = true })
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Blocked apps")
                Text("$blockedAppCount blocked", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text("Blocked categories", style = MaterialTheme.typography.titleSmall)
            TickerSettings.CATEGORY_OPTIONS.forEach { (category, label) ->
                val checked = category in blockedCategories
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = checked,
                            onValueChange = { on ->
                                val newSet = if (on) blockedCategories + category else blockedCategories - category
                                blockedCategories = newSet
                                TickerSettings.setBlockedCategories(context, newSet)
                            }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(label)
                }
            }
        }

        DescriptionDivider()
        Text(
            "Shows a scrolling banner across the top of the screen for new notifications " +
                "instead of a heads-up popup, and turns heads-up off system-wide while enabled. " +
                "Needs root to grant notification access, and the accessibility service enabled " +
                "to draw the banner.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TickerPermissionStatus(notifAccessGranted: Boolean) {
    if (notifAccessGranted) {
        Text("Notification access granted", color = Color(0xFF81C784), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            "Notification access not granted - the root grant may have failed; " +
                "toggle off and on again, or check root access on the Info tab.",
            color = Color(0xFFE57373),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
