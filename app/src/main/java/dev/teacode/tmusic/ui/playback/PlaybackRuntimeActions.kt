package dev.teacode.tmusic.ui

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun handlePlaybackPlayerErrorAction(
    scope: CoroutineScope,
    message: String,
    httpStatusCode: Int?,
    exoPlayer: ExoPlayer,
    getPlayerState: () -> PlayerState,
    setPlayerState: (PlayerState) -> Unit,
    getPlaybackQueue: () -> PlaybackQueue,
    getAccount: () -> Account?,
    getPrefetchedPlaybackUrls: () -> Map<String, String>,
    setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    getPlaybackUrlPrefetchesInProgress: () -> Set<String>,
    setPlaybackUrlPrefetchesInProgress: (Set<String>) -> Unit,
    incrementStreamRequestSerial: () -> Long,
    getStreamRequestSerial: () -> Long,
    clearGaplessPlaybackState: () -> Unit,
    cancelCrossfade: () -> Unit,
    canUseMediaServerRequests: () -> Boolean,
    mediaDisabledMessage: () -> String,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setAccessToken: (String?) -> Unit,
    setPlaybackBufferedFraction: (Float) -> Unit,
    incrementPlaybackStartSerial: () -> Unit,
    incrementRequestedNextPrefetch: () -> Unit,
    disableMediaPlaybackForAccount: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setPlayerError: (String?) -> Unit,
) : Boolean {
    val failedTrack = getPlayerState().currentTrack ?: run {
        setPlayerError(message)
        return false
    }
    if (httpStatusCode != HttpURLConnection.HTTP_FORBIDDEN) {
        setPlayerError(message)
        return false
    }

    setPrefetchedPlaybackUrls(getPrefetchedPlaybackUrls() - failedTrack.id)
    setPlaybackUrlPrefetchesInProgress(getPlaybackUrlPrefetchesInProgress() - failedTrack.id)
    clearGaplessPlaybackState()
    cancelCrossfade()

    val resumePositionMs = runCatching {
        exoPlayer.currentPosition.coerceAtLeast(0L)
    }.getOrDefault(0L)
    val currentQueue = getPlaybackQueue()
    val currentQueueIndex = currentQueue.currentIndex
    val currentStreamRequestSerial = incrementStreamRequestSerial()

    if (!canUseMediaServerRequests()) {
        setPlayerError(
            if (getAccount()?.canPlayMedia == false) {
                mediaDisabledMessage()
            } else {
                "Playback URL is no longer available. Reconnect to refresh playback."
            },
        )
        setPlayerState(
            getPlayerState().copy(
                isPlaying = false,
                progressSeconds = (resumePositionMs / 1000L).toInt().coerceAtLeast(0),
                streamUrl = null,
            ),
        )
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        return true
    }

    setPlayerError(null)
    setPlayerState(
        getPlayerState().copy(
            isPlaying = true,
            progressSeconds = (resumePositionMs / 1000L).toInt().coerceAtLeast(0),
            streamUrl = null,
        ),
    )
    scope.launch {
        runCatching {
            musicRepository.streamUrl(failedTrack.id)
        }.onSuccess { refreshedStreamUrl ->
            if (
                getStreamRequestSerial() == currentStreamRequestSerial &&
                getPlayerState().currentTrack?.id == failedTrack.id &&
                getPlaybackQueue().currentIndex == currentQueueIndex
            ) {
                setAccessToken(authRepository.accessToken())
                setPlaybackBufferedFraction(0f)
                incrementPlaybackStartSerial()
                setPlayerState(
                    PlayerState(
                        currentTrack = failedTrack,
                        isPlaying = true,
                        progressSeconds = (resumePositionMs / 1000L).toInt().coerceAtLeast(0),
                        streamUrl = refreshedStreamUrl,
                    ),
                )
                incrementRequestedNextPrefetch()
            }
        }.onFailure { error ->
            if (
                getStreamRequestSerial() == currentStreamRequestSerial &&
                getPlayerState().currentTrack?.id == failedTrack.id
            ) {
                if (error.isMediaPlaybackDisabledError()) {
                    disableMediaPlaybackForAccount()
                    setPlayerError(mediaDisabledMessage())
                } else {
                    markServerUnavailable(error)
                    setPlayerError(error.userMessage())
                }
                setPlayerState(getPlayerState().copy(isPlaying = false, streamUrl = null))
            }
        }
    }
    return true
}

