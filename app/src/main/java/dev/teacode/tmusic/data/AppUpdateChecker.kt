package dev.teacode.tmusic.data

import android.util.Log
import dev.teacode.tmusic.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppUpdateInfo(
    val version: String,
    val title: String,
    val changelog: String,
    val pageUrl: String,
    val downloadUrl: String,
    val releaseNotesUrl: String = "",
    val minSupportedVersionCode: Int? = null,
    val latestVersionCode: Int? = null,
    val forceUpdate: Boolean = false,
    val blockingScopes: List<String> = emptyList(),
)

private const val APP_UPDATE_LOG_TAG = "TMusicUpdate"

class AppUpdateChecker(
    private val loadConfig: suspend () -> AppUpdateInfo?,
    private val githubRepository: String,
) {
    suspend fun latestUpdate(currentVersion: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        Log.d(
            APP_UPDATE_LOG_TAG,
            "check start currentVersion=$currentVersion currentCode=${BuildConfig.VERSION_CODE}",
        )
        val serverPolicy = runCatching { loadConfig() }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(APP_UPDATE_LOG_TAG, "config check failed", error)
                null
            }
            ?.also { update ->
                Log.d(
                    APP_UPDATE_LOG_TAG,
                    "config check candidate version=${update.version} latestCode=${update.latestVersionCode} " +
                        "minCode=${update.minSupportedVersionCode} force=${update.forceUpdate}",
                )
            }
        val githubUpdate = runCatching {
            fetchLatestGithubReleaseUpdate(githubRepository)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(APP_UPDATE_LOG_TAG, "github release check failed repository=$githubRepository", error)
            null
        }
        val candidate = githubUpdate?.withServerPolicy(serverPolicy)
            ?: serverPolicy?.withGithubReleaseNotesIfNeeded()
        candidate
            ?.takeIf { update -> update.isAvailableForCurrentApp(currentVersion) }
            ?.enforcedForCurrentApp()
            ?: run {
                Log.d(APP_UPDATE_LOG_TAG, "check finished: no newer update")
                null
            }
    }
}

fun AppUpdateInfo.isRequiredForCurrentApp(): Boolean {
    return forceUpdate || minSupportedVersionCode?.let { BuildConfig.VERSION_CODE < it } == true
}

fun AppUpdateInfo.isAvailableForCurrentApp(currentVersion: String): Boolean {
    return isRequiredForCurrentApp() ||
        if (latestVersionCode != null) {
            latestVersionCode > BuildConfig.VERSION_CODE
        } else {
            isAppVersionNewer(version, currentVersion)
        }
}

fun AppUpdateInfo.enforcedForCurrentApp(): AppUpdateInfo {
    return if (isRequiredForCurrentApp() && !forceUpdate) {
        copy(forceUpdate = true)
    } else {
        this
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

private fun AppUpdateInfo.withGithubReleaseNotesIfNeeded(): AppUpdateInfo {
    if (changelog.isNotBlank()) {
        return this
    }
    val apiUrl = githubReleaseApiUrl() ?: return this
    val notes = runCatching {
        fetchGithubReleaseNotes(apiUrl)
    }.onFailure { error ->
        Log.w(APP_UPDATE_LOG_TAG, "release notes fetch failed url=$apiUrl", error)
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: return this
    return copy(changelog = notes)
}

private fun AppUpdateInfo.withServerPolicy(policy: AppUpdateInfo?): AppUpdateInfo {
    if (policy == null) {
        return this
    }
    return copy(
        minSupportedVersionCode = policy.minSupportedVersionCode,
        forceUpdate = policy.forceUpdate || policy.isRequiredForCurrentApp(),
        blockingScopes = policy.blockingScopes,
    )
}

private fun fetchLatestGithubReleaseUpdate(repository: String): AppUpdateInfo? {
    val normalizedRepository = repository.trim().removePrefix("https://github.com/").trim('/')
    if (normalizedRepository.isBlank() || "/" !in normalizedRepository) {
        Log.w(APP_UPDATE_LOG_TAG, "github release check skipped: invalid repository=$repository")
        return null
    }
    val apiUrl = "https://api.github.com/repos/$normalizedRepository/releases/latest"
    val release = fetchGithubRelease(apiUrl) ?: return null
    return release.toAppUpdateInfo(apiUrl)
}

private fun AppUpdateInfo.githubReleaseApiUrl(): String? {
    val candidates = listOf(releaseNotesUrl, pageUrl, downloadUrl)
    for (candidate in candidates) {
        val url = candidate.takeIf { it.isNotBlank() } ?: continue
        if (url.startsWith("https://api.github.com/repos/") && "/releases/" in url) {
            return url
        }
        githubReleaseRef(url)?.let { ref ->
            return "https://api.github.com/repos/${ref.owner}/${ref.repo}/releases/tags/${ref.encodedTag()}"
        }
    }
    return null
}

private data class GithubReleaseRef(
    val owner: String,
    val repo: String,
    val tag: String,
) {
    fun encodedTag(): String {
        return URLEncoder.encode(tag, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

private fun githubReleaseRef(url: String): GithubReleaseRef? {
    val releasePage = Regex("""^https://github\.com/([^/]+)/([^/]+)/releases/tag/([^?#]+)""")
    val releaseAsset = Regex("""^https://github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/.*""")
    val match = releasePage.find(url) ?: releaseAsset.find(url) ?: return null
    return GithubReleaseRef(
        owner = match.groupValues[1],
        repo = match.groupValues[2].removeSuffix(".git"),
        tag = match.groupValues[3],
    )
}

private fun fetchGithubReleaseNotes(apiUrl: String): String? {
    return fetchGithubRelease(apiUrl)?.body
}

private data class GithubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
) {
    fun toAppUpdateInfo(apiUrl: String): AppUpdateInfo {
        val releaseVersion = tagName.takeIf { it.isNotBlank() } ?: name
        val page = htmlUrl.takeIf { it.isNotBlank() } ?: apiUrl
        return AppUpdateInfo(
            version = releaseVersion,
            title = name.takeIf { it.isNotBlank() } ?: releaseVersion,
            changelog = body,
            pageUrl = page,
            downloadUrl = apkDownloadUrl.takeIf { it.isNotBlank() } ?: page,
            releaseNotesUrl = apiUrl,
        )
    }
}

private fun fetchGithubRelease(apiUrl: String): GithubRelease? {
    val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "TMusic-Android")
    }
    return try {
        if (connection.responseCode !in 200..299) {
            Log.w(APP_UPDATE_LOG_TAG, "release notes fetch http=${connection.responseCode} url=$apiUrl")
            return null
        }
        val payload = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(payload)
        val assets = root.optJSONArray("assets")
        var apkDownloadUrl = ""
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkDownloadUrl = url
                    break
                }
            }
        }
        GithubRelease(
            tagName = root.optString("tag_name"),
            name = root.optString("name"),
            body = root.optString("body"),
            htmlUrl = root.optString("html_url"),
            apkDownloadUrl = apkDownloadUrl,
        )
    } finally {
        connection.disconnect()
    }
}
