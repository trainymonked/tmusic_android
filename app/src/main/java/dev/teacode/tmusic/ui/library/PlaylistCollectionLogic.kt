package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal fun List<Playlist>.updatePlaylist(
    updatedPlaylist: Playlist,
    preserveOfflineFlag: Boolean = true,
): List<Playlist> {
    if (none { it.id == updatedPlaylist.id }) {
        return this
    }
    return map { playlist ->
        if (playlist.id == updatedPlaylist.id) {
            val mergedPlaylist = updatedPlaylist.mergeKnownPlaylistState(playlist)
            mergedPlaylist.copy(
                isOfflineEnabled = if (preserveOfflineFlag) {
                    mergedPlaylist.isOfflineEnabled
                } else {
                    updatedPlaylist.isOfflineEnabled
                },
            )
        } else {
            playlist
        }
    }
}

private data class PlaylistTrackReference(
    val trackId: String,
    val playlistTrackId: String?,
)

private fun Playlist.trackReferences(): List<PlaylistTrackReference> {
    return trackIds.mapIndexed { index, trackId ->
        PlaylistTrackReference(
            trackId = trackId,
            playlistTrackId = playlistTrackIds.getOrNull(index),
        )
    }
}

private fun Playlist.mergedKnownTrackReferences(existing: Playlist): List<PlaylistTrackReference> {
    val loadedReferences = trackReferences()
    if (loadedReferences.isEmpty()) {
        return existing.trackReferences()
    }
    val knownTrackIds = loadedReferences.map { it.trackId }.toMutableSet()
    return loadedReferences + existing.trackReferences().filter { reference ->
        knownTrackIds.add(reference.trackId)
    }
}

internal fun Playlist.mergeKnownPlaylistState(existing: Playlist?): Playlist {
    if (existing == null) {
        return this
    }
    val isPartialTrackPage = trackCount > trackIds.size && existing.trackIds.size > trackIds.size
    val mergedTrackReferences = if (trackIds.isEmpty() || isPartialTrackPage) {
        mergedKnownTrackReferences(existing)
    } else {
        trackReferences()
    }
    val mergedTrackIds = mergedTrackReferences.map { it.trackId }
    val mergedPlaylistTrackIds = mergedTrackReferences.mapNotNull { it.playlistTrackId }
    return copy(
        title = title.takeUnless { it == "Untitled playlist" } ?: existing.title,
        trackIds = mergedTrackIds,
        playlistTrackIds = mergedPlaylistTrackIds,
        playlistTrackIdsByTrackId = existing.playlistTrackIdsByTrackId + playlistTrackIdsByTrackId,
        isOfflineEnabled = isOfflineEnabled || existing.isOfflineEnabled,
        isFavorites = isFavorites || existing.isFavorites,
        trackCount = maxOf(trackCount, existing.trackCount, mergedTrackIds.size),
        totalDurationSeconds = totalDurationSeconds ?: existing.totalDurationSeconds,
    )
}

internal fun List<Playlist>.updateOrAppendPlaylist(updatedPlaylist: Playlist): List<Playlist> {
    val normalizedPlaylist = updatedPlaylist.normalizedClientPlaylist()
    if (normalizedPlaylist.isSyntheticPlaceholderPlaylist()) {
        return sanitizeClientPlaylists()
    }
    val sanitized = sanitizeClientPlaylists()
    return if (sanitized.any { it.id == normalizedPlaylist.id }) {
        sanitized.updatePlaylist(normalizedPlaylist)
    } else {
        listOf(normalizedPlaylist) + sanitized
    }
}

internal fun List<Playlist>.mergePlaylistMetadata(loadedPlaylists: List<Playlist>): List<Playlist> {
    val sanitizedLoadedPlaylists = loadedPlaylists.sanitizeClientPlaylists()
    if (sanitizedLoadedPlaylists.isEmpty()) {
        return sanitizeClientPlaylists()
    }
    val sanitizedExisting = sanitizeClientPlaylists()
    val existingById = sanitizedExisting.associateBy { it.id }
    val mergedLoaded = sanitizedLoadedPlaylists.map { loaded ->
        loaded.mergeKnownPlaylistState(existingById[loaded.id])
    }
    val loadedIds = sanitizedLoadedPlaylists.map { it.id }.toSet()
    return (mergedLoaded + sanitizedExisting.filterNot { it.id in loadedIds }).distinctBy { it.id }
}

internal fun List<Playlist>.mergeLoadedPlaylists(loadedPlaylists: List<Playlist>): List<Playlist> {
    val sanitizedLoadedPlaylists = loadedPlaylists.sanitizeClientPlaylists()
    if (sanitizedLoadedPlaylists.isEmpty()) {
        return sanitizeClientPlaylists()
    }
    val sanitizedExisting = sanitizeClientPlaylists()
    val existingById = sanitizedExisting.associateBy { it.id }
    val mergedLoaded = sanitizedLoadedPlaylists.map { loaded ->
        loaded.mergeKnownPlaylistState(existingById[loaded.id])
    }
    val loadedById = mergedLoaded.associateBy { it.id }
    return (mergedLoaded + sanitizedExisting.filterNot { it.id in loadedById }).distinctBy { it.id }
}

internal fun playlistDownloadedTrackCount(
    playlist: Playlist,
    tracks: List<Track>,
    hasLocalPlaybackUrl: (String) -> Boolean,
): Int {
    val downloadedTrackIds = tracks
        .filter { it.downloadState == DownloadState.Downloaded }
        .map { it.id }
        .toSet()
    return playlist.trackIds.count { trackId ->
        trackId in downloadedTrackIds || hasLocalPlaybackUrl(trackId)
    }
}

internal fun playlistIsFullyDownloaded(
    playlist: Playlist,
    tracks: List<Track>,
    hasLocalPlaybackUrl: (String) -> Boolean,
): Boolean {
    if (!playlist.isOfflineEnabled) {
        return false
    }
    val expectedTrackCount = playlist.trackCount.coerceAtLeast(playlist.trackIds.size)
    return expectedTrackCount > 0 &&
        playlist.trackIds.size >= expectedTrackCount &&
        playlistDownloadedTrackCount(playlist, tracks, hasLocalPlaybackUrl) >= expectedTrackCount
}
