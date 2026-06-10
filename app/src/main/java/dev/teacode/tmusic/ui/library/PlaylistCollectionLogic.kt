package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.Playlist

internal fun List<Playlist>.updatePlaylist(
    updatedPlaylist: Playlist,
    preserveOfflineFlag: Boolean = true,
): List<Playlist> {
    if (none { it.id == updatedPlaylist.id }) {
        return this
    }
    return map { playlist ->
        if (playlist.id == updatedPlaylist.id) {
            updatedPlaylist.copy(
                isOfflineEnabled = if (preserveOfflineFlag) {
                    updatedPlaylist.isOfflineEnabled || playlist.isOfflineEnabled
                } else {
                    updatedPlaylist.isOfflineEnabled
                },
                isFavorites = updatedPlaylist.isFavorites || playlist.isFavorites,
                totalDurationSeconds = updatedPlaylist.totalDurationSeconds ?: playlist.totalDurationSeconds,
            )
        } else {
            playlist
        }
    }
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
        sanitized + normalizedPlaylist
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
        val existing = existingById[loaded.id]
        if (existing != null && loaded.trackIds.isEmpty()) {
            loaded.copy(
                trackIds = existing.trackIds,
                playlistTrackIds = existing.playlistTrackIds,
                playlistTrackIdsByTrackId = existing.playlistTrackIdsByTrackId,
                isOfflineEnabled = loaded.isOfflineEnabled || existing.isOfflineEnabled,
                trackCount = maxOf(loaded.trackCount, existing.trackCount, existing.trackIds.size),
                totalDurationSeconds = loaded.totalDurationSeconds ?: existing.totalDurationSeconds,
            )
        } else {
            loaded.copy(
                isOfflineEnabled = loaded.isOfflineEnabled || existing?.isOfflineEnabled == true,
                totalDurationSeconds = loaded.totalDurationSeconds ?: existing?.totalDurationSeconds,
            )
        }
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
        val existing = existingById[loaded.id]
        if (existing != null && loaded.trackIds.isEmpty()) {
            loaded.copy(
                title = loaded.title.takeUnless { it == "Untitled playlist" } ?: existing.title,
                trackIds = existing.trackIds,
                playlistTrackIds = existing.playlistTrackIds,
                playlistTrackIdsByTrackId = existing.playlistTrackIdsByTrackId,
                isOfflineEnabled = loaded.isOfflineEnabled || existing.isOfflineEnabled,
                isFavorites = loaded.isFavorites || existing.isFavorites,
                trackCount = maxOf(loaded.trackCount, existing.trackCount, existing.trackIds.size),
                totalDurationSeconds = loaded.totalDurationSeconds ?: existing.totalDurationSeconds,
            )
        } else {
            loaded.copy(
                isOfflineEnabled = loaded.isOfflineEnabled || existing?.isOfflineEnabled == true,
                isFavorites = loaded.isFavorites || existing?.isFavorites == true,
                totalDurationSeconds = loaded.totalDurationSeconds ?: existing?.totalDurationSeconds,
            )
        }
    }
    val loadedById = mergedLoaded.associateBy { it.id }
    return (mergedLoaded + sanitizedExisting.filterNot { it.id in loadedById }).distinctBy { it.id }
}
