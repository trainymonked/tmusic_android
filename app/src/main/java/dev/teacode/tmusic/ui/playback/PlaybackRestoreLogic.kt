package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.SavedPlaybackState
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal data class RestoredPlayback(
    val queue: PlaybackQueue,
    val playerState: PlayerState,
)

internal fun SavedPlaybackState.restorePlayback(
    tracks: List<Track>,
    playlists: List<Playlist>,
): RestoredPlayback? {
    val savedPlaybackTracks = queueTracks + sourceTracks + listOfNotNull(track)
    val savedTrack = tracks.firstOrNull { it.id == trackId }
        ?: savedPlaybackTracks.firstOrNull { it.id == trackId }
        ?: track
        ?: return null
    val savedPlaylist = playlistId?.let { id ->
        playlists.firstOrNull { it.id == id }
    }
    val tracksById = (tracks + savedPlaybackTracks + savedTrack).associateBy { it.id }
    val restoredTracks = queueTrackIds
        .mapNotNull(tracksById::get)
        .takeIf { it.isNotEmpty() }
        ?: savedPlaylist?.tracksFrom(tracks)
            ?.takeIf { it.isNotEmpty() }
        ?: listOf(savedTrack)
    val restoredSourceTracks = sourceTrackIds
        .mapNotNull(tracksById::get)
        .takeIf { it.isNotEmpty() }
        ?: restoredTracks
    val restoredSourceType = sourceType
        ?.let { value -> runCatching { PlaybackSourceType.valueOf(value) }.getOrNull() }
    val restoredIndex = currentIndex
        .takeIf { it in restoredTracks.indices }
        ?: restoredTracks.indexOfFirst { it.id == savedTrack.id }.coerceAtLeast(0)
    val restoredQueue = PlaybackQueue(
        playlistId = savedPlaylist?.id,
        sourceType = if (savedPlaylist != null) {
            PlaybackSourceType.Playlist
        } else {
            restoredSourceType ?: PlaybackSourceType.Search
        },
        sourceId = savedPlaylist?.id ?: sourceId ?: "Search",
        sourceTitle = savedPlaylist?.title ?: sourceTitle ?: "Search",
        tracks = restoredTracks,
        sourceTracks = restoredSourceTracks,
        manualQueueFlags = manualQueueFlags
            .takeIf { it.size == restoredTracks.size }
            ?: List(restoredTracks.size) { false },
        isShuffled = isShuffled,
        currentIndex = restoredIndex,
    )
    return RestoredPlayback(
        queue = restoredQueue,
        playerState = PlayerState(
            currentTrack = savedTrack,
            isPlaying = false,
            progressSeconds = (positionMs / 1000L)
                .toInt()
                .coerceIn(0, savedTrack.durationSeconds.coerceAtLeast(0)),
            streamUrl = null,
        ),
    )
}
