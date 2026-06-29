package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal fun List<Playlist>.favoritePlaylistForLocalMutation(): Playlist {
    return firstOrNull { it.isFavoritesPlaylist() }
        ?: Playlist(
            id = "favorites",
            title = "Favorites",
            trackIds = emptyList(),
            isOfflineEnabled = false,
            isFavorites = true,
        )
}

internal fun Playlist?.normalizedFavoriteResponse(
    localState: Playlist,
    track: Track,
    shouldContain: Boolean,
): Playlist {
    if (this == null) {
        return if (shouldContain) {
            localState.withFavoriteTrack(track)
        } else {
            localState.withoutFavoriteTrack(track)
        }
    }
    val trackId = track.id
    val response = this
    val useLocalTracks = localState.trackIds.isNotEmpty() &&
        response.trackIds.size < localState.trackIds.size
    val useResponsePlaylistMetadata = response.title.isNotBlank()
    val nextTrackIds = if (useLocalTracks) localState.trackIds else response.trackIds
    val nextTrackIdsByTrackId = localState.playlistTrackIdsByTrackId + response.playlistTrackIdsByTrackId
    val nextPlaylistTrackIds = if (useLocalTracks) {
        localState.copy(
            playlistTrackIdsByTrackId = nextTrackIdsByTrackId,
        ).playlistTrackIdsAlignedWithTrackIds()
    } else {
        response.playlistTrackIds
    }
    val normalized = localState.copy(
        trackIds = nextTrackIds,
        playlistTrackIds = nextPlaylistTrackIds,
        playlistTrackIdsByTrackId = nextTrackIdsByTrackId,
        isFavorites = true,
        trackCount = if (useResponsePlaylistMetadata) {
            response.trackCount.coerceAtLeast(nextTrackIds.size)
        } else {
            localState.trackCount.coerceAtLeast(nextTrackIds.size)
        },
        totalDurationSeconds = if (useResponsePlaylistMetadata) {
            response.totalDurationSeconds
        } else {
            localState.totalDurationSeconds
        },
        updatedAt = if (useResponsePlaylistMetadata) {
            response.updatedAt
        } else {
            localState.updatedAt
        },
    )
    return if (shouldContain) {
        normalized.withFavoriteTrack(track)
    } else {
        normalized.withoutFavoriteTrack(track)
    }
}

private fun Playlist.playlistTrackIdsAlignedWithTrackIds(): List<String> {
    val alignedPlaylistTrackIds = trackIds.mapNotNull { trackId ->
        playlistTrackIdsByTrackId[trackId]
    }
    return if (alignedPlaylistTrackIds.size == trackIds.size) {
        alignedPlaylistTrackIds
    } else {
        playlistTrackIds
    }
}

internal fun Playlist.withFavoriteTrack(track: Track): Playlist {
    val normalized = normalizedFavoriteMembership()
    val alreadyContainsTrack = track.id in normalized.trackIds
    val nextPlaylist = normalized.withFavoriteTrack(track.id)
    if (alreadyContainsTrack) {
        return nextPlaylist
    }
    return nextPlaylist.copy(
        totalDurationSeconds = normalized.totalDurationSeconds?.let { durationSeconds ->
            (durationSeconds + track.durationSeconds).coerceAtLeast(0)
        },
    )
}

internal fun Playlist.withFavoriteTrack(trackId: String): Playlist {
    val normalized = normalizedFavoriteMembership()
    val existingIndex = normalized.trackIds.indexOf(trackId)
    if (existingIndex >= 0) {
        val remainingTrackIds = normalized.trackIds.filterIndexed { index, _ -> index != existingIndex }
        val nextTrackIds = listOf(trackId) + remainingTrackIds
        val existingPlaylistTrackId = normalized.playlistTrackIdsByTrackId[trackId]
            ?: normalized.playlistTrackIds
                .takeIf { it.size == normalized.trackIds.size }
                ?.getOrNull(existingIndex)
        val nextPlaylistTrackIds = if (normalized.playlistTrackIds.size == normalized.trackIds.size) {
            listOfNotNull(existingPlaylistTrackId) +
                normalized.playlistTrackIds.filterIndexed { index, _ -> index != existingIndex }
        } else {
            normalized.playlistTrackIds
        }
        return normalized.copy(
            trackIds = nextTrackIds,
            playlistTrackIds = nextPlaylistTrackIds,
            playlistTrackIdsByTrackId = existingPlaylistTrackId
                ?.let { normalized.playlistTrackIdsByTrackId + (trackId to it) }
                ?: normalized.playlistTrackIdsByTrackId,
            isFavorites = true,
            trackCount = trackCount.coerceAtLeast(nextTrackIds.size),
        )
    }
    val nextTrackIds = listOf(trackId) + normalized.trackIds
    return normalized.copy(
        trackIds = nextTrackIds,
        isFavorites = true,
        trackCount = maxOf(normalized.trackCount + 1, nextTrackIds.size),
    )
}

