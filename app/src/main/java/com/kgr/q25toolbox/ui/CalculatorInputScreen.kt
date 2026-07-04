package com.kgr.q25toolbox.ui

import android.content.Context
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.service.Q25AccessibilityService
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled

@Composable
fun CalculatorInputScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(Q25AccessibilityService.KEY_CALCULATOR, false))
    }

    LaunchedEffect(Unit) {
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
    }

    ScreenScaffold(title = Screen.CalculatorInput.title, onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Text(
            "In the AOSP/Google Calculator, route physical keys to the calculator " +
                "buttons: digits (or W E R / S D F / Z X C / Q for 1-9, 0), plus " +
                "operators - O = +, I = -, A = ×, G = ÷, M = ., U = %, B = !, " +
                "T/Y = ( ), Sym/Alt toggles scientific mode, Backspace deletes. " +
                "No root needed - uses the accessibility service only.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enabled")
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    prefs.edit().putBoolean(Q25AccessibilityService.KEY_CALCULATOR, checked).apply()
                }
            )
        }
    }
}
