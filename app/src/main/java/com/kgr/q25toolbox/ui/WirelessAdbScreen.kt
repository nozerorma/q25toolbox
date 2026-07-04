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
import androidx.compose.ui.unit.dp
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

    ScreenScaffold(title = Screen.WirelessAdb.title, onBack = onBack) {
        Text("WLAN IP: ${wlanIp ?: "not connected"}")
        Text("Current live port: ${livePort ?: "not set"}")
        Text("Persisted: ${if (persisted) "Yes" else "No"}")

        OutlinedTextField(
            value = portText,
            onValueChange = { value ->
                if (value.length <= 5 && value.all { it.isDigit() }) portText = value
            },
            label = { Text("Port") },
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
                        statusMessage =
                            "Wireless ADB enabled on port $port. " +
                                "Persisted: ${if (persisted) "yes" else "NO - check service.d write permissions"}."
                    }
                }
            ) { Text("Enable / Apply") }

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
                        statusMessage = "Wireless ADB disabled."
                    }
                }
            ) { Text("Disable") }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
