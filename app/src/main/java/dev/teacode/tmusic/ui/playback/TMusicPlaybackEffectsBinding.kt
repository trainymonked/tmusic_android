package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.SavedPlaybackState
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.Job

@Composable
internal fun TMusicPlaybackEffectsBinding(
    appState: TMusicAppMutableState,
    exoPlayer: ExoPlayer,
    primaryExoPlayer: ExoPlayer,
    secondaryExoPlayer: ExoPlayer,
    standbyExoPlayer: ExoPlayer,
    mediaCache: SimpleCache,
    playerStateState: State<PlayerState>,
    playbackQueueState: State<PlaybackQueue>,
    gaplessPlaybackRequestState: State<GaplessPlaybackRequest?>,
    gaplessMediaQueueIndicesState: State<Map<String, Int>>,
    gaplessMediaUrlsState: State<Map<String, String>>,
    repeatModeState: State<PlaybackRepeatMode>,
    currentTrackFavorite: Boolean,
    currentArtworkBitmap: ImageBitmap?,
    onCompleteActivePlayEvent: (Boolean) -> Unit,
    onEnsureActivePlayEvent: (Track, Boolean) -> Unit,
    onClearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    onPlaybackPlayerError: (String, Int?) -> Boolean,
    savePlaybackSnapshot: (PlayerState, PlaybackQueue) -> Unit,
    savePlaybackRuntimeSnapshot: (PlayerState, PlaybackQueue) -> Unit,
    nextCrossfadeQueueIndex: (PlaybackQueue) -> Int?,
    localOrCachedPlaybackUrl: (Track) -> String?,
    prefetchNextTrackUrl: (PlaybackQueue) -> Unit,
    beginPreparedCrossfade: (PreparedCrossfade, Long) -> Unit,
    skipInQueue: (Int, Boolean) -> Unit,
    pauseAtQueueStart: () -> Unit,
    playQueuedTrack: (Track, PlaybackQueue, Long, Int?, Boolean, Boolean, Set<Int>, Int) -> Unit,
    loadArtwork: (String, ArtworkImageSize) -> Unit,
    togglePlayback: () -> Unit,
    seekTo: (Int) -> Unit,
    toggleFavoriteTrack: (Track) -> Unit,
    enforceOfflinePlaybackAvailability: () -> Unit,
) {
    PlaybackPersistenceEffect {
        savePlaybackSnapshot(
            playerStateState.value,
            playbackQueueState.value,
        )
    }

    PlaybackEffects(
        exoPlayer = exoPlayer,
        primaryExoPlayer = primaryExoPlayer,
        secondaryExoPlayer = secondaryExoPlayer,
        mediaCache = mediaCache,
        crossfadeJob = appState.crossfadeJob,
        playerState = appState.playerState,
        setPlayerState = { appState.playerState = it },
        playerStateState = playerStateState,
        playbackQueue = appState.playbackQueue,
        setPlaybackQueue = { appState.playbackQueue = it },
        playbackQueueState = playbackQueueState,
        repeatMode = appState.repeatMode,
        repeatModeState = repeatModeState,
        gaplessPlaybackRequest = appState.gaplessPlaybackRequest,
        setGaplessPlaybackRequest = { appState.gaplessPlaybackRequest = it },
        gaplessPlaybackRequestState = gaplessPlaybackRequestState,
        gaplessMediaQueueIndices = appState.gaplessMediaQueueIndices,
        setGaplessMediaQueueIndices = { appState.gaplessMediaQueueIndices = it },
        gaplessMediaQueueIndicesState = gaplessMediaQueueIndicesState,
        gaplessMediaUrls = appState.gaplessMediaUrls,
        setGaplessMediaUrls = { appState.gaplessMediaUrls = it },
        gaplessMediaUrlsState = gaplessMediaUrlsState,
        activePlayEventState = appState.activePlayEventState,
        playbackStartSerial = appState.playbackStartSerial,
        pendingPlaybackRestore = appState.pendingPlaybackRestore,
        onBufferedFractionChanged = { appState.playbackBufferedFraction = it },
        onCompleteActivePlayEvent = { onCompleteActivePlayEvent(true) },
        onRequestCurrentTrackRestart = { appState.requestedCurrentTrackRestart += 1 },
        onRequestQueueAdvance = { appState.requestedQueueAdvance += 1 },
        onRequestQueueWrapPause = { appState.requestedQueueWrapPause += 1 },
        onQueueTransition = { nextQueue, nextState, direction ->
            logPlaybackDebug(
                "queue transition direction=$direction state=${nextState.currentTrack?.debugTrack()} " +
                    nextQueue.debugSummary(),
            )
            appState.playbackQueue = nextQueue
            appState.playerState = nextState
            appState.pendingTransitionArtworkTrackId = nextState.currentTrack?.listArtworkKey()
        },
        onEnsureActivePlayEvent = onEnsureActivePlayEvent,
        onClearNowPlayingEvent = onClearNowPlayingEvent,
        onRequestNextPrefetch = { appState.requestedNextPrefetch += 1 },
        onPlayerError = onPlaybackPlayerError,
        onDisposePlayer = {
            savePlaybackSnapshot(
                playerStateState.value,
                playbackQueueState.value,
            )
        },
        savePlaybackSnapshot = {
            savePlaybackSnapshot(
                playerStateState.value,
                playbackQueueState.value,
            )
        },
        savePlaybackRuntimeSnapshot = {
            savePlaybackRuntimeSnapshot(
                playerStateState.value,
                playbackQueueState.value,
            )
        },
    )

    CrossfadePreparationEffects(
        exoPlayer = exoPlayer,
        standbyExoPlayer = standbyExoPlayer,
        playerState = appState.playerState,
        playbackQueue = appState.playbackQueue,
        playbackQueueGeneration = appState.playbackQueueGeneration,
        repeatMode = appState.repeatMode,
        crossfadeSeconds = appState.crossfadeSeconds,
        prefetchedPlaybackUrls = appState.prefetchedPlaybackUrls,
        crossfadePreparationSerial = appState.crossfadePreparationSerial,
        crossfadeJob = appState.crossfadeJob,
        preparedCrossfade = appState.preparedCrossfade,
        setPreparedCrossfade = { appState.preparedCrossfade = it },
        nextCrossfadeQueueIndex = nextCrossfadeQueueIndex,
        localOrCachedPlaybackUrl = localOrCachedPlaybackUrl,
        prefetchNextTrackUrl = prefetchNextTrackUrl,
        beginPreparedCrossfade = beginPreparedCrossfade,
    )

    NextTrackPrefetchEffect(
        requestedNextPrefetch = appState.requestedNextPrefetch,
        playbackQueue = appState.playbackQueue,
        prefetchNextTrackUrl = prefetchNextTrackUrl,
    )

    QueueInsertionAnchorResetEffect(
        currentTrackId = appState.playerState.currentTrack?.id,
        clearQueueInsertionAnchor = {
            appState.queueInsertionAnchorTrackId = null
            appState.queueInsertionCursor = null
        },
    )

    PlaybackSystemIntegration(
        playerState = appState.playerState,
        artworkBitmap = currentArtworkBitmap,
        isFavorite = currentTrackFavorite,
        canSkip = appState.playbackQueue.canSkip,
        onPlay = {
            if (!appState.playerState.isPlaying) {
                togglePlayback()
            }
        },
        onPause = {
            if (appState.playerState.isPlaying) {
                togglePlayback()
            }
        },
        onPrevious = {
            if (appState.playbackQueue.canSkip) {
                skipInQueue(-1, true)
            }
        },
        onNext = {
            if (appState.playbackQueue.canSkip) {
                skipInQueue(1, true)
            }
        },
        onSeek = { positionMs -> seekTo((positionMs / 1000L).toInt()) },
        onToggleFavorite = {
            appState.playerState.currentTrack?.let(toggleFavoriteTrack)
        },
    )

    QueueRequestEffects(
        requestedQueueAdvance = appState.requestedQueueAdvance,
        requestedQueueWrapPause = appState.requestedQueueWrapPause,
        requestedCurrentTrackRestart = appState.requestedCurrentTrackRestart,
        playerState = appState.playerState,
        playbackQueue = appState.playbackQueue,
        skipNext = { skipInQueue(1, true) },
        pauseAtQueueStart = pauseAtQueueStart,
        restartCurrentTrack = { track, queue ->
            playQueuedTrack(track, queue, 0L, null, false, false, emptySet(), 1)
        },
    )

    PlaybackMismatchRecoveryEffect(
        playbackQueue = appState.playbackQueue,
        playerState = appState.playerState,
        playbackQueueGeneration = appState.playbackQueueGeneration,
        playQueuedTrack = playQueuedTrack,
    )

    PendingPlaybackRestoreEffect(
        accountId = appState.account?.id,
        syncMode = appState.syncMode,
        tracks = appState.tracks,
        playlists = appState.playlists,
        pendingPlaybackRestore = appState.pendingPlaybackRestore,
        setPlaybackQueue = { appState.playbackQueue = it },
        setPlayerState = { appState.playerState = it },
        loadArtwork = loadArtwork,
        clearPendingPlaybackRestore = { appState.pendingPlaybackRestore = null },
    )

    OfflinePlaybackAvailabilityEffect(
        syncMode = appState.syncMode,
        offlineOnly = appState.offlineOnly,
        enforceOfflinePlaybackAvailability = enforceOfflinePlaybackAvailability,
    )
}

