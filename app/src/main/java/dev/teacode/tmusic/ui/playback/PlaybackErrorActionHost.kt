package dev.teacode.tmusic.ui

import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.PlayerState
import kotlinx.coroutines.CoroutineScope

internal class PlaybackErrorActionHost(
    private val scope: CoroutineScope,
    private val exoPlayer: ExoPlayer,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val getPlayerState: () -> PlayerState,
    private val setPlayerState: (PlayerState) -> Unit,
    private val getPlaybackQueue: () -> PlaybackQueue,
    private val getAccount: () -> Account?,
    private val getPrefetchedPlaybackUrls: () -> Map<String, String>,
    private val setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    private val getPlaybackUrlPrefetchesInProgress: () -> Set<String>,
    private val setPlaybackUrlPrefetchesInProgress: (Set<String>) -> Unit,
    private val incrementStreamRequestSerial: () -> Long,
    private val getStreamRequestSerial: () -> Long,
    private val clearGaplessPlaybackState: () -> Unit,
    private val cancelCrossfade: () -> Unit,
    private val canUseMediaServerRequests: () -> Boolean,
    private val mediaDisabledMessage: () -> String,
    private val setAccessToken: (String?) -> Unit,
    private val setPlaybackBufferedFraction: (Float) -> Unit,
    private val incrementPlaybackStartSerial: () -> Unit,
    private val incrementRequestedNextPrefetch: () -> Unit,
    private val disableMediaPlaybackForAccount: () -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setPlayerError: (String?) -> Unit,
) {
    fun handlePlaybackPlayerError(message: String, httpStatusCode: Int?): Boolean {
        return handlePlaybackPlayerErrorAction(
            scope = scope,
            message = message,
            httpStatusCode = httpStatusCode,
            exoPlayer = exoPlayer,
            getPlayerState = getPlayerState,
            setPlayerState = setPlayerState,
            getPlaybackQueue = getPlaybackQueue,
            getAccount = getAccount,
            getPrefetchedPlaybackUrls = getPrefetchedPlaybackUrls,
            setPrefetchedPlaybackUrls = setPrefetchedPlaybackUrls,
            getPlaybackUrlPrefetchesInProgress = getPlaybackUrlPrefetchesInProgress,
            setPlaybackUrlPrefetchesInProgress = setPlaybackUrlPrefetchesInProgress,
            incrementStreamRequestSerial = incrementStreamRequestSerial,
            getStreamRequestSerial = getStreamRequestSerial,
            clearGaplessPlaybackState = clearGaplessPlaybackState,
            cancelCrossfade = cancelCrossfade,
            canUseMediaServerRequests = canUseMediaServerRequests,
            mediaDisabledMessage = mediaDisabledMessage,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = setAccessToken,
            setPlaybackBufferedFraction = setPlaybackBufferedFraction,
            incrementPlaybackStartSerial = incrementPlaybackStartSerial,
            incrementRequestedNextPrefetch = incrementRequestedNextPrefetch,
            disableMediaPlaybackForAccount = disableMediaPlaybackForAccount,
            markServerUnavailable = markServerUnavailable,
            setPlayerError = setPlayerError,
        )
    }
}
