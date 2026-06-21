package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal fun downloadedArtworkKeys(
    playlists: List<Playlist>,
    sourceTracks: List<Track>,
): Set<String> {
    val trackArtworkKeys = sourceTracks
        .filter { it.downloadState == DownloadState.Downloaded }
        .flatMap { track ->
            listOfNotNull(
                track.listArtworkKey(),
                track.albumId?.let(::albumArtworkKey),
            )
        }
        .toSet()
    val playlistArtworkKeys = playlists
        .filter { playlist -> playlist.isOfflineEnabled || playlist.isFavorites }
        .map(::playlistArtworkKey)
        .toSet()
    return trackArtworkKeys + playlistArtworkKeys
}

internal fun artworkCacheKeysFor(artworkKeys: Set<String>): Set<String> {
    return artworkKeys.flatMap { artworkKey ->
        ArtworkImageSize.entries.map { imageSize -> artworkCacheKey(artworkKey, imageSize) }
    }.toSet()
}

internal fun downloadedArtworkCacheKeys(
    playlists: List<Playlist>,
    sourceTracks: List<Track>,
): Set<String> {
    return artworkCacheKeysFor(downloadedArtworkKeys(playlists, sourceTracks))
}
