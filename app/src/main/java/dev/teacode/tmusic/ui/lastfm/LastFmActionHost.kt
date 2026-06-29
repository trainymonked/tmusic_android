package dev.teacode.tmusic.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope

internal class LastFmActionHost(
    private val scope: CoroutineScope,
    private val context: Context,
    private val canUseServerRequests: () -> Boolean,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val lastFmAuthTokenStore: LastFmAuthTokenStore,
    private val userPreferencesStore: UserPreferencesStore,
    private val getPendingLastFmToken: () -> String?,
    private val getPendingPlayEventCount: () -> Int,
    private val setAccessToken: (String?) -> Unit,
    private val setPendingLastFmToken: (String?) -> Unit,
    private val setWaitingForLastFmSession: (Boolean) -> Unit,
    private val setLastFmConnection: (LastFmConnection) -> Unit,
    private val getActivePlayEvent: () -> ActivePlayEvent?,
    private val setActivePlayEvent: (ActivePlayEvent?) -> Unit,
    private val clearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    private val getScrobblingPaused: () -> Boolean,
    private val setScrobblingPausedState: (Boolean) -> Unit,
    private val getCurrentTrack: () -> Track?,
    private val getIsPlaying: () -> Boolean,
    private val sendNowPlayingEvent: (ActivePlayEvent, Boolean) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setLibraryError: (String?) -> Unit,
) {
    fun connectLastFm() {
        connectLastFmAction(
            scope = scope,
            canUseServerRequests = canUseServerRequests,
            musicRepository = musicRepository,
            authRepository = authRepository,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            openAuthUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            setAccessToken = setAccessToken,
            setPendingLastFmToken = setPendingLastFmToken,
            setWaitingForLastFmSession = setWaitingForLastFmSession,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun completeLastFmSession() {
        completeLastFmSessionAction(
            scope = scope,
            canUseServerRequests = canUseServerRequests,
            getPendingLastFmToken = getPendingLastFmToken,
            getPendingPlayEventCount = getPendingPlayEventCount,
            musicRepository = musicRepository,
            authRepository = authRepository,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            userPreferencesStore = userPreferencesStore,
            setAccessToken = setAccessToken,
            setPendingLastFmToken = setPendingLastFmToken,
            setWaitingForLastFmSession = setWaitingForLastFmSession,
            setLastFmConnection = setLastFmConnection,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun disconnectLastFm() {
        disconnectLastFmAction(
            scope = scope,
            canUseServerRequests = canUseServerRequests,
            getPendingPlayEventCount = getPendingPlayEventCount,
            musicRepository = musicRepository,
            authRepository = authRepository,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            userPreferencesStore = userPreferencesStore,
            setAccessToken = setAccessToken,
            setPendingLastFmToken = setPendingLastFmToken,
            setWaitingForLastFmSession = setWaitingForLastFmSession,
            setLastFmConnection = setLastFmConnection,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun setScrobblingPaused(paused: Boolean) {
        if (paused) {
            clearNowPlayingEvent(getActivePlayEvent())
            setScrobblingPausedState(paused)
            userPreferencesStore.setScrobblingPaused(paused)
            return
        }

        setScrobblingPausedState(paused)
        userPreferencesStore.setScrobblingPaused(paused)
        getCurrentTrack()?.takeIf { getIsPlaying() }?.let { track ->
            val activeEvent = getActivePlayEvent()?.takeIf { it.trackId == track.id }
                ?: newActivePlayEvent(track).also(setActivePlayEvent)
            sendNowPlayingEvent(activeEvent, true)
        }
    }
}
