package com.kgr.q25toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.modules.WirelessAdbController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WirelessAdbScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var portText by remember { mutableStateOf(WirelessAdbController.DEFAULT_PORT.toString()) }
    var persisted by remember { mutableStateOf(false) }
    var livePort by remember { mutableStateOf<Int?>(null) }
    var wlanIp by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            persisted = WirelessAdbController.isPersisted()
            WirelessAdbController.persistedPort()?.let { portText = it.toString() }
            livePort = WirelessAdbController.currentLivePort()
            wlanIp = WirelessAdbController.currentWlanIp()
        }
    }

    ScreenScaffold(title = stringResource(Screen.WirelessAdb.titleRes), onBack = onBack) {
        Text(stringResource(R.string.wadb_wlan_ip, wlanIp ?: stringResource(R.string.wadb_not_connected)))
        Text(stringResource(R.string.wadb_live_port, livePort?.toString() ?: stringResource(R.string.wadb_not_set)))
        Text(stringResource(R.string.wadb_persisted, if (persisted) stringResource(R.string.wadb_yes) else stringResource(R.string.wadb_no)))

        OutlinedTextField(
            value = portText,
            onValueChange = { value ->
                if (value.length <= 5 && value.all { it.isDigit() }) portText = value
            },
            label = { Text(stringResource(R.string.wadb_port_label)) },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && portText.toIntOrNull() != null,
                onClick = {
                    val port = portText.toIntOrNull() ?: return@Button
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        WirelessAdbController.setPort(context, port, applyLive = true)
                        persisted = WirelessAdbController.isPersisted()
                        livePort = WirelessAdbController.currentLivePort()
                        wlanIp = WirelessAdbController.currentWlanIp()
                        busy = false
                        val persistedStr = if (persisted) {
                            context.getString(R.string.wadb_persisted_yes)
                        } else {
                            context.getString(R.string.wadb_persisted_no)
                        }
                        statusMessage = context.getString(R.string.wadb_status_enabled, port, persistedStr)
                    }
                }
            ) { Text(stringResource(R.string.wadb_btn_apply)) }

            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        WirelessAdbController.disable()
                        persisted = WirelessAdbController.isPersisted()
                        livePort = WirelessAdbController.currentLivePort()
                        wlanIp = WirelessAdbController.currentWlanIp()
                        busy = false
                        statusMessage = context.getString(R.string.wadb_status_disabled)
                    }
                }
            ) { Text(stringResource(R.string.wadb_btn_disable)) }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
