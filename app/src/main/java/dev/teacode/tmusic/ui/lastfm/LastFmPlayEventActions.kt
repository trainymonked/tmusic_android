package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun syncPendingPlayEventsAction(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    getPendingPlayEventCount: () -> Int,
    setPendingPlayEventCount: (Int) -> Unit,
    getPendingPlayEventSyncProgress: () -> Pair<Int, Int>?,
    setPendingPlayEventSyncProgress: (Pair<Int, Int>?) -> Unit,
    pendingPlayEventStore: PendingPlayEventStore,
    musicRepository: RemoteMusicRepository,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    getLastFmConnection: () -> LastFmConnection,
    setLastFmConnection: (LastFmConnection) -> Unit,
    userPreferencesStore: UserPreferencesStore,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests() || getPendingPlayEventCount() == 0 || getPendingPlayEventSyncProgress() != null) {
        return
    }

    fun refreshPendingCount() {
        val pendingCount = pendingPlayEventStore.count()
        setPendingPlayEventCount(pendingCount)
        val nextConnection = getLastFmConnection().copy(pendingScrobbles = pendingCount)
        setLastFmConnection(nextConnection)
        userPreferencesStore.setLastFmConnection(nextConnection)
    }

    scope.launch {
        syncPendingPlayEventBatches(
            store = pendingPlayEventStore,
            musicRepository = musicRepository,
            onProgress = { syncedCount, totalCount ->
                setPendingPlayEventSyncProgress(
                    if (totalCount > 0) {
                        syncedCount to totalCount
                    } else {
                        null
                    },
                )
            },
            onBatchSynced = {
                setAccessToken(refreshAccessToken())
                refreshPendingCount()
            },
        )?.let { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        refreshPendingCount()
        setPendingPlayEventSyncProgress(null)
    }
}

internal fun completeActivePlayEventAction(
    scope: CoroutineScope,
    force: Boolean,
    getActivePlayEvent: () -> ActivePlayEvent?,
    setActivePlayEvent: (ActivePlayEvent?) -> Unit,
    trackForPlayEvent: (ActivePlayEvent) -> Track?,
    discardActivePlayEvent: (ActivePlayEvent) -> Unit,
    getLastFmConnected: () -> Boolean,
    getScrobblingPaused: () -> Boolean,
    canSendPlayEvents: () -> Boolean,
    queuePendingPlayEvent: (ActivePlayEvent) -> Unit,
    getCompletingPlayEventIds: () -> Set<String>,
    setCompletingPlayEventIds: (Set<String>) -> Unit,
    musicRepository: RemoteMusicRepository,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    getNowPlayingEventIds: () -> Set<String>,
    setNowPlayingEventIds: (Set<String>) -> Unit,
    getNowPlayingTrackId: () -> String?,
    setNowPlayingTrackId: (String?) -> Unit,
    savePlaybackSnapshot: () -> Unit,
) {
    val activeEvent = getActivePlayEvent() ?: return
    val eventTrack = trackForPlayEvent(activeEvent)
    if (eventTrack == null || !shouldCompletePlayEvent(activeEvent, eventTrack)) {
        if (force) {
            discardActivePlayEvent(activeEvent)
        }
        return
    }
    if (activeEvent.clientEventId in getCompletingPlayEventIds()) {
        return
    }
    if (!getLastFmConnected() || getScrobblingPaused()) {
        setActivePlayEvent(null)
        return
    }
    if (!canSendPlayEvents()) {
        queuePendingPlayEvent(activeEvent)
        return
    }

    setCompletingPlayEventIds(getCompletingPlayEventIds() + activeEvent.clientEventId)
    scope.launch {
        runCatching {
            musicRepository.syncPlayEvents(listOf(activeEvent.toPendingPlayEvent()))
        }.onSuccess {
            setAccessToken(refreshAccessToken())
            if (getActivePlayEvent()?.clientEventId == activeEvent.clientEventId) {
                setActivePlayEvent(null)
                setNowPlayingEventIds(getNowPlayingEventIds() - activeEvent.clientEventId)
                if (getNowPlayingTrackId() == activeEvent.trackId) {
                    setNowPlayingTrackId(null)
                }
                savePlaybackSnapshot()
            }
        }.onFailure {
            queuePendingPlayEvent(activeEvent)
        }
        setCompletingPlayEventIds(getCompletingPlayEventIds() - activeEvent.clientEventId)
    }
}
