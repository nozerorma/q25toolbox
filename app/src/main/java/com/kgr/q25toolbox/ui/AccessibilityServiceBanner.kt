package com.kgr.q25toolbox.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Shown on Nav Lock / PIN Keyboard / Audio FX screens when
 * Q25AccessibilityService isn't enabled - all three depend on it.
 */
@Composable
fun AccessibilityServiceBanner(serviceEnabled: Boolean) {
    if (serviceEnabled) {
        Text(
            "Accessibility service: enabled",
            color = androidx.compose.ui.graphics.Color(0xFF81C784)
        )
        return
    }

    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "This feature needs the accessibility service enabled.",
                color = androidx.compose.ui.graphics.Color(0xFFE57373),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) {
                Text("Open Accessibility Settings")
            }
        }
    }
}
