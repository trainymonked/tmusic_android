package dev.teacode.tmusic.ui

import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope

internal class PlayQueuedTrackActionHost(
    private val scope: CoroutineScope,
    private val exoPlayer: ExoPlayer,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val getAccount: () -> Account?,
    private val getShuffleEnabled: () -> Boolean,
    private val getPlaybackQueueGeneration: () -> Long,
    private val setPlaybackQueueGeneration: (Long) -> Unit,
    private val getPlaybackQueue: () -> PlaybackQueue,
    private val setPlaybackQueue: (PlaybackQueue) -> Unit,
    private val getPlayerState: () -> PlayerState,
    private val setPlayerState: (PlayerState) -> Unit,
    private val setPlaybackBufferedFraction: (Float) -> Unit,
    private val getPrefetchedPlaybackUrls: () -> Map<String, String>,
    private val setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    private val getOfflineOnly: () -> Boolean,
    private val getSyncMode: () -> SyncMode,
    private val currentStreamRequestSerial: () -> Long,
    private val nextStreamRequestSerial: () -> Long,
    private val cancelCrossfade: () -> Unit,
    private val canUseMediaServerRequests: () -> Boolean,
    private val disableMediaPlaybackForAccount: () -> Unit,
    private val mediaDisabledMessage: () -> String,
    private val clearGaplessPlaybackState: () -> Unit,
    private val localOrCachedPlaybackUrl: (Track) -> String?,
    private val loadArtwork: (String, ArtworkImageSize) -> Unit,
    private val prefetchNextTrackUrl: (PlaybackQueue) -> Unit,
    private val startGaplessPlayback: (Track, PlaybackQueue, List<String>, Long) -> Unit,
    private val startPlayback: (Track, String, Long) -> Unit,
    private val setAccessToken: (String?) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setLibraryNotice: (String?) -> Unit,
    private val setPlayerError: (String?) -> Unit,
) {
    fun playQueuedTrack(
        track: Track,
        queue: PlaybackQueue,
        resumePositionMs: Long = 0L,
        preferredIndex: Int? = null,
        allowResume: Boolean = false,
        newQueue: Boolean = false,
        skippedQueueIndices: Set<Int> = emptySet(),
        unavailableSkipDirection: Int = 1,
    ) {
        playQueuedTrack(PlayQueuedTrackRequest(
            track = track,
            queue = queue,
            resumePositionMs = resumePositionMs,
            preferredIndex = preferredIndex,
            allowResume = allowResume,
            newQueue = newQueue,
            skippedQueueIndices = skippedQueueIndices,
            unavailableSkipDirection = unavailableSkipDirection,
        ))
    }

    private fun playQueuedTrack(request: PlayQueuedTrackRequest) {
        playQueuedTrackAction(
            request = request,
            scope = scope,
            exoPlayer = exoPlayer,
            musicRepository = musicRepository,
            getAccount = getAccount,
            getShuffleEnabled = getShuffleEnabled,
            getPlaybackQueueGeneration = getPlaybackQueueGeneration,
            setPlaybackQueueGeneration = setPlaybackQueueGeneration,
            getPlaybackQueue = getPlaybackQueue,
            setPlaybackQueue = setPlaybackQueue,
            getPlayerState = getPlayerState,
            setPlayerState = setPlayerState,
            setPlaybackBufferedFraction = setPlaybackBufferedFraction,
            getPrefetchedPlaybackUrls = getPrefetchedPlaybackUrls,
            setPrefetchedPlaybackUrls = setPrefetchedPlaybackUrls,
            getOfflineOnly = getOfflineOnly,
            getSyncMode = getSyncMode,
            currentStreamRequestSerial = currentStreamRequestSerial,
            nextStreamRequestSerial = nextStreamRequestSerial,
            cancelCrossfade = cancelCrossfade,
            canUseMediaServerRequests = canUseMediaServerRequests,
            disableMediaPlaybackForAccount = disableMediaPlaybackForAccount,
            mediaDisabledMessage = mediaDisabledMessage,
            clearGaplessPlaybackState = clearGaplessPlaybackState,
            localOrCachedPlaybackUrl = localOrCachedPlaybackUrl,
            loadArtwork = loadArtwork,
            prefetchNextTrackUrl = prefetchNextTrackUrl,
            startGaplessPlayback = startGaplessPlayback,
            startPlayback = startPlayback,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            markServerUnavailable = markServerUnavailable,
            setLibraryNotice = setLibraryNotice,
            setPlayerError = setPlayerError,
            replay = ::playQueuedTrack,
        )
    }
}
