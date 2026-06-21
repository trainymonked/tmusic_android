package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.cache.SimpleCache
import dev.teacode.tmusic.data.SavedPlaybackState
import dev.teacode.tmusic.domain.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@Composable
internal fun PlaybackEffects(
    exoPlayer: ExoPlayer,
    primaryExoPlayer: ExoPlayer,
    secondaryExoPlayer: ExoPlayer,
    mediaCache: SimpleCache,
    crossfadeJob: Job?,
    playerState: PlayerState,
    setPlayerState: (PlayerState) -> Unit,
    playerStateState: State<PlayerState>,
    playbackQueue: PlaybackQueue,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    playbackQueueState: State<PlaybackQueue>,
    repeatMode: PlaybackRepeatMode,
    repeatModeState: State<PlaybackRepeatMode>,
    gaplessPlaybackRequest: GaplessPlaybackRequest?,
    setGaplessPlaybackRequest: (GaplessPlaybackRequest?) -> Unit,
    gaplessPlaybackRequestState: State<GaplessPlaybackRequest?>,
    gaplessMediaQueueIndices: Map<String, Int>,
    setGaplessMediaQueueIndices: (Map<String, Int>) -> Unit,
    gaplessMediaQueueIndicesState: State<Map<String, Int>>,
    gaplessMediaUrls: Map<String, String>,
    setGaplessMediaUrls: (Map<String, String>) -> Unit,
    gaplessMediaUrlsState: State<Map<String, String>>,
    activePlayEventState: MutableState<ActivePlayEvent?>,
    playbackStartSerial: Long,
    pendingPlaybackRestore: SavedPlaybackState?,
    onBufferedFractionChanged: (Float) -> Unit,
    onCompleteActivePlayEvent: () -> Unit,
    onRequestCurrentTrackRestart: () -> Unit,
    onRequestQueueAdvance: () -> Unit,
    onRequestQueueWrapPause: () -> Unit,
    onQueueTransition: (PlaybackQueue, PlayerState, Int) -> Unit,
    onEnsureActivePlayEvent: (dev.teacode.tmusic.domain.Track, Boolean) -> Unit,
    onClearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    onRequestNextPrefetch: () -> Unit,
    onPlayerError: (String, Int?) -> Boolean,
    onDisposePlayer: () -> Unit,
    savePlaybackSnapshot: () -> Unit,
    savePlaybackRuntimeSnapshot: () -> Unit,
) {
    PlaybackPlayerListenerEffect(
        player = exoPlayer,
        isCurrentPlayer = { observedPlayer -> observedPlayer === exoPlayer },
        isCrossfadeActive = { crossfadeJob?.isActive == true },
        playerState = { playerStateState.value },
        playbackQueue = { playbackQueueState.value },
        repeatMode = { repeatModeState.value },
        gaplessPlaybackRequest = { gaplessPlaybackRequestState.value },
        gaplessMediaQueueIndices = { gaplessMediaQueueIndicesState.value },
        gaplessMediaUrls = { gaplessMediaUrlsState.value },
        activePlayEvent = { activePlayEventState.value },
        onBufferedFractionChanged = onBufferedFractionChanged,
        onCompleteActivePlayEvent = onCompleteActivePlayEvent,
        onRequestCurrentTrackRestart = onRequestCurrentTrackRestart,
        onRequestQueueAdvance = onRequestQueueAdvance,
        onRequestQueueWrapPause = onRequestQueueWrapPause,
        onPlayerStateChanged = setPlayerState,
        onQueueTransition = onQueueTransition,
        onEnsureActivePlayEvent = onEnsureActivePlayEvent,
        onClearNowPlayingEvent = onClearNowPlayingEvent,
        onRequestNextPrefetch = onRequestNextPrefetch,
        onPlayerError = onPlayerError,
        onDisposePlayer = onDisposePlayer,
    )

    DisposableEffect(primaryExoPlayer, secondaryExoPlayer, mediaCache) {
        onDispose {
            crossfadeJob?.cancel()
            primaryExoPlayer.release()
            secondaryExoPlayer.release()
            mediaCache.release()
        }
    }

    LaunchedEffect(
        playerState.currentTrack?.id,
        playerState.streamUrl,
        playbackStartSerial,
        gaplessPlaybackRequest?.signature,
        playbackQueue.currentIndex,
        playbackQueue.tracks.getOrNull(playbackQueue.currentIndex)?.id,
    ) {
        val queuedTrackId = playbackQueue.tracks.getOrNull(playbackQueue.currentIndex)?.id
        val stateTrackId = playerState.currentTrack?.id
        if (queuedTrackId != null && stateTrackId != null && queuedTrackId != stateTrackId) {
            logPlaybackDebug(
                "playback effect ignored stale state player=$stateTrackId queue=$queuedTrackId " +
                    playbackQueue.debugSummary(),
            )
            return@LaunchedEffect
        }
        if (gaplessPlaybackRequest != null) {
            return@LaunchedEffect
        }
        val streamUrl = playerState.streamUrl
        if (streamUrl == null) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = false)
        } else {
            val currentMediaItem = exoPlayer.currentMediaItem
            val currentMediaId = currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
            val currentQueuedIndex = currentMediaId?.let { mediaId -> gaplessMediaQueueIndices[mediaId] }
            val currentQueuedTrack = currentQueuedIndex?.let { index -> playbackQueue.tracks.getOrNull(index) }
            val currentMediaUri = currentMediaItem?.localConfiguration?.uri?.toString()
            val currentTrack = playerState.currentTrack
            val mediaQueueIndex = currentTrack?.let { track ->
                playbackQueue.currentIndex
                    .takeIf { index -> index in playbackQueue.tracks.indices && playbackQueue.tracks[index].id == track.id }
                    ?: playbackQueue.tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }
            }
            if (
                currentMediaItem != null &&
                currentMediaUri == streamUrl &&
                exoPlayer.playbackState != Player.STATE_ENDED
            ) {
                if (
                    currentMediaId != null &&
                    mediaQueueIndex != null &&
                    gaplessMediaQueueIndices[currentMediaId] != mediaQueueIndex
                ) {
                    setGaplessMediaQueueIndices(gaplessMediaQueueIndices + (currentMediaId to mediaQueueIndex))
                    setGaplessMediaUrls(gaplessMediaUrls + (currentMediaId to streamUrl))
                }
                exoPlayer.playbackParameters = PlaybackParameters(1f, 1f)
                exoPlayer.setSkipSilenceEnabled(false)
                exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = false)
                exoPlayer.shuffleModeEnabled = false
                if (playerState.isPlaying) {
                    exoPlayer.play()
                } else {
                    exoPlayer.pause()
                }
                onRequestNextPrefetch()
                return@LaunchedEffect
            }
            val preparedTargetMediaIndex = currentTrack?.let { track ->
                (0 until exoPlayer.mediaItemCount).firstOrNull { mediaIndex ->
                    val mediaItem = exoPlayer.getMediaItemAt(mediaIndex)
                    val mediaId = mediaItem.mediaId.takeIf { it.isNotBlank() }
                    val queuedIndex = mediaId?.let { gaplessMediaQueueIndices[it] }
                    queuedIndex != null &&
                        queuedIndex == playbackQueue.currentIndex &&
                        playbackQueue.tracks.getOrNull(queuedIndex)?.id == track.id &&
                        mediaItem.localConfiguration?.uri?.toString() == streamUrl
                }
            }
            if (
                currentQueuedIndex == playbackQueue.currentIndex &&
                currentQueuedTrack?.id == playerState.currentTrack?.id &&
                currentMediaUri == streamUrl
            ) {
                exoPlayer.playbackParameters = PlaybackParameters(1f, 1f)
                exoPlayer.setSkipSilenceEnabled(false)
                exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = false)
                exoPlayer.shuffleModeEnabled = false
                if (playerState.isPlaying) {
                    exoPlayer.play()
                } else {
                    exoPlayer.pause()
                }
                onRequestNextPrefetch()
                return@LaunchedEffect
            }
            if (preparedTargetMediaIndex != null) {
                exoPlayer.playbackParameters = PlaybackParameters(1f, 1f)
                exoPlayer.setSkipSilenceEnabled(false)
                exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = false)
                exoPlayer.shuffleModeEnabled = false
                if (playerState.isPlaying) {
                    exoPlayer.play()
                } else {
                    exoPlayer.pause()
                }
                onRequestNextPrefetch()
                return@LaunchedEffect
            }
            logPlaybackDebug(
                "playback effect rebuild target=${currentTrack?.debugTrack()} queueIndex=${playbackQueue.currentIndex} " +
                    "currentMediaUri=$currentMediaUri targetUri=$streamUrl currentMediaId=$currentMediaId " +
                    playbackQueue.debugSummary(),
            )
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = false)
            exoPlayer.playbackParameters = PlaybackParameters(1f, 1f)
            exoPlayer.setSkipSilenceEnabled(false)
            val mediaId = if (currentTrack != null && mediaQueueIndex != null) {
                "${System.nanoTime()}:$mediaQueueIndex:${currentTrack.id}"
            } else {
                null
            }
            exoPlayer.setMediaItem(
                if (mediaId != null) {
                    MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaId(mediaId)
                        .build()
                } else {
                    MediaItem.fromUri(streamUrl)
                },
            )
            if (mediaId != null && mediaQueueIndex != null) {
                setGaplessMediaQueueIndices(mapOf(mediaId to mediaQueueIndex))
                setGaplessMediaUrls(mapOf(mediaId to streamUrl))
            }
            exoPlayer.prepare()
            exoPlayer.seekTo(playerState.progressSeconds.toLong().coerceAtLeast(0L) * 1000L)
            if (playerState.isPlaying) {
                exoPlayer.play()
            }
            if (playbackQueue.canSkip) {
                onRequestNextPrefetch()
            }
        }
    }

    LaunchedEffect(gaplessPlaybackRequest?.signature) {
        val request = gaplessPlaybackRequest ?: return@LaunchedEffect
        val hasCompleteGaplessQueue = request.trackIds == playbackQueue.tracks.map { it.id } &&
            request.queueIndices == playbackQueue.tracks.indices.toList()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.playbackParameters = PlaybackParameters(1f, 1f)
        exoPlayer.setSkipSilenceEnabled(false)
        exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = hasCompleteGaplessQueue)
        exoPlayer.shuffleModeEnabled = false
        exoPlayer.setMediaItems(
            request.urls.mapIndexed { index, url ->
                MediaItem.Builder()
                    .setUri(url)
                    .setMediaId(request.mediaIds[index])
                    .build()
            },
            request.startIndex,
            request.resumePositionMs.coerceAtLeast(0L),
        )
        exoPlayer.prepare()
        if (playerState.isPlaying) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(repeatMode, gaplessPlaybackRequest?.signature) {
        val request = gaplessPlaybackRequest
        val hasCompleteGaplessQueue = request != null &&
            request.trackIds == playbackQueue.tracks.map { it.id } &&
            request.queueIndices == playbackQueue.tracks.indices.toList()
        exoPlayer.repeatMode = desiredExoRepeatMode(
            mode = repeatMode,
            hasGaplessQueue = hasCompleteGaplessQueue,
        )
        exoPlayer.shuffleModeEnabled = false
    }

    LaunchedEffect(playerState.isPlaying, playerState.streamUrl) {
        if (playerState.streamUrl != null) {
            if (playerState.isPlaying) {
                exoPlayer.play()
            } else {
                exoPlayer.pause()
            }
        }
    }

    LaunchedEffect(playerState.currentTrack?.id, playerState.streamUrl, playerState.isPlaying) {
        val observedTrackId = playerState.currentTrack?.id
        val observedStreamUrl = playerState.streamUrl
        var lastTickMs = System.currentTimeMillis()
        while (
            observedTrackId != null &&
            observedStreamUrl != null
        ) {
            val latestState = playerStateState.value
            if (
                latestState.currentTrack?.id != observedTrackId ||
                latestState.streamUrl != observedStreamUrl ||
                !latestState.isPlaying
            ) {
                break
            }
            delay(1_000)
            val stateAfterDelay = playerStateState.value
            if (
                stateAfterDelay.currentTrack?.id != observedTrackId ||
                stateAfterDelay.streamUrl != observedStreamUrl ||
                !stateAfterDelay.isPlaying
            ) {
                break
            }
            val nowMs = System.currentTimeMillis()
            val elapsedMs = (nowMs - lastTickMs).coerceAtLeast(0L)
            lastTickMs = nowMs
            val currentTrack = stateAfterDelay.currentTrack
            setPlayerState(
                stateAfterDelay.copy(
                    progressSeconds = (exoPlayer.currentPosition / 1000).toInt().coerceAtLeast(0),
                ),
            )
            onBufferedFractionChanged(exoPlayer.bufferedPercentage.coerceIn(0, 100) / 100f)
            if (exoPlayer.isPlaying) {
                val activeEvent = activePlayEventState.value
                if (activeEvent?.trackId == currentTrack.id) {
                    activePlayEventState.value = activeEvent.copy(
                        durationPlayedMs = activeEvent.durationPlayedMs + elapsedMs,
                    )
                }
            }
        }
    }

    val activePlayEvent = activePlayEventState.value
    LaunchedEffect(
        playerState.currentTrack?.id,
        playerState.isPlaying,
        playbackQueue.playlistId,
        playbackQueue.sourceType,
        playbackQueue.sourceId,
        playbackQueue.sourceTitle,
        playbackQueue.tracks.map { it.id },
        playbackQueue.sourceTracks.map { it.id },
        playbackQueue.normalizedManualQueueFlags(),
        playbackQueue.isShuffled,
        playbackQueue.currentIndex,
        activePlayEvent?.clientEventId,
        activePlayEvent?.playedAt,
    ) {
        if (pendingPlaybackRestore != null) {
            return@LaunchedEffect
        }
        savePlaybackSnapshot()
    }

    LaunchedEffect(
        playerState.currentTrack?.id,
        playerState.streamUrl,
        playerState.isPlaying,
        activePlayEvent?.clientEventId,
    ) {
        val observedTrackId = playerState.currentTrack?.id ?: return@LaunchedEffect
        while (
            playerStateState.value.currentTrack?.id == observedTrackId &&
            pendingPlaybackRestore == null
        ) {
            delay(15_000)
            val latestState = playerStateState.value
            if (latestState.currentTrack?.id != observedTrackId) {
                break
            }
            savePlaybackRuntimeSnapshot()
        }
    }
}
