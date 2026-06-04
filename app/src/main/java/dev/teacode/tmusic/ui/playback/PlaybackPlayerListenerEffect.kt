package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.teacode.tmusic.domain.PlayerState
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@Composable
internal fun PlaybackPlayerListenerEffect(
    player: Player,
    isCurrentPlayer: (Player) -> Boolean,
    isCrossfadeActive: () -> Boolean,
    playerState: () -> PlayerState,
    playbackQueue: () -> PlaybackQueue,
    repeatMode: () -> PlaybackRepeatMode,
    gaplessPlaybackRequest: () -> GaplessPlaybackRequest?,
    gaplessMediaQueueIndices: () -> Map<String, Int>,
    gaplessMediaUrls: () -> Map<String, String>,
    activePlayEvent: () -> ActivePlayEvent?,
    onBufferedFractionChanged: (Float) -> Unit,
    onCompleteActivePlayEvent: () -> Unit,
    onRequestCurrentTrackRestart: () -> Unit,
    onRequestQueueAdvance: () -> Unit,
    onRequestQueueWrapPause: () -> Unit,
    onPlayerStateChanged: (PlayerState) -> Unit,
    onQueueTransition: (
        nextQueue: PlaybackQueue,
        nextState: PlayerState,
        artworkDirection: Int,
    ) -> Unit,
    onEnsureActivePlayEvent: (track: dev.teacode.tmusic.domain.Track, forceNew: Boolean) -> Unit,
    onClearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    onRequestNextPrefetch: () -> Unit,
    onPlayerError: (String) -> Unit,
    onDisposePlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    DisposableEffect(player) {
        val observedPlayer = player
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isCurrentPlayer(observedPlayer)) {
                    return
                }
                onBufferedFractionChanged(observedPlayer.bufferedPercentage.coerceIn(0, 100) / 100f)
                if (playbackState != Player.STATE_ENDED || isCrossfadeActive()) {
                    return
                }

                onCompleteActivePlayEvent()
                val queue = playbackQueue()
                val currentTrack = playerState().currentTrack
                when {
                    repeatMode() == PlaybackRepeatMode.Track && currentTrack != null -> {
                        onRequestCurrentTrackRestart()
                    }
                    queue.canSkip &&
                        (
                            repeatMode() == PlaybackRepeatMode.Queue ||
                                queue.currentIndex < queue.tracks.lastIndex
                            ) -> {
                        onRequestQueueAdvance()
                    }
                    queue.canSkip && currentTrack != null -> {
                        onRequestQueueWrapPause()
                    }
                    repeatMode() == PlaybackRepeatMode.Queue && currentTrack != null -> {
                        onRequestCurrentTrackRestart()
                    }
                    else -> {
                        onPlayerStateChanged(playerState().copy(isPlaying = false))
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isCurrentPlayer(observedPlayer) || isCrossfadeActive()) {
                    return
                }
                val mediaId = mediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
                val request = gaplessPlaybackRequest()
                val requestIndex = request?.mediaIds?.indexOf(mediaId)?.takeIf { it >= 0 }
                val queueIndex = gaplessMediaQueueIndices()[mediaId]
                    ?: requestIndex?.let { request.queueIndices.getOrNull(it) }
                    ?: return
                val nextTrack = playbackQueue().tracks.getOrNull(queueIndex) ?: return
                if (
                    playbackQueue().currentIndex == queueIndex &&
                    playerState().currentTrack?.id == nextTrack.id
                ) {
                    return
                }
                scope.launch {
                    yield()
                    if (!isCurrentPlayer(observedPlayer) || observedPlayer.currentMediaItem?.mediaId != mediaId) {
                        return@launch
                    }
                    val previousEvent = activePlayEvent()
                    onCompleteActivePlayEvent()
                    val currentQueue = playbackQueue()
                    val previousQueueIndex = currentQueue.currentIndex
                    val artworkDirection = if (
                        queueIndex < previousQueueIndex &&
                        !(previousQueueIndex == currentQueue.tracks.lastIndex && queueIndex == 0)
                    ) {
                        -1
                    } else {
                        1
                    }
                    val nextQueue = currentQueue.copy(currentIndex = queueIndex)
                    val nextState = playerState().copy(
                        currentTrack = nextTrack,
                        isPlaying = true,
                        progressSeconds = 0,
                        streamUrl = gaplessMediaUrls()[mediaId]
                            ?: requestIndex?.let { request?.urls?.getOrNull(it) },
                    )
                    onQueueTransition(nextQueue, nextState, artworkDirection)
                    onEnsureActivePlayEvent(nextTrack, previousEvent?.trackId == nextTrack.id)
                    onRequestNextPrefetch()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isCurrentPlayer(observedPlayer)) {
                    return
                }
                onPlayerError(
                    error.causeChainMessage().takeIf { it.isNotBlank() }
                        ?: error.localizedMessage
                        ?: "Playback failed",
                )
                onPlayerStateChanged(playerState().copy(isPlaying = false))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCurrentPlayer(observedPlayer)) {
                    return
                }
                val currentState = playerState()
                if (!isPlaying && currentState.isPlaying && observedPlayer.playbackState != Player.STATE_READY) {
                    return
                }
                if (
                    !isPlaying &&
                    currentState.isPlaying &&
                    observedPlayer.playbackSuppressionReason ==
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                ) {
                    return
                }
                if (currentState.streamUrl == null || currentState.isPlaying == isPlaying) {
                    return
                }
                if (isPlaying) {
                    currentState.currentTrack?.let { track -> onEnsureActivePlayEvent(track, false) }
                } else {
                    onClearNowPlayingEvent(activePlayEvent())
                }
                onPlayerStateChanged(
                    currentState.copy(
                        isPlaying = isPlaying,
                        progressSeconds = if (isPlaying) {
                            currentState.progressSeconds
                        } else {
                            (observedPlayer.currentPosition / 1000L).toInt().coerceAtLeast(0)
                        },
                    ),
                )
            }
        }
        observedPlayer.addListener(listener)
        onDispose {
            onDisposePlayer()
            observedPlayer.removeListener(listener)
        }
    }
}
