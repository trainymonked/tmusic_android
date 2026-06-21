package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.Playlist

internal data class PlaylistTrackMove(
    val playlistTrackId: String,
    val position: Int,
)

internal data class PlaylistReorderPlan(
    val updatedPlaylist: Playlist,
    val move: PlaylistTrackMove,
)

internal fun playlistReorderPlan(
    playlist: Playlist,
    playlists: List<Playlist>,
    playlistTrackIds: List<String>,
): PlaylistReorderPlan? {
    val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
    val oldPlaylistTrackIds = currentPlaylist.playlistTrackIds
    if (
        playlistTrackIds.isEmpty() ||
        playlistTrackIds.size != playlistTrackIds.distinct().size ||
        oldPlaylistTrackIds.size != playlistTrackIds.size
    ) {
        return null
    }

    val trackIdsByPlaylistTrackId = oldPlaylistTrackIds
        .zip(currentPlaylist.trackIds)
        .toMap()
    val reorderedTrackIds = playlistTrackIds.mapNotNull(trackIdsByPlaylistTrackId::get)
    if (reorderedTrackIds.size != playlistTrackIds.size) {
        return null
    }

    val move = singlePlaylistTrackMove(oldPlaylistTrackIds, playlistTrackIds) ?: return null
    return PlaylistReorderPlan(
        updatedPlaylist = currentPlaylist.copy(
            trackIds = reorderedTrackIds,
            playlistTrackIds = playlistTrackIds,
        ),
        move = move,
    )
}

private fun singlePlaylistTrackMove(
    oldIds: List<String>,
    newIds: List<String>,
): PlaylistTrackMove? {
    if (oldIds == newIds) {
        return null
    }
    if (oldIds.size != newIds.size || oldIds.toSet() != newIds.toSet()) {
        return null
    }
    val firstChangedIndex = oldIds.indices.firstOrNull { index -> oldIds[index] != newIds[index] }
        ?: return null
    val lastChangedIndex = oldIds.indices.lastOrNull { index -> oldIds[index] != newIds[index] }
        ?: return null
    return if (oldIds[firstChangedIndex] == newIds[lastChangedIndex]) {
        PlaylistTrackMove(
            playlistTrackId = oldIds[firstChangedIndex],
            position = lastChangedIndex + 1,
        )
    } else {
        PlaylistTrackMove(
            playlistTrackId = newIds[firstChangedIndex],
            position = firstChangedIndex + 1,
        )
    }
}
