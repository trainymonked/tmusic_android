package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.ScrobbleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun connectLastFmAction(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
    openAuthUrl: (String) -> Result<Unit>,
    setAccessToken: (String?) -> Unit,
    setPendingLastFmToken: (String?) -> Unit,
    setWaitingForLastFmSession: (Boolean) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests()) {
        setLibraryError("Connect to the server before linking Last.fm.")
        return
    }

    scope.launch {
        setLibraryError(null)
        runCatching {
            musicRepository.lastFmAuthRequest()
        }.onSuccess { authRequest ->
            setAccessToken(authRepository.accessToken())
            setPendingLastFmToken(authRequest.token)
            setWaitingForLastFmSession(true)
            lastFmAuthTokenStore.saveToken(authRequest.token)
            openAuthUrl(authRequest.url).onFailure { error ->
                setLibraryError(error.userMessage())
            }
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
    }
}

internal fun completeLastFmSessionAction(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    getPendingLastFmToken: () -> String?,
    getPendingPlayEventCount: () -> Int,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
    userPreferencesStore: UserPreferencesStore,
    setAccessToken: (String?) -> Unit,
    setPendingLastFmToken: (String?) -> Unit,
    setWaitingForLastFmSession: (Boolean) -> Unit,
    setLastFmConnection: (LastFmConnection) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests()) {
        setLibraryError("Connect to the server before completing Last.fm setup.")
        return
    }
    val token = getPendingLastFmToken() ?: lastFmAuthTokenStore.token()
    if (token.isNullOrBlank()) {
        setLibraryError("Request a Last.fm token first.")
        return
    }

    fun applyConnection(connection: LastFmConnection) {
        setPendingLastFmToken(null)
        setWaitingForLastFmSession(false)
        lastFmAuthTokenStore.clear()
        val nextConnection = connection.copy(pendingScrobbles = getPendingPlayEventCount())
        setLastFmConnection(nextConnection)
        userPreferencesStore.setLastFmConnection(nextConnection)
    }

    scope.launch {
        setLibraryError(null)
        runCatching {
            musicRepository.completeLastFmSession(token)
        }.onSuccess { connection ->
            setAccessToken(authRepository.accessToken())
            applyConnection(connection)
        }.onFailure { error ->
            markServerUnavailable(error)
            val existingConnection = runCatching { musicRepository.lastFmSession() }.getOrNull()
            if (
                existingConnection?.state == ScrobbleState.Ready &&
                !existingConnection.username.isNullOrBlank()
            ) {
                setAccessToken(authRepository.accessToken())
                applyConnection(existingConnection)
            } else {
                setLibraryError(
                    if (error.userMessage().contains("Unauthorized Token", ignoreCase = true)) {
                        setPendingLastFmToken(null)
                        setWaitingForLastFmSession(false)
                        lastFmAuthTokenStore.clear()
                        "Last.fm token expired. Start Last.fm linking again."
                    } else {
                        error.userMessage()
                    },
                )
            }
        }
    }
}

internal fun disconnectLastFmAction(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    getPendingPlayEventCount: () -> Int,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
    userPreferencesStore: UserPreferencesStore,
    setAccessToken: (String?) -> Unit,
    setPendingLastFmToken: (String?) -> Unit,
    setWaitingForLastFmSession: (Boolean) -> Unit,
    setLastFmConnection: (LastFmConnection) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests()) {
        setLibraryError("Connect to the server before unlinking Last.fm.")
        return
    }

    scope.launch {
        setLibraryError(null)
        runCatching {
            musicRepository.disconnectLastFm()
        }.onSuccess { connection ->
            setAccessToken(authRepository.accessToken())
            setPendingLastFmToken(null)
            setWaitingForLastFmSession(false)
            lastFmAuthTokenStore.clear()
            setLastFmConnection(connection.copy(pendingScrobbles = getPendingPlayEventCount()))
            userPreferencesStore.clearLastFmConnection()
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
    }
}
