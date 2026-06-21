package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.PendingLibraryMutationStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun syncPendingLibraryMutationsAction(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    getLibraryMutationSyncInProgress: () -> Boolean,
    setLibraryMutationSyncInProgress: (Boolean) -> Unit,
    getPendingLibraryMutationCount: () -> Int,
    setPendingLibraryMutationCount: (Int) -> Unit,
    pendingLibraryMutationStore: PendingLibraryMutationStore,
    musicRepository: RemoteMusicRepository,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    loadLibrary: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
) {
    if (
        getLibraryMutationSyncInProgress() ||
        getPendingLibraryMutationCount() <= 0 ||
        !canUseServerRequests()
    ) {
        return
    }
    setLibraryMutationSyncInProgress(true)
    scope.launch {
        runCatching {
            val pendingMutations = pendingLibraryMutationStore.all()
            musicRepository.syncLibraryMutations(pendingMutations)
        }.onSuccess { syncedMutationIds ->
            setAccessToken(refreshAccessToken())
            pendingLibraryMutationStore.removeSyncedPreservingDependencies(syncedMutationIds)
            setPendingLibraryMutationCount(pendingLibraryMutationStore.count())
            if (syncedMutationIds.isNotEmpty()) {
                loadLibrary()
            }
        }.onFailure { error ->
            markServerUnavailable(error)
        }
        setLibraryMutationSyncInProgress(false)
    }
}
