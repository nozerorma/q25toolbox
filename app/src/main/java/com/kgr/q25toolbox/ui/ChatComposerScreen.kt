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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.service.Q25AccessibilityService
import com.kgr.q25toolbox.service.isQ25AccessibilityServiceEnabled

@Composable
fun ChatComposerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Q25AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(Q25AccessibilityService.KEY_CHAT_COMPOSER, false))
    }

    LaunchedEffect(Unit) {
        serviceEnabled = isQ25AccessibilityServiceEnabled(context)
    }

    ScreenScaffold(title = Screen.ChatComposer.title, onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_composer_enabled_label))
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    prefs.edit().putBoolean(Q25AccessibilityService.KEY_CHAT_COMPOSER, checked).apply()
                }
            )
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.chat_composer_desc),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
