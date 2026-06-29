package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope

internal class LastFmPlaybackEventActionHost(
    private val scope: CoroutineScope,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val pendingPlayEventStore: PendingPlayEventStore,
    private val userPreferencesStore: UserPreferencesStore,
    private val getLastFmConnected: () -> Boolean,
    private val getScrobblingPaused: () -> Boolean,
    private val canUseServerRequests: () -> Boolean,
    private val canSendPlayEvents: () -> Boolean,
    private val getNowPlayingEventIds: () -> Set<String>,
    private val setNowPlayingEventIds: (Set<String>) -> Unit,
    private val getNowPlayingTrackIdsInFlight: () -> Set<String>,
    private val setNowPlayingTrackIdsInFlight: (Set<String>) -> Unit,
    private val getNowPlayingTrackId: () -> String?,
    private val setNowPlayingTrackId: (String?) -> Unit,
    private val setAccessToken: (String?) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val getActivePlayEvent: () -> ActivePlayEvent?,
    private val setActivePlayEvent: (ActivePlayEvent?) -> Unit,
    private val setPendingPlayEventCount: (Int) -> Unit,
    private val getLastFmConnection: () -> LastFmConnection,
    private val setLastFmConnection: (LastFmConnection) -> Unit,
    private val savePlaybackSnapshot: () -> Unit,
    private val getPlayerState: () -> PlayerState,
    private val getTracks: () -> List<Track>,
    private val getPlaybackQueue: () -> PlaybackQueue,
    private val getCompletingPlayEventIds: () -> Set<String>,
    private val setCompletingPlayEventIds: (Set<String>) -> Unit,
    private val getPendingPlayEventCount: () -> Int,
    private val setPendingPlayEventSyncProgress: (Pair<Int, Int>?) -> Unit,
    private val getPendingPlayEventSyncProgress: () -> Pair<Int, Int>?,
    private val setLibraryError: (String?) -> Unit,
) {
    fun sendNowPlayingEvent(
        activeEvent: ActivePlayEvent,
        track: Track? = getPlayerState().currentTrack?.takeIf { it.id == activeEvent.trackId },
        force: Boolean = false,
    ) {
        sendNowPlayingEventAction(
            scope = scope,
            activeEvent = activeEvent,
            track = track,
            force = force,
            getLastFmConnected = getLastFmConnected,
            getScrobblingPaused = getScrobblingPaused,
            canUseServerRequests = canUseServerRequests,
            canSendPlayEvents = canSendPlayEvents,
            getNowPlayingEventIds = getNowPlayingEventIds,
            setNowPlayingEventIds = setNowPlayingEventIds,
            getNowPlayingTrackIdsInFlight = getNowPlayingTrackIdsInFlight,
            setNowPlayingTrackIdsInFlight = setNowPlayingTrackIdsInFlight,
            getNowPlayingTrackId = getNowPlayingTrackId,
            setNowPlayingTrackId = setNowPlayingTrackId,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            markServerUnavailable = markServerUnavailable,
        )
    }

    fun clearNowPlayingEvent(activeEvent: ActivePlayEvent?) {
        clearNowPlayingEventAction(
            activeEvent = activeEvent,
            currentTrackId = getPlayerState().currentTrack?.id,
            getNowPlayingTrackId = getNowPlayingTrackId,
            setNowPlayingTrackId = setNowPlayingTrackId,
            getNowPlayingEventIds = getNowPlayingEventIds,
            setNowPlayingEventIds = setNowPlayingEventIds,
            getNowPlayingTrackIdsInFlight = getNowPlayingTrackIdsInFlight,
            setNowPlayingTrackIdsInFlight = setNowPlayingTrackIdsInFlight,
        )
    }

    fun ensureActivePlayEvent(track: Track, forceNew: Boolean = false) {
        ensureActivePlayEventAction(
            track = track,
            forceNew = forceNew,
            getLastFmConnected = getLastFmConnected,
            getScrobblingPaused = getScrobblingPaused,
            getActivePlayEvent = getActivePlayEvent,
            setActivePlayEvent = setActivePlayEvent,
            sendNowPlayingEvent = { event, eventTrack, force ->
                sendNowPlayingEvent(event, track = eventTrack, force = force)
            },
        )
    }

    fun queuePendingPlayEvent(activeEvent: ActivePlayEvent) {
        queuePendingPlayEventAction(
            activeEvent = activeEvent,
            getLastFmConnected = getLastFmConnected,
            getScrobblingPaused = getScrobblingPaused,
            getActivePlayEvent = getActivePlayEvent,
            setActivePlayEvent = setActivePlayEvent,
            getNowPlayingEventIds = getNowPlayingEventIds,
            setNowPlayingEventIds = setNowPlayingEventIds,
            getNowPlayingTrackIdsInFlight = getNowPlayingTrackIdsInFlight,
            setNowPlayingTrackIdsInFlight = setNowPlayingTrackIdsInFlight,
            getNowPlayingTrackId = getNowPlayingTrackId,
            setNowPlayingTrackId = setNowPlayingTrackId,
            pendingPlayEventStore = pendingPlayEventStore,
            setPendingPlayEventCount = setPendingPlayEventCount,
            getLastFmConnection = getLastFmConnection,
            setLastFmConnection = setLastFmConnection,
            userPreferencesStore = userPreferencesStore,
            savePlaybackSnapshot = savePlaybackSnapshot,
        )
    }

    fun syncPendingPlayEvents() {
        syncPendingPlayEventsAction(
            scope = scope,
            canUseServerRequests = canUseServerRequests,
            getPendingPlayEventCount = getPendingPlayEventCount,
            setPendingPlayEventCount = setPendingPlayEventCount,
            getPendingPlayEventSyncProgress = getPendingPlayEventSyncProgress,
            setPendingPlayEventSyncProgress = setPendingPlayEventSyncProgress,
            pendingPlayEventStore = pendingPlayEventStore,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            getLastFmConnection = getLastFmConnection,
            setLastFmConnection = setLastFmConnection,
            userPreferencesStore = userPreferencesStore,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun discardActivePlayEvent(activeEvent: ActivePlayEvent) {
        discardActivePlayEventAction(
            activeEvent = activeEvent,
            getActivePlayEvent = getActivePlayEvent,
            setActivePlayEvent = setActivePlayEvent,
            getNowPlayingEventIds = getNowPlayingEventIds,
            setNowPlayingEventIds = setNowPlayingEventIds,
            getNowPlayingTrackIdsInFlight = getNowPlayingTrackIdsInFlight,
            setNowPlayingTrackIdsInFlight = setNowPlayingTrackIdsInFlight,
            getNowPlayingTrackId = getNowPlayingTrackId,
            setNowPlayingTrackId = setNowPlayingTrackId,
            savePlaybackSnapshot = savePlaybackSnapshot,
        )
    }

    fun completeActivePlayEvent(force: Boolean = false) {
        completeActivePlayEventAction(
            scope = scope,
            force = force,
            getActivePlayEvent = getActivePlayEvent,
            setActivePlayEvent = setActivePlayEvent,
            trackForPlayEvent = { activeEvent ->
                trackForPlayEvent(
                    activeEvent = activeEvent,
                    playerState = getPlayerState(),
                    tracks = getTracks(),
                    playbackQueue = getPlaybackQueue(),
                )
            },
            discardActivePlayEvent = ::discardActivePlayEvent,
            getLastFmConnected = getLastFmConnected,
            getScrobblingPaused = getScrobblingPaused,
            canSendPlayEvents = canSendPlayEvents,
            queuePendingPlayEvent = ::queuePendingPlayEvent,
            getCompletingPlayEventIds = getCompletingPlayEventIds,
            setCompletingPlayEventIds = setCompletingPlayEventIds,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            getNowPlayingEventIds = getNowPlayingEventIds,
            setNowPlayingEventIds = setNowPlayingEventIds,
            getNowPlayingTrackId = getNowPlayingTrackId,
            setNowPlayingTrackId = setNowPlayingTrackId,
            savePlaybackSnapshot = savePlaybackSnapshot,
        )
    }
}
