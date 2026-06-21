package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Track

internal fun List<LibraryAlbum>.updateAlbum(updatedAlbum: LibraryAlbum): List<LibraryAlbum> {
    if (none { it.id == updatedAlbum.id }) {
        return this
    }
    return map { album ->
        if (album.id == updatedAlbum.id) {
            updatedAlbum.copy(
                artist = updatedAlbum.artist.takeIf { it.isNotBlank() } ?: album.artist,
                artistId = updatedAlbum.artistId ?: album.artistId,
                artistIds = updatedAlbum.artistIds.ifEmpty { album.artistIds },
                artists = updatedAlbum.artists.ifEmpty { album.artists },
                totalDurationSeconds = updatedAlbum.totalDurationSeconds ?: album.totalDurationSeconds,
            )
        } else {
            album
        }
    }
}

internal fun List<LibraryAlbum>.updateOrAppendAlbum(updatedAlbum: LibraryAlbum): List<LibraryAlbum> {
    return if (any { it.id == updatedAlbum.id }) {
        updateAlbum(updatedAlbum)
    } else {
        this + updatedAlbum
    }
}

internal fun List<LibraryAlbum>.sortedAlbumsForDisplay(): List<LibraryAlbum> {
    return sortedWith(
        compareByDescending<LibraryAlbum> { it.releaseYear ?: Int.MIN_VALUE }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id },
    )
}

fun aggregateDownloadState(
    isOfflineEnabled: Boolean,
    expectedTrackCount: Int,
    loadedTrackCount: Int,
    tracks: List<Track>,
): DownloadState {
    if (!isOfflineEnabled) {
        return DownloadState.NotDownloaded
    }
    if (tracks.any { it.downloadState == DownloadState.Queued }) {
        return DownloadState.Queued
    }
    val expected = expectedTrackCount.coerceAtLeast(loadedTrackCount)
    val complete = expected > 0 &&
        loadedTrackCount >= expected &&
        tracks.isNotEmpty() &&
        tracks.all { it.downloadState == DownloadState.Downloaded }
    return if (complete) DownloadState.Downloaded else DownloadState.Queued
}

fun albumArtworkKey(
    album: LibraryAlbum,
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
): String? {
    if (album.hasArtwork) {
        return albumArtworkKey(album.id)
    }

    return albumArtworkTrackId(album, tracks, albumTracksById)
}

private fun albumArtworkTrackId(
    album: LibraryAlbum,
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
): String? {
    return album.artworkTrackId
        ?: albumTracksById[album.id]
            ?.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
            ?.firstOrNull()
            ?.id
        ?: tracks
            .filter { track ->
                track.albumId == album.id ||
                    (track.album == album.title && track.matchesAlbumArtist(album))
            }
            .sortedBy { it.trackNumber ?: Int.MAX_VALUE }
            .firstOrNull()
            ?.id
}

internal fun Track.matchesAlbumArtist(album: LibraryAlbum): Boolean {
    if (album.artistLogicNames().any { albumArtistName ->
            artistLogicNames().any { it.equals(albumArtistName, ignoreCase = true) }
        }
    ) {
        return true
    }
    return artist.equals(album.artist, ignoreCase = true) ||
        albumArtist?.equals(album.artist, ignoreCase = true) == true
}
