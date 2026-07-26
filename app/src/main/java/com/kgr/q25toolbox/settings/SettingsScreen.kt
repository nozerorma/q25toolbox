package com.kgr.q25toolbox.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kgr.q25toolbox.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(currentVersionName: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var contributors by remember { mutableStateOf<List<GitHubContributor>?>(null) }
    var contributorsError by remember { mutableStateOf<String?>(null) }

    // Load contributors once when the screen is first shown
    LaunchedEffect(Unit) {
        when (val result = withContext(Dispatchers.IO) { GitHubClient.fetchContributors() }) {
            is GitHubResult.Success -> contributors = result.data
            is GitHubResult.Error -> contributorsError = result.message
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        AppLogoHeader()

        SectionHeader(stringResource(R.string.settings_section_updates))
        UpdateCard(
            currentVersion = currentVersionName,
            state = updateState,
            onCheckNow = {
                scope.launch {
                    updateState = UpdateState.Checking
                    updateState = UpdateChecker.checkForUpdate(currentVersionName)
                }
            },
            onInstall = { release ->
                scope.launch {
                    try {
                        var lastProgress = -2
                        val apk = UpdateChecker.downloadApk(context, release) { progress ->
                            if (progress != lastProgress) {
                                lastProgress = progress
                                updateState = UpdateState.Downloading(progress.coerceAtLeast(0))
                            }
                        }
                        updateState = UpdateState.Installing
                        val success = UpdateChecker.installApkAsRoot(apk)
                        updateState = if (success) {
                            UpdateState.Installed(release.tagName)
                        } else {
                            UpdateState.Failed(context.getString(R.string.settings_install_failed_hint))
                        }
                    } catch (e: Exception) {
                        updateState = UpdateState.Failed(e.message ?: "Unknown error during update")
                    }
                }
            }
        )

        Spacer(Modifier.height(24.dp))

        SectionHeader(stringResource(R.string.settings_section_quick_access))
        SettingsRow(
            title = stringResource(R.string.settings_quick_access_a11y_title),
            subtitle = stringResource(R.string.settings_quick_access_a11y_subtitle),
            icon = Icons.Default.Accessibility
        ) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        SettingsRow(
            title = stringResource(R.string.settings_quick_access_ime_title),
            subtitle = stringResource(R.string.settings_quick_access_ime_subtitle),
            icon = Icons.Default.Keyboard
        ) {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        Spacer(Modifier.height(24.dp))

        SectionHeader(stringResource(R.string.settings_section_contributors))
        ContributorsSection(
            contributors = contributors,
            error = contributorsError,
            onContributorClick = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(R.string.settings_section_about))
        Text(
            stringResource(R.string.settings_about_version, currentVersionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.settings_about_repo),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nozerorma/q25toolbox"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
        )
    }
}

/** App identity header: the same glyph as the launcher icon, tinted to match the theme
 * instead of the icon's fixed colors, plus the app name - so Settings reads as "about
 * this app" rather than just another module screen. */
@Composable
private fun AppLogoHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp)
            )
        }
        Column {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpdateCard(
    currentVersion: String,
    state: UpdateState,
    onCheckNow: () -> Unit,
    onInstall: (GitHubRelease) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.settings_current_version), style = MaterialTheme.typography.bodySmall)
                    Text(currentVersion, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                if (state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                    Button(onClick = onCheckNow) {
                        Text(stringResource(R.string.settings_check_now))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (state) {
                is UpdateState.Idle -> Unit

                is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_checking), style = MaterialTheme.typography.bodySmall)
                }

                is UpdateState.UpToDate -> Text(
                    stringResource(R.string.settings_up_to_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is UpdateState.UpdateAvailable -> Column {
                    Text(
                        stringResource(R.string.settings_update_available, state.release.tagName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (state.release.body.isNotBlank()) {
                        Text(
                            state.release.body.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onInstall(state.release) }) {
                        Text(stringResource(R.string.settings_download_install))
                    }
                }

                is UpdateState.Downloading -> Column {
                    Text(stringResource(R.string.settings_downloading), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    if (state.progress >= 0) {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is UpdateState.Installing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_installing), style = MaterialTheme.typography.bodySmall)
                }

                is UpdateState.Installed -> Text(
                    stringResource(R.string.settings_installed, state.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                is UpdateState.Failed -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ContributorsSection(
    contributors: List<GitHubContributor>?,
    error: String?,
    onContributorClick: (String) -> Unit
) {
    when {
        error != null -> Text(
            stringResource(R.string.settings_contributors_error, error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        contributors == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_contributors_loading), style = MaterialTheme.typography.bodySmall)
        }

        contributors.isEmpty() -> Text(
            stringResource(R.string.settings_contributors_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(contributors) { contributor ->
                ContributorChip(contributor) { onContributorClick(contributor.htmlUrl) }
            }
        }
    }
}

@Composable
private fun ContributorChip(contributor: GitHubContributor, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = contributor.avatarUrl,
            contentDescription = contributor.login,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            contributor.login,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Text(
            stringResource(R.string.settings_contributions, contributor.contributions),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