internal fun startGaplessPlaybackAction(
    track: Track,
    queue: PlaybackQueue,
    urls: List<String>,
    resumePositionMs: Long,
    getActivePlayEvent: () -> ActivePlayEvent?,
    completeActivePlayEvent: (Boolean) -> Unit,
    ensureActivePlayEvent: (Track, Boolean) -> Unit,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    setPlaybackBufferedFraction: (Float) -> Unit,
    setPlayerState: (PlayerState) -> Unit,
    setGaplessPlaybackRequest: (GaplessPlaybackRequest) -> Unit,
    setGaplessMediaQueueIndices: (Map<String, Int>) -> Unit,
    setGaplessMediaUrls: (Map<String, String>) -> Unit,
    loadArtwork: (String, ArtworkImageSize) -> Unit,
) {
    val startIndex = queue.currentIndex
        .takeIf { index -> index in queue.tracks.indices && queue.tracks[index].id == track.id }
        ?: queue.tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }
        ?: queue.currentIndex.coerceAtLeast(0)
    val previousEvent = getActivePlayEvent()
    val isRestart = previousEvent?.trackId == track.id && resumePositionMs == 0L
    if (previousEvent != null && (previousEvent.trackId != track.id || isRestart)) {
        completeActivePlayEvent(true)
    }
    ensureActivePlayEvent(track, isRestart)
    setPlaybackQueue(queue.copy(currentIndex = startIndex))
    setPlaybackBufferedFraction(0f)
    setPlayerState(
        PlayerState(
            currentTrack = track,
            isPlaying = true,
            progressSeconds = (resumePositionMs / 1000L).toInt().coerceAtLeast(0),
            streamUrl = urls[startIndex],
        ),
    )
    val request = GaplessPlaybackRequest(
        queueKey = queue.playlistId
            ?: "${queue.sourceType.name}:${queue.sourceId.orEmpty()}:${queue.sourceTitle.orEmpty()}:${
                queue.tracks.joinToString(",") { it.id }
            }",
        trackIds = queue.tracks.map { it.id },
        urls = urls,
        startIndex = startIndex,
        resumePositionMs = resumePositionMs,
        queueIndices = queue.tracks.indices.toList(),
    )
    setGaplessPlaybackRequest(request)
    setGaplessMediaQueueIndices(request.mediaIds.zip(request.queueIndices).toMap())
    setGaplessMediaUrls(request.mediaIds.zip(request.urls).toMap())
    loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
}

