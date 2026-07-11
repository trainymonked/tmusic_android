package dev.teacode.tmusic.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class NetworkPolicyController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appState: TMusicAppMutableState,
    private val checkForAppUpdate: suspend (manual: Boolean) -> Unit,
) {
    fun hasNetworkConnection(): Boolean {
        return context.hasUsableNetworkConnection(appState.useLocalBackend)
    }

    fun canUseServerRequests(): Boolean {
        return canUseServerRequests(
            account = appState.account,
            offlineOnly = appState.offlineOnly,
            syncMode = appState.syncMode,
            hasNetworkConnection = hasNetworkConnection(),
        )
    }

    fun canAttemptMetadataRequest(): Boolean {
        return canAttemptMetadataRequest(
            account = appState.account,
            offlineOnly = appState.offlineOnly,
            syncMode = appState.syncMode,
            hasNetworkConnection = hasNetworkConnection(),
        )
    }

    fun canUseMediaServerRequests(): Boolean {
        return canUseMediaServerRequests(
            account = appState.account,
            offlineOnly = appState.offlineOnly,
            syncMode = appState.syncMode,
            hasNetworkConnection = hasNetworkConnection(),
        )
    }

    fun canCheckAppUpdates(): Boolean {
        return canCheckAppUpdates(
            account = appState.account,
            offlineOnly = appState.offlineOnly,
            syncMode = appState.syncMode,
            hasNetworkConnection = hasNetworkConnection(),
        )
    }

    fun appUpdateDebugStatus(): String {
        return appUpdateDebugStatus(
            account = appState.account,
            offlineOnly = appState.offlineOnly,
            syncMode = appState.syncMode,
            hasNetworkConnection = hasNetworkConnection(),
        )
    }

    fun canUseNetworkForCollectionDownloads(): Boolean {
        if (!canUseMediaServerRequests()) {
            return false
        }
        if (appState.downloadUsingCellular) {
            return true
        }
        return !context.isUsingCellularNetwork()
    }

    fun mediaDisabledMessage(): String {
        return "Media playback is disabled for this account."
    }

    fun disableMediaPlaybackForAccount() {
        appState.account = appState.account?.copy(canPlayMedia = false)
    }

    fun markServerUnavailable(error: Throwable) {
        if (error.isAppUpdateRequiredError()) {
            scope.launch {
                checkForAppUpdate(false)
            }
            return
        }
        if (error.isServerAvailabilityFailure()) {
            appState.syncMode = SyncMode.Offline
        }
    }
}
