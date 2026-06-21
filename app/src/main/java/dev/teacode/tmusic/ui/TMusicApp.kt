package dev.teacode.tmusic.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.teacode.tmusic.BuildConfig
import dev.teacode.tmusic.auth.GoogleSignInTokenProvider
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.data.AppConfig
import dev.teacode.tmusic.data.AppCacheStore
import dev.teacode.tmusic.data.AppUpdateChecker
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.PendingLibraryMutationStore
import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.TMusicApiException
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.data.isAppVersionNewer
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.RecentLibraryItemType
import dev.teacode.tmusic.domain.ScrobbleState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

@Composable
@NonRestartableComposable
fun TMusicApp(
    authRepository: RemoteAuthRepository,
    musicRepository: RemoteMusicRepository,
    googleSignInTokenProvider: GoogleSignInTokenProvider,
    userPreferencesStore: UserPreferencesStore,
    libraryCacheStore: LibraryCacheStore,
    offlineLyricsStore: OfflineLyricsStore,
    artworkCacheStore: ArtworkCacheStore,
    playbackStateStore: PlaybackStateStore,
    pendingLibraryMutationStore: PendingLibraryMutationStore,
    pendingPlayEventStore: PendingPlayEventStore,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playbackSnapshotSaveMutex = remember { Mutex() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {}
    val mediaCache = remember {
        SimpleCache(
            File(context.cacheDir, MEDIA3_PLAYBACK_CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(2L * 1024L * 1024L * 1024L),
            StandaloneDatabaseProvider(context),
        )
    }
    val appCacheStore = remember(context) { AppCacheStore(context) }
    val primaryExoPlayer = remember(mediaCache) {
        createPlaybackPlayer(context, mediaCache, handleAudioFocus = true)
    }
    val secondaryExoPlayer = remember(mediaCache) {
        createPlaybackPlayer(context, mediaCache, handleAudioFocus = false)
    }
    var exoPlayer by remember { mutableStateOf(primaryExoPlayer) }
    val standbyExoPlayer = if (exoPlayer === primaryExoPlayer) secondaryExoPlayer else primaryExoPlayer
    val appUpdateChecker = remember {
        AppUpdateChecker(
            loadConfig = authRepository::appUpdateConfig,
            githubRepository = BuildConfig.GITHUB_RELEASES_REPOSITORY,
        )
    }

    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val initialState = remember {
        loadInitialAppState(
            authRepository = authRepository,
            musicRepository = musicRepository,
            userPreferencesStore = userPreferencesStore,
            libraryCacheStore = libraryCacheStore,
            playbackStateStore = playbackStateStore,
        )
    }

    var account by remember { mutableStateOf(initialState.account) }
    var signingIn by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var accessToken by remember { mutableStateOf(authRepository.accessToken()) }
    var canContinueOffline by remember { mutableStateOf(initialState.canContinueOffline) }
    var useLocalBackend by remember { mutableStateOf(userPreferencesStore.useLocalBackend()) }
    var offlineOnly by remember { mutableStateOf(initialState.offlineOnly && initialState.canContinueOffline) }
    var syncMode by remember {
        mutableStateOf(if (initialState.offlineOnly && initialState.canContinueOffline) SyncMode.OfflineOnly else SyncMode.Offline)
    }

    var destination by remember { mutableStateOf(AppDestination()) }
    var backStack by remember { mutableStateOf<List<AppDestination>>(emptyList()) }
    var playerState by remember { mutableStateOf(initialState.playerState) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var fullPlayerOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var playbackStartSerial by remember { mutableStateOf(0L) }
    var playbackBufferedFraction by remember { mutableStateOf(0f) }
    var artworkBitmaps by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var artworkLoadsInProgress by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lyricsByTrackId by remember { mutableStateOf<Map<String, TrackLyrics>>(emptyMap()) }
    var lyricsLoadsInProgress by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lyricsUnavailableIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var profileAvatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var profileAvatarLoadKey by remember { mutableStateOf<String?>(null) }
    var prefetchedPlaybackUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var playbackUrlPrefetchesInProgress by remember { mutableStateOf<Set<String>>(emptySet()) }
    var gaplessPlaybackRequest by remember { mutableStateOf<GaplessPlaybackRequest?>(null) }
    var gaplessMediaQueueIndices by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var gaplessMediaUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var streamRequestSerial by remember { mutableStateOf(0L) }
    val queueStartRequestSerial = remember { AtomicLong(0L) }
    var pendingTransitionArtworkTrackId by remember { mutableStateOf<String?>(null) }
    var playbackQueueGeneration by remember { mutableStateOf(0L) }
    var playbackQueue by remember { mutableStateOf(initialState.playbackQueue) }
    var pendingPlaybackRestore by remember { mutableStateOf(initialState.savedPlayback) }
    var requestedQueueAdvance by remember { mutableStateOf(0) }
    var requestedQueueWrapPause by remember { mutableStateOf(0) }
    var requestedCurrentTrackRestart by remember { mutableStateOf(0) }
    var requestedNextPrefetch by remember { mutableStateOf(0) }
    var completingPlayEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var nowPlayingEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var nowPlayingTrackId by remember { mutableStateOf<String?>(null) }
    var scrobblingPaused by remember { mutableStateOf(userPreferencesStore.scrobblingPaused()) }
    var shuffleEnabled by remember { mutableStateOf(userPreferencesStore.shuffleEnabled()) }
    var showLyrics by remember { mutableStateOf(userPreferencesStore.showLyrics()) }
    var downloadUsingCellular by remember { mutableStateOf(userPreferencesStore.downloadUsingCellular()) }
    var crossfadeSeconds by remember { mutableStateOf(userPreferencesStore.crossfadeSeconds()) }
    var equalizerAvailable by remember { mutableStateOf(isSystemEqualizerAvailable(context)) }
    var preparedCrossfade by remember { mutableStateOf<PreparedCrossfade?>(null) }
    var crossfadeJob by remember { mutableStateOf<Job?>(null) }
    var crossfadePreparationSerial by remember { mutableStateOf(0L) }
    var repeatMode by remember {
        mutableStateOf(
            runCatching {
                PlaybackRepeatMode.valueOf(userPreferencesStore.playbackRepeatMode())
            }.getOrDefault(PlaybackRepeatMode.None),
        )
    }
    var pendingPlayEventCount by remember { mutableStateOf(pendingPlayEventStore.count()) }
    var pendingPlayEventSyncProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingLibraryMutationCount by remember { mutableStateOf(pendingLibraryMutationStore.count()) }
    var pendingLastFmToken by remember { mutableStateOf(lastFmAuthTokenStore.token()) }
    var waitingForLastFmSession by remember { mutableStateOf(pendingLastFmToken != null) }
    var lastFmConnection by remember {
        mutableStateOf(
            (
                userPreferencesStore.lastFmConnection()
                    ?: LastFmConnection(
                        username = null,
                        state = ScrobbleState.NeedsAuth,
                        pendingScrobbles = 0,
                    )
                ).copy(pendingScrobbles = pendingPlayEventCount),
        )
    }
    val activePlayEventState = remember { mutableStateOf(initialState.activePlayEvent) }
    val lastFmConnected = lastFmConnection.state == ScrobbleState.Ready &&
        !lastFmConnection.username.isNullOrBlank()
    val lastFmConnectedState = rememberUpdatedState(lastFmConnected)
    val playerStateState = rememberUpdatedState(playerState)
    val playbackQueueState = rememberUpdatedState(playbackQueue)
    val gaplessPlaybackRequestState = rememberUpdatedState(gaplessPlaybackRequest)
    val gaplessMediaQueueIndicesState = rememberUpdatedState(gaplessMediaQueueIndices)
    val gaplessMediaUrlsState = rememberUpdatedState(gaplessMediaUrls)
    val repeatModeState = rememberUpdatedState(repeatMode)

    fun clearLastFmAccountState() {
        pendingLastFmToken = null
        waitingForLastFmSession = false
        lastFmAuthTokenStore.clear()
        userPreferencesStore.clearLastFmConnection()
        lastFmConnection = LastFmConnection(
            username = null,
            state = ScrobbleState.NeedsAuth,
            pendingScrobbles = pendingPlayEventCount,
        )
    }

    var playlists by remember {
        mutableStateOf(initialState.cachedLibrary.playlists.sanitizeClientPlaylists())
    }
    var tracks by remember { mutableStateOf(initialState.tracks) }
    var recentAlbums by remember { mutableStateOf<List<LibraryAlbum>>(emptyList()) }
    var databaseTrackCount by remember { mutableStateOf<Int?>(null) }
    var offlineAlbumIds by remember { mutableStateOf(userPreferencesStore.offlineAlbumIds()) }
    var artists by remember { mutableStateOf(initialState.tracks.downloadedArtists()) }
    var albums by remember { mutableStateOf(initialState.tracks.downloadedAlbums(offlineAlbumIds)) }
    var savedAlbums by remember {
        mutableStateOf(
            initialState.cachedLibrary.savedAlbums.map { album ->
                album.copy(
                    savedByCurrentUser = true,
                    isOfflineEnabled = album.isOfflineEnabled || album.id in offlineAlbumIds,
                )
            },
        )
    }
    var albumsByArtist by remember { mutableStateOf<Map<String, List<LibraryAlbum>>>(emptyMap()) }
    var appearsOnByArtist by remember { mutableStateOf<Map<String, List<LibraryAlbum>>>(emptyMap()) }
    var looseTracksByArtist by remember { mutableStateOf<Map<String, List<Track>>>(emptyMap()) }
    var similarArtistsByArtist by remember { mutableStateOf<Map<String, List<LibraryArtist>>>(emptyMap()) }
    var artistAlbumLoadsInProgress by remember { mutableStateOf<Set<String>>(emptySet()) }
    var similarArtistLoadsInProgress by remember { mutableStateOf<Set<String>>(emptySet()) }
    var albumTrackLoadsInProgress by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistTrackLoadsInProgress by remember { mutableStateOf<Set<String>>(emptySet()) }
    var albumDownloadJobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    var playlistDownloadJobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    var libraryPaging by remember { mutableStateOf(LibraryPagingState()) }
    var recentAlbumsPaging by remember { mutableStateOf(RecentAlbumsPagingState()) }
    var albumTrackHasMoreById by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var playlistTrackHasMoreById by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var albumTracksById by remember { mutableStateOf<Map<String, List<Track>>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchFocusRequestSerial by remember { mutableStateOf(0) }
    var searchResults by remember { mutableStateOf(LibrarySearchResults(emptyList(), emptyList(), emptyList())) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchTrackOffset by remember { mutableStateOf(0) }
    var searchHasMore by remember { mutableStateOf(false) }
    var favoriteSyncTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recentItems by remember { mutableStateOf(userPreferencesStore.recentLibraryItems()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryLoadSerial by remember { mutableStateOf(0) }
    var libraryLoadJob by remember { mutableStateOf<Job?>(null) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var libraryNotice by remember { mutableStateOf<String?>(null) }
    val appUpdateController = remember {
        AppUpdateController(
            context = context,
            userPreferencesStore = userPreferencesStore,
            appUpdateChecker = appUpdateChecker,
            currentVersion = BuildConfig.VERSION_NAME,
            initialUpdate = userPreferencesStore.cachedAppUpdate()
                ?.takeIf { update ->
                    update.forceUpdate ||
                        if (update.latestVersionCode != null) {
                            update.latestVersionCode > BuildConfig.VERSION_CODE
                        } else {
                            isAppVersionNewer(update.version, BuildConfig.VERSION_NAME)
                        }
                },
            onNotice = { message -> libraryNotice = message },
            onError = { message -> libraryError = message },
        )
    }
    var trackForPlaylistAdd by remember { mutableStateOf<Track?>(null) }
    var artistChoices by remember { mutableStateOf<List<LibraryArtist>>(emptyList()) }
    var playlistPickerPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var playlistPickerLoading by remember { mutableStateOf(false) }
    var playlistMetadataLoaded by remember { mutableStateOf(false) }
    var duplicatePlaylistForAdd by remember { mutableStateOf<Playlist?>(null) }
    var playlistAddInProgress by remember { mutableStateOf(false) }
    var libraryMutationSyncInProgress by remember { mutableStateOf(false) }
    var downloadedSizeBytes by remember { mutableStateOf(0L) }
    var cacheSizeBytes by remember { mutableStateOf(0L) }
    var queueInsertionAnchorTrackId by remember { mutableStateOf<String?>(null) }
    var queueInsertionCursor by remember { mutableStateOf<Int?>(null) }
    val offlinePlayableTrackIds = remember(tracks, downloadedSizeBytes, cacheSizeBytes) {
        tracks
            .filter { track ->
                track.downloadState == DownloadState.Downloaded ||
                    musicRepository.localPlaybackUrl(track.id) != null ||
                    musicRepository.cachedPlaybackUrl(track.id) != null
            }
            .map { it.id }
            .toSet()
    }

    fun hasNetworkConnection(): Boolean {
        return context.hasUsableNetworkConnection(useLocalBackend)
    }

    fun canUseServerRequests(): Boolean {
        return account != null &&
            !offlineOnly &&
            syncMode != SyncMode.Offline &&
            syncMode != SyncMode.OfflineOnly &&
            hasNetworkConnection()
    }

    fun canAttemptMetadataRequest(): Boolean {
        return account != null &&
            !offlineOnly &&
            syncMode != SyncMode.OfflineOnly &&
            hasNetworkConnection()
    }

    fun canUseMediaServerRequests(): Boolean {
        return account != null &&
            !offlineOnly &&
            syncMode != SyncMode.OfflineOnly &&
            hasNetworkConnection() &&
            account?.canPlayMedia != false
    }

    fun mediaDisabledMessage(): String {
        return "Media playback is disabled for this account."
    }

    fun disableMediaPlaybackForAccount() {
        account = account?.copy(canPlayMedia = false)
    }

    fun canCheckAppUpdates(): Boolean {
        return account != null &&
            !offlineOnly &&
            hasNetworkConnection()
    }

    val appUpdateDebugStatus = "account=${account != null} offlineOnly=$offlineOnly syncMode=$syncMode network=${hasNetworkConnection()}"
    AppUpdateEffects(
        controller = appUpdateController,
        context = context,
        accountId = account?.id,
        offlineOnly = offlineOnly,
        useLocalBackend = useLocalBackend,
        canCheck = canCheckAppUpdates(),
        debugStatus = appUpdateDebugStatus,
    )

    suspend fun checkForAppUpdate(manual: Boolean) {
        appUpdateController.checkForUpdate(
            manual = manual,
            canCheck = canCheckAppUpdates(),
            debugStatus = appUpdateDebugStatus,
        )
    }

    fun canUseNetworkForCollectionDownloads(): Boolean {
        if (!canUseMediaServerRequests()) {
            return false
        }
        if (downloadUsingCellular) {
            return true
        }
        return !context.isUsingCellularNetwork()
    }

    fun markServerUnavailable(error: Throwable) {
        if (error.isAppUpdateRequiredError()) {
            scope.launch {
                checkForAppUpdate(manual = false)
            }
            return
        }
        if (error.isServerAvailabilityFailure()) {
            syncMode = SyncMode.Offline
        }
    }

    val canSendPlayEventsState = rememberUpdatedState(
        lastFmConnected && canUseServerRequests() && !scrobblingPaused,
    )

    fun savePlaybackSnapshot(
        state: PlayerState = playerState,
        queue: PlaybackQueue = playbackQueue,
        runtimeOnly: Boolean = false,
    ) {
        val snapshot = capturePlaybackSnapshot(
            player = exoPlayer,
            state = state,
            queue = queue,
            activeEvent = activePlayEventState.value,
        ) ?: return
        scope.launch(Dispatchers.IO) {
            playbackSnapshotSaveMutex.withLock {
                if (runtimeOnly) {
                    playbackStateStore.saveRuntime(snapshot)
                } else {
                    playbackStateStore.save(snapshot)
                }
            }
        }
    }

    fun savePlaybackRuntimeSnapshot(
        state: PlayerState = playerState,
        queue: PlaybackQueue = playbackQueue,
    ) {
        savePlaybackSnapshot(
            state = state,
            queue = queue,
            runtimeOnly = true,
        )
    }

    PlaybackPersistenceEffect {
        savePlaybackSnapshot(
            state = playerStateState.value,
            queue = playbackQueueState.value,
        )
    }

    fun clearGaplessPlaybackState() {
        gaplessPlaybackRequest = null
        gaplessMediaQueueIndices = emptyMap()
        gaplessMediaUrls = emptyMap()
    }

    fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        preparedCrossfade?.player
            ?.takeIf { it !== exoPlayer }
            ?.run {
                stop()
                clearMediaItems()
                volume = 0f
            }
        preparedCrossfade = null
        standbyExoPlayer.stop()
        standbyExoPlayer.clearMediaItems()
        standbyExoPlayer.volume = 0f
        exoPlayer.volume = 1f
        exoPlayer.configurePlaybackAudioFocus(handleAudioFocus = true)
        crossfadePreparationSerial += 1
    }

    val playbackErrorActionHost = object {
        fun handlePlaybackPlayerError(message: String, httpStatusCode: Int?): Boolean {
            return handlePlaybackPlayerErrorAction(
            scope = scope,
            message = message,
            httpStatusCode = httpStatusCode,
            exoPlayer = exoPlayer,
            getPlayerState = { playerStateState.value },
            setPlayerState = { playerState = it },
            getPlaybackQueue = { playbackQueueState.value },
            getAccount = { account },
            getPrefetchedPlaybackUrls = { prefetchedPlaybackUrls },
            setPrefetchedPlaybackUrls = { prefetchedPlaybackUrls = it },
            getPlaybackUrlPrefetchesInProgress = { playbackUrlPrefetchesInProgress },
            setPlaybackUrlPrefetchesInProgress = { playbackUrlPrefetchesInProgress = it },
            incrementStreamRequestSerial = {
                streamRequestSerial += 1
                streamRequestSerial
            },
            getStreamRequestSerial = { streamRequestSerial },
            clearGaplessPlaybackState = ::clearGaplessPlaybackState,
            cancelCrossfade = ::cancelCrossfade,
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            mediaDisabledMessage = ::mediaDisabledMessage,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = { accessToken = it },
            setPlaybackBufferedFraction = { playbackBufferedFraction = it },
            incrementPlaybackStartSerial = { playbackStartSerial += 1 },
            incrementRequestedNextPrefetch = { requestedNextPrefetch += 1 },
            disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
            markServerUnavailable = ::markServerUnavailable,
            setPlayerError = { playerError = it },
        )
    }

    }

    fun handlePlaybackPlayerError(message: String, httpStatusCode: Int?): Boolean {
        return playbackErrorActionHost.handlePlaybackPlayerError(message, httpStatusCode)
    }

    fun sendNowPlayingEvent(
        activeEvent: ActivePlayEvent,
        track: Track? = playerState.currentTrack?.takeIf { it.id == activeEvent.trackId },
        force: Boolean = false,
    ) {
        track ?: return
        if (!lastFmConnectedState.value || scrobblingPaused) {
            return
        }
        if (!force && activeEvent.clientEventId in nowPlayingEventIds) {
            return
        }
        if (force && !canUseServerRequests()) {
            return
        } else if (!force && !canSendPlayEventsState.value) {
            return
        }

        nowPlayingEventIds = nowPlayingEventIds + activeEvent.clientEventId
        scope.launch {
            runCatching {
                musicRepository.sendNowPlaying(activeEvent.trackId)
            }.onSuccess {
                accessToken = authRepository.accessToken()
                nowPlayingTrackId = activeEvent.trackId
            }.onFailure {
                nowPlayingEventIds = nowPlayingEventIds - activeEvent.clientEventId
                markServerUnavailable(it)
            }
        }
    }

    fun clearNowPlayingEvent(activeEvent: ActivePlayEvent?) {
        activeEvent?.let { event ->
            nowPlayingEventIds = nowPlayingEventIds - event.clientEventId
        }
        val trackId = activeEvent?.trackId ?: playerState.currentTrack?.id
        if (trackId != null && nowPlayingTrackId == trackId) {
            nowPlayingTrackId = null
        } else if (activeEvent == null) {
            nowPlayingTrackId = null
        }
    }

    fun ensureActivePlayEvent(track: Track, forceNew: Boolean = false) {
        if (!lastFmConnectedState.value || scrobblingPaused) {
            activePlayEventState.value = null
            return
        }
        val trackId = track.id
        val activeEvent = activePlayEventState.value
        if (!forceNew && activeEvent?.trackId == trackId) {
            sendNowPlayingEvent(activeEvent, track = track)
            return
        }
        val nextEvent = newActivePlayEvent(track)
        activePlayEventState.value = nextEvent
        sendNowPlayingEvent(nextEvent, track = track)
    }

    fun trackForPlayEvent(activeEvent: ActivePlayEvent): Track? {
        return trackForPlayEvent(
            activeEvent = activeEvent,
            playerState = playerStateState.value,
            tracks = tracks,
            playbackQueue = playbackQueueState.value,
        )
    }

    fun queuePendingPlayEvent(activeEvent: ActivePlayEvent) {
        if (!lastFmConnectedState.value || scrobblingPaused) {
            return
        }
        pendingPlayEventStore.append(activeEvent.toPendingPlayEvent())
        pendingPlayEventCount = pendingPlayEventStore.count()
        lastFmConnection = lastFmConnection.copy(pendingScrobbles = pendingPlayEventCount)
        userPreferencesStore.setLastFmConnection(lastFmConnection)
        if (activePlayEventState.value?.clientEventId == activeEvent.clientEventId) {
            activePlayEventState.value = null
            nowPlayingEventIds = nowPlayingEventIds - activeEvent.clientEventId
            if (nowPlayingTrackId == activeEvent.trackId) {
                nowPlayingTrackId = null
            }
            savePlaybackSnapshot()
        }
    }

    fun syncPendingPlayEvents() {
        syncPendingPlayEventsAction(
            scope = scope,
            canUseServerRequests = ::canUseServerRequests,
            getPendingPlayEventCount = { pendingPlayEventCount },
            setPendingPlayEventCount = { pendingPlayEventCount = it },
            getPendingPlayEventSyncProgress = { pendingPlayEventSyncProgress },
            setPendingPlayEventSyncProgress = { pendingPlayEventSyncProgress = it },
            pendingPlayEventStore = pendingPlayEventStore,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            getLastFmConnection = { lastFmConnection },
            setLastFmConnection = { lastFmConnection = it },
            userPreferencesStore = userPreferencesStore,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun discardActivePlayEvent(activeEvent: ActivePlayEvent) {
        if (activePlayEventState.value?.clientEventId == activeEvent.clientEventId) {
            activePlayEventState.value = null
        }
        nowPlayingEventIds = nowPlayingEventIds - activeEvent.clientEventId
        if (nowPlayingTrackId == activeEvent.trackId) {
            nowPlayingTrackId = null
        }
        savePlaybackSnapshot()
    }

    fun completeActivePlayEvent(force: Boolean = false) {
        completeActivePlayEventAction(
            scope = scope,
            force = force,
            getActivePlayEvent = { activePlayEventState.value },
            setActivePlayEvent = { activePlayEventState.value = it },
            trackForPlayEvent = ::trackForPlayEvent,
            discardActivePlayEvent = ::discardActivePlayEvent,
            getLastFmConnected = { lastFmConnectedState.value },
            getScrobblingPaused = { scrobblingPaused },
            canSendPlayEvents = { canSendPlayEventsState.value },
            queuePendingPlayEvent = ::queuePendingPlayEvent,
            getCompletingPlayEventIds = { completingPlayEventIds },
            setCompletingPlayEventIds = { completingPlayEventIds = it },
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            getNowPlayingEventIds = { nowPlayingEventIds },
            setNowPlayingEventIds = { nowPlayingEventIds = it },
            getNowPlayingTrackId = { nowPlayingTrackId },
            setNowPlayingTrackId = { nowPlayingTrackId = it },
            savePlaybackSnapshot = ::savePlaybackSnapshot,
        )
    }

    PlaybackEffects(
        exoPlayer = exoPlayer,
        primaryExoPlayer = primaryExoPlayer,
        secondaryExoPlayer = secondaryExoPlayer,
        mediaCache = mediaCache,
        crossfadeJob = crossfadeJob,
        playerState = playerState,
        setPlayerState = { playerState = it },
        playerStateState = playerStateState,
        playbackQueue = playbackQueue,
        setPlaybackQueue = { playbackQueue = it },
        playbackQueueState = playbackQueueState,
        repeatMode = repeatMode,
        repeatModeState = repeatModeState,
        gaplessPlaybackRequest = gaplessPlaybackRequest,
        setGaplessPlaybackRequest = { gaplessPlaybackRequest = it },
        gaplessPlaybackRequestState = gaplessPlaybackRequestState,
        gaplessMediaQueueIndices = gaplessMediaQueueIndices,
        setGaplessMediaQueueIndices = { gaplessMediaQueueIndices = it },
        gaplessMediaQueueIndicesState = gaplessMediaQueueIndicesState,
        gaplessMediaUrls = gaplessMediaUrls,
        setGaplessMediaUrls = { gaplessMediaUrls = it },
        gaplessMediaUrlsState = gaplessMediaUrlsState,
        activePlayEventState = activePlayEventState,
        playbackStartSerial = playbackStartSerial,
        pendingPlaybackRestore = pendingPlaybackRestore,
        onBufferedFractionChanged = { playbackBufferedFraction = it },
        onCompleteActivePlayEvent = { completeActivePlayEvent(force = true) },
        onRequestCurrentTrackRestart = { requestedCurrentTrackRestart += 1 },
        onRequestQueueAdvance = { requestedQueueAdvance += 1 },
        onRequestQueueWrapPause = { requestedQueueWrapPause += 1 },
        onQueueTransition = { nextQueue, nextState, direction ->
            logPlaybackDebug(
                "queue transition direction=$direction state=${nextState.currentTrack?.debugTrack()} " +
                    nextQueue.debugSummary(),
            )
            playbackQueue = nextQueue
            playerState = nextState
            pendingTransitionArtworkTrackId = nextState.currentTrack?.listArtworkKey()
        },
        onEnsureActivePlayEvent = ::ensureActivePlayEvent,
        onClearNowPlayingEvent = ::clearNowPlayingEvent,
        onRequestNextPrefetch = { requestedNextPrefetch += 1 },
        onPlayerError = ::handlePlaybackPlayerError,
        onDisposePlayer = {
            savePlaybackSnapshot(
                state = playerStateState.value,
                queue = playbackQueueState.value,
            )
        },
        savePlaybackSnapshot = ::savePlaybackSnapshot,
        savePlaybackRuntimeSnapshot = ::savePlaybackRuntimeSnapshot,
    )

    fun navigateTo(next: AppDestination) {
        if (next != destination) {
            backStack = backStack + destination
            destination = next
        }
    }

    fun goBack() {
        if (backStack.isNotEmpty()) {
            destination = backStack.last()
            backStack = backStack.dropLast(1)
        }
    }

    val authSessionActionHost = object {
        suspend fun signOutLocalSession(message: String? = null) {
        signOutLocalSessionAction(
            message = message,
            authRepository = authRepository,
            googleSignInTokenProvider = googleSignInTokenProvider,
            userPreferencesStore = userPreferencesStore,
            libraryCacheStore = libraryCacheStore,
            playbackStateStore = playbackStateStore,
            pendingPlayEventStore = pendingPlayEventStore,
            pendingLibraryMutationStore = pendingLibraryMutationStore,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            getLibraryLoadSerial = { libraryLoadSerial },
            setLibraryLoadSerial = { libraryLoadSerial = it },
            getLibraryLoadJob = { libraryLoadJob },
            setLibraryLoadJob = { libraryLoadJob = it },
            setAccount = { account = it },
            setAccessToken = { accessToken = it },
            setCanContinueOffline = { canContinueOffline = it },
            setOfflineOnly = { offlineOnly = it },
            setSyncMode = { syncMode = it },
            setLibraryLoading = { libraryLoading = it },
            setLibraryError = { libraryError = it },
            setLibraryNotice = { libraryNotice = it },
            setPlaylists = { playlists = it },
            setTracks = { tracks = it },
            setArtists = { artists = it },
            setAlbums = { albums = it },
            setSavedAlbums = { savedAlbums = it },
            setRecentAlbums = { recentAlbums = it },
            setDatabaseTrackCount = { databaseTrackCount = it },
            setAlbumsByArtist = { albumsByArtist = it },
            setAppearsOnByArtist = { appearsOnByArtist = it },
            setLooseTracksByArtist = { looseTracksByArtist = it },
            setSimilarArtistsByArtist = { similarArtistsByArtist = it },
            resetLibraryPaging = {
                libraryPaging = libraryPaging.copy(
                    artistNextOffset = 0,
                    albumNextOffset = 0,
                    artistHasMore = true,
                    albumHasMore = true,
                )
            },
            setRecentAlbumsPaging = { recentAlbumsPaging = it },
            setAlbumTrackHasMoreById = { albumTrackHasMoreById = it },
            setPlaylistTrackHasMoreById = { playlistTrackHasMoreById = it },
            setAlbumTracksById = { albumTracksById = it },
            setSearchResults = { searchResults = it },
            setPlayerState = { playerState = it },
            setPlaybackQueue = { playbackQueue = it },
            clearGaplessPlaybackState = ::clearGaplessPlaybackState,
            clearCrossfadeState = {
                crossfadeJob?.cancel()
                crossfadeJob = null
                preparedCrossfade = null
            },
            setActivePlayEvent = { activePlayEventState.value = it },
            setPendingPlayEventCount = { pendingPlayEventCount = it },
            setPendingPlayEventSyncProgress = { pendingPlayEventSyncProgress = it },
            setPendingLibraryMutationCount = { pendingLibraryMutationCount = it },
            setPendingLastFmToken = { pendingLastFmToken = it },
            setWaitingForLastFmSession = { waitingForLastFmSession = it },
            setLastFmConnection = { lastFmConnection = it },
            setScrobblingPaused = { scrobblingPaused = it },
            setShuffleEnabled = { shuffleEnabled = it },
            setRepeatMode = { repeatMode = it },
            setProfileAvatarBitmap = { profileAvatarBitmap = it },
            setProfileAvatarLoadKey = { profileAvatarLoadKey = it },
            setPrefetchedPlaybackUrls = { prefetchedPlaybackUrls = it },
            setPlaybackUrlPrefetchesInProgress = { playbackUrlPrefetchesInProgress = it },
            setTrackForPlaylistAdd = { trackForPlaylistAdd = it },
            setPlaylistPickerPlaylists = { playlistPickerPlaylists = it },
            setPlaylistPickerLoading = { playlistPickerLoading = it },
            setPlaylistMetadataLoaded = { playlistMetadataLoaded = it },
            setDuplicatePlaylistForAdd = { duplicatePlaylistForAdd = it },
            setPlaylistAddInProgress = { playlistAddInProgress = it },
            setFullPlayerOpen = { fullPlayerOpen = it },
            setQueueOpen = { queueOpen = it },
            setDestination = { destination = it },
            setBackStack = { backStack = it },
            setAuthError = { authError = it },
        )
    }

    }

    suspend fun signOutLocalSession(message: String? = null) {
        authSessionActionHost.signOutLocalSession(message)
    }

    val libraryLoadActionHost = object {
        fun loadLibrary(targetDestination: AppDestination = destination) {
        loadLibraryAction(
            scope = scope,
            targetDestination = targetDestination,
            getLibraryLoadSerial = { libraryLoadSerial },
            setLibraryLoadSerial = { libraryLoadSerial = it },
            getLibraryLoadJob = { libraryLoadJob },
            setLibraryLoadJob = { libraryLoadJob = it },
            getLibraryLoading = { libraryLoading },
            setLibraryLoading = { libraryLoading = it },
            getOfflineOnly = { offlineOnly },
            getAccount = { account },
            setAccount = { account = it },
            authRepository = authRepository,
            musicRepository = musicRepository,
            setSyncMode = { syncMode = it },
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getTracks = { tracks },
            setTracks = { tracks = it },
            getRecentAlbums = { recentAlbums },
            setRecentAlbums = { recentAlbums = it },
            getDatabaseTrackCount = { databaseTrackCount },
            setDatabaseTrackCount = { databaseTrackCount = it },
            getArtists = { artists },
            setArtists = { artists = it },
            getAlbums = { albums },
            setAlbums = { albums = it },
            getSavedAlbums = { savedAlbums },
            setSavedAlbums = { savedAlbums = it },
            getOfflineAlbumIds = { offlineAlbumIds },
            getLibraryPaging = { libraryPaging },
            setLibraryPaging = { libraryPaging = it },
            getRecentAlbumsPaging = { recentAlbumsPaging },
            setRecentAlbumsPaging = { recentAlbumsPaging = it },
            libraryCacheStore = libraryCacheStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            getPendingPlayEventCount = { pendingPlayEventCount },
            setLastFmConnection = { lastFmConnection = it },
            userPreferencesStore = userPreferencesStore,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            setPendingLastFmToken = { pendingLastFmToken = it },
            setWaitingForLastFmSession = { waitingForLastFmSession = it },
            signOutLocalSession = ::signOutLocalSession,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    }

    fun loadLibrary(targetDestination: AppDestination = destination) {
        libraryLoadActionHost.loadLibrary(targetDestination)
    }

    fun saveLibraryCache() {
        libraryCacheStore.saveLibrary(
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
        )
    }

    fun enqueueLibraryMutation(type: String, payload: JSONObject) {
        pendingLibraryMutationStore.append(type = type, payload = payload)
        pendingLibraryMutationCount = pendingLibraryMutationStore.count()
    }

    fun syncPendingLibraryMutations() {
        syncPendingLibraryMutationsAction(
            scope = scope,
            canUseServerRequests = ::canUseServerRequests,
            getLibraryMutationSyncInProgress = { libraryMutationSyncInProgress },
            setLibraryMutationSyncInProgress = { libraryMutationSyncInProgress = it },
            getPendingLibraryMutationCount = { pendingLibraryMutationCount },
            setPendingLibraryMutationCount = { pendingLibraryMutationCount = it },
            pendingLibraryMutationStore = pendingLibraryMutationStore,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            loadLibrary = ::loadLibrary,
            markServerUnavailable = ::markServerUnavailable,
        )
    }

    LibrarySearchEffect(
        searchQuery = searchQuery,
        syncMode = syncMode,
        offlineOnly = offlineOnly,
        tracks = tracks,
        searchTrackOffset = searchTrackOffset,
        canUseServerRequests = ::canUseServerRequests,
        musicRepository = musicRepository,
        authRepository = authRepository,
        setSearchLoading = { searchLoading = it },
        getSearchResults = { searchResults },
        setSearchResults = { searchResults = it },
        setSearchHasMore = { searchHasMore = it },
        setAccessToken = { accessToken = it },
        markServerUnavailable = ::markServerUnavailable,
        setLibraryError = { libraryError = it },
    )

    fun addRecentItem(item: RecentLibraryItem) {
        userPreferencesStore.addRecentLibraryItem(item)
        recentItems = userPreferencesStore.recentLibraryItems()
    }

    fun refreshStorageStats() {
        scope.launch {
            val retainedArtworkKeys = downloadedArtworkCacheKeys(playlists, tracks)
            downloadedSizeBytes = musicRepository.downloadsSizeBytes() +
                offlineLyricsStore.sizeBytes() +
                artworkCacheStore.sizeBytesFor(retainedArtworkKeys)
            cacheSizeBytes = artworkCacheStore.sizeBytesExcluding(retainedArtworkKeys) +
                libraryCacheStore.sizeBytes() +
                musicRepository.musicCacheSizeBytes() +
                appCacheStore.androidCacheSizeBytes(setOf(MEDIA3_PLAYBACK_CACHE_DIR)) +
                mediaCache.cacheSpace
        }
    }

    fun clearRecentItems() {
        userPreferencesStore.clearRecentLibraryItems()
        recentItems = emptyList()
    }

    TimedMessageClearEffect(libraryNotice, timeoutMs = 2_500) { current ->
        if (libraryNotice == current) {
            libraryNotice = null
        }
    }
    TimedMessageClearEffect(libraryError, timeoutMs = 5_000) { current ->
        if (libraryError == current) {
            libraryError = null
        }
    }
    TimedMessageClearEffect(playerError, timeoutMs = 5_000) { current ->
        if (playerError == current) {
            playerError = null
        }
    }
    InitialStorageStatsEffect(::refreshStorageStats)

    suspend fun cachedArtworkBitmap(
        artworkKey: String,
        imageSize: ArtworkImageSize,
    ): ImageBitmap? {
        return cachedArtworkBitmapAction(
            artworkKey = artworkKey,
            imageSize = imageSize,
            artworkCacheStore = artworkCacheStore,
        )
    }

    suspend fun cacheArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize,
    ): ImageBitmap? {
        return cacheArtworkAction(
            artworkKey = artworkKey,
            imageSize = imageSize,
            artworkCacheStore = artworkCacheStore,
            libraryCacheStore = libraryCacheStore,
            musicRepository = musicRepository,
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            getArtists = { artists },
            getSearchResults = { searchResults },
            getSimilarArtistsByArtist = { similarArtistsByArtist },
            getPlaylists = { playlists },
            getTracks = { tracks },
            getArtworkLoadsInProgress = { artworkLoadsInProgress },
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            setCacheSizeBytes = { cacheSizeBytes = it },
        )
    }

    fun loadArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize = ArtworkImageSize.AlbumGrid,
    ) {
        loadArtworkAction(
            scope = scope,
            artworkKey = artworkKey,
            imageSize = imageSize,
            getArtworkBitmaps = { artworkBitmaps },
            setArtworkBitmaps = { artworkBitmaps = it },
            getArtworkLoadsInProgress = { artworkLoadsInProgress },
            setArtworkLoadsInProgress = { artworkLoadsInProgress = it },
            cacheArtwork = ::cacheArtwork,
            disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
        )
    }

    fun loadLyrics(track: Track) {
        loadLyricsAction(
            scope = scope,
            track = track,
            showLyrics = showLyrics,
            getLyricsByTrackId = { lyricsByTrackId },
            setLyricsByTrackId = { lyricsByTrackId = it },
            getLyricsUnavailableIds = { lyricsUnavailableIds },
            setLyricsUnavailableIds = { lyricsUnavailableIds = it },
            getLyricsLoadsInProgress = { lyricsLoadsInProgress },
            setLyricsLoadsInProgress = { lyricsLoadsInProgress = it },
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
            markServerUnavailable = ::markServerUnavailable,
        )
    }

    fun refreshLyrics(track: Track) {
        refreshLyricsAction(
            scope = scope,
            track = track,
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            isOfflineOnly = { offlineOnly },
            getSyncMode = { syncMode },
            getAccount = { account },
            mediaDisabledMessage = ::mediaDisabledMessage,
            getLyricsByTrackId = { lyricsByTrackId },
            setLyricsByTrackId = { lyricsByTrackId = it },
            getLyricsUnavailableIds = { lyricsUnavailableIds },
            setLyricsUnavailableIds = { lyricsUnavailableIds = it },
            getLyricsLoadsInProgress = { lyricsLoadsInProgress },
            setLyricsLoadsInProgress = { lyricsLoadsInProgress = it },
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    CurrentTrackLyricsEffect(
        playerState = playerState,
        syncMode = syncMode,
        offlineOnly = offlineOnly,
        showLyrics = showLyrics,
        loadLyrics = ::loadLyrics,
    )

    fun resolveCachedArtist(artistName: String): LibraryArtist? {
        return resolveCachedArtist(
            artistName = artistName,
            artists = artists,
            searchResults = searchResults,
            similarArtistsByArtist = similarArtistsByArtist,
        )
    }

    suspend fun cacheDownloadedAssets(track: Track) {
        cacheDownloadedAssetsAction(
            track = track,
            getTracks = { tracks },
            setTracks = { tracks = it },
            getArtworkBitmaps = { artworkBitmaps },
            setArtworkBitmaps = { artworkBitmaps = it },
            cacheArtwork = ::cacheArtwork,
            resolveCachedArtist = ::resolveCachedArtist,
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            getLyricsByTrackId = { lyricsByTrackId },
            setLyricsByTrackId = { lyricsByTrackId = it },
        )
    }

    PendingTransitionArtworkEffect(
        pendingArtworkKey = pendingTransitionArtworkTrackId,
        loadArtwork = ::loadArtwork,
        clearPendingArtworkKey = { pendingTransitionArtworkTrackId = null },
    )

    fun loadProfileAvatar(currentAccount: Account) {
        loadProfileAvatarAction(
            scope = scope,
            currentAccount = currentAccount,
            getProfileAvatarLoadKey = { profileAvatarLoadKey },
            setProfileAvatarLoadKey = { profileAvatarLoadKey = it },
            getProfileAvatarBitmap = { profileAvatarBitmap },
            setProfileAvatarBitmap = { profileAvatarBitmap = it },
            getPlaylists = { playlists },
            getTracks = { tracks },
            getArtworkLoadsInProgress = { artworkLoadsInProgress },
            artworkCacheStore = artworkCacheStore,
            libraryCacheStore = libraryCacheStore,
            cachedArtworkBitmap = ::cachedArtworkBitmap,
            setCacheSizeBytes = { cacheSizeBytes = it },
        )
    }

    ProfileAvatarEffect(
        account = account,
        loadProfileAvatar = ::loadProfileAvatar,
        clearProfileAvatar = {
            profileAvatarLoadKey = null
            profileAvatarBitmap = null
        },
    )

    fun startPlayback(
        track: Track,
        playbackUrl: String,
        startPositionMs: Long = 0L,
    ) {
        startPlaybackAction(
            track = track,
            playbackUrl = playbackUrl,
            startPositionMs = startPositionMs,
            getActivePlayEvent = { activePlayEventState.value },
            completeActivePlayEvent = ::completeActivePlayEvent,
            ensureActivePlayEvent = ::ensureActivePlayEvent,
            getPlaybackQueue = { playbackQueue },
            setPlaybackQueue = { playbackQueue = it },
            incrementPlaybackStartSerial = { playbackStartSerial += 1 },
            setPlaybackBufferedFraction = { playbackBufferedFraction = it },
            setPlayerState = { playerState = it },
            clearGaplessPlaybackState = ::clearGaplessPlaybackState,
            loadArtwork = ::loadArtwork,
        )
    }

    fun startGaplessPlayback(
        track: Track,
        queue: PlaybackQueue,
        urls: List<String>,
        resumePositionMs: Long = 0L,
    ) {
        startGaplessPlaybackAction(
            track = track,
            queue = queue,
            urls = urls,
            resumePositionMs = resumePositionMs,
            getActivePlayEvent = { activePlayEventState.value },
            completeActivePlayEvent = ::completeActivePlayEvent,
            ensureActivePlayEvent = ::ensureActivePlayEvent,
            setPlaybackQueue = { playbackQueue = it },
            setPlaybackBufferedFraction = { playbackBufferedFraction = it },
            setPlayerState = { playerState = it },
            setGaplessPlaybackRequest = { gaplessPlaybackRequest = it },
            setGaplessMediaQueueIndices = { gaplessMediaQueueIndices = it },
            setGaplessMediaUrls = { gaplessMediaUrls = it },
            loadArtwork = ::loadArtwork,
        )
    }

    fun installGaplessPrefetch(
        queue: PlaybackQueue,
        nextTrack: Track,
        nextIndex: Int,
        nextUrl: String,
    ) {
        installGaplessPrefetchAction(
            queue = queue,
            nextTrack = nextTrack,
            nextIndex = nextIndex,
            nextUrl = nextUrl,
            exoPlayer = exoPlayer,
            getPlayerState = { playerState },
            getGaplessPlaybackRequest = { gaplessPlaybackRequest },
            getGaplessMediaQueueIndices = { gaplessMediaQueueIndices },
            setGaplessMediaQueueIndices = { gaplessMediaQueueIndices = it },
            getGaplessMediaUrls = { gaplessMediaUrls },
            setGaplessMediaUrls = { gaplessMediaUrls = it },
        )
    }

    fun localOrCachedPlaybackUrl(trackId: String): String? {
        return localOrCachedPlaybackUrl(musicRepository, trackId)
    }

    fun localOrCachedPlaybackUrl(track: Track): String? {
        return localOrCachedPlaybackUrl(musicRepository, track)
    }

    fun enforceOfflinePlaybackAvailability() {
        enforceOfflinePlaybackAvailabilityAction(
            exoPlayer = exoPlayer,
            getPlayerState = { playerState },
            setPlayerState = { playerState = it },
            cancelCrossfade = ::cancelCrossfade,
            localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
            clearNowPlayingEvent = ::clearNowPlayingEvent,
            getActivePlayEvent = { activePlayEventState.value },
            clearGaplessPlaybackState = ::clearGaplessPlaybackState,
            incrementPlaybackStartSerial = { playbackStartSerial += 1 },
        )
    }

    fun prefetchTrackAssets(track: Track) {
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.TrackList)
        loadLyrics(track)
    }

    fun prefetchNextTrackUrl(queue: PlaybackQueue) {
        prefetchNextTrackUrlAction(
            scope = scope,
            queue = queue,
            getAccount = { account },
            getRepeatMode = { repeatMode },
            getPrefetchedPlaybackUrls = { prefetchedPlaybackUrls },
            setPrefetchedPlaybackUrls = { prefetchedPlaybackUrls = it },
            getPlaybackUrlPrefetchesInProgress = { playbackUrlPrefetchesInProgress },
            setPlaybackUrlPrefetchesInProgress = { playbackUrlPrefetchesInProgress = it },
            prefetchTrackAssets = ::prefetchTrackAssets,
            localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
            installGaplessPrefetch = ::installGaplessPrefetch,
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = { accessToken = it },
        )
    }

    fun nextCrossfadeQueueIndex(queue: PlaybackQueue): Int? {
        if (crossfadeSeconds <= 0 || repeatMode == PlaybackRepeatMode.Track || !queue.canSkip) {
            return null
        }
        val currentIndex = queue.currentIndex.takeIf { it in queue.tracks.indices } ?: return null
        return when {
            currentIndex < queue.tracks.lastIndex -> currentIndex + 1
            repeatMode == PlaybackRepeatMode.Queue -> 0
            else -> null
        }
    }

    fun beginPreparedCrossfade(prepared: PreparedCrossfade, fadeDurationMs: Long) {
        beginPreparedCrossfadeAction(
            scope = scope,
            prepared = prepared,
            fadeDurationMs = fadeDurationMs,
            getCrossfadeJob = { crossfadeJob },
            setCrossfadeJob = { crossfadeJob = it },
            getExoPlayer = { exoPlayer },
            setExoPlayer = { exoPlayer = it },
            getPlaybackQueueGeneration = { playbackQueueGeneration },
            getPlaybackQueue = { playbackQueue },
            setPlaybackQueue = { playbackQueue = it },
            setPlayerState = { playerState = it },
            getActivePlayEvent = { activePlayEventState.value },
            completeActivePlayEvent = ::completeActivePlayEvent,
            ensureActivePlayEvent = ::ensureActivePlayEvent,
            clearGaplessPlaybackState = ::clearGaplessPlaybackState,
            setGaplessMediaQueueIndices = { gaplessMediaQueueIndices = it },
            setGaplessMediaUrls = { gaplessMediaUrls = it },
            setPendingTransitionArtworkTrackId = { pendingTransitionArtworkTrackId = it },
            setPreparedCrossfade = { preparedCrossfade = it },
            incrementRequestedNextPrefetch = { requestedNextPrefetch += 1 },
            incrementCrossfadePreparationSerial = { crossfadePreparationSerial += 1 },
        )
    }

    CrossfadePreparationEffects(
        exoPlayer = exoPlayer,
        standbyExoPlayer = standbyExoPlayer,
        playerState = playerState,
        playbackQueue = playbackQueue,
        playbackQueueGeneration = playbackQueueGeneration,
        repeatMode = repeatMode,
        crossfadeSeconds = crossfadeSeconds,
        prefetchedPlaybackUrls = prefetchedPlaybackUrls,
        crossfadePreparationSerial = crossfadePreparationSerial,
        crossfadeJob = crossfadeJob,
        preparedCrossfade = preparedCrossfade,
        setPreparedCrossfade = { preparedCrossfade = it },
        nextCrossfadeQueueIndex = ::nextCrossfadeQueueIndex,
        localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
        prefetchNextTrackUrl = ::prefetchNextTrackUrl,
        beginPreparedCrossfade = ::beginPreparedCrossfade,
    )

    fun seekPreparedQueueMediaItem(targetIndex: Int, direction: Int): Boolean {
        return seekPreparedQueueMediaItemAction(
            targetIndex = targetIndex,
            direction = direction,
            exoPlayer = exoPlayer,
            getPlaybackQueue = { playbackQueue },
            getActivePlayEvent = { activePlayEventState.value },
            completeActivePlayEvent = ::completeActivePlayEvent,
            ensureActivePlayEvent = ::ensureActivePlayEvent,
            setPlaybackQueue = { playbackQueue = it },
            setPlayerState = { playerState = it },
            getGaplessMediaQueueIndices = { gaplessMediaQueueIndices },
            getGaplessMediaUrls = { gaplessMediaUrls },
            getPrefetchedPlaybackUrls = { prefetchedPlaybackUrls },
            localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
            setPendingTransitionArtworkTrackId = { pendingTransitionArtworkTrackId = it },
            setPlaybackBufferedFraction = { playbackBufferedFraction = it },
            incrementRequestedNextPrefetch = { requestedNextPrefetch += 1 },
        )
    }

    NextTrackPrefetchEffect(
        requestedNextPrefetch = requestedNextPrefetch,
        playbackQueue = playbackQueue,
        prefetchNextTrackUrl = ::prefetchNextTrackUrl,
    )

    val playQueuedTrackActionHost = object {
        fun playQueuedTrack(
        track: Track,
        queue: PlaybackQueue,
        resumePositionMs: Long = 0L,
        preferredIndex: Int? = null,
        allowResume: Boolean = false,
        newQueue: Boolean = false,
        skippedQueueIndices: Set<Int> = emptySet(),
        unavailableSkipDirection: Int = 1,
    ) {
        playQueuedTrackAction(
            request = PlayQueuedTrackRequest(
                track = track,
                queue = queue,
                resumePositionMs = resumePositionMs,
                preferredIndex = preferredIndex,
                allowResume = allowResume,
                newQueue = newQueue,
                skippedQueueIndices = skippedQueueIndices,
                unavailableSkipDirection = unavailableSkipDirection,
            ),
            scope = scope,
            exoPlayer = exoPlayer,
            musicRepository = musicRepository,
            getAccount = { account },
            getShuffleEnabled = { shuffleEnabled },
            getPlaybackQueueGeneration = { playbackQueueGeneration },
            setPlaybackQueueGeneration = { playbackQueueGeneration = it },
            getPlaybackQueue = { playbackQueue },
            setPlaybackQueue = { playbackQueue = it },
            getPlayerState = { playerState },
            setPlayerState = { playerState = it },
            setPlaybackBufferedFraction = { playbackBufferedFraction = it },
            getPrefetchedPlaybackUrls = { prefetchedPlaybackUrls },
            setPrefetchedPlaybackUrls = { prefetchedPlaybackUrls = it },
            getOfflineOnly = { offlineOnly },
            getSyncMode = { syncMode },
            currentStreamRequestSerial = { streamRequestSerial },
            nextStreamRequestSerial = {
                streamRequestSerial += 1
                streamRequestSerial
            },
            cancelCrossfade = ::cancelCrossfade,
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
            mediaDisabledMessage = ::mediaDisabledMessage,
            clearGaplessPlaybackState = ::clearGaplessPlaybackState,
            localOrCachedPlaybackUrl = { queuedTrack -> localOrCachedPlaybackUrl(queuedTrack) },
            loadArtwork = ::loadArtwork,
            prefetchNextTrackUrl = ::prefetchNextTrackUrl,
            startGaplessPlayback = ::startGaplessPlayback,
            startPlayback = ::startPlayback,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryNotice = { libraryNotice = it },
            setPlayerError = { playerError = it },
            replay = { nextRequest ->
                playQueuedTrack(
                    track = nextRequest.track,
                    queue = nextRequest.queue,
                    resumePositionMs = nextRequest.resumePositionMs,
                    preferredIndex = nextRequest.preferredIndex,
                    allowResume = nextRequest.allowResume,
                    newQueue = nextRequest.newQueue,
                    skippedQueueIndices = nextRequest.skippedQueueIndices,
                    unavailableSkipDirection = nextRequest.unavailableSkipDirection,
                )
            },
        )
    }

    }

    fun playQueuedTrack(
        track: Track,
        queue: PlaybackQueue,
        resumePositionMs: Long = 0L,
        preferredIndex: Int? = null,
        allowResume: Boolean = false,
        newQueue: Boolean = false,
        skippedQueueIndices: Set<Int> = emptySet(),
        unavailableSkipDirection: Int = 1,
    ) {
        playQueuedTrackActionHost.playQueuedTrack(
            track = track,
            queue = queue,
            resumePositionMs = resumePositionMs,
            preferredIndex = preferredIndex,
            allowResume = allowResume,
            newQueue = newQueue,
            skippedQueueIndices = skippedQueueIndices,
            unavailableSkipDirection = unavailableSkipDirection,
        )
    }

    fun applyPlaybackQueueOrderWithoutInterrupt(nextQueue: PlaybackQueue) {
        applyPlaybackQueueOrderWithoutInterruptAction(
            nextQueue = nextQueue,
            exoPlayer = exoPlayer,
            getPlayerState = { playerState },
            setPlaybackQueue = { playbackQueue = it },
            setGaplessPlaybackRequest = { gaplessPlaybackRequest = it },
            setGaplessMediaQueueIndices = { gaplessMediaQueueIndices = it },
            setGaplessMediaUrls = { gaplessMediaUrls = it },
            prefetchNextTrackUrl = ::prefetchNextTrackUrl,
        )
    }

    fun nextQueueStartRequestSerial(): Long {
        return queueStartRequestSerial.incrementAndGet()
    }

    fun replacePlaybackQueueIfRequestCurrent(
        requestSerial: Long,
        sourceType: PlaybackSourceType,
        sourceId: String,
        resolvedTracks: List<Track>,
        sourceTracks: List<Track> = resolvedTracks,
    ) {
        if (
            requestSerial != queueStartRequestSerial.get() ||
            resolvedTracks.isEmpty() ||
            playbackQueue.sourceType != sourceType ||
            playbackQueue.sourceId != sourceId
        ) {
            return
        }
        val currentTrack = playerState.currentTrack ?: return
        val replacementQueue = playbackQueue.withResolvedTracksForCurrentTrack(
            currentTrack = currentTrack,
            resolvedTracks = resolvedTracks,
            resolvedSourceTracks = sourceTracks,
        ) ?: return
        applyPlaybackQueueOrderWithoutInterrupt(replacementQueue)
    }

    fun replacePlaybackQueueSnapshotIfRequestCurrent(
        requestSerial: Long,
        nextQueue: PlaybackQueue,
        expectedCurrentTrackId: String,
    ) {
        if (
            requestSerial != queueStartRequestSerial.get() ||
            nextQueue.tracks.isEmpty() ||
            playerState.currentTrack?.id != expectedCurrentTrackId ||
            playbackQueue.sourceType != nextQueue.sourceType ||
            playbackQueue.sourceId != nextQueue.sourceId
        ) {
            return
        }
        val currentTrack = playerState.currentTrack ?: return
        val replacementQueue = if (playbackQueue.isShuffled && !nextQueue.isShuffled) {
            playbackQueue.withResolvedTracksForCurrentTrack(
                currentTrack = currentTrack,
                resolvedTracks = nextQueue.tracks,
                resolvedSourceTracks = nextQueue.sourceTracks,
            ) ?: return
        } else {
            nextQueue
        }
        applyPlaybackQueueOrderWithoutInterrupt(replacementQueue)
    }

    fun selectTrack(track: Track, sourceTitle: String? = null) {
        nextQueueStartRequestSerial()
        playQueuedTrack(
            track = track,
            queue = PlaybackQueue(
                sourceType = PlaybackSourceType.Search,
                sourceId = sourceTitle?.takeIf { it.isNotBlank() } ?: "Search",
                sourceTitle = sourceTitle?.takeIf { it.isNotBlank() } ?: "Search",
                tracks = listOf(track),
                currentIndex = 0,
            ),
            newQueue = true,
        )
    }

    fun selectSearchTrack(track: Track, sourceTitle: String? = null) {
        addRecentItem(
            RecentLibraryItem(
                type = RecentLibraryItemType.Track,
                title = track.title,
                subtitle = track.artist,
                id = track.id,
            ),
        )
        selectTrack(track, sourceTitle)
    }

    val playlistPlaybackActionHost = object {
        fun knownPlaylistTracksForPlayback(
            playlist: Playlist,
            fallbackTracks: List<Track>,
        ): List<Track> {
            val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
            val knownTracks = currentPlaylist.tracksFrom(tracks).let { candidateTracks ->
                if (canUseMediaServerRequests()) {
                    candidateTracks
                } else {
                    candidateTracks.filter { track -> localOrCachedPlaybackUrl(track) != null }
                }
            }
            return knownTracks.takeIf { it.size > fallbackTracks.size } ?: fallbackTracks
        }

        suspend fun resolvePlaylistTracksForPlayback(
        playlist: Playlist,
        fallbackTracks: List<Track>,
    ): List<Track> {
        val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        val cachedTracks = currentPlaylist.tracksFrom(tracks)
        val expectedTrackCount = currentPlaylist.trackCount.coerceAtLeast(currentPlaylist.trackIds.size)
        if (
            cachedTracks.isNotEmpty() &&
            (expectedTrackCount <= 0 || cachedTracks.size >= expectedTrackCount || !canUseServerRequests())
        ) {
            return cachedTracks
        }
        if (!canUseServerRequests()) {
            return cachedTracks.takeIf { it.isNotEmpty() } ?: fallbackTracks
        }

        val payload = if (playlist.isFavoritesPlaylist()) {
            musicRepository.favoritesPlaylistPayload(currentPlaylist)
        } else {
            musicRepository.playlistPayload(playlist.id)
        }
        val merged = payload.mergeWithCachedPlaylistData(
            cachedPlaylists = playlists,
            cachedTracks = tracks,
            withOfflineState = musicRepository::withOfflineState,
        )
        tracks = merged.tracks
        playlists = merged.playlists
        libraryCacheStore.saveLibrary(
            playlists = merged.playlists,
            tracks = merged.tracks,
            savedAlbums = savedAlbums,
        )
        val loadedPlaylist = merged.playlist ?: currentPlaylist
        return loadedPlaylist.tracksFrom(tracks).takeIf { it.isNotEmpty() } ?: fallbackTracks
    }

        fun playPlaylistTrackAt(playlist: Playlist, playlistTracks: List<Track>, trackIndex: Int) {
        val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        val knownPlaylistTracks = knownPlaylistTracksForPlayback(currentPlaylist, playlistTracks)
        val selectedTrack = playlistTracks.getOrNull(trackIndex)
        val knownTrackIndex = selectedTrack?.let { track ->
            selectedTrackIndexInResolvedTracks(
                selectedTrack = track,
                selectedIndex = trackIndex,
                sourceTracks = playlistTracks,
                resolvedTracks = knownPlaylistTracks,
            )
        } ?: trackIndex.coerceIn(0, (knownPlaylistTracks.size - 1).coerceAtLeast(0))
        playPlaylistTrackAtWithBackgroundResolve(
            scope = scope,
            playlist = currentPlaylist,
            playlistTracks = knownPlaylistTracks,
            trackIndex = knownTrackIndex,
            canUseServerRequests = ::canUseServerRequests,
            nextRequestSerial = ::nextQueueStartRequestSerial,
            playQueue = { track, queue, index ->
                playQueuedTrack(track = track, queue = queue, preferredIndex = index, newQueue = true)
            },
            resolveTracks = { currentPlaylist, fallbackTracks ->
                resolvePlaylistTracksForPlayback(currentPlaylist, fallbackTracks)
            },
            replaceQueue = ::replacePlaybackQueueSnapshotIfRequestCurrent,
            isRequestCurrent = { it == queueStartRequestSerial.get() },
            markServerUnavailable = ::markServerUnavailable,
            setPlayerError = { playerError = it },
        )
    }

        fun playPlaylist(playlist: Playlist, playlistTracks: List<Track>) {
        if (playlistTracks.isEmpty()) {
            return
        }
        playPlaylistTrackAt(
            playlist = playlist,
            playlistTracks = playlistTracks,
            trackIndex = 0,
        )
    }

        fun shufflePlayPlaylist(playlist: Playlist, playlistTracks: List<Track>) {
        val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        val knownPlaylistTracks = knownPlaylistTracksForPlayback(currentPlaylist, playlistTracks)
        shufflePlayPlaylistWithBackgroundResolve(
            scope = scope,
            playlist = currentPlaylist,
            playlistTracks = knownPlaylistTracks,
            existingQueueTracks = playbackQueue.tracks,
            nextRequestSerial = ::nextQueueStartRequestSerial,
            setShuffleEnabled = {
                shuffleEnabled = true
                userPreferencesStore.setShuffleEnabled(true)
            },
            playQueue = { track, queue, index ->
                playQueuedTrack(track = track, queue = queue, preferredIndex = index, newQueue = true)
            },
            isRequestCurrent = { it == queueStartRequestSerial.get() },
            replaceQueue = ::replacePlaybackQueueSnapshotIfRequestCurrent,
        )
    }

    }

    suspend fun resolvePlaylistTracksForPlayback(
        playlist: Playlist,
        fallbackTracks: List<Track>,
    ): List<Track> {
        return playlistPlaybackActionHost.resolvePlaylistTracksForPlayback(playlist, fallbackTracks)
    }

    fun playPlaylistTrackAt(playlist: Playlist, playlistTracks: List<Track>, trackIndex: Int) {
        playlistPlaybackActionHost.playPlaylistTrackAt(playlist, playlistTracks, trackIndex)
    }

    fun playPlaylist(playlist: Playlist, playlistTracks: List<Track>) {
        playlistPlaybackActionHost.playPlaylist(playlist, playlistTracks)
    }

    fun shufflePlayPlaylist(playlist: Playlist, playlistTracks: List<Track>) {
        playlistPlaybackActionHost.shufflePlayPlaylist(playlist, playlistTracks)
    }

    fun mergeLoadedTracks(loadedTracks: List<Track>) {
        if (loadedTracks.isEmpty()) {
            return
        }

        val mergedTracks = (tracks + loadedTracks.withKnownTrackMetadata(tracks))
            .associateBy { it.id }
            .values
            .toList()
        tracks = musicRepository.withOfflineState(mergedTracks).withKnownTrackMetadata(tracks)
        libraryCacheStore.saveLibrary(
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
        )
    }

    fun applyPlaylistPayload(payload: dev.teacode.tmusic.data.PlaylistPayload): Playlist? {
        val merged = payload.mergeWithCachedPlaylistData(
            cachedPlaylists = playlists,
            cachedTracks = tracks,
            withOfflineState = musicRepository::withOfflineState,
        )
        tracks = merged.tracks
        playlists = merged.playlists
        libraryCacheStore.saveLibrary(
            playlists = merged.playlists,
            tracks = merged.tracks,
            savedAlbums = savedAlbums,
        )
        return merged.playlist
    }

    fun applyPlaylistTrackPage(
        playlist: Playlist,
        payload: dev.teacode.tmusic.data.PlaylistPayload,
        append: Boolean,
    ): Playlist? {
        val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        val mergedPayload = payload.mergePlaylistTrackPage(
            playlist = playlist,
            currentPlaylist = currentPlaylist,
            append = append,
        )
        return applyPlaylistPayload(mergedPayload)
    }

    fun playlistDownloadedTrackCount(playlist: Playlist): Int {
        return playlistDownloadedTrackCount(
            playlist = playlist,
            tracks = tracks,
            hasLocalPlaybackUrl = { musicRepository.localPlaybackUrl(it) != null },
        )
    }

    fun playlistIsFullyDownloaded(playlist: Playlist): Boolean {
        return playlistIsFullyDownloaded(
            playlist = playlist,
            tracks = tracks,
            hasLocalPlaybackUrl = { musicRepository.localPlaybackUrl(it) != null },
        )
    }

    val libraryDetailActionHost = object {
        fun loadPlaylistTracks(playlist: Playlist, force: Boolean = false) {
        loadPlaylistTracksAction(
            scope = scope,
            playlist = playlist,
            force = force,
            canAttemptMetadataRequest = ::canAttemptMetadataRequest,
            getSyncMode = { syncMode },
            setSyncMode = { syncMode = it },
            getPlaylistTrackLoadsInProgress = { playlistTrackLoadsInProgress },
            setPlaylistTrackLoadsInProgress = { playlistTrackLoadsInProgress = it },
            getPlaylistTrackHasMoreById = { playlistTrackHasMoreById },
            setPlaylistTrackHasMoreById = { playlistTrackHasMoreById = it },
            getPlaylists = { playlists },
            getTracks = { tracks },
            playlistIsFullyDownloaded = ::playlistIsFullyDownloaded,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            applyPlaylistTrackPage = ::applyPlaylistTrackPage,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

        fun loadArtistAlbums(artist: LibraryArtist, force: Boolean = false) {
        loadArtistAlbumsAction(
            scope = scope,
            artist = artist,
            canUseServerRequests = ::canUseServerRequests,
            getArtistAlbumLoadsInProgress = { artistAlbumLoadsInProgress },
            setArtistAlbumLoadsInProgress = { artistAlbumLoadsInProgress = it },
            getAlbums = { albums },
            setAlbums = { albums = it },
            getSavedAlbums = { savedAlbums },
            getTracks = { tracks },
            getAlbumsByArtist = { albumsByArtist },
            setAlbumsByArtist = { albumsByArtist = it },
            getAppearsOnByArtist = { appearsOnByArtist },
            setAppearsOnByArtist = { appearsOnByArtist = it },
            getLooseTracksByArtist = { looseTracksByArtist },
            setLooseTracksByArtist = { looseTracksByArtist = it },
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            mergeLoadedTracks = ::mergeLoadedTracks,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

        fun loadSimilarArtists(artist: LibraryArtist, force: Boolean = false) {
        loadSimilarArtistsAction(
            scope = scope,
            artist = artist,
            force = force,
            canUseServerRequests = ::canUseServerRequests,
            getSimilarArtistLoadsInProgress = { similarArtistLoadsInProgress },
            setSimilarArtistLoadsInProgress = { similarArtistLoadsInProgress = it },
            getSimilarArtistsByArtist = { similarArtistsByArtist },
            setSimilarArtistsByArtist = { similarArtistsByArtist = it },
            getArtists = { artists },
            setArtists = { artists = it },
            getSearchResults = { searchResults },
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

        fun loadAlbumTracks(album: LibraryAlbum, force: Boolean = false) {
        loadAlbumTracksAction(
            scope = scope,
            album = album,
            force = force,
            canAttemptMetadataRequest = ::canAttemptMetadataRequest,
            getSyncMode = { syncMode },
            setSyncMode = { syncMode = it },
            getAlbumTrackLoadsInProgress = { albumTrackLoadsInProgress },
            setAlbumTrackLoadsInProgress = { albumTrackLoadsInProgress = it },
            getAlbumTrackHasMoreById = { albumTrackHasMoreById },
            setAlbumTrackHasMoreById = { albumTrackHasMoreById = it },
            getAlbumTracksById = { albumTracksById },
            setAlbumTracksById = { albumTracksById = it },
            getTracks = { tracks },
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            mergeLoadedTracks = ::mergeLoadedTracks,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

        fun loadMoreArtists() {
        loadMoreArtistsAction(
            scope = scope,
            canUseServerRequests = ::canUseServerRequests,
            getLibraryPaging = { libraryPaging },
            setLibraryPaging = { libraryPaging = it },
            getArtists = { artists },
            setArtists = { artists = it },
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = { accessToken = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

        fun loadMoreAlbums() {
        loadMoreAlbumsAction(
            scope = scope,
            canUseServerRequests = ::canUseServerRequests,
            getLibraryPaging = { libraryPaging },
            setLibraryPaging = { libraryPaging = it },
            getAlbums = { albums },
            setAlbums = { albums = it },
            getSavedAlbums = { savedAlbums },
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = { accessToken = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    }

    fun loadPlaylistTracks(playlist: Playlist, force: Boolean = false) {
        libraryDetailActionHost.loadPlaylistTracks(playlist, force)
    }

    fun loadArtistAlbums(artist: LibraryArtist, force: Boolean = false) {
        libraryDetailActionHost.loadArtistAlbums(artist, force)
    }

    fun loadSimilarArtists(artist: LibraryArtist, force: Boolean = false) {
        libraryDetailActionHost.loadSimilarArtists(artist, force)
    }

    fun loadAlbumTracks(album: LibraryAlbum, force: Boolean = false) {
        libraryDetailActionHost.loadAlbumTracks(album, force)
    }

    fun loadMoreArtists() {
        libraryDetailActionHost.loadMoreArtists()
    }

    fun loadMoreAlbums() {
        libraryDetailActionHost.loadMoreAlbums()
    }

    fun loadMoreRecentAlbums() {
        loadMoreRecentAlbumsPage(
            scope = scope,
            canUseServerRequests = ::canUseServerRequests,
            currentAlbums = recentAlbums,
            nextOffset = recentAlbumsPaging.nextOffset,
            isLoadingMore = recentAlbumsPaging.loadingMore,
            hasMore = recentAlbumsPaging.hasMore,
            musicRepository = musicRepository,
            authRepository = authRepository,
            markServerUnavailable = ::markServerUnavailable,
            setLoadingMore = { recentAlbumsPaging = recentAlbumsPaging.copy(loadingMore = it) },
            setAccessToken = { accessToken = it },
            setRecentAlbums = { recentAlbums = it },
            setNextOffset = { recentAlbumsPaging = recentAlbumsPaging.copy(nextOffset = it) },
            setHasMore = { recentAlbumsPaging = recentAlbumsPaging.copy(hasMore = it) },
            setLibraryError = { libraryError = it },
        )
    }

    fun openArtist(artist: LibraryArtist) {
        artists = (artists + artist)
            .distinctBy { it.id }
            .sortedArtistsForDisplay()
        navigateTo(
            AppDestination(
                tab = AppTab.Home,
                homeRoute = HomeRoute.Artist,
                artistId = artist.id,
                artistName = artist.name,
            ),
        )
        loadArtistAlbums(artist)
        loadSimilarArtists(artist)
    }

    fun openAlbum(album: LibraryAlbum) {
        val existingAlbum = (albums + savedAlbums + searchResults.albums + albumsByArtist.values.flatten() + appearsOnByArtist.values.flatten())
            .firstOrNull { it.id == album.id }
        val wasSaved = album.savedByCurrentUser ||
            existingAlbum?.savedByCurrentUser == true
        val wasOfflineEnabled = album.isOfflineEnabled ||
            album.id in offlineAlbumIds ||
            existingAlbum?.isOfflineEnabled == true
        val cachedAlbum = album.copy(
            artistId = album.artistId ?: existingAlbum?.artistId,
            artistIds = album.artistIds.ifEmpty { existingAlbum?.artistIds.orEmpty() },
            artists = album.artists.ifEmpty { existingAlbum?.artists.orEmpty() },
            totalDurationSeconds = album.totalDurationSeconds ?: existingAlbum?.totalDurationSeconds,
            savedByCurrentUser = wasSaved,
            isOfflineEnabled = wasOfflineEnabled,
        )
        albums = albums.updateOrAppendAlbum(cachedAlbum)
        navigateTo(
            AppDestination(
                tab = AppTab.Home,
                homeRoute = HomeRoute.Album,
                albumId = album.id,
            ),
        )
        loadAlbumTracks(cachedAlbum)
    }

    fun selectSearchArtist(artist: LibraryArtist) {
        addRecentItem(
            RecentLibraryItem(
                type = RecentLibraryItemType.Artist,
                title = artist.name,
                subtitle = "Artist",
                id = artist.id,
            ),
        )
        openArtist(artist)
    }

    fun selectSearchAlbum(album: LibraryAlbum) {
        addRecentItem(
            RecentLibraryItem(
                type = RecentLibraryItemType.Album,
                title = album.title,
                subtitle = album.artist,
                id = album.id,
            ),
        )
        openAlbum(album)
    }

    val albumPlaybackActionHost = object {
        suspend fun resolveAlbumTracksForPlayback(
        album: LibraryAlbum,
        fallbackTracks: List<Track>,
    ): List<Track> {
        val cachedTracks = albumTracksById[album.id].orEmpty().takeIf { it.isNotEmpty() }
            ?: tracks
                .filter { track -> track.albumId == album.id || (track.album == album.title && track.matchesAlbumArtist(album)) }
                .sortedBy { it.trackNumber ?: Int.MAX_VALUE }
        if (
            cachedTracks.isNotEmpty() &&
            (!canUseServerRequests() || (album.trackCount > 0 && cachedTracks.size >= album.trackCount))
        ) {
            return cachedTracks
        }
        if (!canUseServerRequests()) {
            return cachedTracks.takeIf { it.isNotEmpty() } ?: fallbackTracks
        }

        val loadedTracks = musicRepository.albumTracks(album.id)
            .sortedBy { it.trackNumber ?: Int.MAX_VALUE }
        albumTracksById = albumTracksById + (album.id to loadedTracks)
        mergeLoadedTracks(loadedTracks)
        return loadedTracks.takeIf { it.isNotEmpty() } ?: fallbackTracks
    }

        fun playAlbumFromTrack(album: LibraryAlbum, albumTracks: List<Track>, track: Track) {
        if (shuffleEnabled) {
            shuffleEnabled = false
            userPreferencesStore.setShuffleEnabled(false)
        }
        playAlbumTrackWithBackgroundResolve(
            scope = scope,
            album = album,
            albumTracks = albumTracks,
            track = track,
            canUseServerRequests = ::canUseServerRequests,
            nextRequestSerial = ::nextQueueStartRequestSerial,
            playQueue = { selectedTrack, queue, index ->
                playQueuedTrack(track = selectedTrack, queue = queue, preferredIndex = index, newQueue = true)
            },
            resolveTracks = { currentAlbum, fallbackTracks ->
                resolveAlbumTracksForPlayback(currentAlbum, fallbackTracks)
            },
            replaceResolvedQueue = ::replacePlaybackQueueIfRequestCurrent,
            isRequestCurrent = { it == queueStartRequestSerial.get() },
            markServerUnavailable = ::markServerUnavailable,
            setPlayerError = { playerError = it },
        )
    }

        fun playAlbum(album: LibraryAlbum, albumTracks: List<Track>) {
        if (shuffleEnabled) {
            shuffleEnabled = false
            userPreferencesStore.setShuffleEnabled(false)
        }
        val firstTrack = albumTracks.firstOrNull()
        if (firstTrack != null) {
            playAlbumFromTrack(album, albumTracks, firstTrack)
            return
        }

        if (!canUseServerRequests()) {
            playerError = "Album is not available offline"
            return
        }

        scope.launch {
            runCatching {
                resolveAlbumTracksForPlayback(album, emptyList())
            }.onSuccess { resolvedTracks ->
                resolvedTracks.firstOrNull()?.let { track ->
                    playAlbumFromTrack(album, resolvedTracks, track)
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                playerError = error.userMessage()
            }
        }
    }

    }

    suspend fun resolveAlbumTracksForPlayback(
        album: LibraryAlbum,
        fallbackTracks: List<Track>,
    ): List<Track> {
        return albumPlaybackActionHost.resolveAlbumTracksForPlayback(album, fallbackTracks)
    }

    fun playAlbumFromTrack(album: LibraryAlbum, albumTracks: List<Track>, track: Track) {
        albumPlaybackActionHost.playAlbumFromTrack(album, albumTracks, track)
    }

    fun playAlbum(album: LibraryAlbum, albumTracks: List<Track>) {
        albumPlaybackActionHost.playAlbum(album, albumTracks)
    }

    val albumLibraryActionHost = object {
        fun toggleAlbumInLibrary(album: LibraryAlbum) {
            toggleAlbumInLibraryAction(
            scope = scope,
            album = album,
            canUseServerRequests = ::canUseServerRequests,
            getOfflineAlbumIds = { offlineAlbumIds },
            getAlbums = { albums },
            setAlbums = { albums = it },
            getAlbumsByArtist = { albumsByArtist },
            setAlbumsByArtist = { albumsByArtist = it },
            getAppearsOnByArtist = { appearsOnByArtist },
            setAppearsOnByArtist = { appearsOnByArtist = it },
            getSavedAlbums = { savedAlbums },
            setSavedAlbums = { savedAlbums = it },
            getPlaylists = { playlists },
            getTracks = { tracks },
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            enqueueLibraryMutation = ::enqueueLibraryMutation,
            saveLibraryCache = ::saveLibraryCache,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    }

    fun toggleAlbumInLibrary(album: LibraryAlbum) {
        albumLibraryActionHost.toggleAlbumInLibrary(album)
    }

    fun skipInQueue(
        direction: Int,
        restartCurrentWhenPrevious: Boolean = true,
    ) {
        skipInQueueAction(
            direction = direction,
            restartCurrentWhenPrevious = restartCurrentWhenPrevious,
            exoPlayer = exoPlayer,
            getPlaybackQueue = { playbackQueue },
            getPlayerState = { playerState },
            setPlayerState = { playerState = it },
            cancelCrossfade = ::cancelCrossfade,
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
            completeActivePlayEvent = ::completeActivePlayEvent,
            ensureActivePlayEvent = ::ensureActivePlayEvent,
            seekPreparedQueueMediaItem = ::seekPreparedQueueMediaItem,
            playQueuedTrack = { track, queue, preferredIndex, unavailableSkipDirection ->
                playQueuedTrack(
                    track = track,
                    queue = queue,
                    preferredIndex = preferredIndex,
                    unavailableSkipDirection = unavailableSkipDirection,
                )
            },
        )
    }

    fun pauseAtQueueStart() {
        val queue = playbackQueue
        val firstTrack = queue.tracks.firstOrNull() ?: return
        clearNowPlayingEvent(activePlayEventState.value)
        activePlayEventState.value = null
        clearGaplessPlaybackState()
        playbackQueue = queue.copy(currentIndex = 0)
        playerState = PlayerState(
            currentTrack = firstTrack,
            isPlaying = false,
            progressSeconds = 0,
            streamUrl = null,
        )
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        loadArtwork(firstTrack.listArtworkKey(), ArtworkImageSize.FullPlayer)
    }

    fun playTrackFromCurrentQueueAt(index: Int) {
        cancelCrossfade()
        val queue = playbackQueue
        val track = queue.tracks.getOrNull(index) ?: return
        val direction = if (index < queue.currentIndex) -1 else 1
        if (seekPreparedQueueMediaItem(index, direction)) {
            return
        }
        playQueuedTrack(
            track = track,
            queue = queue.copy(currentIndex = index),
            preferredIndex = index,
        )
    }

    fun addTrackToQueue(track: Track) {
        cancelCrossfade()
        val insertResult = playbackQueue.withManualTrackInsertedAfterCurrent(
            track = track,
            currentTrack = playerState.currentTrack,
            insertionAnchorTrackId = queueInsertionAnchorTrackId,
            insertionCursor = queueInsertionCursor,
        )
        clearGaplessPlaybackState()
        playbackQueue = insertResult.queue
        queueInsertionAnchorTrackId = insertResult.anchorTrackId
        queueInsertionCursor = insertResult.insertionIndex
        mergeLoadedTracks(listOf(track))
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.TrackList)
        libraryNotice = "Added to queue."
    }

    fun removeTrackFromQueueAt(index: Int) {
        cancelCrossfade()
        val removal = playbackQueue.withTrackRemovedAt(index, queueInsertionCursor) ?: return
        if (removal.queue.tracks.isEmpty()) {
            clearNowPlayingEvent(activePlayEventState.value)
            activePlayEventState.value = null
            clearGaplessPlaybackState()
            playbackQueue = removal.queue
            playerState = PlayerState(null, isPlaying = false, progressSeconds = 0, streamUrl = null)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            return
        }
        clearGaplessPlaybackState()
        playbackQueue = removal.queue
        queueInsertionCursor = removal.nextQueueInsertionCursor
        removal.nextTrackToPlay?.let { nextTrack ->
            playQueuedTrack(
                track = nextTrack,
                queue = removal.queue,
                preferredIndex = removal.nextTrackIndex,
            )
        }
    }

    fun reorderQueueTracks(reorderedIndices: List<Int>) {
        cancelCrossfade()
        val nextQueue = playbackQueue.withReorderedTracks(
            reorderedIndices = reorderedIndices,
            currentTrackId = playerState.currentTrack?.id,
        ) ?: return
        queueInsertionCursor = null
        clearGaplessPlaybackState()
        playbackQueue = nextQueue
    }

    fun openArtistOptions(
        artistOptions: List<LibraryArtist>,
        missingMessage: String,
    ) {
        when (artistOptions.size) {
            0 -> libraryError = missingMessage
            1 -> openArtist(artistOptions.first())
            else -> artistChoices = artistOptions
        }
    }

    fun openTrackArtist(track: Track) {
        openTrackArtistAction(
            scope = scope,
            track = track,
            canAttemptMetadataRequest = ::canAttemptMetadataRequest,
            resolveCachedArtist = ::resolveCachedArtist,
            openArtistOptions = ::openArtistOptions,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = { accessToken = it },
            mergeLoadedTracks = ::mergeLoadedTracks,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun openAlbumArtist(album: LibraryAlbum) {
        openAlbumArtistAction(
            scope = scope,
            album = album,
            getAlbumTracksById = { albumTracksById },
            setAlbumTracksById = { albumTracksById = it },
            getTracks = { tracks },
            canAttemptMetadataRequest = ::canAttemptMetadataRequest,
            resolveCachedArtist = ::resolveCachedArtist,
            openArtistOptions = ::openArtistOptions,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = { accessToken = it },
            mergeLoadedTracks = ::mergeLoadedTracks,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun openTrackAlbum(track: Track) {
        track.albumId?.takeIf { it.isNotBlank() } ?: return
        openAlbum(track.navigationAlbum())
    }

    fun setShuffleEnabled(enabled: Boolean) {
        if (shuffleEnabled == enabled) {
            return
        }
        cancelCrossfade()
        val currentTrack = playerState.currentTrack
        val nextQueue = if (enabled) {
            shuffleQueue(playbackQueue, currentTrack)
        } else {
            restoreNaturalQueue(playbackQueue, currentTrack)
        }
        shuffleEnabled = enabled
        applyPlaybackQueueOrderWithoutInterrupt(nextQueue)
        userPreferencesStore.setShuffleEnabled(enabled)
    }

    fun setRepeatMode(mode: PlaybackRepeatMode) {
        repeatMode = mode
        userPreferencesStore.setPlaybackRepeatMode(mode.name)
    }

    QueueInsertionAnchorResetEffect(
        currentTrackId = playerState.currentTrack?.id,
        clearQueueInsertionAnchor = {
            queueInsertionAnchorTrackId = null
            queueInsertionCursor = null
        },
    )

    fun setShowLyrics(enabled: Boolean) {
        showLyrics = enabled
        userPreferencesStore.setShowLyrics(enabled)
    }

    fun togglePlayback() {
        togglePlaybackAction(
            exoPlayer = exoPlayer,
            getPlayerState = { playerState },
            setPlayerState = { playerState = it },
            getPlaybackQueue = { playbackQueue },
            getCrossfadeJob = { crossfadeJob },
            getPreparedCrossfade = { preparedCrossfade },
            cancelCrossfade = ::cancelCrossfade,
            playQueuedTrack = { track, queue, resumePositionMs, allowResume ->
                playQueuedTrack(
                    track = track,
                    queue = queue,
                    resumePositionMs = resumePositionMs,
                    allowResume = allowResume,
                )
            },
            ensureActivePlayEvent = { track -> ensureActivePlayEvent(track) },
            clearNowPlayingEvent = ::clearNowPlayingEvent,
            getActivePlayEvent = { activePlayEventState.value },
        )
    }

    fun seekTo(seconds: Int) {
        cancelCrossfade()
        val currentTrack = playerState.currentTrack ?: return
        val boundedSeconds = seconds.coerceIn(0, currentTrack.durationSeconds.coerceAtLeast(0))
        playerState = playerState.copy(progressSeconds = boundedSeconds)
        if (playerState.streamUrl != null) {
            exoPlayer.seekTo(boundedSeconds.toLong() * 1000L)
        }
    }

    fun updateTrackDownloadState(trackId: String, downloadState: DownloadState) {
        val update = trackDownloadStateUpdate(
            trackId = trackId,
            downloadState = downloadState,
            tracks = tracks,
            albumTracksById = albumTracksById,
            playbackQueue = playbackQueue,
            playerState = playerState,
        )
        tracks = update.tracks
        albumTracksById = update.albumTracksById
        playbackQueue = update.playbackQueue
        playerState = update.playerState
    }

    fun updateKnownTrackLikedState(trackId: String, isLiked: Boolean) {
        val update = knownTrackLikedStateUpdate(
            trackId = trackId,
            isLiked = isLiked,
            tracks = tracks,
            albumTracksById = albumTracksById,
            looseTracksByArtist = looseTracksByArtist,
            searchResults = searchResults,
            playbackQueue = playbackQueue,
            playerState = playerState,
        )
        tracks = update.tracks
        albumTracksById = update.albumTracksById
        looseTracksByArtist = update.looseTracksByArtist
        searchResults = update.searchResults
        playbackQueue = update.playbackQueue
        playerState = update.playerState
    }

    suspend fun ensureTrackDownloaded(track: Track) {
        val promotedManifest = musicRepository.promoteCachedTrack(track.id)
        if (promotedManifest != null) {
            return
        }
        if (!canUseMediaServerRequests()) {
            throw IllegalStateException(mediaDisabledMessage())
        }
        musicRepository.downloadTrack(track.id)
    }

    fun mergePlaylistPickerMetadata(loadedPlaylists: List<Playlist>) {
        playlistPickerPlaylists = loadedPlaylists.sanitizeClientPlaylists().filterNot { it.isFavoritesPlaylist() }
        playlists = playlists.mergePlaylistMetadata(loadedPlaylists)
        playlistMetadataLoaded = true
    }

    suspend fun loadFavoritesPlaylistForTrack(track: Track): Playlist? {
        return loadFavoritesPlaylistForTrackAction(
            track = track,
            getPlaylists = { playlists },
            musicRepository = musicRepository,
            mergePlaylistPickerMetadata = ::mergePlaylistPickerMetadata,
            applyPlaylistPayload = ::applyPlaylistPayload,
        )
    }

    val favoriteTrackActionHost = object {
        fun toggleFavoriteTrack(track: Track) {
            toggleFavoriteTrackAction(
            scope = scope,
            track = track,
            canUseServerRequests = ::canUseServerRequests,
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getTracks = { tracks },
            setTracks = { tracks = it },
            getSavedAlbums = { savedAlbums },
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            mergePlaylistPickerMetadata = ::mergePlaylistPickerMetadata,
            applyPlaylistPayload = ::applyPlaylistPayload,
            updateKnownTrackLikedState = ::updateKnownTrackLikedState,
            updateTrackDownloadState = ::updateTrackDownloadState,
            ensureTrackDownloaded = ::ensureTrackDownloaded,
            cacheDownloadedAssets = ::cacheDownloadedAssets,
            refreshStorageStats = ::refreshStorageStats,
            getFavoriteSyncTrackIds = { favoriteSyncTrackIds },
            setFavoriteSyncTrackIds = { favoriteSyncTrackIds = it },
            loadArtwork = ::loadArtwork,
            enqueueLibraryMutation = ::enqueueLibraryMutation,
            saveLibraryCache = ::saveLibraryCache,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    }

    fun toggleFavoriteTrack(track: Track) {
        favoriteTrackActionHost.toggleFavoriteTrack(track)
    }

    val currentTrackFavorite = playerState.currentTrack?.let { currentTrack ->
        playlists.firstOrNull { it.isFavoritesPlaylist() }?.trackIds?.contains(currentTrack.id)
            ?: tracks.firstOrNull { it.id == currentTrack.id }?.isLiked
            ?: searchResults.tracks.firstOrNull { it.id == currentTrack.id }?.isLiked
            ?: albumTracksById.values.asSequence().flatten().firstOrNull { it.id == currentTrack.id }?.isLiked
            ?: looseTracksByArtist.values.asSequence().flatten().firstOrNull { it.id == currentTrack.id }?.isLiked
            ?: playbackQueue.tracks.firstOrNull { it.id == currentTrack.id }?.isLiked
            ?: currentTrack.isLiked
    } == true

    PlaybackSystemIntegration(
        playerState = playerState,
        artworkBitmap = artworkBitmaps.artworkBitmap(
            playerState.currentTrack?.listArtworkKey(),
            ArtworkImageSize.FullPlayer,
        ),
        isFavorite = currentTrackFavorite,
        canSkip = playbackQueue.canSkip,
        onPlay = {
            if (!playerState.isPlaying) {
                togglePlayback()
            }
        },
        onPause = {
            if (playerState.isPlaying) {
                togglePlayback()
            }
        },
        onPrevious = {
            if (playbackQueue.canSkip) {
                skipInQueue(direction = -1)
            }
        },
        onNext = {
            if (playbackQueue.canSkip) {
                skipInQueue(direction = 1)
            }
        },
        onSeek = { positionMs -> seekTo((positionMs / 1000L).toInt()) },
        onToggleFavorite = {
            playerState.currentTrack?.let(::toggleFavoriteTrack)
        },
    )

    QueueRequestEffects(
        requestedQueueAdvance = requestedQueueAdvance,
        requestedQueueWrapPause = requestedQueueWrapPause,
        requestedCurrentTrackRestart = requestedCurrentTrackRestart,
        playerState = playerState,
        playbackQueue = playbackQueue,
        skipNext = { skipInQueue(direction = 1) },
        pauseAtQueueStart = ::pauseAtQueueStart,
        restartCurrentTrack = { track, queue ->
            playQueuedTrack(
                track = track,
                queue = queue,
                resumePositionMs = 0L,
            )
        },
    )

    LaunchedEffect(
        playbackQueue.currentIndex,
        playbackQueue.tracks.getOrNull(playbackQueue.currentIndex)?.id,
        playerState.currentTrack?.id,
        playbackQueueGeneration,
    ) {
        val queueIndex = playbackQueue.currentIndex
        val queuedTrack = playbackQueue.tracks.getOrNull(queueIndex) ?: return@LaunchedEffect
        val playerTrack = playerState.currentTrack ?: return@LaunchedEffect
        if (queuedTrack.id == playerTrack.id) {
            return@LaunchedEffect
        }
        logPlaybackDebug(
            "recover playback mismatch player=${playerTrack.debugTrack()} queue=${queuedTrack.debugTrack()} " +
                playbackQueue.debugSummary(),
        )
        playQueuedTrack(
            track = queuedTrack,
            queue = playbackQueue.copy(currentIndex = queueIndex),
            preferredIndex = queueIndex,
        )
    }

    PendingPlaybackRestoreEffect(
        accountId = account?.id,
        syncMode = syncMode,
        tracks = tracks,
        playlists = playlists,
        pendingPlaybackRestore = pendingPlaybackRestore,
        setPlaybackQueue = { playbackQueue = it },
        setPlayerState = { playerState = it },
        loadArtwork = ::loadArtwork,
        clearPendingPlaybackRestore = { pendingPlaybackRestore = null },
    )

    fun loadPlaylistPickerPlaylists(force: Boolean = false) {
        if (!canUseServerRequests()) {
            playlistPickerPlaylists = playlists.sanitizeClientPlaylists().filterNot { it.isFavoritesPlaylist() }
            playlistMetadataLoaded = true
            return
        }
        if (playlistPickerLoading || (!force && playlistMetadataLoaded)) {
            return
        }

        playlistPickerLoading = true
        scope.launch {
            runCatching {
                musicRepository.playlistsMetadata()
            }.onSuccess { loadedPlaylists ->
                accessToken = authRepository.accessToken()
                mergePlaylistPickerMetadata(loadedPlaylists)
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            playlistPickerLoading = false
        }
    }

    fun openAddTrackToPlaylist(track: Track) {
        trackForPlaylistAdd = track
        duplicatePlaylistForAdd = null
        playlistPickerPlaylists = playlists.sanitizeClientPlaylists().filterNot { it.isFavoritesPlaylist() }.map { playlist ->
            playlist.copy(trackIds = emptyList(), playlistTrackIds = emptyList(), playlistTrackIdsByTrackId = emptyMap())
        }
        if (canUseServerRequests()) {
            loadPlaylistPickerPlaylists(force = true)
        } else {
            playlistMetadataLoaded = true
        }
    }

    PlaylistSyncEffects(
        accountId = account?.id,
        offlineOnly = offlineOnly,
        syncMode = syncMode,
        pendingLibraryMutationCount = pendingLibraryMutationCount,
        playlists = playlists,
        currentTrack = playerState.currentTrack,
        canUseServerRequests = ::canUseServerRequests,
        loadPlaylistPickerPlaylists = { force -> loadPlaylistPickerPlaylists(force = force) },
        syncPendingLibraryMutations = ::syncPendingLibraryMutations,
        musicRepository = musicRepository,
        authRepository = authRepository,
        setAccessToken = { accessToken = it },
        applyFavoritesPayload = { favorites ->
            applyPlaylistPayload(musicRepository.favoritesPlaylistPayload(favorites))
        },
        loadFavoritesPlaylistForTrack = { track -> loadFavoritesPlaylistForTrack(track) },
        markServerUnavailable = ::markServerUnavailable,
        setLibraryError = { libraryError = it },
    )

    fun createPlaylist(name: String) {
        createPlaylistAction(
            scope = scope,
            name = name,
            canUseServerRequests = ::canUseServerRequests,
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getPlaylistPickerPlaylists = { playlistPickerPlaylists },
            setPlaylistPickerPlaylists = { playlistPickerPlaylists = it },
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = { accessToken = it },
            enqueueLibraryMutation = ::enqueueLibraryMutation,
            saveLibraryCache = ::saveLibraryCache,
            loadLibrary = ::loadLibrary,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun updatePlaylistDetails(playlist: Playlist, name: String) {
        updatePlaylistDetailsAction(
            scope = scope,
            playlist = playlist,
            name = name,
            canUseServerRequests = ::canUseServerRequests,
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getPlaylistPickerPlaylists = { playlistPickerPlaylists },
            setPlaylistPickerPlaylists = { playlistPickerPlaylists = it },
            getTracks = { tracks },
            getSavedAlbums = { savedAlbums },
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            enqueueLibraryMutation = ::enqueueLibraryMutation,
            saveLibraryCache = ::saveLibraryCache,
            loadLibrary = ::loadLibrary,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    suspend fun loadPlaylistForMembershipCheck(playlist: Playlist, track: Track): Playlist {
        val cachedPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        val cachedHasTrack = track.id in cachedPlaylist.trackIds
        val cacheCoversPlaylist = cachedPlaylist.trackCount <= 0 ||
            cachedPlaylist.trackIds.size >= cachedPlaylist.trackCount
        if (cachedHasTrack || cacheCoversPlaylist) {
            return cachedPlaylist
        }

        val payload = musicRepository.playlistPayload(playlist.id)
        return applyPlaylistPayload(payload) ?: cachedPlaylist
    }

    val playlistTrackMutationActionHost = object {
        fun addTrackToPlaylist(playlist: Playlist, track: Track) {
        addTrackToPlaylistAction(
            scope = scope,
            playlist = playlist,
            track = track,
            canUseServerRequests = ::canUseServerRequests,
            getPlaylistAddInProgress = { playlistAddInProgress },
            setPlaylistAddInProgress = { playlistAddInProgress = it },
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getPlaylistPickerPlaylists = { playlistPickerPlaylists },
            setPlaylistPickerPlaylists = { playlistPickerPlaylists = it },
            getTracks = { tracks },
            setTracks = { tracks = it },
            getSavedAlbums = { savedAlbums },
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            enqueueLibraryMutation = ::enqueueLibraryMutation,
            saveLibraryCache = ::saveLibraryCache,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            ensureTrackDownloaded = ::ensureTrackDownloaded,
            cacheDownloadedAssets = ::cacheDownloadedAssets,
            updateTrackDownloadState = ::updateTrackDownloadState,
            refreshStorageStats = ::refreshStorageStats,
            setTrackForPlaylistAdd = { trackForPlaylistAdd = it },
            setDuplicatePlaylistForAdd = { duplicatePlaylistForAdd = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    }

    fun addTrackToPlaylist(playlist: Playlist, track: Track) {
        playlistTrackMutationActionHost.addTrackToPlaylist(playlist, track)
    }

    fun deletePlaylist(playlist: Playlist) {
        deletePlaylistAction(
            scope = scope,
            playlist = playlist,
            destination = destination,
            canUseServerRequests = ::canUseServerRequests,
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getPlaylistPickerPlaylists = { playlistPickerPlaylists },
            setPlaylistPickerPlaylists = { playlistPickerPlaylists = it },
            getTracks = { tracks },
            getSavedAlbums = { savedAlbums },
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            enqueueLibraryMutation = ::enqueueLibraryMutation,
            saveLibraryCache = ::saveLibraryCache,
            navigateTo = ::navigateTo,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun requestAddTrackToPlaylist(playlist: Playlist, track: Track, allowDuplicate: Boolean = false) {
        requestAddTrackToPlaylistAction(
            scope = scope,
            playlist = playlist,
            track = track,
            allowDuplicate = allowDuplicate,
            canUseServerRequests = ::canUseServerRequests,
            getPlaylists = { playlists },
            getPlaylistAddInProgress = { playlistAddInProgress },
            setPlaylistAddInProgress = { playlistAddInProgress = it },
            loadPlaylistForMembershipCheck = ::loadPlaylistForMembershipCheck,
            addTrackToPlaylist = ::addTrackToPlaylist,
            setDuplicatePlaylistForAdd = { duplicatePlaylistForAdd = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun removeTrackFromPlaylist(playlist: Playlist, playlistTrackId: String, trackId: String) {
        removeTrackFromPlaylistAction(
            scope = scope,
            playlist = playlist,
            playlistTrackId = playlistTrackId,
            trackId = trackId,
            canUseServerRequests = ::canUseServerRequests,
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getTracks = { tracks },
            getSavedAlbums = { savedAlbums },
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            enqueueLibraryMutation = ::enqueueLibraryMutation,
            saveLibraryCache = ::saveLibraryCache,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            updateTrackDownloadState = ::updateTrackDownloadState,
            refreshStorageStats = ::refreshStorageStats,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun reorderPlaylistTracks(playlist: Playlist, playlistTrackIds: List<String>) {
        reorderPlaylistTracksAction(
            playlist = playlist,
            playlistTrackIds = playlistTrackIds,
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
            scope = scope,
            musicRepository = musicRepository,
            authRepository = authRepository,
            libraryCacheStore = libraryCacheStore,
            canUseServerRequests = ::canUseServerRequests,
            updatePlaylists = { playlists = it },
            enqueueLibraryMutation = ::enqueueLibraryMutation,
            saveLibraryCache = ::saveLibraryCache,
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
            setAccessToken = { accessToken = it },
        )
    }

    fun updateAlbumOfflineFlag(albumId: String, enabled: Boolean) {
        if (enabled) {
            userPreferencesStore.addOfflineAlbumId(albumId)
        } else {
            userPreferencesStore.removeOfflineAlbumId(albumId)
        }
        val update = albumOfflineFlagUpdate(
            albumId = albumId,
            enabled = enabled,
            offlineAlbumIds = offlineAlbumIds,
            albums = albums,
            savedAlbums = savedAlbums,
            albumsByArtist = albumsByArtist,
            appearsOnByArtist = appearsOnByArtist,
        )
        offlineAlbumIds = update.offlineAlbumIds
        albums = update.albums
        savedAlbums = update.savedAlbums
        albumsByArtist = update.albumsByArtist
        appearsOnByArtist = update.appearsOnByArtist
    }

    fun pausePlaylistDownload(playlist: Playlist) {
        pausePlaylistDownloadAction(
            playlist = playlist,
            playlistDownloadJobs = playlistDownloadJobs,
            setPlaylistDownloadJobs = { playlistDownloadJobs = it },
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
            libraryCacheStore = libraryCacheStore,
            updateTrackDownloadState = ::updateTrackDownloadState,
            refreshStorageStats = ::refreshStorageStats,
        )
    }

    fun pauseAlbumDownload(album: LibraryAlbum, albumTracks: List<Track>) {
        pauseAlbumDownloadAction(
            album = album,
            albumTracks = albumTracks,
            albumDownloadJobs = albumDownloadJobs,
            setAlbumDownloadJobs = { albumDownloadJobs = it },
            tracks = tracks,
            playlists = playlists,
            savedAlbums = savedAlbums,
            albumTracksById = albumTracksById,
            libraryCacheStore = libraryCacheStore,
            updateTrackDownloadState = ::updateTrackDownloadState,
            refreshStorageStats = ::refreshStorageStats,
        )
    }

    fun deletePlaylistDownload(playlist: Playlist) {
        deletePlaylistDownloadAction(
            scope = scope,
            playlist = playlist,
            playlistDownloadJobs = playlistDownloadJobs,
            setPlaylistDownloadJobs = { playlistDownloadJobs = it },
            playlists = playlists,
            setPlaylists = { playlists = it },
            tracks = tracks,
            savedAlbums = savedAlbums,
            albumTracksById = albumTracksById,
            offlineAlbumIds = offlineAlbumIds,
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            updateTrackDownloadState = ::updateTrackDownloadState,
            refreshStorageStats = ::refreshStorageStats,
        )
    }

    fun deleteAlbumDownload(album: LibraryAlbum, albumTracks: List<Track>) {
        deleteAlbumDownloadAction(
            scope = scope,
            album = album,
            albumTracks = albumTracks,
            albumDownloadJobs = albumDownloadJobs,
            setAlbumDownloadJobs = { albumDownloadJobs = it },
            tracks = tracks,
            playlists = playlists,
            savedAlbums = savedAlbums,
            albumTracksById = albumTracksById,
            offlineAlbumIds = offlineAlbumIds,
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            updateAlbumOfflineFlag = ::updateAlbumOfflineFlag,
            updateTrackDownloadState = ::updateTrackDownloadState,
            refreshStorageStats = ::refreshStorageStats,
        )
    }

    val downloadActionHost = object {
        fun downloadTrack(track: Track) {
        downloadTrackAction(
            scope = scope,
            track = track,
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            isOfflineOnly = { offlineOnly },
            getSyncMode = { syncMode },
            getAccount = { account },
            musicRepository = musicRepository,
            authRepository = authRepository,
            libraryCacheStore = libraryCacheStore,
            getPlaylists = { playlists },
            getTracks = { tracks },
            setTracks = { tracks = it },
            getSavedAlbums = { savedAlbums },
            mediaDisabledMessage = ::mediaDisabledMessage,
            updateTrackDownloadState = ::updateTrackDownloadState,
            ensureTrackDownloaded = ::ensureTrackDownloaded,
            cacheDownloadedAssets = ::cacheDownloadedAssets,
            disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
            setAccessToken = { accessToken = it },
            setLibraryError = { libraryError = it },
            refreshStorageStats = ::refreshStorageStats,
        )
    }

        fun downloadPlaylist(playlist: Playlist) {
        downloadPlaylistAction(
            scope = scope,
            playlist = playlist,
            getPlaylistDownloadJobs = { playlistDownloadJobs },
            setPlaylistDownloadJobs = { playlistDownloadJobs = it },
            pausePlaylistDownload = ::pausePlaylistDownload,
            canUseNetworkForCollectionDownloads = ::canUseNetworkForCollectionDownloads,
            isOfflineOnly = { offlineOnly },
            getSyncMode = { syncMode },
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            getAccount = { account },
            mediaDisabledMessage = ::mediaDisabledMessage,
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getTracks = { tracks },
            setTracks = { tracks = it },
            getSavedAlbums = { savedAlbums },
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            applyPlaylistTrackPage = ::applyPlaylistTrackPage,
            markServerUnavailable = ::markServerUnavailable,
            updateTrackDownloadState = ::updateTrackDownloadState,
            ensureTrackDownloaded = ::ensureTrackDownloaded,
            cacheDownloadedAssets = ::cacheDownloadedAssets,
            disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            setLibraryError = { libraryError = it },
            refreshStorageStats = ::refreshStorageStats,
        )
    }

        fun downloadAlbum(album: LibraryAlbum, albumTracks: List<Track>) {
        downloadAlbumAction(
            scope = scope,
            album = album,
            albumTracks = albumTracks,
            getAlbumDownloadJobs = { albumDownloadJobs },
            setAlbumDownloadJobs = { albumDownloadJobs = it },
            pauseAlbumDownload = ::pauseAlbumDownload,
            canUseNetworkForCollectionDownloads = ::canUseNetworkForCollectionDownloads,
            isOfflineOnly = { offlineOnly },
            getSyncMode = { syncMode },
            canUseMediaServerRequests = ::canUseMediaServerRequests,
            getAccount = { account },
            mediaDisabledMessage = ::mediaDisabledMessage,
            getTracks = { tracks },
            setTracks = { tracks = it },
            getPlaylists = { playlists },
            getSavedAlbums = { savedAlbums },
            setSavedAlbums = { savedAlbums = it },
            getAlbums = { albums },
            setAlbums = { albums = it },
            getAlbumsByArtist = { albumsByArtist },
            setAlbumsByArtist = { albumsByArtist = it },
            getAppearsOnByArtist = { appearsOnByArtist },
            setAppearsOnByArtist = { appearsOnByArtist = it },
            getAlbumTracksById = { albumTracksById },
            setAlbumTracksById = { albumTracksById = it },
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            markServerUnavailable = ::markServerUnavailable,
            mergeLoadedTracks = ::mergeLoadedTracks,
            updateAlbumOfflineFlag = ::updateAlbumOfflineFlag,
            updateTrackDownloadState = ::updateTrackDownloadState,
            ensureTrackDownloaded = ::ensureTrackDownloaded,
            cacheDownloadedAssets = ::cacheDownloadedAssets,
            disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = { accessToken = it },
            setLibraryError = { libraryError = it },
            refreshStorageStats = ::refreshStorageStats,
        )
    }

    }

    fun downloadTrack(track: Track) {
        downloadActionHost.downloadTrack(track)
    }

    fun downloadPlaylist(playlist: Playlist) {
        downloadActionHost.downloadPlaylist(playlist)
    }

    fun downloadAlbum(album: LibraryAlbum, albumTracks: List<Track>) {
        downloadActionHost.downloadAlbum(album, albumTracks)
    }

    fun resumePendingOfflineDownloads() {
        resumePendingOfflineDownloadsAction(
            canUseNetworkForCollectionDownloads = ::canUseNetworkForCollectionDownloads,
            playlists = playlists,
            playlistDownloadJobs = playlistDownloadJobs,
            playlistIsFullyDownloaded = ::playlistIsFullyDownloaded,
            tracks = tracks,
            albums = albums,
            savedAlbums = savedAlbums,
            albumsByArtist = albumsByArtist,
            appearsOnByArtist = appearsOnByArtist,
            offlineAlbumIds = offlineAlbumIds,
            albumDownloadJobs = albumDownloadJobs,
            albumTracksById = albumTracksById,
            downloadPlaylist = ::downloadPlaylist,
            downloadAlbum = ::downloadAlbum,
        )
    }

    fun pauseCollectionDownloadsForNetworkPolicy() {
        playlistDownloadJobs.values.forEach(Job::cancel)
        albumDownloadJobs.values.forEach(Job::cancel)
    }

    fun setDownloadUsingCellular(enabled: Boolean) {
        downloadUsingCellular = enabled
        userPreferencesStore.setDownloadUsingCellular(enabled)
        if (canUseNetworkForCollectionDownloads()) {
            resumePendingOfflineDownloads()
        } else {
            pauseCollectionDownloadsForNetworkPolicy()
        }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        cancelCrossfade()
        crossfadeSeconds = seconds.coerceIn(0, 12)
        userPreferencesStore.setCrossfadeSeconds(crossfadeSeconds)
    }

    fun openSystemEqualizer() {
        if (!openSystemEqualizer(context, exoPlayer.audioSessionId)) {
            equalizerAvailable = false
        }
    }

    OfflineDownloadResumeEffect(
        accountId = account?.id,
        offlineOnly = offlineOnly,
        resumePendingOfflineDownloads = ::resumePendingOfflineDownloads,
    )

    val storageMaintenanceActionHost = object {
        fun clearDownloads() {
        clearDownloadsAction(
            scope = scope,
            getAlbumDownloadJobs = { albumDownloadJobs },
            setAlbumDownloadJobs = { albumDownloadJobs = it },
            getPlaylistDownloadJobs = { playlistDownloadJobs },
            setPlaylistDownloadJobs = { playlistDownloadJobs = it },
            getPlayerState = { playerState },
            setPlayerState = { playerState = it },
            getPlaylists = { playlists },
            setPlaylists = { playlists = it },
            getTracks = { tracks },
            setTracks = { tracks = it },
            getAlbums = { albums },
            setAlbums = { albums = it },
            getSavedAlbums = { savedAlbums },
            setSavedAlbums = { savedAlbums = it },
            getAlbumsByArtist = { albumsByArtist },
            setAlbumsByArtist = { albumsByArtist = it },
            getAppearsOnByArtist = { appearsOnByArtist },
            setAppearsOnByArtist = { appearsOnByArtist = it },
            setOfflineAlbumIds = { offlineAlbumIds = it },
            userPreferencesStore = userPreferencesStore,
            getPlaybackQueue = { playbackQueue },
            setPlaybackQueue = { playbackQueue = it },
            clearGaplessPlaybackState = ::clearGaplessPlaybackState,
            setPrefetchedPlaybackUrls = { prefetchedPlaybackUrls = it },
            setPlaybackUrlPrefetchesInProgress = { playbackUrlPrefetchesInProgress = it },
            playbackStateStore = playbackStateStore,
            canUseServerRequests = ::canUseServerRequests,
            setArtists = { artists = it },
            getArtworkBitmaps = { artworkBitmaps },
            setArtworkBitmaps = { artworkBitmaps = it },
            getLyricsByTrackId = { lyricsByTrackId },
            setLyricsByTrackId = { lyricsByTrackId = it },
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            artworkCacheStore = artworkCacheStore,
            libraryCacheStore = libraryCacheStore,
            setLibraryNotice = { libraryNotice = it },
            setLibraryError = { libraryError = it },
            refreshStorageStats = ::refreshStorageStats,
        )
    }

        fun clearAppCache() {
        clearAppCacheAction(
            scope = scope,
            getAccount = { account },
            getPlaylists = { playlists },
            getTracks = { tracks },
            getSavedAlbums = { savedAlbums },
            getArtworkBitmaps = { artworkBitmaps },
            setArtworkBitmaps = { artworkBitmaps = it },
            setArtworkLoadsInProgress = { artworkLoadsInProgress = it },
            setProfileAvatarBitmap = { profileAvatarBitmap = it },
            setProfileAvatarLoadKey = { profileAvatarLoadKey = it },
            setPrefetchedPlaybackUrls = { prefetchedPlaybackUrls = it },
            setLibraryNotice = { libraryNotice = it },
            musicRepository = musicRepository,
            appCacheStore = appCacheStore,
            artworkCacheStore = artworkCacheStore,
            libraryCacheStore = libraryCacheStore,
            playbackStateStore = playbackStateStore,
            retainedTrackIds = setOfNotNull(playerState.currentTrack?.id),
            retainedPlaybackCacheKeys = setOfNotNull(playerState.streamUrl),
            clearPlaybackCache = {
                mediaCache.keys.toList().forEach { key -> mediaCache.removeResource(key) }
            },
            clearPlaybackCacheExcept = { retainedKeys ->
                mediaCache.keys.toList()
                    .filterNot { key -> key in retainedKeys }
                    .forEach { key -> mediaCache.removeResource(key) }
            },
            playbackCacheDirName = MEDIA3_PLAYBACK_CACHE_DIR,
            refreshStorageStats = ::refreshStorageStats,
            loadProfileAvatar = ::loadProfileAvatar,
        )
    }

    }

    fun clearDownloads() {
        storageMaintenanceActionHost.clearDownloads()
    }

    fun clearAppCache() {
        storageMaintenanceActionHost.clearAppCache()
    }

    fun connectLastFm() {
        connectLastFmAction(
            scope = scope,
            canUseServerRequests = ::canUseServerRequests,
            musicRepository = musicRepository,
            authRepository = authRepository,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            openAuthUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            setAccessToken = { accessToken = it },
            setPendingLastFmToken = { pendingLastFmToken = it },
            setWaitingForLastFmSession = { waitingForLastFmSession = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun completeLastFmSession() {
        completeLastFmSessionAction(
            scope = scope,
            canUseServerRequests = ::canUseServerRequests,
            getPendingLastFmToken = { pendingLastFmToken },
            getPendingPlayEventCount = { pendingPlayEventCount },
            musicRepository = musicRepository,
            authRepository = authRepository,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            userPreferencesStore = userPreferencesStore,
            setAccessToken = { accessToken = it },
            setPendingLastFmToken = { pendingLastFmToken = it },
            setWaitingForLastFmSession = { waitingForLastFmSession = it },
            setLastFmConnection = { lastFmConnection = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun disconnectLastFm() {
        disconnectLastFmAction(
            scope = scope,
            canUseServerRequests = ::canUseServerRequests,
            getPendingPlayEventCount = { pendingPlayEventCount },
            musicRepository = musicRepository,
            authRepository = authRepository,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            userPreferencesStore = userPreferencesStore,
            setAccessToken = { accessToken = it },
            setPendingLastFmToken = { pendingLastFmToken = it },
            setWaitingForLastFmSession = { waitingForLastFmSession = it },
            setLastFmConnection = { lastFmConnection = it },
            markServerUnavailable = ::markServerUnavailable,
            setLibraryError = { libraryError = it },
        )
    }

    fun setScrobblingPaused(paused: Boolean) {
        if (paused) {
            clearNowPlayingEvent(activePlayEventState.value)
            scrobblingPaused = paused
            userPreferencesStore.setScrobblingPaused(paused)
        } else {
            scrobblingPaused = paused
            userPreferencesStore.setScrobblingPaused(paused)
            playerState.currentTrack?.takeIf { playerState.isPlaying }?.let { track ->
                val activeEvent = activePlayEventState.value?.takeIf { it.trackId == track.id }
                    ?: newActivePlayEvent(track).also { activePlayEventState.value = it }
                sendNowPlayingEvent(activeEvent, force = true)
            }
        }
    }

    fun continueOffline() {
        if (!canContinueOffline) {
            authError = "Sign in once online before offline mode can be used."
            return
        }

        account = authRepository.cachedAccount() ?: OfflineAccount
        syncMode = if (offlineOnly) SyncMode.OfflineOnly else SyncMode.Offline
        libraryError = if (playlists.isEmpty() && tracks.isEmpty()) {
            "Offline mode. No cached library is available yet."
        } else {
            "Offline mode. Showing cached library."
        }
        destination = AppDestination(AppTab.Home)
        backStack = emptyList()
    }

    fun setOfflineOnly(enabled: Boolean) {
        if (enabled) {
            clearNowPlayingEvent(activePlayEventState.value)
        }

        offlineOnly = enabled
        userPreferencesStore.setOfflineOnly(enabled)

        if (enabled) {
            enforceOfflinePlaybackAvailability()
            libraryLoadSerial += 1
            libraryLoadJob?.cancel()
            libraryLoadJob = null
            syncMode = SyncMode.OfflineOnly
            libraryLoading = false
            libraryError = null
            libraryNotice = null
        } else if (account != null) {
            loadLibrary()
        }
    }

    OfflinePlaybackAvailabilityEffect(
        syncMode = syncMode,
        offlineOnly = offlineOnly,
        enforceOfflinePlaybackAvailability = ::enforceOfflinePlaybackAvailability,
    )

    fun setUseLocalBackend(enabled: Boolean) {
        if (useLocalBackend == enabled) {
            return
        }

        clearNowPlayingEvent(activePlayEventState.value)
        useLocalBackend = enabled
        userPreferencesStore.setUseLocalBackend(enabled)
        authRepository.setApiBaseUrl(AppConfig.apiBaseUrl(enabled))

        if (!offlineOnly) {
            syncMode = SyncMode.Offline
            account?.let { loadLibrary() }
        }
    }

    InitialLibraryLoadEffect(
        account = account,
        offlineOnly = offlineOnly,
        loadLibrary = ::loadLibrary,
        setOfflineOnlySyncMode = { syncMode = SyncMode.OfflineOnly },
        clearLibraryError = { libraryError = null },
    )

    ObserveNetworkConnectivity(
        enabled = account != null && !offlineOnly,
        useLocalBackend = useLocalBackend,
        onNetworkPolicyChanged = {
            if (canUseNetworkForCollectionDownloads()) {
                resumePendingOfflineDownloads()
            } else {
                pauseCollectionDownloadsForNetworkPolicy()
            }
        },
        onDisconnected = {
            if (account != null && !offlineOnly) {
                syncMode = SyncMode.Offline
            }
        },
        onReconnected = {
            if (account != null && !offlineOnly && libraryLoadJob == null) {
                loadLibrary()
            }
            if (account != null && !offlineOnly) {
                scope.launch {
                    checkForAppUpdate(manual = false)
                }
            }
        },
    )

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        scope.launch {
            googleSignInTokenProvider.idTokenFromIntent(result.data)
                .fold(
                    onSuccess = { idToken ->
                        authRepository.signInWithGoogle(idToken)
                            .onSuccess { signedInAccount ->
                                account = signedInAccount
                                val signedInLastFmConnection = signedInAccount.lastFmConnection
                                signedInLastFmConnection?.let { connection ->
                                    lastFmConnection = connection.copy(pendingScrobbles = pendingPlayEventCount)
                                    userPreferencesStore.setLastFmConnection(lastFmConnection)
                                }
                                accessToken = authRepository.accessToken()
                                canContinueOffline = true
                                destination = AppDestination(AppTab.Home)
                                backStack = emptyList()
                                authError = null
                                loadLibrary()
                            }
                            .onFailure { error ->
                                if (error is TMusicApiException && error.statusCode in 400..499) {
                                    authError = error.userMessage()
                                    libraryError = null
                                } else if (canContinueOffline) {
                                    authError = null
                                    account = authRepository.cachedAccount() ?: OfflineAccount
                                    syncMode = SyncMode.Offline
                                    libraryError = "Server unavailable after Google Sign-In. Showing offline data. ${error.userMessage()}"
                                } else {
                                    authError = "Server unavailable. Sign in once online before offline mode can be used."
                                }
                            }
                    },
                    onFailure = { error ->
                        authError = error.userMessage()
                    },
                )
            signingIn = false
        }
    }

    fun startGoogleSignIn() {
        signingIn = true
        authError = null
        googleSignInLauncher.launch(googleSignInTokenProvider.signInIntent())
    }

    fun refreshCurrentPlaylist() {
        val selectedPlaylist = destination.playlistId?.let { playlistId ->
            playlists.firstOrNull { it.id == playlistId }
        }
        if (selectedPlaylist != null) {
            loadPlaylistTracks(selectedPlaylist, force = true)
        } else {
            loadLibrary(AppDestination(tab = AppTab.Library))
        }
    }

    fun selectTab(tab: AppTab) {
        val nextDestination = AppDestination(tab = tab)
        if (destination == nextDestination) {
            when (tab) {
                AppTab.Home, AppTab.Library -> loadLibrary(nextDestination)
                AppTab.Search -> {
                    searchQuery = ""
                    searchFocusRequestSerial += 1
                }
                AppTab.Profile -> Unit
            }
        } else {
            navigateTo(nextDestination)
            if (tab == AppTab.Profile) {
                loadLibrary(nextDestination)
            }
        }
    }

    fun selectPlaylist(playlist: Playlist) {
        navigateTo(AppDestination(tab = AppTab.Library, playlistId = playlist.id))
        loadPlaylistTracks(playlist, force = playlist.isFavoritesPlaylist())
    }

    fun showAllArtists() {
        val nextDestination = AppDestination(tab = AppTab.Home, homeRoute = HomeRoute.Artists)
        navigateTo(nextDestination)
        loadLibrary(nextDestination)
    }

    fun openFullPlayerFromMiniPlayer() {
        playerState.currentTrack?.let { loadArtwork(it.listArtworkKey(), ArtworkImageSize.FullPlayer) }
        fullPlayerOpen = true
    }

    fun handleRecentItemClick(item: RecentLibraryItem) {
        handleRecentItemClickAction(
            item = item,
            artists = artists,
            albums = albums,
            savedAlbums = savedAlbums,
            searchResults = searchResults,
            similarArtistsByArtist = similarArtistsByArtist,
            albumsByArtist = albumsByArtist,
            appearsOnByArtist = appearsOnByArtist,
            tracks = tracks,
            searchQuery = searchQuery,
            setSearchQuery = { searchQuery = it },
            resolveCachedArtist = ::resolveCachedArtist,
            openArtist = ::openArtist,
            openAlbum = ::openAlbum,
            selectSearchTrack = ::selectSearchTrack,
            setLibraryError = { libraryError = it },
        )
    }

    fun checkUpdatesManually() {
        scope.launch {
            checkForAppUpdate(manual = true)
        }
    }

    fun signOutFromUi() {
        scope.launch {
            signOutLocalSession()
        }
    }

    BackHandler(enabled = queueOpen) {
        queueOpen = false
    }

    BackHandler(enabled = account != null && backStack.isNotEmpty() && !fullPlayerOpen) {
        goBack()
    }

    TMusicAppRender(
        state = TMusicAppRenderState(
        account = account,
        signingIn = signingIn,
        authError = authError,
        useLocalBackend = useLocalBackend,
        canContinueOffline = canContinueOffline,
        destination = destination,
        playlists = playlists,
        tracks = tracks,
        recentAlbums = recentAlbums,
        databaseTrackCount = databaseTrackCount,
        offlineAlbumIds = offlineAlbumIds,
        offlinePlayableTrackIds = offlinePlayableTrackIds,
        artists = artists,
        albums = albums,
        savedAlbums = savedAlbums,
        albumsByArtist = albumsByArtist,
        appearsOnByArtist = appearsOnByArtist,
        looseTracksByArtist = looseTracksByArtist,
        similarArtistsByArtist = similarArtistsByArtist,
        artistAlbumLoadsInProgress = artistAlbumLoadsInProgress,
        similarArtistLoadsInProgress = similarArtistLoadsInProgress,
        albumTrackLoadsInProgress = albumTrackLoadsInProgress,
        playlistTrackLoadsInProgress = playlistTrackLoadsInProgress,
        libraryPaging = libraryPaging,
        recentAlbumsPaging = recentAlbumsPaging,
        albumTrackHasMoreById = albumTrackHasMoreById,
        playlistTrackHasMoreById = playlistTrackHasMoreById,
        albumTracksById = albumTracksById,
        searchQuery = searchQuery,
        searchFocusRequestSerial = searchFocusRequestSerial,
        searchResults = searchResults,
        searchLoading = searchLoading,
        searchHasMore = searchHasMore,
        recentItems = recentItems,
        playerState = playerState,
        playbackBufferedFraction = playbackBufferedFraction,
        playerError = playerError,
        fullPlayerOpen = fullPlayerOpen,
        queueOpen = queueOpen,
        artworkBitmaps = artworkBitmaps,
        profileAvatarBitmap = profileAvatarBitmap,
        playbackQueue = playbackQueue,
        playbackQueueGeneration = playbackQueueGeneration,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        libraryLoading = libraryLoading,
        libraryError = libraryError,
        libraryNotice = libraryNotice,
        canUseServerRequests = canUseServerRequests(),
        syncMode = syncMode,
        lastFmConnection = lastFmConnection,
        pendingPlayEventCount = pendingPlayEventCount,
        pendingPlayEventSyncProgress = pendingPlayEventSyncProgress,
        waitingForLastFmSession = waitingForLastFmSession,
        scrobblingPaused = scrobblingPaused,
        showLyrics = showLyrics,
        lyricsByTrackId = lyricsByTrackId,
        lyricsUnavailableIds = lyricsUnavailableIds,
        lyricsLoadsInProgress = lyricsLoadsInProgress,
        crossfadeSeconds = crossfadeSeconds,
        equalizerAvailable = equalizerAvailable && exoPlayer.audioSessionId > 0,
        offlineOnly = offlineOnly,
        downloadUsingCellular = downloadUsingCellular,
        downloadedSizeBytes = downloadedSizeBytes,
        cacheSizeBytes = cacheSizeBytes,
        appUpdateController = appUpdateController,
        trackForPlaylistAdd = trackForPlaylistAdd,
        playlistPickerPlaylists = playlistPickerPlaylists,
        playlistPickerLoading = playlistPickerLoading,
        playlistAddInProgress = playlistAddInProgress,
        duplicatePlaylistForAdd = duplicatePlaylistForAdd,
        artistChoices = artistChoices,
        ),
        actions = TMusicAppRenderActions(
        onUseLocalBackendChange = ::setUseLocalBackend,
        onGoogleSignIn = ::startGoogleSignIn,
        onContinueOffline = ::continueOffline,
        onRetry = { loadLibrary(destination) },
        onRefreshCurrentPlaylist = ::refreshCurrentPlaylist,
        onRefreshArtist = { artist ->
            loadArtistAlbums(artist, force = true)
            loadSimilarArtists(artist, force = true)
        },
        onRefreshAlbum = { album -> loadAlbumTracks(album, force = true) },
        onLoadMoreArtists = ::loadMoreArtists,
        onLoadMoreAlbums = ::loadMoreAlbums,
        onLoadMoreRecentAlbums = ::loadMoreRecentAlbums,
        onLoadMoreAlbumTracks = { album -> loadAlbumTracks(album, force = false) },
        onLoadMorePlaylistTracks = { playlist -> loadPlaylistTracks(playlist, force = false) },
        onOfflineOnlyChange = ::setOfflineOnly,
        onScrobblingPausedChange = ::setScrobblingPaused,
        onShowLyricsChange = ::setShowLyrics,
        onCrossfadeSecondsChange = ::setCrossfadeSeconds,
        onDownloadUsingCellularChange = ::setDownloadUsingCellular,
        onOpenEqualizer = ::openSystemEqualizer,
        onCreatePlaylist = ::createPlaylist,
        onUpdatePlaylist = ::updatePlaylistDetails,
        onDeletePlaylist = ::deletePlaylist,
        onAddTrackToPlaylistClick = ::openAddTrackToPlaylist,
        onDownloadPlaylist = ::downloadPlaylist,
        onDeletePlaylistDownload = ::deletePlaylistDownload,
        onPlayPlaylist = ::playPlaylist,
        onShufflePlayPlaylist = ::shufflePlayPlaylist,
        onPlayPlaylistTrack = ::playPlaylistTrackAt,
        onRemoveTrackFromPlaylist = ::removeTrackFromPlaylist,
        onReorderPlaylistTracks = ::reorderPlaylistTracks,
        onRequestArtwork = ::loadArtwork,
        onConnectLastFm = ::connectLastFm,
        onCompleteLastFmSession = ::completeLastFmSession,
        onDisconnectLastFm = ::disconnectLastFm,
        onSyncLastFmUpdates = ::syncPendingPlayEvents,
        onClearDownloads = ::clearDownloads,
        onClearCache = ::clearAppCache,
        onCheckUpdates = ::checkUpdatesManually,
        onSelectTab = ::selectTab,
        onSelectPlaylist = ::selectPlaylist,
        onShowAllArtists = ::showAllArtists,
        onSelectArtist = ::openArtist,
        onSelectAlbum = ::openAlbum,
        onPlayAlbum = ::playAlbum,
        onPlayAlbumTrack = ::playAlbumFromTrack,
        onToggleAlbumInLibrary = ::toggleAlbumInLibrary,
        onDownloadAlbum = ::downloadAlbum,
        onDeleteAlbumDownload = ::deleteAlbumDownload,
        onGoToAlbumArtist = ::openAlbumArtist,
        onBack = ::goBack,
        onSelectTrack = ::selectTrack,
        onSearchQueryChange = {
            searchQuery = it
            searchTrackOffset = 0
            searchHasMore = false
        },
        onLoadMoreSearchTracks = {
            if (!searchLoading && searchHasMore && searchQuery.isNotBlank()) {
                searchTrackOffset = searchResults.tracks.size
            }
        },
        onClearRecentItems = ::clearRecentItems,
        onRecentItemClick = ::handleRecentItemClick,
        onSelectSearchArtist = ::selectSearchArtist,
        onSelectSearchAlbum = ::selectSearchAlbum,
        onSelectSearchTrack = ::selectSearchTrack,
        onOpenFullPlayer = ::openFullPlayerFromMiniPlayer,
        onCloseFullPlayer = { fullPlayerOpen = false },
        onOpenQueue = { queueOpen = true },
        onCloseQueue = { queueOpen = false },
        onSelectQueueTrack = ::playTrackFromCurrentQueueAt,
        onRemoveQueueTrack = ::removeTrackFromQueueAt,
        onReorderQueueTracks = ::reorderQueueTracks,
        onAddCurrentTrackToPlaylist = { playerState.currentTrack?.let(::openAddTrackToPlaylist) },
        onAddTrackToQueue = ::addTrackToQueue,
        onGoToTrackArtist = ::openTrackArtist,
        onGoToTrackAlbum = ::openTrackAlbum,
        onToggleCurrentFavorite = { playerState.currentTrack?.let(::toggleFavoriteTrack) },
        onToggleTrackFavorite = ::toggleFavoriteTrack,
        onSkipPrevious = { skipInQueue(direction = -1) },
        onSkipNext = { skipInQueue(direction = 1) },
        onSwipePreviousTrack = { skipInQueue(direction = -1, restartCurrentWhenPrevious = false) },
        onShuffleChange = ::setShuffleEnabled,
        onRepeatModeChange = ::setRepeatMode,
        onTogglePlayback = ::togglePlayback,
        onSeek = ::seekTo,
        onRefreshCurrentLyrics = { playerState.currentTrack?.let(::refreshLyrics) },
        onSignOut = ::signOutFromUi,
        onDismissAddToPlaylist = {
            trackForPlaylistAdd = null
            duplicatePlaylistForAdd = null
        },
        onSelectPlaylistForTrack = { playlist, track -> requestAddTrackToPlaylist(playlist, track) },
        onConfirmDuplicatePlaylist = { playlist, track -> requestAddTrackToPlaylist(playlist, track, allowDuplicate = true) },
        onDismissDuplicatePlaylist = { duplicatePlaylistForAdd = null },
        onDismissArtistChoices = { artistChoices = emptyList() },
        onSelectArtistChoice = { artist ->
            artistChoices = emptyList()
            openArtist(artist)
        },
        ),
    )
}

private const val MEDIA3_PLAYBACK_CACHE_DIR = "media3_playback_cache"