internal fun startPlaybackAction(
    track: Track,
    playbackUrl: String,
    startPositionMs: Long,
    getActivePlayEvent: () -> ActivePlayEvent?,
    completeActivePlayEvent: (Boolean) -> Unit,
    ensureActivePlayEvent: (Track, Boolean) -> Unit,
    getPlaybackQueue: () -> PlaybackQueue,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    incrementPlaybackStartSerial: () -> Unit,
    setPlaybackBufferedFraction: (Float) -> Unit,
    setPlayerState: (PlayerState) -> Unit,
    clearGaplessPlaybackState: () -> Unit,
    loadArtwork: (String, ArtworkImageSize) -> Unit,
) {
    clearGaplessPlaybackState()
    val safeStartPositionMs = startPositionMs.coerceAtLeast(0L)
    val previousEvent = getActivePlayEvent()
    val isRestart = previousEvent?.trackId == track.id && safeStartPositionMs == 0L
    if (previousEvent != null && (previousEvent.trackId != track.id || isRestart)) {
        completeActivePlayEvent(true)
    }
    ensureActivePlayEvent(track, isRestart)
    incrementPlaybackStartSerial()
    val queue = getPlaybackQueue()
    val queueIndex = queue.currentIndex
        .takeIf { index -> index in queue.tracks.indices && queue.tracks[index].id == track.id }
        ?: queue.tracks.indexOfFirst { it.id == track.id }
    if (queueIndex >= 0 && queue.currentIndex != queueIndex) {
        setPlaybackQueue(queue.copy(currentIndex = queueIndex))
    }
    setPlaybackBufferedFraction(0f)
    setPlayerState(
        PlayerState(
            currentTrack = track,
            isPlaying = true,
            progressSeconds = (safeStartPositionMs / 1000L).toInt().coerceAtLeast(0),
            streamUrl = playbackUrl,
        ),
    )
    loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
}

internal fun installGaplessPrefetchAction(
    queue: PlaybackQueue,
    nextTrack: Track,
    nextIndex: Int,
    nextUrl: String,
    exoPlayer: ExoPlayer,
    getPlayerState: () -> PlayerState,
    getGaplessPlaybackRequest: () -> GaplessPlaybackRequest?,
    getGaplessMediaQueueIndices: () -> Map<String, Int>,
    setGaplessMediaQueueIndices: (Map<String, Int>) -> Unit,
    getGaplessMediaUrls: () -> Map<String, String>,
    setGaplessMediaUrls: (Map<String, String>) -> Unit,
) {
    if (!queue.canSkip) {
        return
    }
    val currentTrack = getPlayerState().currentTrack ?: return
    getPlayerState().streamUrl ?: return
    val currentIndex = queue.currentIndex.coerceIn(0, queue.tracks.lastIndex)
    if (queue.tracks.getOrNull(currentIndex)?.id != currentTrack.id) {
        return
    }
    if (nextIndex < 0) {
        return
    }

    fun addPrefetchedMediaItemAhead(): Boolean {
        if (exoPlayer.mediaItemCount <= 0) {
            return false
        }
        val mediaQueueIndices = getGaplessMediaQueueIndices()
        val currentMediaItemIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val alreadyQueuedAhead = ((currentMediaItemIndex + 1) until exoPlayer.mediaItemCount).any { mediaIndex ->
            val queuedMediaId = exoPlayer.getMediaItemAt(mediaIndex).mediaId
            mediaQueueIndices[queuedMediaId] == nextIndex
        }
        if (alreadyQueuedAhead) {
            return true
        }
        val mediaId = "${System.nanoTime()}:$nextIndex:${nextTrack.id}"
        val nextDistance = (nextIndex - currentIndex).floorMod(queue.tracks.size)
        val insertionMediaIndex = ((currentMediaItemIndex + 1) until exoPlayer.mediaItemCount)
            .firstOrNull { mediaIndex ->
                val queuedMediaId = exoPlayer.getMediaItemAt(mediaIndex).mediaId
                val queuedIndex = mediaQueueIndices[queuedMediaId] ?: return@firstOrNull false
                val queuedDistance = (queuedIndex - currentIndex).floorMod(queue.tracks.size)
                queuedDistance > nextDistance
            }
            ?: exoPlayer.mediaItemCount
        exoPlayer.addMediaItem(
            insertionMediaIndex,
            MediaItem.Builder()
                .setUri(nextUrl)
                .setMediaId(mediaId)
                .build(),
        )
        setGaplessMediaQueueIndices(getGaplessMediaQueueIndices() + (mediaId to nextIndex))
        setGaplessMediaUrls(getGaplessMediaUrls() + (mediaId to nextUrl))
        return true
    }

    val existingRequest = getGaplessPlaybackRequest()
    if (existingRequest != null) {
        if (existingRequest.queueIndices == queue.tracks.indices.toList()) {
            return
        }
        if (addPrefetchedMediaItemAhead()) {
            return
        }
    } else if (addPrefetchedMediaItemAhead()) {
        return
    }
}

