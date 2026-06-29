package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.PlaylistPayload
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal data class MergedPlaylistPayload(
    val playlist: Playlist?,
    val playlists: List<Playlist>,
    val tracks: List<Track>,
)

internal fun PlaylistPayload.mergeWithCachedPlaylistData(
    cachedPlaylists: List<Playlist>,
    cachedTracks: List<Track>,
    withOfflineState: (List<Track>) -> List<Track>,
): MergedPlaylistPayload {
    val rawPlaylist = playlists
        .sanitizeClientPlaylists()
        .firstOrNull()
        ?: return MergedPlaylistPayload(
            playlist = null,
            playlists = cachedPlaylists,
            tracks = cachedTracks,
        )
    val currentPlaylist = cachedPlaylists.firstOrNull { it.id == rawPlaylist.id }
    val loadedPlaylist = rawPlaylist.mergeKnownPlaylistState(currentPlaylist)
    val mergedTracks = if (tracks.isEmpty()) {
        cachedTracks
    } else {
        withOfflineState(
            (cachedTracks + tracks.withKnownTrackMetadata(cachedTracks))
                .associateBy { it.id }
                .values
                .toList(),
        ).withKnownTrackMetadata(cachedTracks)
    }
    return MergedPlaylistPayload(
        playlist = loadedPlaylist,
        playlists = cachedPlaylists
            .sanitizeClientPlaylists()
            .updatePlaylist(loadedPlaylist),
        tracks = mergedTracks,
    )
}

internal fun PlaylistPayload.mergePlaylistTrackPage(
    playlist: Playlist,
    currentPlaylist: Playlist,
    append: Boolean,
): PlaylistPayload {
    val pagePlaylist = playlists.firstOrNull() ?: return this
    val mergedPlaylist = when {
        append -> pagePlaylist.copy(
            trackIds = currentPlaylist.trackIds + pagePlaylist.trackIds,
            playlistTrackIds = currentPlaylist.playlistTrackIds + pagePlaylist.playlistTrackIds,
            playlistTrackIdsByTrackId = currentPlaylist.playlistTrackIdsByTrackId + pagePlaylist.playlistTrackIdsByTrackId,
            trackCount = pagePlaylist.trackCount.coerceAtLeast(
                currentPlaylist.trackIds.size + pagePlaylist.trackIds.size,
            ),
            totalDurationSeconds = pagePlaylist.totalDurationSeconds ?: currentPlaylist.totalDurationSeconds,
        )

        else -> pagePlaylist.copy(
            trackCount = pagePlaylist.trackCount.coerceAtLeast(pagePlaylist.trackIds.size),
            totalDurationSeconds = pagePlaylist.totalDurationSeconds ?: currentPlaylist.totalDurationSeconds,
        )
    }
    return copy(playlists = listOf(mergedPlaylist.copy(id = playlist.id)))
}
