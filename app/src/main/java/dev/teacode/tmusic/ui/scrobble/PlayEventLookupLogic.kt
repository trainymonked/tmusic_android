package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track

internal fun trackForPlayEvent(
    activeEvent: ActivePlayEvent,
    playerState: PlayerState,
    tracks: List<Track>,
    playbackQueue: PlaybackQueue,
): Track? {
    return playerState.currentTrack?.takeIf { it.id == activeEvent.trackId }
        ?: tracks.firstOrNull { it.id == activeEvent.trackId }
        ?: playbackQueue.tracks.firstOrNull { it.id == activeEvent.trackId }
}
