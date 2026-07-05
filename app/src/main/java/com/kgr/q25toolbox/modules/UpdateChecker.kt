package com.kgr.q25toolbox.modules

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Result of a successful update check: a release published under a different tag than what's installed. */
data class UpdateInfo(val latestVersion: String, val htmlUrl: String)

/**
 * Checks GitHub Releases for a newer build than the one installed. This is a
 * single-maintainer repo where releases are only ever published forward, so
 * "the latest tag differs from what's installed" is treated as "update
 * available" rather than doing full semver comparison.
 */
object UpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/nozerorma/q25toolbox/releases/latest"

    /** Blocking network call - run off the main thread. Returns null on any error or if already up to date. */
    fun checkForUpdate(currentVersionName: String): UpdateInfo? {
        return try {
            val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name").removePrefix("v")
                val htmlUrl = json.optString("html_url")
                if (tagName.isEmpty() || tagName == currentVersionName) null
                else UpdateInfo(tagName, htmlUrl)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w("UpdateChecker", "Update check failed", e)
            null
        }
    }
}