internal fun prefetchNextTrackUrlAction(
    scope: CoroutineScope,
    queue: PlaybackQueue,
    getAccount: () -> Account?,
    getRepeatMode: () -> PlaybackRepeatMode,
    getPrefetchedPlaybackUrls: () -> Map<String, String>,
    setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    getPlaybackUrlPrefetchesInProgress: () -> Set<String>,
    setPlaybackUrlPrefetchesInProgress: (Set<String>) -> Unit,
    prefetchTrackAssets: (Track) -> Unit,
    localOrCachedPlaybackUrl: (Track) -> String?,
    installGaplessPrefetch: (PlaybackQueue, Track, Int, String) -> Unit,
    canUseMediaServerRequests: () -> Boolean,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setAccessToken: (String?) -> Unit,
) {
    if (!queue.canSkip || getAccount()?.canPlayMedia == false) {
        return
    }

    val currentIndex = queue.currentIndex.coerceIn(0, queue.tracks.lastIndex)
    val queuedIndices = linkedSetOf<Int>()
    for (step in 1..GAPLESS_PREFETCH_LOOKAHEAD) {
        val rawIndex = currentIndex + step
        val nextIndex = when {
            rawIndex < queue.tracks.size -> rawIndex
            getRepeatMode() == PlaybackRepeatMode.Queue -> rawIndex % queue.tracks.size
            else -> break
        }
        if (!queuedIndices.add(nextIndex)) {
            break
        }
        val nextTrack = queue.tracks[nextIndex]
        prefetchTrackAssets(nextTrack)
        val cachedUrl = localOrCachedPlaybackUrl(nextTrack)
        val prefetchedUrl = getPrefetchedPlaybackUrls()[nextTrack.id]
        when {
            cachedUrl != null -> installGaplessPrefetch(queue, nextTrack, nextIndex, cachedUrl)
            prefetchedUrl != null -> installGaplessPrefetch(queue, nextTrack, nextIndex, prefetchedUrl)
            !canUseMediaServerRequests() || nextTrack.id in getPlaybackUrlPrefetchesInProgress() -> Unit
            else -> {
                setPlaybackUrlPrefetchesInProgress(getPlaybackUrlPrefetchesInProgress() + nextTrack.id)
                scope.launch {
                    runCatching {
                        musicRepository.streamUrl(nextTrack.id)
                    }.onSuccess { streamUrl ->
                        setPrefetchedPlaybackUrls(getPrefetchedPlaybackUrls() + (nextTrack.id to streamUrl))
                        setAccessToken(authRepository.accessToken())
                        installGaplessPrefetch(queue, nextTrack, nextIndex, streamUrl)
                    }
                    setPlaybackUrlPrefetchesInProgress(getPlaybackUrlPrefetchesInProgress() - nextTrack.id)
                }
            }
        }
    }
}