internal fun Playlist.withoutFavoriteTrack(track: Track): Playlist {
    val normalized = normalizedFavoriteMembership()
    val removedTrackCount = normalized.trackIds.count { trackId -> trackId == track.id }
    val nextPlaylist = normalized.withoutFavoriteTrack(track.id)
    if (removedTrackCount == 0) {
        return nextPlaylist
    }
    return nextPlaylist.copy(
        totalDurationSeconds = normalized.totalDurationSeconds?.let { durationSeconds ->
            (durationSeconds - track.durationSeconds * removedTrackCount).coerceAtLeast(0)
        },
    )
}

internal fun Playlist.withoutFavoriteTrack(trackId: String): Playlist {
    val normalized = normalizedFavoriteMembership()
    val removeIndices = normalized.trackIds.mapIndexedNotNull { index, itemTrackId ->
        index.takeIf { itemTrackId == trackId }
    }.toSet()
    if (removeIndices.isEmpty()) {
        return normalized.copy(isFavorites = true, trackCount = normalized.trackCount.coerceAtLeast(normalized.trackIds.size))
    }
    val nextTrackIds = normalized.trackIds.filterIndexed { index, _ -> index !in removeIndices }
    val nextPlaylistTrackIds = if (normalized.playlistTrackIds.size == normalized.trackIds.size) {
        normalized.playlistTrackIds.filterIndexed { index, _ -> index !in removeIndices }
    } else {
        normalized.playlistTrackIds
    }
    return normalized.copy(
        trackIds = nextTrackIds,
        playlistTrackIds = nextPlaylistTrackIds,
        playlistTrackIdsByTrackId = normalized.playlistTrackIdsByTrackId - trackId,
        isFavorites = true,
        trackCount = (normalized.trackCount - removeIndices.size).coerceAtLeast(nextTrackIds.size),
    )
}

internal fun Playlist.normalizedFavoriteMembership(): Playlist {
    if (!isFavoritesPlaylist()) {
        return this
    }
    val seenTrackIds = mutableSetOf<String>()
    val nextTrackIds = mutableListOf<String>()
    val nextPlaylistTrackIds = mutableListOf<String>()
    val nextPlaylistTrackIdsByTrackId = linkedMapOf<String, String>()
    val hasAlignedPlaylistTrackIds = playlistTrackIds.size == trackIds.size
    trackIds.forEachIndexed { index, trackId ->
        if (!seenTrackIds.add(trackId)) {
            return@forEachIndexed
        }
        nextTrackIds += trackId
        val playlistTrackId = playlistTrackIdsByTrackId[trackId]
            ?: if (hasAlignedPlaylistTrackIds) playlistTrackIds.getOrNull(index) else null
        if (playlistTrackId != null) {
            nextPlaylistTrackIds += playlistTrackId
            nextPlaylistTrackIdsByTrackId[trackId] = playlistTrackId
        }
    }
    return copy(
        trackIds = nextTrackIds,
        playlistTrackIds = nextPlaylistTrackIds,
        playlistTrackIdsByTrackId = nextPlaylistTrackIdsByTrackId,
        isFavorites = true,
        trackCount = trackCount.coerceAtLeast(nextTrackIds.size),
    )
}

internal fun Playlist.playlistTrackIdsForTrack(trackId: String): List<String> {
    return playlistTrackIdsByTrackId[trackId]?.let(::listOf).orEmpty()
}

internal fun Playlist.withoutPlaylistTrackId(
    playlistTrackId: String,
    fallbackTrackId: String? = null,
): Playlist {
    val removeIndex = playlistTrackIds.indexOf(playlistTrackId)
        .takeIf { it >= 0 }
        ?: fallbackTrackId?.let { trackId -> trackIds.indexOf(trackId).takeIf { it >= 0 } }
        ?: -1
    val nextTrackIds = if (removeIndex >= 0) {
        trackIds.filterIndexed { index, _ -> index != removeIndex }
    } else {
        trackIds
    }
    val nextPlaylistTrackIds = playlistTrackIds.filterIndexed { index, id ->
        index != removeIndex && id != playlistTrackId
    }
    return copy(
        trackIds = nextTrackIds,
        playlistTrackIds = nextPlaylistTrackIds,
        playlistTrackIdsByTrackId = playlistTrackIdsByTrackId
            .filterValues { it != playlistTrackId } - listOfNotNull(fallbackTrackId).toSet(),
        trackCount = if (removeIndex >= 0 || playlistTrackId in playlistTrackIds) {
            trackCount.minus(1).coerceAtLeast(nextTrackIds.size)
        } else {
            trackCount.coerceAtLeast(nextTrackIds.size)
        },
    )
}
