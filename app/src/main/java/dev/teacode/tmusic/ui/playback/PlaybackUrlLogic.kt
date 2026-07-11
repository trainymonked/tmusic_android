package dev.teacode.tmusic.ui

import androidx.media3.datasource.cache.SimpleCache
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Track
import java.io.File
import java.net.URI

private const val PLAYBACK_MEDIA_CACHE_KEY_PREFIX = "tmusic-track:"

internal fun playbackMediaCacheKey(trackId: String, playbackUrl: String): String? {
    val isRemoteStream = playbackUrl.startsWith("https://", ignoreCase = true) ||
        playbackUrl.startsWith("http://", ignoreCase = true)
    return if (isRemoteStream) "$PLAYBACK_MEDIA_CACHE_KEY_PREFIX$trackId" else null
}

internal fun SimpleCache.resolvePlaybackMediaCacheKey(
    trackId: String,
    playbackUrl: String,
): String? {
    return resolvePlaybackMediaCacheKeys(listOf(trackId to playbackUrl)).single()
}

internal fun SimpleCache.resolvePlaybackMediaCacheKeys(
    tracks: List<Pair<String, String>>,
): List<String?> {
    val candidates = tracks.map { (trackId, playbackUrl) ->
        val stableKey = playbackMediaCacheKey(trackId, playbackUrl)
        val stableBytes = stableKey?.let { key -> cachedPlaybackBytes(key) } ?: 0L
        val urlBytes = if (stableKey == null) 0L else cachedPlaybackBytes(playbackUrl)
        val directKey = when {
            stableKey == null -> null
            stableBytes <= 0L && urlBytes <= 0L -> null
            stableBytes >= urlBytes -> stableKey
            else -> playbackUrl
        }
        PlaybackCacheCandidate(
            stableKey = stableKey,
            resourceIdentity = stableKey?.let { playbackUrl.playbackResourceIdentity() },
            directKey = directKey,
            directBytes = maxOf(stableBytes, urlBytes),
        )
    }
    val resourceIdentities = candidates
        .filter { candidate -> candidate.stableKey != null }
        .mapNotNull { candidate -> candidate.resourceIdentity }
        .toSet()
    val legacyKeysByIdentity = mutableMapOf<String, Pair<String, Long>>()
    if (resourceIdentities.isNotEmpty()) {
        keys.forEach { key ->
            val identity = key.playbackResourceIdentity()
                ?.takeIf { it in resourceIdentities }
                ?: return@forEach
            val cachedBytes = cachedPlaybackBytes(key)
            val previousBytes = legacyKeysByIdentity[identity]?.second ?: 0L
            if (cachedBytes > previousBytes) {
                legacyKeysByIdentity[identity] = key to cachedBytes
            }
        }
    }
    return candidates.map { candidate ->
        val legacyKey = candidate.resourceIdentity?.let { identity -> legacyKeysByIdentity[identity] }
        if (legacyKey != null && legacyKey.second > candidate.directBytes) {
            legacyKey.first
        } else {
            candidate.directKey ?: candidate.stableKey
        }
    }
}

private data class PlaybackCacheCandidate(
    val stableKey: String?,
    val resourceIdentity: String?,
    val directKey: String?,
    val directBytes: Long,
)

private fun SimpleCache.cachedPlaybackBytes(key: String): Long {
    return getCachedSpans(key).sumOf { span -> span.length.coerceAtLeast(0L) }
}

private fun String.playbackResourceIdentity(): String? {
    val uri = runCatching { URI(this) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val authority = uri.rawAuthority?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: return null
    return "$scheme://$authority$path"
}

internal fun localOrCachedPlaybackUrl(
    musicRepository: RemoteMusicRepository,
    trackId: String,
): String? {
    return musicRepository.localPlaybackUrl(trackId)
        ?: musicRepository.cachedPlaybackUrl(trackId)
}

internal fun localOrCachedPlaybackUrl(
    musicRepository: RemoteMusicRepository,
    track: Track,
): String? {
    return localOrCachedPlaybackUrl(musicRepository, track.id)
        ?: track.serverPath
            .takeIf { track.downloadState == DownloadState.Downloaded && it.isNotBlank() }
            ?.let { path -> File(path).takeIf { it.exists() && it.length() > 0L }?.toURI()?.toString() }
}
