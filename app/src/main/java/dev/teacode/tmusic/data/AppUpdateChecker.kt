package dev.teacode.tmusic.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val version: String,
    val title: String,
    val changelog: String,
    val pageUrl: String,
    val downloadUrl: String,
)

private const val DEFAULT_GITHUB_REPOSITORY = "trainymonked/tmusic_android"
private const val APP_UPDATE_LOG_TAG = "TMusicUpdate"

class AppUpdateChecker(
    private val repository: String = DEFAULT_GITHUB_REPOSITORY,
) {
    suspend fun latestUpdate(currentVersion: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        Log.d(APP_UPDATE_LOG_TAG, "check start currentVersion=$currentVersion repository=$repository")
        runCatching { latestReleaseUpdate(currentVersion) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(APP_UPDATE_LOG_TAG, "release check failed", error)
                null
            }
            ?.also { update ->
                Log.d(APP_UPDATE_LOG_TAG, "release check found update=${update.version} downloadUrl=${update.downloadUrl}")
            }
            ?: runCatching { latestTagUpdate(currentVersion) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w(APP_UPDATE_LOG_TAG, "tag check failed", error)
                    null
                }
                ?.also { update ->
                    Log.d(APP_UPDATE_LOG_TAG, "tag check found update=${update.version} downloadUrl=${update.downloadUrl}")
                }
                ?: run {
                    Log.d(APP_UPDATE_LOG_TAG, "check finished: no newer update")
                    null
                }
    }

    private fun latestReleaseUpdate(currentVersion: String): AppUpdateInfo? {
        val response = requestJsonObject("https://api.github.com/repos/$repository/releases/latest")
            ?: run {
                Log.d(APP_UPDATE_LOG_TAG, "release check: no release payload")
                return null
            }
        val version = response.optString("tag_name").ifBlank { response.optString("name") }
            .normalizedVersionTag()
        Log.d(APP_UPDATE_LOG_TAG, "release check: candidate=$version current=$currentVersion")
        if (!isAppVersionNewer(version, currentVersion)) {
            Log.d(APP_UPDATE_LOG_TAG, "release check: candidate is not newer")
            return null
        }

        val assets = response.optJSONArray("assets") ?: JSONArray()
        val apkUrl = (0 until assets.length())
            .asSequence()
            .mapNotNull { index -> assets.optJSONObject(index) }
            .firstOrNull { asset ->
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                name.endsWith(".apk", ignoreCase = true) || url.endsWith(".apk", ignoreCase = true)
            }
            ?.optString("browser_download_url")
            ?.takeIf { it.isNotBlank() }
        val pageUrl = response.optString("html_url")
            .takeIf { it.isNotBlank() }
            ?: "https://github.com/$repository/releases/tag/$version"

        return AppUpdateInfo(
            version = version,
            title = response.optString("name").takeIf { it.isNotBlank() } ?: version,
            changelog = response.optString("body").trim(),
            pageUrl = pageUrl,
            downloadUrl = apkUrl ?: pageUrl,
        )
    }

    private fun latestTagUpdate(currentVersion: String): AppUpdateInfo? {
        val tags = requestJsonArray("https://api.github.com/repos/$repository/tags") ?: run {
            Log.d(APP_UPDATE_LOG_TAG, "tag check: no tags payload")
            return null
        }
        val tag = tags.optJSONObject(0) ?: run {
            Log.d(APP_UPDATE_LOG_TAG, "tag check: empty tags response")
            return null
        }
        val version = tag.optString("name").normalizedVersionTag()
        Log.d(APP_UPDATE_LOG_TAG, "tag check: candidate=$version current=$currentVersion")
        if (!isAppVersionNewer(version, currentVersion)) {
            Log.d(APP_UPDATE_LOG_TAG, "tag check: candidate is not newer")
            return null
        }

        val commit = tag.optJSONObject("commit")
        val commitDetails = commit?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?.let(::requestJsonObject)
        val commitMessage = commitDetails
            ?.optJSONObject("commit")
            ?.optString("message")
            ?.trim()
            .orEmpty()
        val pageUrl = commitDetails?.optString("html_url")
            ?.takeIf { it.isNotBlank() }
            ?: "https://github.com/$repository/releases/tag/$version"

        return AppUpdateInfo(
            version = version,
            title = version,
            changelog = commitMessage,
            pageUrl = pageUrl,
            downloadUrl = pageUrl,
        )
    }

    private fun requestJsonObject(url: String): JSONObject? {
        val body = request(url) ?: return null
        return runCatching { JSONObject(body) }
            .onFailure { error -> Log.w(APP_UPDATE_LOG_TAG, "failed to parse JSON object from $url", error) }
            .getOrNull()
    }

    private fun requestJsonArray(url: String): JSONArray? {
        val body = request(url) ?: return null
        return runCatching { JSONArray(body) }
            .onFailure { error -> Log.w(APP_UPDATE_LOG_TAG, "failed to parse JSON array from $url", error) }
            .getOrNull()
    }

    private fun request(url: String): String? {
        Log.d(APP_UPDATE_LOG_TAG, "GET $url")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TMusic-Android")
        }
        return try {
            val responseCode = connection.responseCode
            Log.d(
                APP_UPDATE_LOG_TAG,
                "GET $url -> HTTP $responseCode remaining=${connection.getHeaderField("X-RateLimit-Remaining")} reset=${connection.getHeaderField("X-RateLimit-Reset")}",
            )
            if (responseCode !in 200..299) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(300) }
                }.getOrNull()
                if (!errorBody.isNullOrBlank()) {
                    Log.w(APP_UPDATE_LOG_TAG, "GET $url error body: $errorBody")
                }
                return null
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 6_000
    }
}

private fun String.normalizedVersionTag(): String {
    return trim()
        .removePrefix("refs/tags/")
        .removePrefix("release/")
        .removePrefix("v")
}

fun isAppVersionNewer(candidateVersion: String, currentVersion: String): Boolean {
    val nextParts = candidateVersion.normalizedVersionTag().semanticVersionParts()
    val currentParts = currentVersion.normalizedVersionTag().semanticVersionParts()
    val maxSize = maxOf(nextParts.size, currentParts.size).coerceAtLeast(1)
    for (index in 0 until maxSize) {
        val next = nextParts.getOrElse(index) { 0 }
        val current = currentParts.getOrElse(index) { 0 }
        if (next != current) {
            return next > current
        }
    }
    return false
}

private fun String.semanticVersionParts(): List<Int> {
    return split('.', '-', '_')
        .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
}
