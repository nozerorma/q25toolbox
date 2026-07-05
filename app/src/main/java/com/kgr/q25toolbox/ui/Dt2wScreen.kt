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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
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

    ScreenScaffold(title = stringResource(Screen.Dt2w.titleRes), onBack = onBack) {
        Text(
            stringResource(R.string.dt2w_desc),
            style = MaterialTheme.typography.bodySmall
        )
        val stateStr = if (enabled) stringResource(R.string.dt2w_on) else stringResource(R.string.dt2w_off)
        val bootNotice = if (enabled && !running) stringResource(R.string.dt2w_boot_notice) else ""
        Text(stringResource(R.string.dt2w_state, "$stateStr$bootNotice"))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dt2w_enabled_label))
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
                            context.getString(R.string.dt2w_status_on)
                        else context.getString(R.string.dt2w_status_off)
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
