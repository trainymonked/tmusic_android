package dev.teacode.tmusic.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
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
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.data.enforcedForCurrentApp
import dev.teacode.tmusic.data.isAvailableForCurrentApp
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.ScrobbleState
import dev.teacode.tmusic.domain.Track
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import org.json.JSONObject

@Composable
@NonRestartableComposable
internal fun TMusicAppControllerContent(
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

    val appState = remember(initialState) {
        TMusicAppMutableState(
            initialState = initialState,
            userPreferencesStore = userPreferencesStore,
            initialAccessToken = authRepository.accessToken(),
            initialEqualizerAvailable = isSystemEqualizerAvailable(context),
            initialPendingPlayEventCount = pendingPlayEventStore.count(),
            initialPendingLibraryMutationCount = pendingLibraryMutationStore.count(),
            initialPendingLastFmToken = lastFmAuthTokenStore.token(),
        )
    }

    DisposableEffect(authRepository, appState, context) {
        authRepository.setServerResponseListener {
            scope.launch {
                if (
                    appState.account != null &&
                    !appState.offlineOnly &&
                    appState.syncMode != SyncMode.OfflineOnly &&
                    context.hasUsableNetworkConnection(appState.useLocalBackend)
                ) {
                    appState.syncMode = SyncMode.Online
                }
            }
        }
        authRepository.setServerFailureListener { error ->
            if (error.isServerAvailabilityFailure()) {
                scope.launch {
                    if (!appState.offlineOnly) {
                        appState.syncMode = SyncMode.Offline
                    }
                }
            }
        }
        onDispose {
            authRepository.setServerResponseListener(null)
            authRepository.setServerFailureListener(null)
        }
    }

    TMusicAppStateContent(appState) {
        val lastFmConnected = lastFmConnection.state == ScrobbleState.Ready &&
            !lastFmConnection.username.isNullOrBlank()
        val lastFmConnectedState = rememberUpdatedState(lastFmConnected)
        val playerStateState = rememberUpdatedState(playerState)
        val playbackQueueState = rememberUpdatedState(playbackQueue)
        val gaplessPlaybackRequestState = rememberUpdatedState(gaplessPlaybackRequest)
        val gaplessMediaQueueIndicesState = rememberUpdatedState(gaplessMediaQueueIndices)
        val gaplessMediaUrlsState = rememberUpdatedState(gaplessMediaUrls)
        val repeatModeState = rememberUpdatedState(repeatMode)
        val appUpdateController = remember {
            val cachedUpdate = userPreferencesStore.cachedAppUpdate()?.enforcedForCurrentApp()
            val initialUpdate = cachedUpdate?.takeIf { update ->
                update.isAvailableForCurrentApp(BuildConfig.VERSION_NAME)
            }
            if (cachedUpdate != null && initialUpdate == null) {
                userPreferencesStore.clearCachedAppUpdate()
            }
            AppUpdateController(
                context = context,
                userPreferencesStore = userPreferencesStore,
                appUpdateChecker = appUpdateChecker,
                currentVersion = BuildConfig.VERSION_NAME,
                initialUpdate = initialUpdate,
                onNotice = { message -> libraryNotice = message },
                onError = { message -> libraryError = message },
            )
        }
        val offlinePlayableTrackIds = remember(tracks, albumTracksById, downloadedSizeBytes, cacheSizeBytes) {
            (tracks + albumTracksById.values.flatten())
                .distinctBy { track -> track.id }
                .filter { track ->
                    track.downloadState == DownloadState.Downloaded ||
                        musicRepository.localPlaybackUrl(track.id) != null ||
                        musicRepository.cachedPlaybackUrl(track.id) != null
                }
                .map { it.id }
                .toSet()
        }
    lateinit var networkPolicyController: NetworkPolicyController
    lateinit var appUpdateHost: AppUpdateHost
    networkPolicyController = NetworkPolicyController(
        context = context,
        scope = scope,
        appState = appState,
        checkForAppUpdate = { manual -> appUpdateHost.checkForAppUpdate(manual) },
    )
    appUpdateHost = AppUpdateHost(
        scope = scope,
        appUpdateController = appUpdateController,
        networkPolicyController = networkPolicyController,
    )

    fun hasNetworkConnection(): Boolean = networkPolicyController.hasNetworkConnection()
    fun canUseServerRequests(): Boolean = networkPolicyController.canUseServerRequests()
    fun canAttemptMetadataRequest(): Boolean = networkPolicyController.canAttemptMetadataRequest()
    fun canUseMediaServerRequests(): Boolean = networkPolicyController.canUseMediaServerRequests()
    fun mediaDisabledMessage(): String = networkPolicyController.mediaDisabledMessage()
    fun disableMediaPlaybackForAccount() = networkPolicyController.disableMediaPlaybackForAccount()
    fun canCheckAppUpdates(): Boolean = networkPolicyController.canCheckAppUpdates()
    fun canUseNetworkForCollectionDownloads(): Boolean = networkPolicyController.canUseNetworkForCollectionDownloads()

    val appUpdateDebugStatus = networkPolicyController.appUpdateDebugStatus()
    AppUpdateEffects(
        controller = appUpdateController,
        context = context,
        accountId = account?.id,
        offlineOnly = offlineOnly,
        useLocalBackend = useLocalBackend,
        canCheck = canCheckAppUpdates(),
        debugStatus = appUpdateDebugStatus,
    )

    val canSendPlayEventsState = rememberUpdatedState(
        lastFmConnected && canUseServerRequests() && !scrobblingPaused,
    )

    val playbackPersistenceController = remember(scope, playbackStateStore, playbackSnapshotSaveMutex) {
        PlaybackPersistenceController(
            scope = scope,
            playbackStateStore = playbackStateStore,
            playbackSnapshotSaveMutex = playbackSnapshotSaveMutex,
            appState = appState,
            getExoPlayer = { exoPlayer },
            getStandbyExoPlayer = { standbyExoPlayer },
        )
    }

    fun savePlaybackSnapshot(
        state: PlayerState = playerState,
        queue: PlaybackQueue = playbackQueue,
        runtimeOnly: Boolean = false,
    ) = playbackPersistenceController.savePlaybackSnapshot(
            state = state,
            queue = queue,
            runtimeOnly = runtimeOnly,
        )

    fun savePlaybackRuntimeSnapshot(
        state: PlayerState = playerState,
        queue: PlaybackQueue = playbackQueue,
    ) = playbackPersistenceController.savePlaybackRuntimeSnapshot(
            state = state,
            queue = queue,
        )

    fun clearGaplessPlaybackState() = playbackPersistenceController.clearGaplessPlaybackState()

    fun removePreparedPlaybackItemsAfterCurrent() =
        playbackPersistenceController.removePreparedPlaybackItemsAfterCurrent()

    fun cancelCrossfade() = playbackPersistenceController.cancelCrossfade()

    val authSessionActionHost = createAuthSessionActionHost(
        appState = appState,
        authRepository = authRepository,
        googleSignInTokenProvider = googleSignInTokenProvider,
        userPreferencesStore = userPreferencesStore,
        libraryCacheStore = libraryCacheStore,
        playbackStateStore = playbackStateStore,
        pendingPlayEventStore = pendingPlayEventStore,
        pendingLibraryMutationStore = pendingLibraryMutationStore,
        lastFmAuthTokenStore = lastFmAuthTokenStore,
        clearGaplessPlaybackState = ::clearGaplessPlaybackState,
    )
    suspend fun signOutLocalSession(message: String? = null) {
        authSessionActionHost.signOutLocalSession(message)
    }

    fun markServerUnavailable(error: Throwable) {
        if (error.isUnauthorizedError()) {
            scope.launch {
                signOutLocalSession(error.unauthorizedSessionMessage())
            }
            return
        }
        networkPolicyController.markServerUnavailable(error)
    }

    val playbackErrorActionHost = createPlaybackErrorController(
        appState = appState,
        scope = scope,
        exoPlayer = exoPlayer,
        musicRepository = musicRepository,
        authRepository = authRepository,
        clearGaplessPlaybackState = ::clearGaplessPlaybackState,
        cancelCrossfade = ::cancelCrossfade,
        canUseMediaServerRequests = ::canUseMediaServerRequests,
        mediaDisabledMessage = ::mediaDisabledMessage,
        disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
        markServerUnavailable = ::markServerUnavailable,
    )
    fun handlePlaybackPlayerError(message: String, httpStatusCode: Int?): Boolean {
        return playbackErrorActionHost.handlePlaybackPlayerError(message, httpStatusCode)
    }

    val lastFmPlaybackEventActionHost = createLastFmPlaybackEventController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        authRepository = authRepository,
        pendingPlayEventStore = pendingPlayEventStore,
        userPreferencesStore = userPreferencesStore,
        getLastFmConnected = { lastFmConnectedState.value },
        canUseServerRequests = ::canUseServerRequests,
        canSendPlayEvents = { canSendPlayEventsState.value },
        markServerUnavailable = ::markServerUnavailable,
        savePlaybackSnapshot = { savePlaybackSnapshot() },
    )
    fun sendNowPlayingEvent(
        activeEvent: ActivePlayEvent,
        track: Track? = playerState.currentTrack?.takeIf { it.id == activeEvent.trackId },
        force: Boolean = false,
    ) {
        lastFmPlaybackEventActionHost.sendNowPlayingEvent(activeEvent, track, force)
    }

    fun clearNowPlayingEvent(activeEvent: ActivePlayEvent?) {
        lastFmPlaybackEventActionHost.clearNowPlayingEvent(activeEvent)
    }

    fun ensureActivePlayEvent(track: Track, forceNew: Boolean = false) {
        lastFmPlaybackEventActionHost.ensureActivePlayEvent(track, forceNew)
    }

    fun queuePendingPlayEvent(activeEvent: ActivePlayEvent) {
        lastFmPlaybackEventActionHost.queuePendingPlayEvent(activeEvent)
    }

    fun syncPendingPlayEvents() {
        lastFmPlaybackEventActionHost.syncPendingPlayEvents()
    }

    fun discardActivePlayEvent(activeEvent: ActivePlayEvent) {
        lastFmPlaybackEventActionHost.discardActivePlayEvent(activeEvent)
    }

    fun completeActivePlayEvent(force: Boolean = false) {
        lastFmPlaybackEventActionHost.completeActivePlayEvent(force)
    }

    lateinit var navigationController: NavigationController
    fun navigateTo(next: AppDestination) = navigationController.navigateTo(next)

    fun goBack() = navigationController.goBack()

    val libraryLoadActionHost = createLibraryLoadActionHost(
        appState = appState,
        scope = scope,
        authRepository = authRepository,
        musicRepository = musicRepository,
        userPreferencesStore = userPreferencesStore,
        libraryCacheStore = libraryCacheStore,
        lastFmAuthTokenStore = lastFmAuthTokenStore,
        signOutLocalSession = ::signOutLocalSession,
        markServerUnavailable = ::markServerUnavailable,
        hasNetworkConnection = ::hasNetworkConnection,
    )
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

    val storageController = remember(scope, userPreferencesStore, musicRepository, offlineLyricsStore, artworkCacheStore, libraryCacheStore, appCacheStore, mediaCache) {
        StorageController(
            scope = scope,
            appState = appState,
            userPreferencesStore = userPreferencesStore,
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            artworkCacheStore = artworkCacheStore,
            libraryCacheStore = libraryCacheStore,
            appCacheStore = appCacheStore,
            mediaCache = mediaCache,
        )
    }
    fun addRecentItem(item: RecentLibraryItem) = storageController.addRecentItem(item)

    fun refreshStorageStats() = storageController.refreshStorageStats()

    fun clearRecentItems() = storageController.clearRecentItems()

    val artworkLyricsActionHost = createArtworkLyricsController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        authRepository = authRepository,
        offlineLyricsStore = offlineLyricsStore,
        artworkCacheStore = artworkCacheStore,
        libraryCacheStore = libraryCacheStore,
        canUseMediaServerRequests = ::canUseMediaServerRequests,
        mediaDisabledMessage = ::mediaDisabledMessage,
        disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
        markServerUnavailable = ::markServerUnavailable,
    )
    suspend fun cachedArtworkBitmap(
        artworkKey: String,
        imageSize: ArtworkImageSize,
    ): ImageBitmap? {
        return artworkLyricsActionHost.cachedArtworkBitmap(artworkKey, imageSize)
    }

    suspend fun cacheArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize,
    ): ImageBitmap? {
        return artworkLyricsActionHost.cacheArtwork(artworkKey, imageSize)
    }

    fun loadArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize = ArtworkImageSize.AlbumGrid,
    ) {
        artworkLyricsActionHost.loadArtwork(artworkKey, imageSize)
    }

    fun refreshArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize = ArtworkImageSize.AlbumGrid,
    ) {
        artworkLyricsActionHost.refreshArtwork(artworkKey, imageSize)
    }

    fun loadLyrics(track: Track) {
        artworkLyricsActionHost.loadLyrics(track)
    }

    fun refreshLyrics(track: Track) {
        artworkLyricsActionHost.refreshLyrics(track)
    }

    CurrentTrackLyricsEffect(
        playerState = playerState,
        syncMode = syncMode,
        offlineOnly = offlineOnly,
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
        artworkLyricsActionHost.cacheDownloadedAssets(track)
    }

    PendingTransitionArtworkEffect(
        pendingArtworkKey = pendingTransitionArtworkTrackId,
        loadArtwork = ::loadArtwork,
        clearPendingArtworkKey = { pendingTransitionArtworkTrackId = null },
    )

    fun loadProfileAvatar(currentAccount: Account) {
        artworkLyricsActionHost.loadProfileAvatar(currentAccount)
    }

    ProfileAvatarEffect(
        account = account,
        loadProfileAvatar = ::loadProfileAvatar,
        clearProfileAvatar = {
            profileAvatarLoadKey = null
            profileAvatarBitmap = null
        },
    )

    var playQueuedTrackInvoker: ((Track, PlaybackQueue, Long, Boolean) -> Unit)? = null
    val playbackRuntimeActionHost = createPlaybackRuntimeController(
        appState = appState,
        scope = scope,
        getExoPlayer = { exoPlayer },
        setExoPlayer = { exoPlayer = it },
        resolvePlaybackMediaCacheKey = { trackId, playbackUrl ->
            mediaCache.resolvePlaybackMediaCacheKey(trackId, playbackUrl)
        },
        musicRepository = musicRepository,
        authRepository = authRepository,
        completeActivePlayEvent = { force -> completeActivePlayEvent(force = force) },
        ensureActivePlayEvent = { track, force -> ensureActivePlayEvent(track, force) },
        clearNowPlayingEvent = ::clearNowPlayingEvent,
        clearGaplessPlaybackState = ::clearGaplessPlaybackState,
        cancelCrossfade = ::cancelCrossfade,
        canUseMediaServerRequests = ::canUseMediaServerRequests,
        loadArtwork = ::loadArtwork,
        loadLyrics = ::loadLyrics,
        playQueuedTrack = { track, queue, resumePositionMs, allowResume ->
            playQueuedTrackInvoker?.invoke(track, queue, resumePositionMs, allowResume)
        },
    )
    fun startPlayback(
        track: Track,
        playbackUrl: String,
        startPositionMs: Long = 0L,
    ) {
        playbackRuntimeActionHost.startPlayback(track, playbackUrl, startPositionMs)
    }

    fun startGaplessPlayback(
        track: Track,
        queue: PlaybackQueue,
        urls: List<String>,
        resumePositionMs: Long = 0L,
    ) {
        playbackRuntimeActionHost.startGaplessPlayback(track, queue, urls, resumePositionMs)
    }

    fun installGaplessPrefetch(
        queue: PlaybackQueue,
        nextTrack: Track,
        nextIndex: Int,
        nextUrl: String,
    ) {
        playbackRuntimeActionHost.installGaplessPrefetch(queue, nextTrack, nextIndex, nextUrl)
    }

    fun localOrCachedPlaybackUrl(trackId: String): String? {
        return playbackRuntimeActionHost.localOrCachedPlaybackUrl(trackId)
    }

    fun localOrCachedPlaybackUrl(track: Track): String? {
        return playbackRuntimeActionHost.localOrCachedPlaybackUrl(track)
    }

    fun enforceOfflinePlaybackAvailability() {
        playbackRuntimeActionHost.enforceOfflinePlaybackAvailability()
    }

    fun prefetchTrackAssets(track: Track) {
        playbackRuntimeActionHost.prefetchTrackAssets(track)
    }

    fun prefetchNextTrackUrl(queue: PlaybackQueue) {
        playbackRuntimeActionHost.prefetchNextTrackUrl(queue)
    }

    fun cancelNextTrackPrefetch() {
        playbackRuntimeActionHost.cancelNextTrackPrefetch()
    }

    fun nextCrossfadeQueueIndex(queue: PlaybackQueue): Int? {
        return playbackRuntimeActionHost.nextCrossfadeQueueIndex(queue)
    }

    fun beginPreparedCrossfade(prepared: PreparedCrossfade, fadeDurationMs: Long) {
        playbackRuntimeActionHost.beginPreparedCrossfade(prepared, fadeDurationMs)
    }

    fun seekPreparedQueueMediaItem(targetIndex: Int, direction: Int): Boolean {
        return playbackRuntimeActionHost.seekPreparedQueueMediaItem(targetIndex, direction)
    }

    val playQueuedTrackActionHost = createPlayQueuedTrackController(
        appState = appState,
        scope = scope,
        exoPlayer = exoPlayer,
        musicRepository = musicRepository,
        authRepository = authRepository,
        cancelCrossfade = ::cancelCrossfade,
        canUseMediaServerRequests = ::canUseMediaServerRequests,
        disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
        mediaDisabledMessage = ::mediaDisabledMessage,
        clearGaplessPlaybackState = ::clearGaplessPlaybackState,
        localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
        loadArtwork = ::loadArtwork,
        cancelNextTrackPrefetch = ::cancelNextTrackPrefetch,
        prefetchNextTrackUrl = ::prefetchNextTrackUrl,
        startGaplessPlayback = ::startGaplessPlayback,
        startPlayback = ::startPlayback,
        markServerUnavailable = ::markServerUnavailable,
    )
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

    playQueuedTrackInvoker = { track, queue, resumePositionMs, allowResume ->
        playQueuedTrack(
            track = track,
            queue = queue,
            resumePositionMs = resumePositionMs,
            allowResume = allowResume,
        )
    }

    fun applyPlaybackQueueOrderWithoutInterrupt(nextQueue: PlaybackQueue) {
        playbackRuntimeActionHost.applyPlaybackQueueOrderWithoutInterrupt(nextQueue)
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

    fun selectSearchTrack(track: Track, sourceTitle: String? = null) =
        navigationController.selectSearchTrack(track, sourceTitle)

    val playlistPlaybackActionHost = createPlaylistPlaybackController(
        appState = appState,
        scope = scope,
        userPreferencesStore = userPreferencesStore,
        musicRepository = musicRepository,
        libraryCacheStore = libraryCacheStore,
        queueStartRequestSerial = queueStartRequestSerial,
        canUseServerRequests = ::canUseServerRequests,
        canUseMediaServerRequests = ::canUseMediaServerRequests,
        localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
        nextQueueStartRequestSerial = ::nextQueueStartRequestSerial,
        playQueuedTrack = { track, queue, index ->
            playQueuedTrack(track = track, queue = queue, preferredIndex = index, newQueue = true)
        },
        replacePlaybackQueueSnapshotIfRequestCurrent = ::replacePlaybackQueueSnapshotIfRequestCurrent,
        markServerUnavailable = ::markServerUnavailable,
    )
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

    val libraryPayloadActionHost = createLibraryPayloadController(
        appState = appState,
        musicRepository = musicRepository,
        libraryCacheStore = libraryCacheStore,
    )
    fun mergeLoadedTracks(loadedTracks: List<Track>) {
        libraryPayloadActionHost.mergeLoadedTracks(loadedTracks)
    }

    fun applyPlaylistPayload(payload: dev.teacode.tmusic.data.PlaylistPayload): Playlist? {
        return libraryPayloadActionHost.applyPlaylistPayload(payload)
    }

    fun applyPlaylistTrackPage(
        playlist: Playlist,
        payload: dev.teacode.tmusic.data.PlaylistPayload,
        append: Boolean,
    ): Playlist? {
        return libraryPayloadActionHost.applyPlaylistTrackPage(playlist, payload, append)
    }

    fun playlistDownloadedTrackCount(playlist: Playlist): Int {
        return libraryPayloadActionHost.playlistDownloadedTrackCount(playlist)
    }

    fun playlistIsFullyDownloaded(playlist: Playlist): Boolean {
        return libraryPayloadActionHost.playlistIsFullyDownloaded(playlist)
    }

    val libraryDetailActionHost = createLibraryDetailController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        authRepository = authRepository,
        canAttemptMetadataRequest = ::canAttemptMetadataRequest,
        hasNetworkConnection = ::hasNetworkConnection,
        canUseServerRequests = ::canUseServerRequests,
        playlistIsFullyDownloaded = ::playlistIsFullyDownloaded,
        applyPlaylistTrackPage = ::applyPlaylistTrackPage,
        mergeLoadedTracks = ::mergeLoadedTracks,
        markServerUnavailable = ::markServerUnavailable,
    )
    fun loadPlaylistTracks(playlist: Playlist, force: Boolean = false) {
        libraryDetailActionHost.loadPlaylistTracks(playlist, force)
    }

    fun loadFullFavoritesPlaylist(
        playlist: Playlist,
        allowOfflineProbe: Boolean = false,
        onLoaded: () -> Unit = {},
    ) {
        val requestAllowed = canAttemptMetadataRequest() ||
            (allowOfflineProbe && syncMode == SyncMode.Offline && hasNetworkConnection())
        if (!requestAllowed || playlist.id in playlistTrackLoadsInProgress) {
            return
        }
        playlistTrackLoadsInProgress = playlistTrackLoadsInProgress + playlist.id
        scope.launch {
            runCatching {
                musicRepository.favoritesPlaylistPayload()
            }.onSuccess { payload ->
                accessToken = authRepository.accessToken()
                syncMode = SyncMode.Online
                applyPlaylistPayload(payload)
                playlistTrackHasMoreById = playlistTrackHasMoreById + (playlist.id to false)
                onLoaded()
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            playlistTrackLoadsInProgress = playlistTrackLoadsInProgress - playlist.id
        }
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

    fun changeArtistSortOption(sortOption: ArtistSortOption) {
        if (artistSortOption == sortOption) {
            return
        }
        val currentArtistsForCache = if (artistServerSortOption == artistSortOption) {
            artists
        } else {
            emptyList()
        }
        val cachedNextSort = artistListCache[sortOption]
        val sortChange = changeArtistSortWithCache(
            currentSortOption = artistSortOption,
            nextSortOption = sortOption,
            currentArtists = currentArtistsForCache,
            currentPaging = libraryPaging,
            cache = artistListCache,
        )
        artistSortOption = sortOption
        artistListCache = sortChange.cache
        artists = sortChange.artists
        artistServerSortOption = if (cachedNextSort != null) sortOption else null
        libraryPaging = sortChange.paging
        if (cachedNextSort == null) {
            libraryDetailActionHost.reloadArtists(sortOption)
        }
    }

    fun refreshPlaylist(playlist: Playlist) {
        val refreshCover = {
            refreshArtwork(playlistArtworkKey(playlist), ArtworkImageSize.AlbumGrid)
        }
        val refreshCoverAfterLoad = if (canUseMediaServerRequests()) {
            refreshCover()
            val noOp: () -> Unit = {}
            noOp
        } else {
            refreshCover
        }
        if (playlist.isFavoritesPlaylist()) {
            loadFullFavoritesPlaylist(
                playlist = playlist,
                allowOfflineProbe = true,
                onLoaded = refreshCoverAfterLoad,
            )
        } else {
            libraryDetailActionHost.loadPlaylistTracks(
                playlist = playlist,
                force = true,
                allowOfflineProbe = true,
                onLoaded = refreshCoverAfterLoad,
            )
        }
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
        fullPlayerOpen = false
        val existingArtistIndex = artists.indexOfFirst { it.id == artist.id }
        artists = if (existingArtistIndex >= 0) {
            artists.mapIndexed { index, existingArtist ->
                if (index == existingArtistIndex) {
                    artist
                } else {
                    existingArtist
                }
            }
        } else {
            artists + artist
        }
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

    fun selectSearchArtist(artist: LibraryArtist) = navigationController.selectSearchArtist(artist)

    fun selectSearchAlbum(album: LibraryAlbum) = navigationController.selectSearchAlbum(album)

    navigationController = NavigationController(
        appState = appState,
        addRecentItem = ::addRecentItem,
        loadLibrary = ::loadLibrary,
        loadArtists = libraryDetailActionHost::reloadArtists,
        loadFullFavoritesPlaylist = { playlist -> loadFullFavoritesPlaylist(playlist) },
        loadPlaylistTracks = ::loadPlaylistTracks,
        refreshPlaylist = ::refreshPlaylist,
        loadArtwork = ::loadArtwork,
        resolveCachedArtist = ::resolveCachedArtist,
        openArtist = ::openArtist,
        openAlbum = ::openAlbum,
        selectTrack = ::selectTrack,
    )

    val albumPlaybackActionHost = createAlbumPlaybackController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        userPreferencesStore = userPreferencesStore,
        queueStartRequestSerial = queueStartRequestSerial,
        canUseServerRequests = ::canUseServerRequests,
        nextQueueStartRequestSerial = ::nextQueueStartRequestSerial,
        playQueuedTrack = { selectedTrack, queue, index ->
            playQueuedTrack(track = selectedTrack, queue = queue, preferredIndex = index, newQueue = true)
        },
        replacePlaybackQueueIfRequestCurrent = ::replacePlaybackQueueIfRequestCurrent,
        mergeLoadedTracks = ::mergeLoadedTracks,
        markServerUnavailable = ::markServerUnavailable,
    )
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

    val albumLibraryActionHost = createAlbumLibraryController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        libraryCacheStore = libraryCacheStore,
        canUseServerRequests = ::canUseServerRequests,
        enqueueLibraryMutation = ::enqueueLibraryMutation,
        saveLibraryCache = ::saveLibraryCache,
        refreshAccessToken = authRepository::accessToken,
        markServerUnavailable = ::markServerUnavailable,
    )
    fun toggleAlbumInLibrary(album: LibraryAlbum) {
        albumLibraryActionHost.toggleAlbumInLibrary(album)
    }

    val playbackQueueControlActionHost = createPlaybackQueueControlController(
        appState = appState,
        exoPlayer = exoPlayer,
        userPreferencesStore = userPreferencesStore,
        cancelCrossfade = ::cancelCrossfade,
        clearGaplessPlaybackState = ::clearGaplessPlaybackState,
        removePreparedPlaybackItemsAfterCurrent = ::removePreparedPlaybackItemsAfterCurrent,
        clearNowPlayingEvent = ::clearNowPlayingEvent,
        canUseMediaServerRequests = ::canUseMediaServerRequests,
        localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
        completeActivePlayEvent = { force -> completeActivePlayEvent(force = force) },
        ensureActivePlayEvent = { track, force -> ensureActivePlayEvent(track, force) },
        seekPreparedQueueMediaItem = ::seekPreparedQueueMediaItem,
        playQueuedTrack = { track, queue, preferredIndex, unavailableSkipDirection ->
            playQueuedTrack(
                track = track,
                queue = queue,
                preferredIndex = preferredIndex,
                unavailableSkipDirection = unavailableSkipDirection,
            )
        },
        mergeLoadedTracks = ::mergeLoadedTracks,
        loadArtwork = ::loadArtwork,
        applyPlaybackQueueOrderWithoutInterrupt = ::applyPlaybackQueueOrderWithoutInterrupt,
    )
    fun skipInQueue(
        direction: Int,
        restartCurrentWhenPrevious: Boolean = true,
    ) {
        playbackQueueControlActionHost.skipInQueue(direction, restartCurrentWhenPrevious)
    }

    fun pauseAtQueueStart() {
        playbackQueueControlActionHost.pauseAtQueueStart()
    }

    fun playTrackFromCurrentQueueAt(index: Int) {
        playbackQueueControlActionHost.playTrackFromCurrentQueueAt(index)
    }

    fun addTrackToQueue(track: Track) {
        playbackQueueControlActionHost.addTrackToQueue(track)
    }

    fun removeTrackFromQueueAt(index: Int) {
        playbackQueueControlActionHost.removeTrackFromQueueAt(index)
    }

    fun reorderQueueTracks(reorderedIndices: List<Int>) {
        playbackQueueControlActionHost.reorderQueueTracks(reorderedIndices)
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
        playbackQueueControlActionHost.setShuffleEnabled(enabled)
    }

    fun setRepeatMode(mode: PlaybackRepeatMode) {
        playbackQueueControlActionHost.setRepeatMode(mode)
    }

    fun setShowOnlyActiveSyncedLyrics(enabled: Boolean) {
        showOnlyActiveSyncedLyrics = enabled
        userPreferencesStore.setShowOnlyActiveSyncedLyrics(enabled)
    }

    fun setCenterSyncedLyrics(enabled: Boolean) {
        centerSyncedLyrics = enabled
        userPreferencesStore.setCenterSyncedLyrics(enabled)
    }

    fun togglePlayback() {
        playbackRuntimeActionHost.togglePlayback()
    }

    fun seekTo(seconds: Int) {
        playbackQueueControlActionHost.seekTo(seconds)
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

    val favoriteTrackActionHost = createFavoriteTrackController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        authRepository = authRepository,
        libraryCacheStore = libraryCacheStore,
        canUseServerRequests = ::canUseServerRequests,
        mergePlaylistPickerMetadata = ::mergePlaylistPickerMetadata,
        updateKnownTrackLikedState = ::updateKnownTrackLikedState,
        updateTrackDownloadState = ::updateTrackDownloadState,
        ensureTrackDownloaded = ::ensureTrackDownloaded,
        cacheDownloadedAssets = ::cacheDownloadedAssets,
        refreshStorageStats = ::refreshStorageStats,
        loadArtwork = ::loadArtwork,
        enqueueLibraryMutation = ::enqueueLibraryMutation,
        saveLibraryCache = ::saveLibraryCache,
        markServerUnavailable = ::markServerUnavailable,
    )
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

    TMusicPlaybackEffectsBinding(
        appState = appState,
        exoPlayer = exoPlayer,
        primaryExoPlayer = primaryExoPlayer,
        secondaryExoPlayer = secondaryExoPlayer,
        standbyExoPlayer = standbyExoPlayer,
        mediaCache = mediaCache,
        playerStateState = playerStateState,
        playbackQueueState = playbackQueueState,
        gaplessPlaybackRequestState = gaplessPlaybackRequestState,
        gaplessMediaQueueIndicesState = gaplessMediaQueueIndicesState,
        gaplessMediaUrlsState = gaplessMediaUrlsState,
        repeatModeState = repeatModeState,
        currentTrackFavorite = currentTrackFavorite,
        currentArtworkBitmap = artworkBitmaps.artworkBitmap(
            playerState.currentTrack?.listArtworkKey(),
            ArtworkImageSize.FullPlayer,
        ),
        onCompleteActivePlayEvent = { force -> completeActivePlayEvent(force = force) },
        onEnsureActivePlayEvent = { track, force -> ensureActivePlayEvent(track, force) },
        onClearNowPlayingEvent = ::clearNowPlayingEvent,
        onPlaybackPlayerError = ::handlePlaybackPlayerError,
        savePlaybackSnapshot = { state, queue ->
            savePlaybackSnapshot(state = state, queue = queue)
        },
        savePlaybackRuntimeSnapshot = { state, queue ->
            savePlaybackRuntimeSnapshot(state = state, queue = queue)
        },
        nextCrossfadeQueueIndex = ::nextCrossfadeQueueIndex,
        localOrCachedPlaybackUrl = ::localOrCachedPlaybackUrl,
        prefetchNextTrackUrl = ::prefetchNextTrackUrl,
        beginPreparedCrossfade = ::beginPreparedCrossfade,
        skipInQueue = { direction, restartCurrentWhenPrevious ->
            skipInQueue(
                direction = direction,
                restartCurrentWhenPrevious = restartCurrentWhenPrevious,
            )
        },
        pauseAtQueueStart = ::pauseAtQueueStart,
        playQueuedTrack = { track, queue, resumePositionMs, preferredIndex, allowResume, newQueue, skippedQueueIndices, unavailableSkipDirection ->
            playQueuedTrack(
                track = track,
                queue = queue,
                resumePositionMs = resumePositionMs,
                preferredIndex = preferredIndex,
                allowResume = allowResume,
                newQueue = newQueue,
                skippedQueueIndices = skippedQueueIndices,
                unavailableSkipDirection = unavailableSkipDirection,
            )
        },
        loadArtwork = ::loadArtwork,
        togglePlayback = ::togglePlayback,
        seekTo = ::seekTo,
        toggleFavoriteTrack = ::toggleFavoriteTrack,
        enforceOfflinePlaybackAvailability = ::enforceOfflinePlaybackAvailability,
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
        canUseServerRequests = ::canUseServerRequests,
        loadPlaylistPickerPlaylists = { force -> loadPlaylistPickerPlaylists(force = force) },
        syncPendingLibraryMutations = ::syncPendingLibraryMutations,
    )

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

    val playlistMutationActionHost = createPlaylistMutationController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        authRepository = authRepository,
        libraryCacheStore = libraryCacheStore,
        canUseServerRequests = ::canUseServerRequests,
        enqueueLibraryMutation = ::enqueueLibraryMutation,
        saveLibraryCache = ::saveLibraryCache,
        loadLibrary = { loadLibrary() },
        navigateTo = ::navigateTo,
        loadPlaylistForMembershipCheck = ::loadPlaylistForMembershipCheck,
        ensureTrackDownloaded = ::ensureTrackDownloaded,
        cacheDownloadedAssets = ::cacheDownloadedAssets,
        updateTrackDownloadState = ::updateTrackDownloadState,
        refreshStorageStats = ::refreshStorageStats,
        markServerUnavailable = ::markServerUnavailable,
    )
    fun createPlaylist(name: String) {
        playlistMutationActionHost.createPlaylist(name)
    }

    fun updatePlaylistDetails(playlist: Playlist, name: String) {
        playlistMutationActionHost.updatePlaylistDetails(playlist, name)
    }

    fun addTrackToPlaylist(playlist: Playlist, track: Track) {
        playlistMutationActionHost.addTrackToPlaylist(playlist, track)
    }

    fun deletePlaylist(playlist: Playlist) {
        playlistMutationActionHost.deletePlaylist(playlist)
    }

    fun requestAddTrackToPlaylist(playlist: Playlist, track: Track, allowDuplicate: Boolean = false) {
        playlistMutationActionHost.requestAddTrackToPlaylist(playlist, track, allowDuplicate)
    }

    fun removeTrackFromPlaylist(playlist: Playlist, playlistTrackId: String, trackId: String) {
        playlistMutationActionHost.removeTrackFromPlaylist(playlist, playlistTrackId, trackId)
    }

    fun reorderPlaylistTracks(playlist: Playlist, playlistTrackIds: List<String>) {
        playlistMutationActionHost.reorderPlaylistTracks(playlist, playlistTrackIds)
    }

    var downloadPlaylistInvoker: ((Playlist) -> Unit)? = null
    var downloadAlbumInvoker: ((LibraryAlbum, List<Track>) -> Unit)? = null
    val collectionDownloadControlActionHost = createCollectionDownloadController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        libraryCacheStore = libraryCacheStore,
        userPreferencesStore = userPreferencesStore,
        canUseNetworkForCollectionDownloads = ::canUseNetworkForCollectionDownloads,
        playlistIsFullyDownloaded = ::playlistIsFullyDownloaded,
        updateTrackDownloadState = ::updateTrackDownloadState,
        refreshStorageStats = ::refreshStorageStats,
        downloadPlaylist = { playlist -> downloadPlaylistInvoker?.invoke(playlist) },
        downloadAlbum = { album, albumTracks -> downloadAlbumInvoker?.invoke(album, albumTracks) },
    )
    fun updateAlbumOfflineFlag(albumId: String, enabled: Boolean) {
        collectionDownloadControlActionHost.updateAlbumOfflineFlag(albumId, enabled)
    }

    fun pausePlaylistDownload(playlist: Playlist) {
        collectionDownloadControlActionHost.pausePlaylistDownload(playlist)
    }

    fun pauseAlbumDownload(album: LibraryAlbum, albumTracks: List<Track>) {
        collectionDownloadControlActionHost.pauseAlbumDownload(album, albumTracks)
    }

    fun deletePlaylistDownload(playlist: Playlist) {
        collectionDownloadControlActionHost.deletePlaylistDownload(playlist)
    }

    fun deleteAlbumDownload(album: LibraryAlbum, albumTracks: List<Track>) {
        collectionDownloadControlActionHost.deleteAlbumDownload(album, albumTracks)
    }

    val downloadActionHost = createDownloadController(
        appState = appState,
        scope = scope,
        musicRepository = musicRepository,
        authRepository = authRepository,
        libraryCacheStore = libraryCacheStore,
        canUseMediaServerRequests = ::canUseMediaServerRequests,
        mediaDisabledMessage = ::mediaDisabledMessage,
        updateTrackDownloadState = ::updateTrackDownloadState,
        ensureTrackDownloaded = ::ensureTrackDownloaded,
        cacheDownloadedAssets = ::cacheDownloadedAssets,
        disableMediaPlaybackForAccount = ::disableMediaPlaybackForAccount,
        refreshStorageStats = ::refreshStorageStats,
        pausePlaylistDownload = ::pausePlaylistDownload,
        canUseNetworkForCollectionDownloads = ::canUseNetworkForCollectionDownloads,
        applyPlaylistTrackPage = ::applyPlaylistTrackPage,
        markServerUnavailable = ::markServerUnavailable,
        requestEnableCellularDownloads = { showEnableCellularDownloadDialog = true },
        pauseAlbumDownload = ::pauseAlbumDownload,
        mergeLoadedTracks = ::mergeLoadedTracks,
        updateAlbumOfflineFlag = ::updateAlbumOfflineFlag,
    )
    fun downloadTrack(track: Track) {
        downloadActionHost.downloadTrack(track)
    }

    fun downloadPlaylist(playlist: Playlist) {
        downloadActionHost.downloadPlaylist(playlist)
    }

    fun downloadAlbum(album: LibraryAlbum, albumTracks: List<Track>) {
        downloadActionHost.downloadAlbum(album, albumTracks)
    }

    downloadPlaylistInvoker = ::downloadPlaylist
    downloadAlbumInvoker = ::downloadAlbum

    fun resumePendingOfflineDownloads() {
        collectionDownloadControlActionHost.resumePendingOfflineDownloads()
    }

    fun pauseCollectionDownloadsForNetworkPolicy() {
        collectionDownloadControlActionHost.pauseCollectionDownloadsForNetworkPolicy()
    }

    fun setDownloadUsingCellular(enabled: Boolean) {
        collectionDownloadControlActionHost.setDownloadUsingCellular(enabled)
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

    val storageMaintenanceActionHost = createStorageController(
        appState = appState,
        scope = scope,
        userPreferencesStore = userPreferencesStore,
        playbackStateStore = playbackStateStore,
        musicRepository = musicRepository,
        offlineLyricsStore = offlineLyricsStore,
        artworkCacheStore = artworkCacheStore,
        libraryCacheStore = libraryCacheStore,
        appCacheStore = appCacheStore,
        canUseServerRequests = ::canUseServerRequests,
        getActivePlaybackCacheKey = {
            runCatching { exoPlayer.currentMediaItem?.localConfiguration?.customCacheKey }
                .getOrNull()
        },
        clearGaplessPlaybackState = ::clearGaplessPlaybackState,
        refreshStorageStats = ::refreshStorageStats,
        updateTrackDownloadState = ::updateTrackDownloadState,
        clearPlaybackCache = {
            mediaCache.keys.toList().forEach { key -> mediaCache.removeResource(key) }
        },
        clearPlaybackCacheExcept = { retainedKeys ->
            mediaCache.keys.toList()
                .filterNot { key -> key in retainedKeys }
                .forEach { key -> mediaCache.removeResource(key) }
        },
        playbackCacheDirName = MEDIA3_PLAYBACK_CACHE_DIR,
    )
    fun clearDownloads() {
        storageMaintenanceActionHost.clearDownloads()
    }

    fun clearAppCache() {
        storageMaintenanceActionHost.clearAppCache()
    }

    val lastFmActionHost = createLastFmController(
        appState = appState,
        scope = scope,
        context = context,
        musicRepository = musicRepository,
        authRepository = authRepository,
        lastFmAuthTokenStore = lastFmAuthTokenStore,
        userPreferencesStore = userPreferencesStore,
        canUseServerRequests = ::canUseServerRequests,
        clearNowPlayingEvent = ::clearNowPlayingEvent,
        sendNowPlayingEvent = { activeEvent, force -> sendNowPlayingEvent(activeEvent, force = force) },
        markServerUnavailable = ::markServerUnavailable,
    )
    fun connectLastFm() {
        lastFmActionHost.connectLastFm()
    }

    fun completeLastFmSession() {
        lastFmActionHost.completeLastFmSession()
    }

    fun disconnectLastFm() {
        lastFmActionHost.disconnectLastFm()
    }

    fun setScrobblingPaused(paused: Boolean) {
        lastFmActionHost.setScrobblingPaused(paused)
    }

    val offlineSettingsActionHost = createOfflineSettingsController(
        appState = appState,
        authRepository = authRepository,
        userPreferencesStore = userPreferencesStore,
        clearNowPlayingEvent = ::clearNowPlayingEvent,
        enforceOfflinePlaybackAvailability = ::enforceOfflinePlaybackAvailability,
        loadLibrary = { loadLibrary() },
    )
    fun continueOffline() {
        offlineSettingsActionHost.continueOffline()
    }

    fun setOfflineOnly(enabled: Boolean) {
        offlineSettingsActionHost.setOfflineOnly(enabled)
    }

    fun setUseLocalBackend(enabled: Boolean) {
        offlineSettingsActionHost.setUseLocalBackend(enabled)
    }

    TMusicAppLifecycleEffects(
        appState = appState,
        scope = scope,
        refreshStorageStats = ::refreshStorageStats,
        loadLibrary = { loadLibrary() },
        canUseNetworkForCollectionDownloads = ::canUseNetworkForCollectionDownloads,
        resumePendingOfflineDownloads = ::resumePendingOfflineDownloads,
        pauseCollectionDownloadsForNetworkPolicy = ::pauseCollectionDownloadsForNetworkPolicy,
        checkForAppUpdate = appUpdateHost::checkForAppUpdate,
        goBack = ::goBack,
    )

    val googleSignInActionHost = createGoogleSignInController(
        appState = appState,
        scope = scope,
        googleSignInTokenProvider = googleSignInTokenProvider,
        authRepository = authRepository,
        userPreferencesStore = userPreferencesStore,
        loadLibrary = ::loadLibrary,
    )
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        googleSignInActionHost.handleSignInResult(result.data)
    }

    fun startGoogleSignIn() {
        signingIn = true
        authError = null
        googleSignInLauncher.launch(googleSignInTokenProvider.signInIntent())
    }

    fun refreshCurrentPlaylist() = navigationController.refreshCurrentPlaylist()

    fun selectTab(tab: AppTab) = navigationController.selectTab(tab)

    fun selectPlaylist(playlist: Playlist) = navigationController.selectPlaylist(playlist)

    fun showAllArtists() = navigationController.showAllArtists()

    fun openFullPlayerFromMiniPlayer() = navigationController.openFullPlayerFromMiniPlayer()

    fun handleRecentItemClick(item: RecentLibraryItem) = navigationController.handleRecentItemClick(item)

    fun signOutFromUi() {
        scope.launch {
            signOutLocalSession()
        }
    }

    val serverRequestsAvailable = canUseServerRequests()
    val currentPlaybackPositionMs = remember(exoPlayer) {
        {
            runCatching {
                exoPlayer.currentMediaItem
                    ?.let { exoPlayer.currentPosition.coerceAtLeast(0L) }
                    ?: -1L
            }
                .getOrDefault(-1L)
        }
    }

    CompositionLocalProvider(
        LocalPlaybackPositionMs provides currentPlaybackPositionMs,
        LocalShowOnlyActiveSyncedLyrics provides showOnlyActiveSyncedLyrics,
        LocalCenterSyncedLyrics provides centerSyncedLyrics,
    ) {
        TMusicAppRuntimeRenderBinding(
        appState = appState,
        offlinePlayableTrackIds = offlinePlayableTrackIds,
        appUpdateController = appUpdateController,
        canUseServerRequests = serverRequestsAvailable,
        equalizerAvailable = equalizerAvailable && exoPlayer.audioSessionId > 0,
        onUseLocalBackendChange = ::setUseLocalBackend,
        onGoogleSignIn = ::startGoogleSignIn,
        onContinueOffline = ::continueOffline,
        loadLibrary = ::loadLibrary,
        onRefreshCurrentPlaylist = ::refreshCurrentPlaylist,
        loadArtistAlbums = ::loadArtistAlbums,
        loadSimilarArtists = ::loadSimilarArtists,
        loadAlbumTracks = ::loadAlbumTracks,
        onLoadMoreArtists = ::loadMoreArtists,
        onArtistSortChange = ::changeArtistSortOption,
        onLoadMoreAlbums = ::loadMoreAlbums,
        onLoadMoreRecentAlbums = ::loadMoreRecentAlbums,
        loadFullFavoritesPlaylist = { playlist -> loadFullFavoritesPlaylist(playlist) },
        loadPlaylistTracks = ::loadPlaylistTracks,
        onOfflineOnlyChange = ::setOfflineOnly,
        onScrobblingPausedChange = ::setScrobblingPaused,
        onShowOnlyActiveSyncedLyricsChange = ::setShowOnlyActiveSyncedLyrics,
        onCenterSyncedLyricsChange = ::setCenterSyncedLyrics,
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
        onCheckUpdates = appUpdateHost::checkUpdatesManually,
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
        onClearRecentItems = ::clearRecentItems,
        onRecentItemClick = ::handleRecentItemClick,
        onSelectSearchArtist = ::selectSearchArtist,
        onSelectSearchAlbum = ::selectSearchAlbum,
        onSelectSearchTrack = ::selectSearchTrack,
        onOpenFullPlayer = ::openFullPlayerFromMiniPlayer,
        onSelectQueueTrack = ::playTrackFromCurrentQueueAt,
        onRemoveQueueTrack = ::removeTrackFromQueueAt,
        onReorderQueueTracks = ::reorderQueueTracks,
        onAddTrackToQueue = ::addTrackToQueue,
        onGoToTrackArtist = ::openTrackArtist,
        onGoToTrackAlbum = ::openTrackAlbum,
        onToggleTrackFavorite = ::toggleFavoriteTrack,
        onSkipInQueue = ::skipInQueue,
        onShuffleChange = ::setShuffleEnabled,
        onRepeatModeChange = ::setRepeatMode,
        onTogglePlayback = ::togglePlayback,
        onSeek = ::seekTo,
        onRefreshLyrics = ::refreshLyrics,
        onSignOut = ::signOutFromUi,
        onSelectPlaylistForTrack = { playlist, track -> requestAddTrackToPlaylist(playlist, track) },
        onConfirmDuplicatePlaylist = { playlist, track -> requestAddTrackToPlaylist(playlist, track, allowDuplicate = true) },
        )
    }
}
}
