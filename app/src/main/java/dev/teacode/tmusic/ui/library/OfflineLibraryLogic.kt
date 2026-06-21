package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Track

internal fun List<Track>.downloadedArtists(): List<LibraryArtist> {
    return filter { it.downloadState == DownloadState.Downloaded }
        .flatMap { track -> track.artistReferences().map { artist -> artist to track } }
        .groupBy(keySelector = { it.first.id }, valueTransform = { it })
        .map { (_, entries) ->
            val artist = entries.first().first
            val artistTracks = entries.map { it.second }
            artist.copy(
                albumCount = artistTracks.map { it.album }.distinct().size,
                trackCount = artistTracks.size,
            )
        }
        .sortedArtistsForDisplay()
}

internal fun List<Track>.downloadedAlbums(allowedAlbumIds: Set<String>? = null): List<LibraryAlbum> {
    return filter { it.downloadState == DownloadState.Downloaded }
        .mapNotNull { track -> track.offlineAlbumKey()?.let { albumKey -> albumKey to track } }
        .filter { (albumKey, _) -> allowedAlbumIds == null || albumKey in allowedAlbumIds }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (id, tracks) ->
            val firstTrack = tracks.first()
            LibraryAlbum(
                id = id,
                title = firstTrack.album,
                artist = firstTrack.albumArtist ?: firstTrack.artistLogicNames().firstOrNull() ?: firstTrack.artist,
                artistId = firstTrack.albumArtistId ?: firstTrack.artistId ?: firstTrack.artistIds.firstOrNull(),
                artistIds = tracks
                    .flatMap { track -> listOfNotNull(track.albumArtistId, track.artistId) + track.artistIds }
                    .filter { it.isNotBlank() }
                    .distinct(),
                artists = tracks
                    .flatMap { track -> track.albumArtists.ifEmpty { track.artists } }
                    .distinctBy { it.id },
                trackCount = tracks.size,
                accentColor = firstTrack.accentColor,
                artworkTrackId = firstTrack.id,
                releaseYear = tracks.mapNotNull { it.releaseYear }.maxOrNull(),
                genre = tracks.mapNotNull { it.genre?.trim()?.takeIf(String::isNotBlank) }
                    .distinctBy { it.lowercase() }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() },
                isOfflineEnabled = true,
            )
        }
        .sortedAlbumsForDisplay()
}

internal fun List<Track>.downloadedAlbumTracksById(
    allowedAlbumIds: Set<String>? = null,
): Map<String, List<Track>> {
    return filter { it.downloadState == DownloadState.Downloaded }
        .mapNotNull { track -> track.offlineAlbumKey()?.let { albumKey -> albumKey to track } }
        .filter { (albumKey, _) -> allowedAlbumIds == null || albumKey in allowedAlbumIds }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, albumTracks) ->
            albumTracks.sortedWith(
                compareBy<Track> { it.discNumber ?: Int.MAX_VALUE }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() },
            )
        }
}

internal fun List<Track>.cachedAlbumTracksById(): Map<String, List<Track>> {
    return groupBy { it.albumId?.takeIf(String::isNotBlank) }
        .mapNotNull { (albumId, albumTracks) ->
            val key = albumId ?: return@mapNotNull null
            key to albumTracks.sortedWith(
                compareBy<Track> { it.discNumber ?: Int.MAX_VALUE }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id },
            )
        }
        .toMap()
}

private fun Track.offlineAlbumKey(): String? {
    albumId?.takeIf { it.isNotBlank() }?.let { return it }
    val albumTitle = album.trim().takeIf { it.isNotBlank() } ?: return null
    val albumArtistName = albumArtist?.trim()?.takeIf { it.isNotBlank() }
        ?: artistLogicNames().firstOrNull()
        ?: artist.trim().takeIf { it.isNotBlank() }
        ?: "Unknown artist"
    return "$albumArtistName:$albumTitle"
}

internal fun List<LibraryArtist>.sortedArtistsForDisplay(): List<LibraryArtist> {
    return sortedWith(
        compareByDescending<LibraryArtist> { it.trackCount }
            .thenBy { it.name.lowercase() },
    )
}

internal fun List<LibraryArtist>.manualSimilarArtistsFirst(): List<LibraryArtist> {
    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<LibraryArtist>> {
                if (it.value.similarity?.isManual == true) 0 else 1
            }.thenBy { it.index },
        )
        .map(IndexedValue<LibraryArtist>::value)
}

internal fun List<LibraryArtist>.filterOwnReleaseArtists(): List<LibraryArtist> {
    return filter { artist -> !artist.representativeAlbumId.isNullOrBlank() }
}
