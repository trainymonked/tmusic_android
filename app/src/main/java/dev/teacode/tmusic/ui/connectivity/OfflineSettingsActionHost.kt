package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.AppConfig
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.UserPreferencesStore

internal class OfflineSettingsActionHost(
    private val authRepository: RemoteAuthRepository,
    private val userPreferencesStore: UserPreferencesStore,
    private val getCanContinueOffline: () -> Boolean,
    private val getOfflineOnly: () -> Boolean,
    private val getUseLocalBackend: () -> Boolean,
    private val getAccountAvailable: () -> Boolean,
    private val getPlaylistsEmpty: () -> Boolean,
    private val getTracksEmpty: () -> Boolean,
    private val getActivePlayEvent: () -> ActivePlayEvent?,
    private val clearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    private val enforceOfflinePlaybackAvailability: () -> Unit,
    private val cancelLibraryLoad: () -> Unit,
    private val loadLibrary: () -> Unit,
    private val setAccount: (dev.teacode.tmusic.domain.Account?) -> Unit,
    private val setSyncMode: (SyncMode) -> Unit,
    private val setLibraryError: (String?) -> Unit,
    private val setLibraryLoading: (Boolean) -> Unit,
    private val setLibraryNotice: (String?) -> Unit,
    private val setAuthError: (String?) -> Unit,
    private val setOfflineOnlyState: (Boolean) -> Unit,
    private val setUseLocalBackendState: (Boolean) -> Unit,
    private val setDestination: (AppDestination) -> Unit,
    private val setBackStack: (List<AppDestination>) -> Unit,
) {
    fun continueOffline() {
        if (!getCanContinueOffline()) {
            setAuthError("Sign in once online before offline mode can be used.")
            return
        }

        setAccount(authRepository.cachedAccount() ?: OfflineAccount)
        setSyncMode(if (getOfflineOnly()) SyncMode.OfflineOnly else SyncMode.Offline)
        setLibraryError(
            if (getPlaylistsEmpty() && getTracksEmpty()) {
                "Offline mode. No cached library is available yet."
            } else {
                "Offline mode. Showing cached library."
            },
        )
        setDestination(AppDestination(AppTab.Home))
        setBackStack(emptyList())
    }

    fun setOfflineOnly(enabled: Boolean) {
        if (enabled) {
            clearNowPlayingEvent(getActivePlayEvent())
        }

        setOfflineOnlyState(enabled)
        userPreferencesStore.setOfflineOnly(enabled)

        if (enabled) {
            enforceOfflinePlaybackAvailability()
            cancelLibraryLoad()
            setSyncMode(SyncMode.OfflineOnly)
            setLibraryLoading(false)
            setLibraryError(null)
            setLibraryNotice(null)
        } else if (getAccountAvailable()) {
            loadLibrary()
        }
    }

    fun setUseLocalBackend(enabled: Boolean) {
        if (getUseLocalBackend() == enabled) {
            return
        }

        clearNowPlayingEvent(getActivePlayEvent())
        setUseLocalBackendState(enabled)
        userPreferencesStore.setUseLocalBackend(enabled)
        authRepository.setApiBaseUrl(AppConfig.apiBaseUrl(enabled))

        if (!getOfflineOnly()) {
            setSyncMode(SyncMode.Offline)
            if (getAccountAvailable()) {
                loadLibrary()
            }
        }
    }
}
