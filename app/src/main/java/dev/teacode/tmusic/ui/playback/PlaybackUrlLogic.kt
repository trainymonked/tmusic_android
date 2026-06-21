package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Track
import java.io.File

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
