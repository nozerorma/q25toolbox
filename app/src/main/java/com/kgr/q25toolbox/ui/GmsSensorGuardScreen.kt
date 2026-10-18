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
import com.kgr.q25toolbox.modules.GmsSensorGuardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GmsSensorGuardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var gmsUid by remember { mutableStateOf<String?>(null) }
    var wifiScan by remember { mutableStateOf(false) }
    var bleScan by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshState() {
        gmsUid = GmsSensorGuardController.gmsUid()
        blocked = GmsSensorGuardController.isBlocked(gmsUid)
        wifiScan = GmsSensorGuardController.isWifiScanAlways()
        bleScan = GmsSensorGuardController.isBleScanAlways()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            refreshState()
            // Self-heal the boot re-assert script: same rationale as
            // LocationIdleScreen - a stale script from an older build keeps
            // re-asserting with whatever logic that version shipped.
            if (blocked && !GmsSensorGuardController.isBootGuardCurrent(context)) {
                GmsSensorGuardController.ensureBootGuard(context)
            }
            loaded = true
        }
    }

    fun applyBlock(newBlocked: Boolean) {
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = GmsSensorGuardController.setBlocked(context, newBlocked)
            refreshState()
            busy = false
            statusMessage = if (result.success)
                context.getString(
                    if (newBlocked) R.string.gms_guard_status_applied
                    else R.string.gms_guard_status_removed
                )
            else
                context.getString(R.string.gms_guard_status_failed)
        }
    }

    fun applyScan(setter: (Boolean) -> Unit, newValue: Boolean, onDone: () -> Unit) {
        busy = true
        scope.launch(Dispatchers.IO) {
            setter(newValue)
            refreshState()
            busy = false
            onDone()
        }
    }

    ScreenScaffold(title = Screen.GmsSensorGuard.title, onBack = onBack) {
        if (!loaded) {
            Text(stringResource(R.string.gms_guard_state_unknown))
        } else {
            val state = stringResource(
                if (blocked) R.string.gms_guard_state_blocked
                else R.string.gms_guard_state_allowed
            )
            val withUid = gmsUid?.let { stringResource(R.string.gms_guard_uid_suffix, it) } ?: ""
            Text(stringResource(R.string.gms_guard_state, state + withUid))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.gms_guard_block_label))
                Switch(
                    checked = blocked,
                    enabled = !busy,
                    onCheckedChange = { applyBlock(it) }
                )
            }

            Text(stringResource(R.string.gms_guard_scans_title), style = MaterialTheme.typography.titleSmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.gms_guard_wifi_scan_label))
                Switch(
                    checked = wifiScan,
                    enabled = !busy,
                    onCheckedChange = { newValue ->
                        applyScan(
                            GmsSensorGuardController::setWifiScanAlways,
                            newValue
                        ) { statusMessage = context.getString(if (newValue) R.string.gms_guard_scan_status_on else R.string.gms_guard_scan_status_off) }
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.gms_guard_ble_scan_label))
                Switch(
                    checked = bleScan,
                    enabled = !busy,
                    onCheckedChange = { newValue ->
                        applyScan(
                            GmsSensorGuardController::setBleScanAlways,
                            newValue
                        ) { statusMessage = context.getString(if (newValue) R.string.gms_guard_scan_status_on else R.string.gms_guard_scan_status_off) }
                    }
                )
            }

            statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            DescriptionDivider()
            Text(
                stringResource(R.string.gms_guard_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(R.string.gms_guard_manual_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.gms_guard_manual_text),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