@Composable
private fun PlaybackMismatchRecoveryEffect(
    playbackQueue: PlaybackQueue,
    playerState: PlayerState,
    playbackQueueGeneration: Long,
    playQueuedTrack: (Track, PlaybackQueue, Long, Int?, Boolean, Boolean, Set<Int>, Int) -> Unit,
) {
    LaunchedEffect(
        playbackQueue.currentIndex,
        playbackQueue.tracks.getOrNull(playbackQueue.currentIndex)?.id,
        playerState.currentTrack?.id,
        playbackQueueGeneration,
    ) {
        val queueIndex = playbackQueue.currentIndex
        val queuedTrack = playbackQueue.tracks.getOrNull(queueIndex) ?: return@LaunchedEffect
        val playerTrack = playerState.currentTrack ?: return@LaunchedEffect
        if (queuedTrack.id == playerTrack.id) {
            return@LaunchedEffect
        }
        logPlaybackDebug(
            "recover playback mismatch player=${playerTrack.debugTrack()} queue=${queuedTrack.debugTrack()} " +
                playbackQueue.debugSummary(),
        )
        playQueuedTrack(
            queuedTrack,
            playbackQueue.copy(currentIndex = queueIndex),
            0L,
            queueIndex,
            false,
            false,
            emptySet(),
            1,
        )
    }
}
