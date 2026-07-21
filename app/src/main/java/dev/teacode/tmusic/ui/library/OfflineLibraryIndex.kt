package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

/**
 * One view of every track the app currently knows about and why its offline copy is retained.
 *
 * Album detail pages keep their tracks separately from the main library list, so using only one
 * of those collections can incorrectly discard a downloaded file or forget its artwork.
 */
internal data class OfflineLibraryIndex(
    val tracks: List<Track>,
    val tracksById: Map<String, Track>,
    val downloadedTracks: List<Track>,
    val playlistTrackIds: Set<String>,
    val albumTrackIds: Set<String>,
) {
    val downloadedTrackIds: Set<String> = downloadedTracks.mapTo(linkedSetOf()) { track -> track.id }
    val requiredTrackIds: Set<String> = playlistTrackIds + albumTrackIds

    fun isRequiredByCollection(trackId: String): Boolean = trackId in requiredTrackIds
}

internal fun offlineLibraryIndex(
    playlists: List<Playlist>,
    tracks: List<Track>,
    offlineAlbumIds: Set<String>,
    albumTracksById: Map<String, List<Track>>,
    extraTracks: List<Track> = emptyList(),
): OfflineLibraryIndex {
    val tracksById = linkedMapOf<String, Track>()
    (tracks + albumTracksById.values.flatten() + extraTracks).forEach { track ->
        val existing = tracksById[track.id]
        if (existing == null || track.downloadState.ordinal > existing.downloadState.ordinal) {
            tracksById[track.id] = track
        }
    }
    val knownTracks = tracksById.values.toList()
    val playlistTrackIds = playlists
        .asSequence()
        .filter { playlist -> playlist.isOfflineEnabled }
        .flatMap { playlist -> playlist.trackIds.asSequence() }
        .toSet()
    val albumTrackIds = knownTracks
        .asSequence()
        .filter { track -> track.albumId in offlineAlbumIds }
        .map { track -> track.id }
        .toSet()
    return OfflineLibraryIndex(
        tracks = knownTracks,
        tracksById = tracksById,
        downloadedTracks = knownTracks.filter { track -> track.downloadState == DownloadState.Downloaded },
        playlistTrackIds = playlistTrackIds,
        albumTrackIds = albumTrackIds,
    )
}
