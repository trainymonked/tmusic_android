package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun sendNowPlayingEventAction(
    scope: CoroutineScope,
    activeEvent: ActivePlayEvent,
    track: Track?,
    force: Boolean,
    getLastFmConnected: () -> Boolean,
    getScrobblingPaused: () -> Boolean,
    canUseServerRequests: () -> Boolean,
    canSendPlayEvents: () -> Boolean,
    getNowPlayingEventIds: () -> Set<String>,
    setNowPlayingEventIds: (Set<String>) -> Unit,
    getNowPlayingTrackIdsInFlight: () -> Set<String>,
    setNowPlayingTrackIdsInFlight: (Set<String>) -> Unit,
    getNowPlayingTrackId: () -> String?,
    setNowPlayingTrackId: (String?) -> Unit,
    musicRepository: RemoteMusicRepository,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
) {
    track ?: return
    if (!getLastFmConnected() || getScrobblingPaused()) {
        return
    }
    if (activeEvent.trackId in getNowPlayingTrackIdsInFlight()) {
        return
    }
    if (!force && activeEvent.clientEventId in getNowPlayingEventIds()) {
        return
    }
    if (!force && getNowPlayingTrackId() == activeEvent.trackId) {
        return
    }
    if (force && !canUseServerRequests()) {
        return
    } else if (!force && !canSendPlayEvents()) {
        return
    }

    setNowPlayingEventIds(getNowPlayingEventIds() + activeEvent.clientEventId)
    setNowPlayingTrackIdsInFlight(getNowPlayingTrackIdsInFlight() + activeEvent.trackId)
    scope.launch {
        runCatching {
            musicRepository.sendNowPlaying(activeEvent.trackId)
        }.onSuccess {
            setAccessToken(refreshAccessToken())
            setNowPlayingTrackIdsInFlight(getNowPlayingTrackIdsInFlight() - activeEvent.trackId)
            setNowPlayingTrackId(activeEvent.trackId)
        }.onFailure {
            setNowPlayingEventIds(getNowPlayingEventIds() - activeEvent.clientEventId)
            setNowPlayingTrackIdsInFlight(getNowPlayingTrackIdsInFlight() - activeEvent.trackId)
            markServerUnavailable(it)
        }
    }
}

internal fun clearNowPlayingEventAction(
    activeEvent: ActivePlayEvent?,
    currentTrackId: String?,
    getNowPlayingTrackId: () -> String?,
    setNowPlayingTrackId: (String?) -> Unit,
    getNowPlayingEventIds: () -> Set<String>,
    setNowPlayingEventIds: (Set<String>) -> Unit,
    getNowPlayingTrackIdsInFlight: () -> Set<String>,
    setNowPlayingTrackIdsInFlight: (Set<String>) -> Unit,
) {
    activeEvent?.let { event ->
        setNowPlayingEventIds(getNowPlayingEventIds() - event.clientEventId)
        setNowPlayingTrackIdsInFlight(getNowPlayingTrackIdsInFlight() - event.trackId)
    }
    val trackId = activeEvent?.trackId ?: currentTrackId
    if (trackId != null && getNowPlayingTrackId() == trackId) {
        setNowPlayingTrackId(null)
    } else if (activeEvent == null) {
        setNowPlayingTrackId(null)
    }
}

internal fun ensureActivePlayEventAction(
    track: Track,
    forceNew: Boolean,
    getLastFmConnected: () -> Boolean,
    getScrobblingPaused: () -> Boolean,
    getActivePlayEvent: () -> ActivePlayEvent?,
    setActivePlayEvent: (ActivePlayEvent?) -> Unit,
    sendNowPlayingEvent: (ActivePlayEvent, Track, Boolean) -> Unit,
) {
    if (!getLastFmConnected() || getScrobblingPaused()) {
        setActivePlayEvent(null)
        return
    }
    val activeEvent = getActivePlayEvent()
    if (!forceNew && activeEvent?.trackId == track.id) {
        sendNowPlayingEvent(activeEvent, track, false)
        return
    }
    val nextEvent = newActivePlayEvent(track)
    setActivePlayEvent(nextEvent)
    sendNowPlayingEvent(nextEvent, track, false)
}

internal fun queuePendingPlayEventAction(
    activeEvent: ActivePlayEvent,
    getLastFmConnected: () -> Boolean,
    getScrobblingPaused: () -> Boolean,
    getActivePlayEvent: () -> ActivePlayEvent?,
    setActivePlayEvent: (ActivePlayEvent?) -> Unit,
    getNowPlayingEventIds: () -> Set<String>,
    setNowPlayingEventIds: (Set<String>) -> Unit,
    getNowPlayingTrackIdsInFlight: () -> Set<String>,
    setNowPlayingTrackIdsInFlight: (Set<String>) -> Unit,
    getNowPlayingTrackId: () -> String?,
    setNowPlayingTrackId: (String?) -> Unit,
    pendingPlayEventStore: PendingPlayEventStore,
    setPendingPlayEventCount: (Int) -> Unit,
    getLastFmConnection: () -> LastFmConnection,
    setLastFmConnection: (LastFmConnection) -> Unit,
    userPreferencesStore: UserPreferencesStore,
    savePlaybackSnapshot: () -> Unit,
) {
    if (!getLastFmConnected() || getScrobblingPaused()) {
        return
    }
    pendingPlayEventStore.append(activeEvent.toPendingPlayEvent())
    val pendingCount = pendingPlayEventStore.count()
    setPendingPlayEventCount(pendingCount)
    val nextConnection = getLastFmConnection().copy(pendingScrobbles = pendingCount)
    setLastFmConnection(nextConnection)
    userPreferencesStore.setLastFmConnection(nextConnection)
    if (getActivePlayEvent()?.clientEventId == activeEvent.clientEventId) {
        setActivePlayEvent(null)
        setNowPlayingEventIds(getNowPlayingEventIds() - activeEvent.clientEventId)
        setNowPlayingTrackIdsInFlight(getNowPlayingTrackIdsInFlight() - activeEvent.trackId)
        if (getNowPlayingTrackId() == activeEvent.trackId) {
            setNowPlayingTrackId(null)
        }
        savePlaybackSnapshot()
    }
}

internal fun discardActivePlayEventAction(
    activeEvent: ActivePlayEvent,
    getActivePlayEvent: () -> ActivePlayEvent?,
    setActivePlayEvent: (ActivePlayEvent?) -> Unit,
    getNowPlayingEventIds: () -> Set<String>,
    setNowPlayingEventIds: (Set<String>) -> Unit,
    getNowPlayingTrackIdsInFlight: () -> Set<String>,
    setNowPlayingTrackIdsInFlight: (Set<String>) -> Unit,
    getNowPlayingTrackId: () -> String?,
    setNowPlayingTrackId: (String?) -> Unit,
    savePlaybackSnapshot: () -> Unit,
) {
    if (getActivePlayEvent()?.clientEventId == activeEvent.clientEventId) {
        setActivePlayEvent(null)
    }
    setNowPlayingEventIds(getNowPlayingEventIds() - activeEvent.clientEventId)
    setNowPlayingTrackIdsInFlight(getNowPlayingTrackIdsInFlight() - activeEvent.trackId)
    if (getNowPlayingTrackId() == activeEvent.trackId) {
        setNowPlayingTrackId(null)
    }
    savePlaybackSnapshot()
}

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
