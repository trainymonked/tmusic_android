package dev.teacode.tmusic.ui

import android.content.Intent
import dev.teacode.tmusic.auth.GoogleSignInTokenProvider
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.data.TMusicApiException
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.LastFmConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class GoogleSignInActionHost(
    private val scope: CoroutineScope,
    private val googleSignInTokenProvider: GoogleSignInTokenProvider,
    private val authRepository: RemoteAuthRepository,
    private val userPreferencesStore: UserPreferencesStore,
    private val getCanContinueOffline: () -> Boolean,
    private val getPendingPlayEventCount: () -> Int,
    private val setAccount: (Account?) -> Unit,
    private val setSyncMode: (SyncMode) -> Unit,
    private val setLibraryError: (String?) -> Unit,
    private val setLastFmConnection: (LastFmConnection) -> Unit,
    private val setAccessToken: (String?) -> Unit,
    private val setCanContinueOffline: (Boolean) -> Unit,
    private val setDestination: (AppDestination) -> Unit,
    private val setBackStack: (List<AppDestination>) -> Unit,
    private val setAuthError: (String?) -> Unit,
    private val setSigningIn: (Boolean) -> Unit,
    private val loadLibrary: () -> Unit,
) {
    fun handleSignInResult(data: Intent?) {
        scope.launch {
            googleSignInTokenProvider.idTokenFromIntent(data)
                .fold(
                    onSuccess = { idToken ->
                        authRepository.signInWithGoogle(idToken)
                            .onSuccess { signedInAccount ->
                                setAccount(signedInAccount)
                                signedInAccount.lastFmConnection?.let { connection ->
                                    val connectionWithPending = connection.copy(
                                        pendingScrobbles = getPendingPlayEventCount(),
                                    )
                                    setLastFmConnection(connectionWithPending)
                                    userPreferencesStore.setLastFmConnection(connectionWithPending)
                                }
                                setAccessToken(authRepository.accessToken())
                                setCanContinueOffline(true)
                                setDestination(AppDestination(AppTab.Home))
                                setBackStack(emptyList())
                                setAuthError(null)
                                loadLibrary()
                            }
                            .onFailure { error ->
                                handleServerSignInError(error)
                            }
                    },
                    onFailure = { error ->
                        setAuthError(error.userMessage())
                    },
                )
            setSigningIn(false)
        }
    }

    private fun handleServerSignInError(error: Throwable) {
        if (error is TMusicApiException && error.statusCode in 400..499) {
            setAuthError(error.userMessage())
            setLibraryError(null)
        } else if (getCanContinueOffline()) {
            setAuthError(null)
            setAccount(authRepository.cachedAccount() ?: OfflineAccount)
            setSyncMode(SyncMode.Offline)
            setLibraryError("Server unavailable after Google Sign-In. Showing offline data. ${error.userMessage()}")
        } else {
            setAuthError("Server unavailable. Sign in once online before offline mode can be used.")
        }
    }
}
