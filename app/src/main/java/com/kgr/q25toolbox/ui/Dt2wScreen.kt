package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.modules.Dt2wController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Dt2wScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = Dt2wController.isPersisted()
            running = Dt2wController.isRunning()
        }
    }

    ScreenScaffold(title = Screen.Dt2w.title, onBack = onBack) {
        Text(
            "Wake the device by double-tapping the screen while it's off. The Q25 " +
                "has no hardware gesture-wake, so this runs a small background " +
                "watchdog that watches the touchscreen and wakes on a double-tap.",
            style = MaterialTheme.typography.bodySmall
        )
        Text("State: ${if (enabled) "On" else "Off"}${if (enabled && !running) " (starts at next boot)" else ""}")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enabled")
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        Dt2wController.setEnabled(context, enable)
                        enabled = Dt2wController.isPersisted()
                        running = Dt2wController.isRunning()
                        busy = false
                        statusMessage = if (enable)
                            "DT2W on. Double-tap the off screen to wake."
                        else "DT2W off."
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
