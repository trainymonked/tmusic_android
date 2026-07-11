package dev.teacode.tmusic.ui

import dev.teacode.tmusic.auth.GoogleSignInTokenProvider
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import kotlinx.coroutines.CoroutineScope

internal fun createOfflineSettingsController(
    appState: TMusicAppMutableState,
    authRepository: RemoteAuthRepository,
    userPreferencesStore: UserPreferencesStore,
    clearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    enforceOfflinePlaybackAvailability: () -> Unit,
    loadLibrary: () -> Unit,
) = OfflineSettingsActionHost(
    authRepository = authRepository,
    userPreferencesStore = userPreferencesStore,
    getCanContinueOffline = { appState.canContinueOffline },
    getOfflineOnly = { appState.offlineOnly },
    getUseLocalBackend = { appState.useLocalBackend },
    getAccountAvailable = { appState.account != null },
    getPlaylistsEmpty = { appState.playlists.isEmpty() },
    getTracksEmpty = { appState.tracks.isEmpty() },
    getActivePlayEvent = { appState.activePlayEventState.value },
    clearNowPlayingEvent = clearNowPlayingEvent,
    enforceOfflinePlaybackAvailability = enforceOfflinePlaybackAvailability,
    cancelLibraryLoad = {
        appState.libraryLoadSerial += 1
        appState.libraryLoadJob?.cancel()
        appState.libraryLoadJob = null
    },
    loadLibrary = loadLibrary,
    setAccount = { appState.account = it },
    setSyncMode = { appState.syncMode = it },
    setLibraryError = { appState.libraryError = it },
    setLibraryLoading = { appState.libraryLoading = it },
    setLibraryNotice = { appState.libraryNotice = it },
    setAuthError = { appState.authError = it },
    setOfflineOnlyState = { appState.offlineOnly = it },
    setUseLocalBackendState = { appState.useLocalBackend = it },
    setDestination = { appState.destination = it },
    setBackStack = { appState.backStack = it },
)

internal fun createGoogleSignInController(
    appState: TMusicAppMutableState,
    scope: CoroutineScope,
    googleSignInTokenProvider: GoogleSignInTokenProvider,
    authRepository: RemoteAuthRepository,
    userPreferencesStore: UserPreferencesStore,
    loadLibrary: () -> Unit,
) = GoogleSignInActionHost(
    scope = scope,
    googleSignInTokenProvider = googleSignInTokenProvider,
    authRepository = authRepository,
    userPreferencesStore = userPreferencesStore,
    getCanContinueOffline = { appState.canContinueOffline },
    getPendingPlayEventCount = { appState.pendingPlayEventCount },
    setAccount = { appState.account = it },
    setSyncMode = { appState.syncMode = it },
    setLibraryError = { appState.libraryError = it },
    setLastFmConnection = { appState.lastFmConnection = it },
    setAccessToken = { appState.accessToken = it },
    setCanContinueOffline = { appState.canContinueOffline = it },
    setDestination = { appState.destination = it },
    setBackStack = { appState.backStack = it },
    setAuthError = { appState.authError = it },
    setSigningIn = { appState.signingIn = it },
    loadLibrary = loadLibrary,
)
