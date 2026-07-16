package com.kgr.q25toolbox.settings

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val apkAssetUrl: String?,
    val apkAssetName: String?
)

data class GitHubContributor(
    val login: String,
    val avatarUrl: String,
    val htmlUrl: String,
    val contributions: Int
)

sealed class GitHubResult<out T> {
    data class Success<T>(val data: T) : GitHubResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : GitHubResult<Nothing>()
}

object GitHubClient {

    private const val OWNER = "nozerorma"
    private const val REPO = "q25toolbox"
    private const val USER_AGENT = "Q25Toolbox-App"

    fun fetchLatestRelease(): GitHubResult<GitHubRelease> {
        return try {
            val (code, json) = get("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            if (json == null) {
                return GitHubResult.Error("GitHub returned HTTP $code for /releases/latest")
            }

            val obj = JSONObject(json)
            val assets = obj.optJSONArray("assets") ?: JSONArray()

            var apkUrl: String? = null
            var apkName: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    apkName = name
                    break
                }
            }

            GitHubResult.Success(
                GitHubRelease(
                    tagName = obj.optString("tag_name"),
                    name = obj.optString("name"),
                    body = obj.optString("body"),
                    htmlUrl = obj.optString("html_url"),
                    apkAssetUrl = apkUrl,
                    apkAssetName = apkName
                )
            )
        } catch (e: Exception) {
            GitHubResult.Error("Failed to check for updates: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    fun fetchContributors(): GitHubResult<List<GitHubContributor>> {
        return try {
            val (code, json) = get("https://api.github.com/repos/$OWNER/$REPO/contributors")
            if (json == null) {
                return GitHubResult.Error("GitHub returned HTTP $code for /contributors")
            }

            val arr = JSONArray(json)
            val list = mutableListOf<GitHubContributor>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("type") == "Bot") continue
                list.add(
                    GitHubContributor(
                        login = obj.optString("login"),
                        avatarUrl = obj.optString("avatar_url"),
                        htmlUrl = obj.optString("html_url"),
                        contributions = obj.optInt("contributions")
                    )
                )
            }
            GitHubResult.Success(list.sortedByDescending { it.contributions })
        } catch (e: Exception) {
            GitHubResult.Error("Failed to load contributors: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /** Returns (HTTP status code, body) — body is null if status wasn't 2xx. */
    private fun get(urlString: String): Pair<Int, String?> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val code = connection.responseCode
            if (code !in 200..299) return code to null
            code to connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