internal fun beginPreparedCrossfadeAction(
    scope: CoroutineScope,
    prepared: PreparedCrossfade,
    fadeDurationMs: Long,
    getCrossfadeJob: () -> Job?,
    setCrossfadeJob: (Job?) -> Unit,
    getExoPlayer: () -> ExoPlayer,
    setExoPlayer: (ExoPlayer) -> Unit,
    getPlaybackQueueGeneration: () -> Long,
    getPlaybackQueue: () -> PlaybackQueue,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    setPlayerState: (PlayerState) -> Unit,
    getActivePlayEvent: () -> ActivePlayEvent?,
    completeActivePlayEvent: (Boolean) -> Unit,
    ensureActivePlayEvent: (Track, Boolean) -> Unit,
    clearGaplessPlaybackState: () -> Unit,
    setGaplessMediaQueueIndices: (Map<String, Int>) -> Unit,
    setGaplessMediaUrls: (Map<String, String>) -> Unit,
    setPendingTransitionArtworkTrackId: (String?) -> Unit,
    setPreparedCrossfade: (PreparedCrossfade?) -> Unit,
    incrementRequestedNextPrefetch: () -> Unit,
    incrementCrossfadePreparationSerial: () -> Unit,
) {
    if (
        getCrossfadeJob()?.isActive == true ||
        prepared.player === getExoPlayer() ||
        prepared.queueGeneration != getPlaybackQueueGeneration()
    ) {
        return
    }
    val queue = getPlaybackQueue()
    val nextTrack = queue.tracks.getOrNull(prepared.queueIndex) ?: return
    val fromPlayer = getExoPlayer()
    val toPlayer = prepared.player
    setCrossfadeJob(
        scope.launch {
            var completed = false
            try {
                completed = performCrossfade(
                    fromPlayer = fromPlayer,
                    toPlayer = toPlayer,
                    fadeDurationMs = fadeDurationMs,
                    onOverlapStarted = {
                        val previousEvent = getActivePlayEvent()
                        completeActivePlayEvent(true)
                        clearGaplessPlaybackState()
                        setGaplessMediaQueueIndices(mapOf(prepared.mediaId to prepared.queueIndex))
                        setGaplessMediaUrls(mapOf(prepared.mediaId to prepared.url))
                        setPlaybackQueue(queue.copy(currentIndex = prepared.queueIndex))
                        setPlayerState(
                            PlayerState(
                                currentTrack = nextTrack,
                                isPlaying = true,
                                progressSeconds = 0,
                                streamUrl = prepared.url,
                            ),
                        )
                        ensureActivePlayEvent(nextTrack, previousEvent?.trackId == nextTrack.id)
                        setPendingTransitionArtworkTrackId(nextTrack.listArtworkKey())
                        setPreparedCrossfade(null)
                        setExoPlayer(toPlayer)
                    },
                )
                if (completed) {
                    incrementRequestedNextPrefetch()
                }
            } finally {
                if (!completed) {
                    fromPlayer.volume = if (fromPlayer === getExoPlayer()) 1f else 0f
                    toPlayer.volume = if (toPlayer === getExoPlayer()) 1f else 0f
                    if (fromPlayer !== getExoPlayer()) {
                        fromPlayer.stop()
                        fromPlayer.clearMediaItems()
                    }
                    if (toPlayer !== getExoPlayer()) {
                        toPlayer.stop()
                        toPlayer.clearMediaItems()
                    }
                    getExoPlayer().configurePlaybackAudioFocus(handleAudioFocus = true)
                }
                setCrossfadeJob(null)
                incrementCrossfadePreparationSerial()
            }
        },
    )
}

