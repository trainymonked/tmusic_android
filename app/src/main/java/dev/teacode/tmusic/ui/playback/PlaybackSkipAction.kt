package dev.teacode.tmusic.ui

import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track

internal fun skipInQueueAction(
    direction: Int,
    restartCurrentWhenPrevious: Boolean,
    exoPlayer: ExoPlayer,
    getPlaybackQueue: () -> PlaybackQueue,
    getPlayerState: () -> PlayerState,
    setPlayerState: (PlayerState) -> Unit,
    cancelCrossfade: () -> Unit,
    canUseMediaServerRequests: () -> Boolean,
    localOrCachedPlaybackUrl: (Track) -> String?,
    completeActivePlayEvent: (Boolean) -> Unit,
    ensureActivePlayEvent: (Track, Boolean) -> Unit,
    seekPreparedQueueMediaItem: (Int, Int) -> Boolean,
    playQueuedTrack: (Track, PlaybackQueue, Int, Int) -> Unit,
) {
    cancelCrossfade()
    val queue = getPlaybackQueue()
    val playerState = getPlayerState()
    val currentTrackId = playerState.currentTrack?.id
    if (!queue.canSkip) {
        return
    }

    val indexFromQueue = queue.currentIndex
        .takeIf { it in queue.tracks.indices && queue.tracks[it].id == currentTrackId }
    val indexFromTrack = currentTrackId
        ?.let { trackId -> queue.tracks.indexOfFirst { it.id == trackId } }
        ?.takeIf { it in queue.tracks.indices }
    val currentIndex = indexFromQueue
        ?: indexFromTrack
        ?: queue.currentIndex.coerceIn(0, queue.tracks.lastIndex)
    logPlaybackDebug(
        "skip request direction=$direction restartPrevious=$restartCurrentWhenPrevious " +
            "player=${playerState.currentTrack?.debugTrack()} stream=${playerState.streamUrl != null} " +
            "indexFromQueue=$indexFromQueue indexFromTrack=$indexFromTrack ${queue.debugSummary()}",
    )

    if (direction < 0 && restartCurrentWhenPrevious) {
        val currentPositionMs = runCatching { exoPlayer.currentPosition }
            .getOrDefault(playerState.progressSeconds.toLong() * 1000L)
        val previousIndex = (currentIndex - 1).floorMod(queue.tracks.size)
        val previousTrack = queue.tracks.getOrNull(previousIndex)
        val shouldRestartCurrent = currentPositionMs >= 2_000L &&
            (canUseMediaServerRequests() || previousTrack?.let(localOrCachedPlaybackUrl) != null)
        if (shouldRestartCurrent) {
            val currentTrack = playerState.currentTrack ?: return
            logPlaybackDebug("skip restart current=${currentTrack.debugTrack()} positionMs=$currentPositionMs")
            completeActivePlayEvent(true)
            setPlayerState(playerState.copy(progressSeconds = 0))
            if (playerState.streamUrl != null) {
                exoPlayer.seekTo(0L)
                if (playerState.isPlaying) {
                    exoPlayer.play()
                }
            }
            ensureActivePlayEvent(currentTrack, true)
            return
        }
    }

    val requestedIndex = currentIndex + direction
    val nextIndex = requestedIndex.floorMod(queue.tracks.size)
    if (seekPreparedQueueMediaItem(nextIndex, direction)) {
        logPlaybackDebug("skip prepared targetIndex=$nextIndex target=${queue.tracks[nextIndex].debugTrack()}")
        return
    }
    logPlaybackDebug("skip playQueued targetIndex=$nextIndex target=${queue.tracks[nextIndex].debugTrack()}")
    playQueuedTrack(
        queue.tracks[nextIndex],
        queue.copy(currentIndex = nextIndex),
        nextIndex,
        direction,
    )
}
