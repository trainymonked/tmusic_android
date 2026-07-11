package dev.teacode.tmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun TMusicAppLifecycleEffects(
    appState: TMusicAppMutableState,
    scope: CoroutineScope,
    refreshStorageStats: () -> Unit,
    loadLibrary: () -> Unit,
    canUseNetworkForCollectionDownloads: () -> Boolean,
    resumePendingOfflineDownloads: () -> Unit,
    pauseCollectionDownloadsForNetworkPolicy: () -> Unit,
    checkForAppUpdate: suspend (manual: Boolean) -> Unit,
    goBack: () -> Unit,
) {
    TimedMessageClearEffect(appState.libraryNotice, timeoutMs = 2_500) { current ->
        if (appState.libraryNotice == current) {
            appState.libraryNotice = null
        }
    }
    TimedMessageClearEffect(appState.libraryError, timeoutMs = 5_000) { current ->
        if (appState.libraryError == current) {
            appState.libraryError = null
        }
    }
    TimedMessageClearEffect(appState.playerError, timeoutMs = 5_000) { current ->
        if (appState.playerError == current) {
            appState.playerError = null
        }
    }
    InitialStorageStatsEffect(refreshStorageStats)
    InitialLibraryLoadEffect(
        account = appState.account,
        offlineOnly = appState.offlineOnly,
        loadLibrary = { loadLibrary() },
        setOfflineOnlySyncMode = { appState.syncMode = SyncMode.OfflineOnly },
        clearLibraryError = { appState.libraryError = null },
    )
    LaunchedEffect(
        appState.account?.id,
        appState.syncMode,
        appState.offlineOnly,
        appState.downloadUsingCellular,
    ) {
        if (canUseNetworkForCollectionDownloads()) {
            resumePendingOfflineDownloads()
        } else {
            pauseCollectionDownloadsForNetworkPolicy()
        }
    }
    ObserveNetworkConnectivity(
        enabled = appState.account != null && !appState.offlineOnly,
        useLocalBackend = appState.useLocalBackend,
        onNetworkPolicyChanged = {
            if (canUseNetworkForCollectionDownloads()) {
                resumePendingOfflineDownloads()
            } else {
                pauseCollectionDownloadsForNetworkPolicy()
            }
        },
        onConnectionStateChanged = { connected ->
            if (appState.account != null && !appState.offlineOnly && !connected) {
                appState.syncMode = SyncMode.Offline
            }
        },
        onNetworkAvailableOrChanged = {
            if (appState.account != null && !appState.offlineOnly) {
                loadLibrary()
            }
            if (
                appState.account != null &&
                !appState.offlineOnly &&
                appState.syncMode != SyncMode.Offline
            ) {
                scope.launch {
                    checkForAppUpdate(false)
                }
            }
        },
    )
    BackHandler(enabled = appState.queueOpen) {
        appState.queueOpen = false
    }
    BackHandler(enabled = appState.account != null && appState.backStack.isNotEmpty() && !appState.fullPlayerOpen) {
        goBack()
    }
}
