package com.kgr.q25toolbox.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgr.q25toolbox.R

private const val PREFS = "q25toolbox"
private const val KEY_SEEN_VC = "whats_new_seen_vc"

/**
 * One-time "what's new" dialog. v3.0 is the first build with a feature that
 * needs an Xposed framework, and neither Obtainium nor the app can push a
 * notification on update, so surface it on first launch of the new build.
 * Shows once per versionCode (fresh installs see it too - the LSPosed
 * requirement is exactly what a new user needs to know).
 */
@Composable
fun WhatsNewDialog() {
    val context = LocalContext.current
    val currentVc = remember { appVersionCode(context) }
    var show by remember {
        mutableStateOf(
            currentVc > 0 &&
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_SEEN_VC, 0) != currentVc
        )
    }
    if (!show) return

    fun dismiss() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SEEN_VC, currentVc).apply()
        show = false
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        confirmButton = {
            TextButton(onClick = ::dismiss) { Text(stringResource(R.string.whats_new_ok)) }
        },
        title = { Text(stringResource(R.string.whats_new_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.whats_new_headline),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    stringResource(R.string.whats_new_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.whats_new_matrix_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.whats_new_matrix),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
}

private fun appVersionCode(context: Context): Int = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
} catch (_: Exception) {
    0
}
