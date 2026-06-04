package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.Playlist

internal fun Playlist?.normalizedFavoriteResponse(
    localState: Playlist,
    trackId: String,
    shouldContain: Boolean,
): Playlist {
    if (this == null) {
        return if (shouldContain) {
            localState.withFavoriteTrack(trackId)
        } else {
            localState.withoutFavoriteTrack(trackId)
        }
    }
    val response = this
    val useLocalTracks = response.trackIds.isEmpty() && localState.trackIds.isNotEmpty()
    val nextTrackIds = if (useLocalTracks) localState.trackIds else response.trackIds
    val nextPlaylistTrackIds = if (useLocalTracks) localState.playlistTrackIds else response.playlistTrackIds
    val nextTrackIdsByTrackId = if (useLocalTracks) {
        localState.playlistTrackIdsByTrackId
    } else {
        localState.playlistTrackIdsByTrackId + response.playlistTrackIdsByTrackId
    }
    val normalized = localState.copy(
        trackIds = nextTrackIds,
        playlistTrackIds = nextPlaylistTrackIds,
        playlistTrackIdsByTrackId = nextTrackIdsByTrackId,
        isFavorites = true,
        trackCount = (if (useLocalTracks) localState.trackCount else response.trackCount).coerceAtLeast(nextTrackIds.size),
    )
    return if (shouldContain) {
        normalized.withFavoriteTrack(trackId)
    } else {
        normalized.withoutFavoriteTrack(trackId)
    }
}

internal fun Playlist.withFavoriteTrack(trackId: String): Playlist {
    if (trackId in trackIds) {
        return copy(isFavorites = true, trackCount = trackCount.coerceAtLeast(trackIds.size))
    }
    val nextTrackIds = trackIds + trackId
    return copy(
        trackIds = nextTrackIds,
        isFavorites = true,
        trackCount = maxOf(trackCount + 1, nextTrackIds.size),
    )
}

internal fun Playlist.withoutFavoriteTrack(trackId: String): Playlist {
    val removeIndices = trackIds.mapIndexedNotNull { index, itemTrackId ->
        index.takeIf { itemTrackId == trackId }
    }.toSet()
    if (removeIndices.isEmpty()) {
        return copy(isFavorites = true, trackCount = trackCount.coerceAtLeast(trackIds.size))
    }
    val nextTrackIds = trackIds.filterIndexed { index, _ -> index !in removeIndices }
    val nextPlaylistTrackIds = playlistTrackIds.filterIndexed { index, _ -> index !in removeIndices }
    return copy(
        trackIds = nextTrackIds,
        playlistTrackIds = nextPlaylistTrackIds,
        playlistTrackIdsByTrackId = playlistTrackIdsByTrackId - trackId,
        isFavorites = true,
        trackCount = (trackCount - removeIndices.size).coerceAtLeast(nextTrackIds.size),
    )
}

internal fun Playlist.playlistTrackIdsForTrack(trackId: String): List<String> {
    return trackIds.mapIndexedNotNull { index, itemTrackId ->
        if (itemTrackId == trackId) {
            playlistTrackIds.getOrNull(index) ?: playlistTrackIdsByTrackId[trackId]
        } else {
            null
        }
    }.distinct()
}

internal fun Playlist.withoutPlaylistTrackId(playlistTrackId: String): Playlist {
    val removeIndex = playlistTrackIds.indexOf(playlistTrackId)
    val nextTrackIds = if (removeIndex >= 0) {
        trackIds.filterIndexed { index, _ -> index != removeIndex }
    } else {
        trackIds
    }
    val nextPlaylistTrackIds = playlistTrackIds.filterNot { it == playlistTrackId }
    return copy(
        trackIds = nextTrackIds,
        playlistTrackIds = nextPlaylistTrackIds,
        playlistTrackIdsByTrackId = playlistTrackIdsByTrackId.filterValues { it != playlistTrackId },
        trackCount = trackCount.minus(1).coerceAtLeast(nextTrackIds.size),
    )
}