internal fun seekPreparedQueueMediaItemAction(
    targetIndex: Int,
    direction: Int,
    exoPlayer: ExoPlayer,
    getPlaybackQueue: () -> PlaybackQueue,
    getActivePlayEvent: () -> ActivePlayEvent?,
    completeActivePlayEvent: (Boolean) -> Unit,
    ensureActivePlayEvent: (Track, Boolean) -> Unit,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    setPlayerState: (PlayerState) -> Unit,
    getGaplessMediaQueueIndices: () -> Map<String, Int>,
    getGaplessMediaUrls: () -> Map<String, String>,
    getPrefetchedPlaybackUrls: () -> Map<String, String>,
    localOrCachedPlaybackUrl: (Track) -> String?,
    setPendingTransitionArtworkTrackId: (String?) -> Unit,
    setPlaybackBufferedFraction: (Float) -> Unit,
    incrementRequestedNextPrefetch: () -> Unit,
): Boolean {
    val queue = getPlaybackQueue()
    val targetTrack = queue.tracks.getOrNull(targetIndex) ?: return false
    if (exoPlayer.mediaItemCount <= 0) {
        return false
    }
    val targetMediaIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { mediaIndex ->
        val mediaId = exoPlayer.getMediaItemAt(mediaIndex).mediaId
        getGaplessMediaQueueIndices()[mediaId] == targetIndex
    } ?: return false
    val targetMediaId = exoPlayer.getMediaItemAt(targetMediaIndex).mediaId
    val targetUrl = getGaplessMediaUrls()[targetMediaId]
        ?: getPrefetchedPlaybackUrls()[targetTrack.id]
        ?: localOrCachedPlaybackUrl(targetTrack)
        ?: return false
    logPlaybackDebug(
        "prepared seek targetIndex=$targetIndex target=${targetTrack.debugTrack()} " +
            "mediaIndex=$targetMediaIndex mediaId=$targetMediaId playerMediaIndex=${exoPlayer.currentMediaItemIndex} " +
            queue.debugSummary(),
    )
    setPlaybackBufferedFraction(0f)
    exoPlayer.seekTo(targetMediaIndex, 0L)
    exoPlayer.play()
    val previousEvent = getActivePlayEvent()
    completeActivePlayEvent(true)
    val nextQueue = queue.copy(currentIndex = targetIndex)
    setPlaybackQueue(nextQueue)
    setPlayerState(
        PlayerState(
            currentTrack = targetTrack,
            isPlaying = true,
            progressSeconds = 0,
            streamUrl = targetUrl,
        ),
    )
    ensureActivePlayEvent(targetTrack, previousEvent?.trackId == targetTrack.id)
    setPendingTransitionArtworkTrackId(targetTrack.listArtworkKey())
    incrementRequestedNextPrefetch()
    return true
}

internal fun enforceOfflinePlaybackAvailabilityAction(
    exoPlayer: ExoPlayer,
    getPlayerState: () -> PlayerState,
    setPlayerState: (PlayerState) -> Unit,
    cancelCrossfade: () -> Unit,
    localOrCachedPlaybackUrl: (Track) -> String?,
    clearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    getActivePlayEvent: () -> ActivePlayEvent?,
    clearGaplessPlaybackState: () -> Unit,
    incrementPlaybackStartSerial: () -> Unit,
) {
    cancelCrossfade()
    val playerState = getPlayerState()
    val currentTrack = playerState.currentTrack ?: return
    val currentStreamUrl = playerState.streamUrl ?: return
    val localPlaybackUrl = localOrCachedPlaybackUrl(currentTrack)
    val currentPositionSeconds = runCatching {
        (exoPlayer.currentPosition / 1000L).toInt().coerceAtLeast(0)
    }.getOrDefault(playerState.progressSeconds)
    if (localPlaybackUrl == null) {
        clearNowPlayingEvent(getActivePlayEvent())
        clearGaplessPlaybackState()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        setPlayerState(
            playerState.copy(
                isPlaying = false,
                progressSeconds = currentPositionSeconds,
                streamUrl = null,
            ),
        )
        return
    }
    if (currentStreamUrl != localPlaybackUrl && !currentStreamUrl.startsWith("file:", ignoreCase = true)) {
        clearGaplessPlaybackState()
        incrementPlaybackStartSerial()
        setPlayerState(
            playerState.copy(
                progressSeconds = currentPositionSeconds,
                streamUrl = localPlaybackUrl,
            ),
        )
    }
}

