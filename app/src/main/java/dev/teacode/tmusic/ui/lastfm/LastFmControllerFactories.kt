package dev.teacode.tmusic.ui

import android.content.Context
import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope

internal fun createLastFmPlaybackEventController(
    appState: TMusicAppMutableState,
    scope: CoroutineScope,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    pendingPlayEventStore: PendingPlayEventStore,
    userPreferencesStore: UserPreferencesStore,
    getLastFmConnected: () -> Boolean,
    canUseServerRequests: () -> Boolean,
    canSendPlayEvents: () -> Boolean,
    markServerUnavailable: (Throwable) -> Unit,
    savePlaybackSnapshot: () -> Unit,
) = LastFmPlaybackEventActionHost(
    scope = scope,
    musicRepository = musicRepository,
    authRepository = authRepository,
    pendingPlayEventStore = pendingPlayEventStore,
    userPreferencesStore = userPreferencesStore,
    getLastFmConnected = getLastFmConnected,
    getScrobblingPaused = { appState.scrobblingPaused },
    canUseServerRequests = canUseServerRequests,
    canSendPlayEvents = canSendPlayEvents,
    getNowPlayingEventIds = { appState.nowPlayingEventIds },
    setNowPlayingEventIds = { appState.nowPlayingEventIds = it },
    getNowPlayingTrackIdsInFlight = { appState.nowPlayingTrackIdsInFlight },
    setNowPlayingTrackIdsInFlight = { appState.nowPlayingTrackIdsInFlight = it },
    getNowPlayingTrackId = { appState.nowPlayingTrackId },
    setNowPlayingTrackId = { appState.nowPlayingTrackId = it },
    setAccessToken = { appState.accessToken = it },
    markServerUnavailable = markServerUnavailable,
    getActivePlayEvent = { appState.activePlayEventState.value },
    setActivePlayEvent = { appState.activePlayEventState.value = it },
    setPendingPlayEventCount = { appState.pendingPlayEventCount = it },
    getLastFmConnection = { appState.lastFmConnection },
    setLastFmConnection = { appState.lastFmConnection = it },
    savePlaybackSnapshot = savePlaybackSnapshot,
    getPlayerState = { appState.playerState },
    getTracks = { appState.tracks },
    getPlaybackQueue = { appState.playbackQueue },
    getCompletingPlayEventIds = { appState.completingPlayEventIds },
    setCompletingPlayEventIds = { appState.completingPlayEventIds = it },
    getPendingPlayEventCount = { appState.pendingPlayEventCount },
    getPendingPlayEventSyncProgress = { appState.pendingPlayEventSyncProgress },
    setPendingPlayEventSyncProgress = { appState.pendingPlayEventSyncProgress = it },
    setLibraryError = { appState.libraryError = it },
)

internal fun createLastFmController(
    appState: TMusicAppMutableState,
    scope: CoroutineScope,
    context: Context,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
    userPreferencesStore: UserPreferencesStore,
    canUseServerRequests: () -> Boolean,
    clearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    sendNowPlayingEvent: (ActivePlayEvent, Boolean) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
) = LastFmActionHost(
    scope = scope,
    context = context,
    canUseServerRequests = canUseServerRequests,
    musicRepository = musicRepository,
    authRepository = authRepository,
    lastFmAuthTokenStore = lastFmAuthTokenStore,
    userPreferencesStore = userPreferencesStore,
    getPendingLastFmToken = { appState.pendingLastFmToken },
    getPendingPlayEventCount = { appState.pendingPlayEventCount },
    setAccessToken = { appState.accessToken = it },
    setPendingLastFmToken = { appState.pendingLastFmToken = it },
    setWaitingForLastFmSession = { appState.waitingForLastFmSession = it },
    setLastFmConnection = { appState.lastFmConnection = it },
    getActivePlayEvent = { appState.activePlayEventState.value },
    setActivePlayEvent = { appState.activePlayEventState.value = it },
    clearNowPlayingEvent = clearNowPlayingEvent,
    getScrobblingPaused = { appState.scrobblingPaused },
    setScrobblingPausedState = { appState.scrobblingPaused = it },
    getCurrentTrack = { appState.playerState.currentTrack },
    getIsPlaying = { appState.playerState.isPlaying },
    sendNowPlayingEvent = sendNowPlayingEvent,
    markServerUnavailable = markServerUnavailable,
    setLibraryError = { appState.libraryError = it },
)

