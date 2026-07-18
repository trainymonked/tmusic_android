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
        if (shouldSwitchToOfflineAfterServerFailure(error, hasNetworkConnection())) {
            appState.syncMode = SyncMode.Offline
        }
    }
}

/**
 * A failed request to one endpoint is not proof that the device lost its
 * connection. In particular, requests started while cellular data is coming
 * up can finish after a successful library sync. Only the system-confirmed
 * network state may move the whole app to Offline.
 */
internal fun shouldSwitchToOfflineAfterServerFailure(
    error: Throwable,
    hasNetworkConnection: Boolean,
): Boolean {
    return error.isServerAvailabilityFailure() && !hasNetworkConnection
}