internal fun applyPlaybackQueueOrderWithoutInterruptAction(
    nextQueue: PlaybackQueue,
    exoPlayer: ExoPlayer,
    getPlayerState: () -> PlayerState,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    setGaplessPlaybackRequest: (GaplessPlaybackRequest?) -> Unit,
    setGaplessMediaQueueIndices: (Map<String, Int>) -> Unit,
    setGaplessMediaUrls: (Map<String, String>) -> Unit,
    prefetchNextTrackUrl: (PlaybackQueue) -> Unit,
) {
    val currentMediaIndex = exoPlayer.currentMediaItemIndex
    val currentMediaItem = exoPlayer.currentMediaItem
    val currentMediaId = currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
    val currentMediaUrl = currentMediaItem?.localConfiguration?.uri?.toString()
        ?: getPlayerState().streamUrl
    val currentTrackId = getPlayerState().currentTrack?.id
    val resolvedCurrentIndex = nextQueue.currentIndex
        .takeIf { index -> index in nextQueue.tracks.indices && nextQueue.tracks[index].id == currentTrackId }
        ?: currentTrackId
            ?.let { trackId -> nextQueue.tracks.indexOfFirst { it.id == trackId } }
            ?.takeIf { it >= 0 }
        ?: nextQueue.currentIndex
    val normalizedQueue = nextQueue.copy(currentIndex = resolvedCurrentIndex)
    if (
        currentMediaItem != null &&
        currentMediaIndex >= 0 &&
        currentMediaIndex + 1 < exoPlayer.mediaItemCount
    ) {
        exoPlayer.removeMediaItems(currentMediaIndex + 1, exoPlayer.mediaItemCount)
    }
    setGaplessPlaybackRequest(null)
    setGaplessMediaQueueIndices(currentMediaId?.let { mediaId -> mapOf(mediaId to normalizedQueue.currentIndex) }.orEmpty())
    setGaplessMediaUrls(
        if (currentMediaId != null && currentMediaUrl != null) {
            mapOf(currentMediaId to currentMediaUrl)
        } else {
            emptyMap()
        },
    )
    setPlaybackQueue(normalizedQueue)
    if (getPlayerState().currentTrack != null && getPlayerState().streamUrl != null && normalizedQueue.canSkip) {
        prefetchNextTrackUrl(normalizedQueue)
    }
}

internal fun togglePlaybackAction(
    exoPlayer: ExoPlayer,
    getPlayerState: () -> PlayerState,
    setPlayerState: (PlayerState) -> Unit,
    getPlaybackQueue: () -> PlaybackQueue,
    getCrossfadeJob: () -> Job?,
    getPreparedCrossfade: () -> PreparedCrossfade?,
    cancelCrossfade: () -> Unit,
    playQueuedTrack: (Track, PlaybackQueue, Long, Boolean) -> Unit,
    ensureActivePlayEvent: (Track) -> Unit,
    clearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    getActivePlayEvent: () -> ActivePlayEvent?,
) {
    if (getCrossfadeJob()?.isActive == true || getPreparedCrossfade() != null) {
        cancelCrossfade()
    }
    val playerState = getPlayerState()
    val currentTrack = playerState.currentTrack
    if (!playerState.isPlaying && playerState.streamUrl == null && currentTrack != null) {
        val resumePositionMs = playerState.progressSeconds.toLong().coerceAtLeast(0L) * 1000L
        playQueuedTrack(
            currentTrack,
            getPlaybackQueue().takeIf { it.tracks.isNotEmpty() }
                ?: PlaybackQueue(tracks = listOf(currentTrack), currentIndex = 0),
            resumePositionMs,
            resumePositionMs > 0L,
        )
        return
    }

    val nextIsPlaying = !playerState.isPlaying
    val progressSeconds = if (playerState.isPlaying && playerState.streamUrl != null) {
        (exoPlayer.currentPosition / 1000L).toInt().coerceAtLeast(0)
    } else {
        playerState.progressSeconds
    }
    if (nextIsPlaying && currentTrack != null) {
        ensureActivePlayEvent(currentTrack)
    } else if (!nextIsPlaying) {
        clearNowPlayingEvent(getActivePlayEvent())
    }
    setPlayerState(
        playerState.copy(
            isPlaying = nextIsPlaying,
            progressSeconds = progressSeconds,
        ),
    )
}
