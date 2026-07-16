package com.kgr.q25toolbox.settings

import android.content.Context
import android.util.Log
import com.kgr.q25toolbox.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Q25TB-Updater"

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(val currentVersion: String) : UpdateState()
    data class UpdateAvailable(val release: GitHubRelease) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    object Installing : UpdateState()
    data class Installed(val version: String) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

object UpdateChecker {

    /**
     * Compares a version string like "1.0-beta14" against another.
     * Strategy:
     *  1. Split off everything from the first non-numeric/non-dot character
     *     (e.g. "-beta2", "-rc1") into a separate "pre-release" suffix.
     *  2. Compare the numeric dotted core (e.g. "1.0" vs "1.1") first —
     *     this is the part that actually matters for ordering.
     *  3. Only if the numeric core is EQUAL do we look at the suffix,
     *     and even then just as a simple string compare (beta2 > beta1),
     *     since there's no universal pre-release ordering standard.
     *
     * This avoids the old bug where "1.0-beta14" vs "1.0-beta9" failed
     * numeric parsing (because "0-beta14" isn't an Int) and fell back to
     * treating any two different tags as "newer", regardless of actual order.
     */
    fun isNewer(latestTag: String, currentVersion: String): Boolean {
        val (latestCore, latestSuffix) = splitVersion(latestTag)
        val (currentCore, currentSuffix) = splitVersion(currentVersion)

        val latestParts = latestCore.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentCore.split(".").mapNotNull { it.toIntOrNull() }

        if (latestParts.isEmpty() || currentParts.isEmpty()) {
            // Couldn't parse either core numerically at all — don't guess,
            // treat as "not newer" rather than risk flagging a downgrade.
            Log.w(TAG, "Could not parse version cores: '$latestCore' vs '$currentCore'")
            return false
        }

        val size = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until size) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }

        // Numeric cores are equal (e.g. both "1.0") — fall back to suffix compare.
        // Only meaningful if both have a suffix; otherwise no-suffix > any suffix
        // (a release build outranks a beta of the same numeric version).
        return when {
            latestSuffix.isEmpty() && currentSuffix.isNotEmpty() -> true
            latestSuffix.isNotEmpty() && currentSuffix.isEmpty() -> false
            else -> latestSuffix > currentSuffix
        }
    }

    /** Splits "1.0-beta14" into ("1.0", "beta14"); "1.0" into ("1.0", ""). */
    private fun splitVersion(tag: String): Pair<String, String> {
        val cleaned = tag.removePrefix("v").trim()
        val dashIndex = cleaned.indexOfFirst { it != '.' && !it.isDigit() }
        return if (dashIndex == -1) {
            cleaned to ""
        } else {
            val core = cleaned.substring(0, dashIndex).trimEnd('-')
            val suffix = cleaned.substring(dashIndex).trimStart('-')
            core to suffix
        }
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateState = withContext(Dispatchers.IO) {
        when (val result = GitHubClient.fetchLatestRelease()) {
            is GitHubResult.Success -> {
                val release = result.data
                if (release.apkAssetUrl == null) {
                    UpdateState.Failed("Latest release has no .apk asset attached")
                } else if (isNewer(release.tagName, currentVersion)) {
                    UpdateState.UpdateAvailable(release)
                } else {
                    UpdateState.UpToDate(currentVersion)
                }
            }
            is GitHubResult.Error -> UpdateState.Failed(result.message)
        }
    }

    suspend fun downloadApk(
        context: Context,
        release: GitHubRelease,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val url = release.apkAssetUrl ?: error("No APK asset URL")
        val fileName = release.apkAssetName ?: "q25toolbox-${release.tagName}.apk"
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val outFile = File(outDir, fileName)

        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connect()

            val total = connection.contentLength
            var downloaded = 0

            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        downloaded += bytes
                        if (total > 0) {
                            onProgress((downloaded * 100L / total).toInt())
                        } else {
                            onProgress(-1)
                        }
                        bytes = input.read(buffer)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        Log.d(TAG, "Downloaded ${outFile.name} (${outFile.length()} bytes) to ${outFile.absolutePath}")
        outFile
    }

    suspend fun installApkAsRoot(apkFile: File): Boolean = withContext(Dispatchers.IO) {
        val path = apkFile.absolutePath.replace("\"", "\\\"")
        val result = RootShell.run("pm install -r \"$path\"")
        Log.d(TAG, "pm install output: ${result.outString}")
        result.success
    }
}
