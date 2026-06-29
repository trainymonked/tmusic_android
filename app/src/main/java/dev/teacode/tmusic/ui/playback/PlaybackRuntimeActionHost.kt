package dev.teacode.tmusic.ui

import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal class PlaybackRuntimeActionHost(
    private val scope: CoroutineScope,
    private val getExoPlayer: () -> ExoPlayer,
    private val setExoPlayer: (ExoPlayer) -> Unit,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val getAccount: () -> Account?,
    private val getRepeatMode: () -> PlaybackRepeatMode,
    private val getCrossfadeSeconds: () -> Int,
    private val getCrossfadeJob: () -> Job?,
    private val setCrossfadeJob: (Job?) -> Unit,
    private val getPreparedCrossfade: () -> PreparedCrossfade?,
    private val setPreparedCrossfade: (PreparedCrossfade?) -> Unit,
    private val getPlaybackQueueGeneration: () -> Long,
    private val getPlaybackQueue: () -> PlaybackQueue,
    private val setPlaybackQueue: (PlaybackQueue) -> Unit,
    private val getPlayerState: () -> PlayerState,
    private val setPlayerState: (PlayerState) -> Unit,
    private val getActivePlayEvent: () -> ActivePlayEvent?,
    private val completeActivePlayEvent: (Boolean) -> Unit,
    private val ensureActivePlayEvent: (Track, Boolean) -> Unit,
    private val clearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    private val clearGaplessPlaybackState: () -> Unit,
    private val cancelCrossfade: () -> Unit,
    private val getGaplessPlaybackRequest: () -> GaplessPlaybackRequest?,
    private val setGaplessPlaybackRequest: (GaplessPlaybackRequest?) -> Unit,
    private val getGaplessMediaQueueIndices: () -> Map<String, Int>,
    private val setGaplessMediaQueueIndices: (Map<String, Int>) -> Unit,
    private val getGaplessMediaUrls: () -> Map<String, String>,
    private val setGaplessMediaUrls: (Map<String, String>) -> Unit,
    private val getPrefetchedPlaybackUrls: () -> Map<String, String>,
    private val setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    private val getPlaybackUrlPrefetchesInProgress: () -> Set<String>,
    private val setPlaybackUrlPrefetchesInProgress: (Set<String>) -> Unit,
    private val canUseMediaServerRequests: () -> Boolean,
    private val setAccessToken: (String?) -> Unit,
    private val setPlaybackBufferedFraction: (Float) -> Unit,
    private val incrementPlaybackStartSerial: () -> Unit,
    private val incrementRequestedNextPrefetch: () -> Unit,
    private val incrementCrossfadePreparationSerial: () -> Unit,
    private val setPendingTransitionArtworkTrackId: (String?) -> Unit,
    private val loadArtwork: (String, ArtworkImageSize) -> Unit,
    private val loadLyrics: (Track) -> Unit,
    private val playQueuedTrack: (Track, PlaybackQueue, Long, Boolean) -> Unit,
) {
    fun startPlayback(
        track: Track,
        playbackUrl: String,
        startPositionMs: Long = 0L,
    ) {
        startPlaybackAction(
            track = track,
            playbackUrl = playbackUrl,
            startPositionMs = startPositionMs,
            getActivePlayEvent = getActivePlayEvent,
            completeActivePlayEvent = completeActivePlayEvent,
            ensureActivePlayEvent = ensureActivePlayEvent,
            getPlaybackQueue = getPlaybackQueue,
            setPlaybackQueue = setPlaybackQueue,
            incrementPlaybackStartSerial = incrementPlaybackStartSerial,
            setPlaybackBufferedFraction = setPlaybackBufferedFraction,
            setPlayerState = setPlayerState,
            clearGaplessPlaybackState = clearGaplessPlaybackState,
            loadArtwork = loadArtwork,
        )
    }

    fun startGaplessPlayback(
        track: Track,
        queue: PlaybackQueue,
        urls: List<String>,
        resumePositionMs: Long = 0L,
    ) {
        startGaplessPlaybackAction(
            track = track,
            queue = queue,
            urls = urls,
            resumePositionMs = resumePositionMs,
            getActivePlayEvent = getActivePlayEvent,
            completeActivePlayEvent = completeActivePlayEvent,
            ensureActivePlayEvent = ensureActivePlayEvent,
            setPlaybackQueue = setPlaybackQueue,
            setPlaybackBufferedFraction = setPlaybackBufferedFraction,
            setPlayerState = setPlayerState,
            setGaplessPlaybackRequest = setGaplessPlaybackRequest,
            setGaplessMediaQueueIndices = setGaplessMediaQueueIndices,
            setGaplessMediaUrls = setGaplessMediaUrls,
            loadArtwork = loadArtwork,
        )
    }

    fun installGaplessPrefetch(
        queue: PlaybackQueue,
        nextTrack: Track,
        nextIndex: Int,
        nextUrl: String,
    ) {
        installGaplessPrefetchAction(
            queue = queue,
            nextTrack = nextTrack,
            nextIndex = nextIndex,
            nextUrl = nextUrl,
            exoPlayer = getExoPlayer(),
            getPlayerState = getPlayerState,
            getGaplessPlaybackRequest = getGaplessPlaybackRequest,
            getGaplessMediaQueueIndices = getGaplessMediaQueueIndices,
            setGaplessMediaQueueIndices = setGaplessMediaQueueIndices,
            getGaplessMediaUrls = getGaplessMediaUrls,
            setGaplessMediaUrls = setGaplessMediaUrls,
        )
    }

    fun localOrCachedPlaybackUrl(trackId: String): String? {
        return localOrCachedPlaybackUrl(musicRepository, trackId)
    }

    fun localOrCachedPlaybackUrl(track: Track): String? {
        return localOrCachedPlaybackUrl(musicRepository, track)
    }

    fun enforceOfflinePlaybackAvailability() {
        enforceOfflinePlaybackAvailabilityAction(
            exoPlayer = getExoPlayer(),
            getPlayerState = getPlayerState,
            setPlayerState = setPlayerState,
            cancelCrossfade = cancelCrossfade,
            localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
            clearNowPlayingEvent = clearNowPlayingEvent,
            getActivePlayEvent = getActivePlayEvent,
            clearGaplessPlaybackState = clearGaplessPlaybackState,
            incrementPlaybackStartSerial = incrementPlaybackStartSerial,
        )
    }

    fun prefetchTrackAssets(track: Track) {
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.TrackList)
        loadLyrics(track)
    }

    fun prefetchNextTrackUrl(queue: PlaybackQueue) {
        prefetchNextTrackUrlAction(
            scope = scope,
            queue = queue,
            getAccount = getAccount,
            getRepeatMode = getRepeatMode,
            getPrefetchedPlaybackUrls = getPrefetchedPlaybackUrls,
            setPrefetchedPlaybackUrls = setPrefetchedPlaybackUrls,
            getPlaybackUrlPrefetchesInProgress = getPlaybackUrlPrefetchesInProgress,
            setPlaybackUrlPrefetchesInProgress = setPlaybackUrlPrefetchesInProgress,
            prefetchTrackAssets = ::prefetchTrackAssets,
            localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
            installGaplessPrefetch = ::installGaplessPrefetch,
            canUseMediaServerRequests = canUseMediaServerRequests,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = setAccessToken,
        )
    }

    fun nextCrossfadeQueueIndex(queue: PlaybackQueue): Int? {
        if (getCrossfadeSeconds() <= 0 || getRepeatMode() == PlaybackRepeatMode.Track || !queue.canSkip) {
            return null
        }
        val currentIndex = queue.currentIndex.takeIf { it in queue.tracks.indices } ?: return null
        return when {
            currentIndex < queue.tracks.lastIndex -> currentIndex + 1
            getRepeatMode() == PlaybackRepeatMode.Queue -> 0
            else -> null
        }
    }

    fun beginPreparedCrossfade(prepared: PreparedCrossfade, fadeDurationMs: Long) {
        beginPreparedCrossfadeAction(
            scope = scope,
            prepared = prepared,
            fadeDurationMs = fadeDurationMs,
            getCrossfadeJob = getCrossfadeJob,
            setCrossfadeJob = setCrossfadeJob,
            getExoPlayer = getExoPlayer,
            setExoPlayer = setExoPlayer,
            getPlaybackQueueGeneration = getPlaybackQueueGeneration,
            getPlaybackQueue = getPlaybackQueue,
            setPlaybackQueue = setPlaybackQueue,
            setPlayerState = setPlayerState,
            getActivePlayEvent = getActivePlayEvent,
            completeActivePlayEvent = completeActivePlayEvent,
            ensureActivePlayEvent = ensureActivePlayEvent,
            clearGaplessPlaybackState = clearGaplessPlaybackState,
            setGaplessMediaQueueIndices = setGaplessMediaQueueIndices,
            setGaplessMediaUrls = setGaplessMediaUrls,
            setPendingTransitionArtworkTrackId = setPendingTransitionArtworkTrackId,
            setPreparedCrossfade = setPreparedCrossfade,
            incrementRequestedNextPrefetch = incrementRequestedNextPrefetch,
            incrementCrossfadePreparationSerial = incrementCrossfadePreparationSerial,
        )
    }

    fun seekPreparedQueueMediaItem(targetIndex: Int, direction: Int): Boolean {
        return seekPreparedQueueMediaItemAction(
            targetIndex = targetIndex,
            direction = direction,
            exoPlayer = getExoPlayer(),
            getPlaybackQueue = getPlaybackQueue,
            getActivePlayEvent = getActivePlayEvent,
            completeActivePlayEvent = completeActivePlayEvent,
            ensureActivePlayEvent = ensureActivePlayEvent,
            setPlaybackQueue = setPlaybackQueue,
            setPlayerState = setPlayerState,
            getGaplessMediaQueueIndices = getGaplessMediaQueueIndices,
            getGaplessMediaUrls = getGaplessMediaUrls,
            getPrefetchedPlaybackUrls = getPrefetchedPlaybackUrls,
            localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
            setPendingTransitionArtworkTrackId = setPendingTransitionArtworkTrackId,
            setPlaybackBufferedFraction = setPlaybackBufferedFraction,
            incrementRequestedNextPrefetch = incrementRequestedNextPrefetch,
        )
    }

    fun applyPlaybackQueueOrderWithoutInterrupt(nextQueue: PlaybackQueue) {
        applyPlaybackQueueOrderWithoutInterruptAction(
            nextQueue = nextQueue,
            exoPlayer = getExoPlayer(),
            getPlayerState = getPlayerState,
            setPlaybackQueue = setPlaybackQueue,
            setGaplessPlaybackRequest = setGaplessPlaybackRequest,
            setGaplessMediaQueueIndices = setGaplessMediaQueueIndices,
            setGaplessMediaUrls = setGaplessMediaUrls,
            prefetchNextTrackUrl = ::prefetchNextTrackUrl,
        )
    }

    fun togglePlayback() {
        togglePlaybackAction(
            exoPlayer = getExoPlayer(),
            getPlayerState = getPlayerState,
            setPlayerState = setPlayerState,
            getPlaybackQueue = getPlaybackQueue,
            getCrossfadeJob = getCrossfadeJob,
            getPreparedCrossfade = getPreparedCrossfade,
            cancelCrossfade = cancelCrossfade,
            playQueuedTrack = playQueuedTrack,
            ensureActivePlayEvent = { track -> ensureActivePlayEvent(track, false) },
            clearNowPlayingEvent = clearNowPlayingEvent,
            getActivePlayEvent = getActivePlayEvent,
        )
    }
}
