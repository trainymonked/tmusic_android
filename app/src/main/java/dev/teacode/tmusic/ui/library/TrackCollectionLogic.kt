package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal fun List<Track>.withFavoritePlaylistState(playlists: List<Playlist>): List<Track> {
    if (isEmpty()) {
        return this
    }
    val favoriteTrackIds = playlists
        .firstOrNull { playlist -> playlist.isFavoritesPlaylist() }
        ?.trackIds
        ?.toSet()
        .orEmpty()
    if (favoriteTrackIds.isEmpty()) {
        return this
    }
    return map { track ->
        if (track.id in favoriteTrackIds && track.isLiked != true) {
            track.copy(isLiked = true)
        } else {
            track
        }
    }
}

internal fun List<Track>.withKnownTrackMetadata(existingTracks: List<Track>): List<Track> {
    if (isEmpty() || existingTracks.isEmpty()) {
        return this
    }
    val existingById = existingTracks.associateBy { it.id }
    return map { track ->
        val existing = existingById[track.id] ?: return@map track
        track.copy(
            downloadState = if (
                existing.downloadState == DownloadState.Queued &&
                track.downloadState == DownloadState.NotDownloaded
            ) {
                DownloadState.Queued
            } else {
                track.downloadState
            },
            artistId = track.artistId ?: existing.artistId,
            artistIds = track.artistIds.ifEmpty { existing.artistIds },
            artists = track.artists.ifEmpty { existing.artists },
            albumId = track.albumId ?: existing.albumId,
            albumArtist = track.albumArtist ?: existing.albumArtist,
            albumArtistId = track.albumArtistId ?: existing.albumArtistId,
            albumArtists = track.albumArtists.ifEmpty { existing.albumArtists },
            isLiked = track.isLiked ?: existing.isLiked,
        )
    }
}

internal fun List<Track>.withPendingFavoriteStates(
    pendingFavoriteStates: Map<String, Boolean>,
): List<Track> {
    if (isEmpty() || pendingFavoriteStates.isEmpty()) {
        return this
    }
    return map { track ->
        pendingFavoriteStates[track.id]?.let { isLiked ->
            track.copy(isLiked = isLiked)
        } ?: track
    }
}

internal fun List<Track>.mergeLoadedTracks(loadedTracks: List<Track>): List<Track> {
    if (loadedTracks.isEmpty()) {
        return this
    }
    val loadedById = loadedTracks.associateBy { it.id }
    return map { track -> loadedById[track.id] ?: track } +
        loadedTracks.filterNot { loadedTrack -> any { it.id == loadedTrack.id } }
}
