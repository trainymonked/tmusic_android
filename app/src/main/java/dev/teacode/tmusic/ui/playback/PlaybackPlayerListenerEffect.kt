package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
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
    onPlayerError: (message: String, httpStatusCode: Int?) -> Boolean,
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
                    queue.tracks.isNotEmpty() &&
                        currentTrack != null &&
                        repeatMode() != PlaybackRepeatMode.Queue -> {
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
                val mappedQueueIndex = gaplessMediaQueueIndices()[mediaId]
                    ?: requestIndex?.let { request.queueIndices.getOrNull(it) }
                    ?: run {
                        logPlaybackDebug(
                            "media transition ignored: no queueIndex mediaId=$mediaId reason=$reason " +
                                "playerIndex=${observedPlayer.currentMediaItemIndex} ${playbackQueue().debugSummary()}",
                        )
                        return
                    }
                val mediaTrackId = mediaId.playbackMediaTrackId()
                scope.launch {
                    yield()
                    if (!isCurrentPlayer(observedPlayer) || observedPlayer.currentMediaItem?.mediaId != mediaId) {
                        logPlaybackDebug(
                            "media transition stale after yield mediaId=$mediaId currentMediaId=" +
                                "${observedPlayer.currentMediaItem?.mediaId}",
                        )
                        return@launch
                    }
                    val currentQueue = playbackQueue()
                    val queueIndex = currentQueue.resolvePlaybackMediaQueueIndex(
                        mappedQueueIndex = mappedQueueIndex,
                        mediaTrackId = mediaTrackId,
                    ) ?: run {
                        logPlaybackDebug(
                            "media transition ignored: missing track mappedQueueIndex=$mappedQueueIndex " +
                                "mediaTrackId=$mediaTrackId mediaId=$mediaId reason=$reason ${currentQueue.debugSummary()}",
                        )
                        return@launch
                    }
                    val nextTrack = currentQueue.tracks.getOrNull(queueIndex) ?: run {
                        logPlaybackDebug(
                            "media transition ignored: missing track queueIndex=$queueIndex mediaId=$mediaId " +
                                "reason=$reason ${currentQueue.debugSummary()}",
                        )
                        return@launch
                    }
                    if (queueIndex != mappedQueueIndex) {
                        logPlaybackDebug(
                            "media transition remapped mediaId=$mediaId mapped=$mappedQueueIndex actual=$queueIndex " +
                                "mediaTrackId=$mediaTrackId next=${nextTrack.debugTrack()} ${currentQueue.debugSummary()}",
                        )
                    }
                    logPlaybackDebug(
                        "media transition mediaId=$mediaId reason=$reason queueIndex=$queueIndex " +
                            "next=${nextTrack.debugTrack()} player=${playerState().currentTrack?.debugTrack()} " +
                            currentQueue.debugSummary(),
                    )
                    if (
                        currentQueue.currentIndex == queueIndex &&
                        playerState().currentTrack?.id == nextTrack.id
                    ) {
                        logPlaybackDebug("media transition already applied queueIndex=$queueIndex track=${nextTrack.debugTrack()}")
                        return@launch
                    }
                    val previousEvent = activePlayEvent()
                    onCompleteActivePlayEvent()
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
                    logPlaybackDebug(
                        "media transition apply queueIndex=$queueIndex next=${nextTrack.debugTrack()} " +
                            nextQueue.debugSummary(),
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
                val handled = onPlayerError(
                    error.playbackErrorMessage(),
                    error.httpResponseCode(),
                )
                if (!handled) {
                    onPlayerStateChanged(playerState().copy(isPlaying = false))
                }
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

private fun PlaybackException.playbackErrorMessage(): String {
    if (isServerAvailabilityFailure()) {
        return userMessage()
    }
    return causeChainMessage().takeIf { it.isNotBlank() }
        ?: localizedMessage
        ?: "Playback failed"
}

private fun Throwable.httpResponseCode(): Int? {
    return generateSequence(this as Throwable?) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
}

private fun String.playbackMediaTrackId(): String? {
    val parts = split(':', limit = 4)
    return when {
        parts.size == 4 && parts[0] == "crossfade" -> parts[3]
        parts.size >= 3 -> parts.last()
        else -> null
    }?.takeIf { it.isNotBlank() }
}

private fun PlaybackQueue.resolvePlaybackMediaQueueIndex(
    mappedQueueIndex: Int,
    mediaTrackId: String?,
): Int? {
    if (tracks.isEmpty()) {
        return null
    }
    if (mediaTrackId == null) {
        return mappedQueueIndex.takeIf { it in tracks.indices }
    }
    mappedQueueIndex
        .takeIf { index -> index in tracks.indices && tracks[index].id == mediaTrackId }
        ?.let { return it }
    return tracks.indexOfFirst { it.id == mediaTrackId }.takeIf { it >= 0 }
}
