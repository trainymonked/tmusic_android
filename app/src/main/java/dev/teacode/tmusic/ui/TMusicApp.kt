package dev.teacode.tmusic.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.teacode.tmusic.auth.GoogleSignInTokenProvider
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.data.AppConfig
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.TMusicApiException
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.RecentLibraryItemType
import dev.teacode.tmusic.domain.ScrobbleState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import java.io.File
import java.net.HttpURLConnection

@Composable
fun TMusicApp(
    authRepository: RemoteAuthRepository,
    musicRepository: RemoteMusicRepository,
    googleSignInTokenProvider: GoogleSignInTokenProvider,
    userPreferencesStore: UserPreferencesStore,
    libraryCacheStore: LibraryCacheStore,
    offlineLyricsStore: OfflineLyricsStore,
    artworkCacheStore: ArtworkCacheStore,
    playbackStateStore: PlaybackStateStore,
    pendingPlayEventStore: PendingPlayEventStore,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {}
    val mediaCache = remember {
        SimpleCache(
            File(context.cacheDir, "media3_playback_cache"),
            LeastRecentlyUsedCacheEvictor(2L * 1024L * 1024L * 1024L),
            StandaloneDatabaseProvider(context),
        )
    }
    val primaryExoPlayer = remember(mediaCache) {
        createPlaybackPlayer(context, mediaCache, handleAudioFocus = true)
    }
    val secondaryExoPlayer = remember(mediaCache) {
        createPlaybackPlayer(context, mediaCache, handleAudioFocus = false)
    }
    var exoPlayer by remember { mutableStateOf(primaryExoPlayer) }
    val standbyExoPlayer = if (exoPlayer === primaryExoPlayer) secondaryExoPlayer else primaryExoPlayer

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
    var apiBaseUrl by remember { mutableStateOf(authRepository.apiBaseUrl()) }
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
    var pendingTransitionArtworkTrackId by remember { mutableStateOf<String?>(null) }
    var artworkTransitionDirection by remember { mutableStateOf(1) }
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
    var recentTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
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
    var artistListLoadingMore by remember { mutableStateOf(false) }
    var albumListLoadingMore by remember { mutableStateOf(false) }
    var artistListNextOffset by remember { mutableStateOf(0) }
    var albumListNextOffset by remember { mutableStateOf(0) }
    var artistListHasMore by remember { mutableStateOf(true) }
    var albumListHasMore by remember { mutableStateOf(true) }
    var albumTrackHasMoreById by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var playlistTrackHasMoreById by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var albumTracksById by remember { mutableStateOf<Map<String, List<Track>>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchFocusRequestSerial by remember { mutableStateOf(0) }
    var searchResults by remember { mutableStateOf(LibrarySearchResults(emptyList(), emptyList(), emptyList())) }
    var searchLoading by remember { mutableStateOf(false) }
    var recentItems by remember { mutableStateOf(userPreferencesStore.recentLibraryItems()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryLoadSerial by remember { mutableStateOf(0) }
    var libraryLoadJob by remember { mutableStateOf<Job?>(null) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var libraryNotice by remember { mutableStateOf<String?>(null) }
    var trackForPlaylistAdd by remember { mutableStateOf<Track?>(null) }
    var artistChoices by remember { mutableStateOf<List<LibraryArtist>>(emptyList()) }
    var playlistPickerPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var playlistPickerLoading by remember { mutableStateOf(false) }
    var playlistMetadataLoaded by remember { mutableStateOf(false) }
    var duplicatePlaylistForAdd by remember { mutableStateOf<Playlist?>(null) }
    var playlistAddInProgress by remember { mutableStateOf(false) }
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

    fun canUseNetworkForCollectionDownloads(): Boolean {
        if (!canUseServerRequests()) {
            return false
        }
        if (downloadUsingCellular) {
            return true
        }
        return !context.isUsingCellularNetwork()
    }

    fun markServerUnavailable(error: Throwable) {
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
    ) {
        persistPlaybackSnapshot(
            store = playbackStateStore,
            player = exoPlayer,
            state = state,
            queue = queue,
            activeEvent = activePlayEventState.value,
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
        return playerStateState.value.currentTrack?.takeIf { it.id == activeEvent.trackId }
            ?: tracks.firstOrNull { it.id == activeEvent.trackId }
            ?: playbackQueueState.value.tracks.firstOrNull { it.id == activeEvent.trackId }
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
        if (!canUseServerRequests() || pendingPlayEventCount == 0) {
            return
        }

        scope.launch {
            syncPendingPlayEventBatches(
                store = pendingPlayEventStore,
                musicRepository = musicRepository,
                onBatchSynced = {
                    accessToken = authRepository.accessToken()
                    pendingPlayEventCount = pendingPlayEventStore.count()
                    lastFmConnection = lastFmConnection.copy(pendingScrobbles = pendingPlayEventCount)
                    userPreferencesStore.setLastFmConnection(lastFmConnection)
                },
            )?.let { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
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
        val activeEvent = activePlayEventState.value ?: return
        val eventTrack = trackForPlayEvent(activeEvent)
        if (eventTrack == null || !shouldCompletePlayEvent(activeEvent, eventTrack)) {
            if (force) {
                discardActivePlayEvent(activeEvent)
            }
            return
        }
        if (activeEvent.clientEventId in completingPlayEventIds) {
            return
        }
        if (!lastFmConnectedState.value || scrobblingPaused) {
            activePlayEventState.value = null
            return
        }
        if (!canSendPlayEventsState.value) {
            queuePendingPlayEvent(activeEvent)
            return
        }

        completingPlayEventIds = completingPlayEventIds + activeEvent.clientEventId
        scope.launch {
            runCatching {
                musicRepository.syncPlayEvents(listOf(activeEvent.toPendingPlayEvent()))
            }.onSuccess {
                accessToken = authRepository.accessToken()
                if (activePlayEventState.value?.clientEventId == activeEvent.clientEventId) {
                    activePlayEventState.value = null
                    nowPlayingEventIds = nowPlayingEventIds - activeEvent.clientEventId
                    if (nowPlayingTrackId == activeEvent.trackId) {
                        nowPlayingTrackId = null
                    }
                    savePlaybackSnapshot()
                }
            }.onFailure {
                queuePendingPlayEvent(activeEvent)
            }
            completingPlayEventIds = completingPlayEventIds - activeEvent.clientEventId
        }
    }

    fun desiredExoRepeatMode(mode: PlaybackRepeatMode, hasGaplessQueue: Boolean): Int {
        return when {
            mode == PlaybackRepeatMode.Track -> Player.REPEAT_MODE_ONE
            mode == PlaybackRepeatMode.Queue && hasGaplessQueue -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    PlaybackPlayerListenerEffect(
        player = exoPlayer,
        isCurrentPlayer = { observedPlayer -> observedPlayer === exoPlayer },
        isCrossfadeActive = { crossfadeJob?.isActive == true },
        playerState = { playerStateState.value },
        playbackQueue = { playbackQueueState.value },
        repeatMode = { repeatModeState.value },
        gaplessPlaybackRequest = { gaplessPlaybackRequestState.value },
        gaplessMediaQueueIndices = { gaplessMediaQueueIndicesState.value },
        gaplessMediaUrls = { gaplessMediaUrlsState.value },
        activePlayEvent = { activePlayEventState.value },
        onBufferedFractionChanged = { playbackBufferedFraction = it },
        onCompleteActivePlayEvent = { completeActivePlayEvent(force = true) },
        onRequestCurrentTrackRestart = { requestedCurrentTrackRestart += 1 },
        onRequestQueueAdvance = { requestedQueueAdvance += 1 },
        onRequestQueueWrapPause = { requestedQueueWrapPause += 1 },
        onPlayerStateChanged = { playerState = it },
        onQueueTransition = { nextQueue, nextState, direction ->
            artworkTransitionDirection = direction
            playbackQueue = nextQueue
            playerState = nextState
            pendingTransitionArtworkTrackId = nextState.currentTrack?.listArtworkKey()
        },
        onEnsureActivePlayEvent = ::ensureActivePlayEvent,
        onClearNowPlayingEvent = ::clearNowPlayingEvent,
        onRequestNextPrefetch = { requestedNextPrefetch += 1 },
        onPlayerError = { playerError = it },
        onDisposePlayer = {
            savePlaybackSnapshot(
                state = playerStateState.value,
                queue = playbackQueueState.value,
            )
        },
    )

    DisposableEffect(primaryExoPlayer, secondaryExoPlayer, mediaCache) {
        onDispose {
            crossfadeJob?.cancel()
            primaryExoPlayer.release()
            secondaryExoPlayer.release()
            mediaCache.release()
        }
    }

    LaunchedEffect(playerState.currentTrack?.id, playerState.streamUrl, playbackStartSerial) {
        if (gaplessPlaybackRequest != null) {
            return@LaunchedEffect
        }
        val streamUrl = playerState.streamUrl
        if (streamUrl == null) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = false)
        } else {
            val currentMediaItem = exoPlayer.currentMediaItem
            val currentMediaId = currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
            val currentQueuedIndex = currentMediaId?.let { mediaId -> gaplessMediaQueueIndices[mediaId] }
            val currentQueuedTrack = currentQueuedIndex?.let { index -> playbackQueue.tracks.getOrNull(index) }
            val currentMediaUri = currentMediaItem?.localConfiguration?.uri?.toString()
            if (
                currentQueuedIndex == playbackQueue.currentIndex &&
                currentQueuedTrack?.id == playerState.currentTrack?.id &&
                currentMediaUri == streamUrl
            ) {
                exoPlayer.playbackParameters = PlaybackParameters(1f, 1f)
                exoPlayer.setSkipSilenceEnabled(false)
                exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = false)
                exoPlayer.shuffleModeEnabled = false
                if (playerState.isPlaying) {
                    exoPlayer.play()
                } else {
                    exoPlayer.pause()
                }
                requestedNextPrefetch += 1
                return@LaunchedEffect
            }
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = false)
            exoPlayer.playbackParameters = PlaybackParameters(1f, 1f)
            exoPlayer.setSkipSilenceEnabled(false)
            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
            exoPlayer.prepare()
            exoPlayer.seekTo(playerState.progressSeconds.toLong().coerceAtLeast(0L) * 1000L)
            if (playerState.isPlaying) {
                exoPlayer.play()
            }
            if (playbackQueue.canSkip) {
                requestedNextPrefetch += 1
            }
        }
    }

    LaunchedEffect(gaplessPlaybackRequest?.signature) {
        val request = gaplessPlaybackRequest ?: return@LaunchedEffect
        val hasCompleteGaplessQueue = request.trackIds == playbackQueue.tracks.map { it.id } &&
            request.queueIndices == playbackQueue.tracks.indices.toList()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.playbackParameters = PlaybackParameters(1f, 1f)
        exoPlayer.setSkipSilenceEnabled(false)
        exoPlayer.repeatMode = desiredExoRepeatMode(repeatMode, hasGaplessQueue = hasCompleteGaplessQueue)
        exoPlayer.shuffleModeEnabled = false
        exoPlayer.setMediaItems(
            request.urls.mapIndexed { index, url ->
                MediaItem.Builder()
                    .setUri(url)
                    .setMediaId(request.mediaIds[index])
                    .build()
            },
            request.startIndex,
            request.resumePositionMs.coerceAtLeast(0L),
        )
        exoPlayer.prepare()
        if (playerState.isPlaying) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(repeatMode, gaplessPlaybackRequest?.signature) {
        val request = gaplessPlaybackRequest
        val hasCompleteGaplessQueue = request != null &&
            request.trackIds == playbackQueue.tracks.map { it.id } &&
            request.queueIndices == playbackQueue.tracks.indices.toList()
        exoPlayer.repeatMode = desiredExoRepeatMode(
            mode = repeatMode,
            hasGaplessQueue = hasCompleteGaplessQueue,
        )
        exoPlayer.shuffleModeEnabled = false
    }

    LaunchedEffect(playerState.isPlaying, playerState.streamUrl) {
        if (playerState.streamUrl != null) {
            if (playerState.isPlaying) {
                exoPlayer.play()
            } else {
                exoPlayer.pause()
            }
        }
    }

    LaunchedEffect(playerState.currentTrack?.id, playerState.streamUrl, playerState.isPlaying) {
        val observedTrackId = playerState.currentTrack?.id
        val observedStreamUrl = playerState.streamUrl
        var lastTickMs = System.currentTimeMillis()
        while (
            observedTrackId != null &&
            observedStreamUrl != null &&
            playerState.currentTrack?.id == observedTrackId &&
            playerState.streamUrl == observedStreamUrl &&
            playerState.isPlaying
        ) {
            delay(1_000)
            if (playerState.currentTrack?.id != observedTrackId || playerState.streamUrl != observedStreamUrl) {
                break
            }
            val nowMs = System.currentTimeMillis()
            val elapsedMs = (nowMs - lastTickMs).coerceAtLeast(0L)
            lastTickMs = nowMs
            val currentTrack = playerState.currentTrack
            playerState = playerState.copy(
                progressSeconds = (exoPlayer.currentPosition / 1000).toInt().coerceAtLeast(0),
            )
            playbackBufferedFraction = exoPlayer.bufferedPercentage.coerceIn(0, 100) / 100f
            if (currentTrack != null && exoPlayer.isPlaying) {
                val activeEvent = activePlayEventState.value
                if (activeEvent?.trackId == currentTrack.id) {
                    activePlayEventState.value = activeEvent.copy(
                        durationPlayedMs = activeEvent.durationPlayedMs + elapsedMs,
                    )
                }
            }
        }
    }

    val activePlayEvent = activePlayEventState.value
    LaunchedEffect(
        playerState.currentTrack?.id,
        playerState.progressSeconds,
        playerState.isPlaying,
        playbackQueue.playlistId,
        playbackQueue.sourceType,
        playbackQueue.sourceId,
        playbackQueue.sourceTitle,
        playbackQueue.tracks.map { it.id },
        playbackQueue.sourceTracks.map { it.id },
        playbackQueue.isShuffled,
        playbackQueue.currentIndex,
        activePlayEvent?.clientEventId,
        activePlayEvent?.playedAt,
        activePlayEvent?.durationPlayedMs,
    ) {
        if (pendingPlaybackRestore != null) {
            return@LaunchedEffect
        }
        savePlaybackSnapshot()
    }

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

    fun isDeletedAccountError(error: Throwable): Boolean {
        return error is TMusicApiException &&
            error.statusCode == HttpURLConnection.HTTP_NOT_FOUND &&
            error.userMessage().contains("user", ignoreCase = true) &&
            error.userMessage().contains("not found", ignoreCase = true)
    }

    suspend fun signOutLocalSession(message: String? = null) {
        libraryLoadSerial += 1
        libraryLoadJob?.cancel()
        libraryLoadJob = null
        authRepository.signOut()
        googleSignInTokenProvider.signOut()
        libraryCacheStore.clear()
        playbackStateStore.clear()
        pendingPlayEventStore.clear()
        account = null
        accessToken = null
        canContinueOffline = false
        offlineOnly = false
        userPreferencesStore.setOfflineOnly(false)
        syncMode = SyncMode.Offline
        libraryLoading = false
        libraryError = null
        libraryNotice = null
        playlists = emptyList()
        tracks = emptyList()
        artists = emptyList()
        albums = emptyList()
        savedAlbums = emptyList()
        albumsByArtist = emptyMap()
        appearsOnByArtist = emptyMap()
        looseTracksByArtist = emptyMap()
        artistListNextOffset = 0
        albumListNextOffset = 0
        artistListHasMore = true
        albumListHasMore = true
        albumTrackHasMoreById = emptyMap()
        playlistTrackHasMoreById = emptyMap()
        albumTracksById = emptyMap()
        playerState = PlayerState(null, isPlaying = false, progressSeconds = 0, streamUrl = null)
        playbackQueue = PlaybackQueue()
        clearGaplessPlaybackState()
        activePlayEventState.value = null
        pendingPlayEventCount = 0
        pendingLastFmToken = null
        waitingForLastFmSession = false
        lastFmAuthTokenStore.clear()
        userPreferencesStore.clearLastFmConnection()
        userPreferencesStore.setScrobblingPaused(false)
        userPreferencesStore.setShuffleEnabled(false)
        userPreferencesStore.setPlaybackRepeatMode(PlaybackRepeatMode.None.name)
        lastFmConnection = LastFmConnection(
            username = null,
            state = ScrobbleState.NeedsAuth,
            pendingScrobbles = 0,
        )
        scrobblingPaused = false
        shuffleEnabled = false
        repeatMode = PlaybackRepeatMode.None
        profileAvatarBitmap = null
        profileAvatarLoadKey = null
        prefetchedPlaybackUrls = emptyMap()
        playbackUrlPrefetchesInProgress = emptySet()
        trackForPlaylistAdd = null
        playlistPickerPlaylists = emptyList()
        playlistPickerLoading = false
        playlistMetadataLoaded = false
        duplicatePlaylistForAdd = null
        playlistAddInProgress = false
        fullPlayerOpen = false
        queueOpen = false
        destination = AppDestination(AppTab.Home)
        backStack = emptyList()
        authError = message
    }

    fun loadLibrary(targetDestination: AppDestination = destination) {
        libraryLoadSerial += 1
        val loadSerial = libraryLoadSerial
        libraryLoadJob?.cancel()
        val timeoutJob = scope.launch {
            delay(SERVER_OFFLINE_FALLBACK_TIMEOUT_MS)
            if (libraryLoadSerial == loadSerial && libraryLoading) {
                account = account ?: authRepository.cachedAccount() ?: OfflineAccount
                syncMode = SyncMode.Offline
                libraryLoading = false
                libraryError = if (playlists.isEmpty() && tracks.isEmpty()) {
                    "Sync is taking longer than ${SERVER_OFFLINE_FALLBACK_TIMEOUT_MS / 1000} seconds. Offline library is empty."
                } else {
                    "Sync is taking longer than ${SERVER_OFFLINE_FALLBACK_TIMEOUT_MS / 1000} seconds. Showing offline data while it continues."
                }
            }
        }
        libraryLoadJob = scope.launch {
            if (offlineOnly) {
                syncMode = SyncMode.OfflineOnly
                libraryError = null
                libraryLoading = false
                timeoutJob.cancel()
                if (libraryLoadSerial == loadSerial) {
                    libraryLoadJob = null
                }
                return@launch
            }

            authRepository.cachedAccount()?.let { cachedAccount ->
                if (account == null || account == OfflineAccount) {
                    account = cachedAccount
                }
            }

            syncMode = SyncMode.Syncing
            libraryLoading = true
            libraryError = null
            try {
                runCatching {
                    withTimeout(SERVER_SYNC_HARD_TIMEOUT_MS) {
                        fetchLibraryState(
                            targetDestination = targetDestination,
                            authRepository = authRepository,
                            musicRepository = musicRepository,
                        )
                    }
                }.onSuccess { loadedState ->
                    if (libraryLoadSerial != loadSerial) {
                        return@onSuccess
                    }
                    val loadedAccount = loadedState.account
                    loadedAccount?.let { account = it }
                    val mergedLibrary = loadedState.mergeWithCachedLibrary(
                        targetDestination = targetDestination,
                        cachedPlaylists = playlists,
                        cachedTracks = tracks,
                        cachedRecentTracks = recentTracks,
                        cachedTrackCount = databaseTrackCount,
                        cachedArtists = artists,
                        cachedAlbums = albums,
                        cachedSavedAlbums = savedAlbums,
                        offlineAlbumIds = offlineAlbumIds,
                    )
                    playlists = mergedLibrary.playlists
                    tracks = mergedLibrary.tracks
                    recentTracks = mergedLibrary.recentTracks
                    databaseTrackCount = mergedLibrary.databaseTrackCount
                    artists = mergedLibrary.artists
                    albums = mergedLibrary.albums
                    savedAlbums = mergedLibrary.savedAlbums
                    mergedLibrary.artistListNextOffset?.let { artistListNextOffset = it }
                    mergedLibrary.artistListHasMore?.let { artistListHasMore = it }
                    mergedLibrary.albumListNextOffset?.let { albumListNextOffset = it }
                    mergedLibrary.albumListHasMore?.let { albumListHasMore = it }
                    if (loadedState.playlists != null || loadedState.tracks != null) {
                        libraryCacheStore.saveLibrary(
                            playlists = playlists,
                            tracks = tracks,
                            savedAlbums = savedAlbums,
                        )
                    }
                    syncMode = SyncMode.Online
                    accessToken = authRepository.accessToken()
                    loadedState.lastFmConnection?.let { connection ->
                        lastFmConnection = connection.copy(pendingScrobbles = pendingPlayEventCount)
                        userPreferencesStore.setLastFmConnection(lastFmConnection)
                        if (lastFmConnection.state == ScrobbleState.Ready && !lastFmConnection.username.isNullOrBlank()) {
                            pendingLastFmToken = null
                            waitingForLastFmSession = false
                            lastFmAuthTokenStore.clear()
                        }
                    }
                }.onFailure { error ->
                    if (isDeletedAccountError(error)) {
                        signOutLocalSession("Account was removed. Sign in again.")
                        return@onFailure
                    }
                    if (error is TimeoutCancellationException) {
                        if (libraryLoadSerial != loadSerial) {
                            return@onFailure
                        }
                        account = account ?: authRepository.cachedAccount() ?: OfflineAccount
                        syncMode = SyncMode.Offline
                        libraryError = if (playlists.isEmpty() && tracks.isEmpty()) {
                            "Server sync exceeded ${SERVER_SYNC_HARD_TIMEOUT_MS / 1000} seconds. Offline library is empty."
                        } else {
                            "Server sync exceeded ${SERVER_SYNC_HARD_TIMEOUT_MS / 1000} seconds. Showing offline data."
                        }
                        return@onFailure
                    }
                    if (error is CancellationException) {
                        return@launch
                    }
                    if (libraryLoadSerial != loadSerial) {
                        return@onFailure
                    }
                    account = account ?: authRepository.cachedAccount() ?: OfflineAccount
                    syncMode = SyncMode.Offline
                    libraryError = if (playlists.isEmpty() && tracks.isEmpty()) {
                        "Server unavailable. Offline library is empty."
                    } else {
                        "Server unavailable. Showing offline data. ${error.userMessage()}"
                    }
                }
            } finally {
                timeoutJob.cancel()
                if (libraryLoadSerial == loadSerial) {
                    libraryLoading = false
                    libraryLoadJob = null
                }
            }
        }
    }

    LaunchedEffect(searchQuery, syncMode, offlineOnly, tracks) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            searchLoading = false
            searchResults = LibrarySearchResults(emptyList(), emptyList(), emptyList())
            return@LaunchedEffect
        }

        delay(250)
        if (canUseServerRequests()) {
            searchLoading = true
            runCatching {
                musicRepository.search(query, limit = 10)
            }.onSuccess { results ->
                searchResults = results
                accessToken = authRepository.accessToken()
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@LaunchedEffect
                }
                markServerUnavailable(error)
                searchResults = tracks.localSearch(query)
                libraryError = error.userMessage()
            }
            searchLoading = false
        } else {
            searchLoading = false
            searchResults = tracks
                .filter { it.downloadState == DownloadState.Downloaded }
                .localSearch(query)
        }
    }

    fun addRecentItem(item: RecentLibraryItem) {
        userPreferencesStore.addRecentLibraryItem(item)
        recentItems = userPreferencesStore.recentLibraryItems()
    }

    fun downloadedArtworkKeys(sourceTracks: List<Track> = tracks): Set<String> {
        val trackArtworkKeys = sourceTracks
            .filter { it.downloadState == DownloadState.Downloaded }
            .flatMap { track ->
                listOfNotNull(
                    track.listArtworkKey(),
                    track.albumId?.let(::albumArtworkKey),
                )
            }
            .toSet()
        val playlistArtworkKeys = playlists
            .filter { playlist -> playlist.isOfflineEnabled || playlist.isFavorites }
            .map(::playlistArtworkKey)
            .toSet()
        return trackArtworkKeys + playlistArtworkKeys
    }

    fun artworkCacheKeysFor(artworkKeys: Set<String>): Set<String> {
        return artworkKeys.flatMap { artworkKey ->
            ArtworkImageSize.entries.map { imageSize -> artworkCacheKey(artworkKey, imageSize) }
        }.toSet()
    }

    fun downloadedArtworkCacheKeys(sourceTracks: List<Track> = tracks): Set<String> {
        return artworkCacheKeysFor(downloadedArtworkKeys(sourceTracks))
    }

    fun refreshStorageStats() {
        scope.launch {
            val retainedArtworkKeys = downloadedArtworkCacheKeys()
            downloadedSizeBytes = musicRepository.downloadsSizeBytes() +
                offlineLyricsStore.sizeBytes() +
                artworkCacheStore.sizeBytesFor(retainedArtworkKeys)
            cacheSizeBytes = artworkCacheStore.sizeBytesExcluding(retainedArtworkKeys) +
                libraryCacheStore.sizeBytes() +
                musicRepository.musicCacheSizeBytes()
        }
    }

    fun clearRecentItems() {
        userPreferencesStore.clearRecentLibraryItems()
        recentItems = emptyList()
    }

    LaunchedEffect(libraryNotice) {
        val notice = libraryNotice ?: return@LaunchedEffect
        delay(2_500)
        if (libraryNotice == notice) {
            libraryNotice = null
        }
    }

    LaunchedEffect(libraryError) {
        val error = libraryError ?: return@LaunchedEffect
        delay(5_000)
        if (libraryError == error) {
            libraryError = null
        }
    }

    LaunchedEffect(playerError) {
        val error = playerError ?: return@LaunchedEffect
        delay(5_000)
        if (playerError == error) {
            playerError = null
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageStats()
    }

    suspend fun cachedArtworkBitmap(
        artworkKey: String,
        imageSize: ArtworkImageSize,
    ): ImageBitmap? {
        val cachedPath = artworkCacheStore.cachedPath(artworkCacheKey(artworkKey, imageSize)) ?: return null
        return decodeArtworkBitmap(cachedPath, imageSize.maxSizePx)
    }

    suspend fun cacheArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize,
    ): ImageBitmap? {
        cachedArtworkBitmap(artworkKey, imageSize)?.let { return it }
        val cacheKey = artworkCacheKey(artworkKey, imageSize)
        val cachedPath = artworkCacheStore.cachedPath(cacheKey) ?: run {
            if (!canUseServerRequests()) {
                return null
            }

            val url = when {
                artworkKey.startsWith(ALBUM_ARTWORK_KEY_PREFIX) ->
                    musicRepository.albumArtworkUrl(artworkKey.albumIdFromArtworkKey())
                artworkKey.startsWith(ARTIST_ARTWORK_KEY_PREFIX) -> {
                    val artistId = artworkKey.artistIdFromArtworkKey()
                    val knownArtistHasId = (artists + searchResults.artists + similarArtistsByArtist.values.flatten())
                        .any { it.id == artistId }
                    if (!knownArtistHasId) {
                        return null
                    }
                    musicRepository.artistArtworkUrl(artistId, size = imageSize.maxSizePx)
                }
                artworkKey.startsWith(PLAYLIST_ARTWORK_KEY_PREFIX) ->
                    musicRepository.playlistArtworkUrl(artworkKey.playlistIdFromArtworkKey(), size = imageSize.maxSizePx)
                else -> musicRepository.artworkUrl(artworkKey)
            }
            accessToken = authRepository.accessToken()
            artworkCacheStore.cache(cacheKey, url)
        }
        val bitmap = decodeArtworkBitmap(cachedPath, imageSize.maxSizePx)
        artworkCacheStore.trimToLimit(
            maxBytes = ARTWORK_CACHE_LIMIT_BYTES,
            keysToKeep = downloadedArtworkCacheKeys() + artworkLoadsInProgress + cacheKey,
        )
        cacheSizeBytes = artworkCacheStore.sizeBytesExcluding(downloadedArtworkCacheKeys()) +
            libraryCacheStore.sizeBytes()
        return bitmap
    }

    fun loadArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize = ArtworkImageSize.AlbumGrid,
    ) {
        val bitmapKey = artworkBitmapKey(artworkKey, imageSize)
        if (artworkBitmaps.containsKey(bitmapKey) || bitmapKey in artworkLoadsInProgress) {
            return
        }

        artworkLoadsInProgress = artworkLoadsInProgress + bitmapKey
        scope.launch {
            runCatching {
                cacheArtwork(artworkKey, imageSize)
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    artworkBitmaps = artworkBitmaps + (bitmapKey to bitmap)
                }
            }
            artworkLoadsInProgress = artworkLoadsInProgress - bitmapKey
        }
    }

    fun loadLyrics(track: Track) {
        if (!showLyrics) {
            return
        }
        if (
            track.id in lyricsByTrackId ||
            track.id in lyricsUnavailableIds ||
            track.id in lyricsLoadsInProgress
        ) {
            return
        }
        offlineLyricsStore.lyrics(track.id)?.let { cachedLyrics ->
            lyricsByTrackId = lyricsByTrackId + (track.id to cachedLyrics)
            return
        }
        if (!canUseServerRequests()) {
            return
        }

        lyricsLoadsInProgress = lyricsLoadsInProgress + track.id
        scope.launch {
            runCatching {
                musicRepository.lyrics(track.id)
            }.onSuccess { lyrics ->
                accessToken = authRepository.accessToken()
                if (lyrics == null) {
                    lyricsUnavailableIds = lyricsUnavailableIds + track.id
                } else {
                    lyricsByTrackId = lyricsByTrackId + (track.id to lyrics)
                    if (track.downloadState == DownloadState.Downloaded || musicRepository.localPlaybackUrl(track.id) != null) {
                        offlineLyricsStore.save(track.id, lyrics)
                    }
                }
            }.onFailure {
                markServerUnavailable(it)
                lyricsUnavailableIds = lyricsUnavailableIds + track.id
            }
            lyricsLoadsInProgress = lyricsLoadsInProgress - track.id
        }
    }

    fun refreshLyrics(track: Track) {
        if (!canUseServerRequests() || track.id in lyricsLoadsInProgress) {
            return
        }

        lyricsLoadsInProgress = lyricsLoadsInProgress + track.id
        scope.launch {
            runCatching {
                musicRepository.refreshLyrics(track.id)
            }.onSuccess { lyrics ->
                accessToken = authRepository.accessToken()
                lyricsUnavailableIds = lyricsUnavailableIds - track.id
                if (lyrics == null) {
                    lyricsByTrackId = lyricsByTrackId - track.id
                    lyricsUnavailableIds = lyricsUnavailableIds + track.id
                } else {
                    lyricsByTrackId = lyricsByTrackId + (track.id to lyrics)
                    if (track.downloadState == DownloadState.Downloaded || musicRepository.localPlaybackUrl(track.id) != null) {
                        offlineLyricsStore.save(track.id, lyrics)
                    }
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                if (!offlineOnly) {
                    libraryError = error.userMessage()
                }
            }
            lyricsLoadsInProgress = lyricsLoadsInProgress - track.id
        }
    }

    LaunchedEffect(playerState.currentTrack?.id, syncMode, offlineOnly, showLyrics) {
        if (showLyrics) {
            playerState.currentTrack?.let(::loadLyrics)
        }
    }

    fun resolveCachedArtist(artistName: String): LibraryArtist? {
        val normalizedName = artistName.trim()
        val candidates = artists + searchResults.artists + similarArtistsByArtist.values.flatten()
        return candidates.firstOrNull { candidate ->
            candidate.name.equals(normalizedName, ignoreCase = true)
        }
    }

    suspend fun cacheDownloadedAssets(track: Track) {
        val knownTrack = (tracks.firstOrNull { it.id == track.id } ?: track)
            .copy(downloadState = DownloadState.Downloaded)
        tracks = if (tracks.any { it.id == knownTrack.id }) {
            tracks.map { existingTrack -> if (existingTrack.id == knownTrack.id) knownTrack else existingTrack }
        } else {
            tracks + knownTrack
        }

        suspend fun cacheArtworkSizes(artworkKey: String) {
            ArtworkImageSize.entries.forEach { imageSize ->
                runCatching {
                    cacheArtwork(artworkKey, imageSize)
                }.onSuccess { bitmap ->
                    if (bitmap != null) {
                        artworkBitmaps = artworkBitmaps + (artworkBitmapKey(artworkKey, imageSize) to bitmap)
                    }
                }
            }
        }

        val primaryArtworkKey = knownTrack.listArtworkKey()
        cacheArtworkSizes(primaryArtworkKey)
        knownTrack.albumId
            ?.let(::albumArtworkKey)
            ?.takeIf { it != primaryArtworkKey }
            ?.let { cacheArtworkSizes(it) }
        (knownTrack.artistReferences() + knownTrack.artistLogicNames().mapNotNull(::resolveCachedArtist))
            .distinctBy { it.id }
            .map(::artistArtworkKey)
            .distinct()
            .forEach { artistKey ->
                runCatching {
                    cacheArtwork(artistKey, ArtworkImageSize.AlbumGrid)
                }.onSuccess { bitmap ->
                    if (bitmap != null) {
                        artworkBitmaps = artworkBitmaps + (artworkBitmapKey(artistKey, ArtworkImageSize.AlbumGrid) to bitmap)
                    }
                }
            }
        knownTrack.albumId?.let { albumId ->
            albumTracksById = albumTracksById + (
                albumId to (albumTracksById[albumId].orEmpty() + knownTrack)
                    .distinctBy { it.id }
                    .sortedWith(
                        compareBy<Track> { it.discNumber ?: Int.MAX_VALUE }
                            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                            .thenBy { it.title.lowercase() }
                            .thenBy { it.id },
                    )
                )
        }
        runCatching {
            musicRepository.lyrics(knownTrack.id)
        }.onSuccess { lyrics ->
            accessToken = authRepository.accessToken()
            if (lyrics != null) {
                offlineLyricsStore.save(knownTrack.id, lyrics)
                lyricsByTrackId = lyricsByTrackId + (knownTrack.id to lyrics)
            }
        }
    }

    LaunchedEffect(pendingTransitionArtworkTrackId) {
        val artworkKey = pendingTransitionArtworkTrackId ?: return@LaunchedEffect
        loadArtwork(artworkKey, ArtworkImageSize.FullPlayer)
        pendingTransitionArtworkTrackId = null
    }

    fun loadProfileAvatar(currentAccount: Account) {
        val avatarUrl = currentAccount.avatarUrl
        val loadKey = "${currentAccount.id}:${avatarUrl.orEmpty()}"
        if (profileAvatarLoadKey == loadKey && profileAvatarBitmap != null) {
            return
        }

        profileAvatarLoadKey = loadKey
        profileAvatarBitmap = null
        if (avatarUrl.isNullOrBlank()) {
            return
        }

        scope.launch {
            val cacheKey = "profile_${currentAccount.id}_${avatarUrl.hashCode()}"
            runCatching {
                cachedArtworkBitmap(cacheKey, ArtworkImageSize.TrackList) ?: run {
                    val avatarCacheKey = artworkCacheKey(cacheKey, ArtworkImageSize.TrackList)
                    val cachedPath = artworkCacheStore.cache(avatarCacheKey, avatarUrl)
                    artworkCacheStore.trimToLimit(
                        maxBytes = ARTWORK_CACHE_LIMIT_BYTES,
                        keysToKeep = downloadedArtworkCacheKeys() + artworkLoadsInProgress + avatarCacheKey,
                    )
                    cacheSizeBytes = artworkCacheStore.sizeBytesExcluding(downloadedArtworkCacheKeys()) +
                        libraryCacheStore.sizeBytes()
                    decodeArtworkBitmap(cachedPath, ArtworkImageSize.TrackList.maxSizePx)
                }
            }.onSuccess { bitmap ->
                if (profileAvatarLoadKey == loadKey) {
                    profileAvatarBitmap = bitmap
                }
            }
        }
    }

    LaunchedEffect(account?.id, account?.avatarUrl) {
        account?.let(::loadProfileAvatar) ?: run {
            profileAvatarLoadKey = null
            profileAvatarBitmap = null
        }
    }

    fun startPlayback(
        track: Track,
        playbackUrl: String,
        startPositionMs: Long = 0L,
    ) {
        clearGaplessPlaybackState()
        val safeStartPositionMs = startPositionMs.coerceAtLeast(0L)
        val previousEvent = activePlayEventState.value
        val isRestart = previousEvent?.trackId == track.id && safeStartPositionMs == 0L
        if (previousEvent != null && (previousEvent.trackId != track.id || isRestart)) {
            completeActivePlayEvent(force = true)
        }
        ensureActivePlayEvent(track, forceNew = isRestart)
        playbackStartSerial += 1
        val queueIndex = playbackQueue.currentIndex
            .takeIf { index -> index in playbackQueue.tracks.indices && playbackQueue.tracks[index].id == track.id }
            ?: playbackQueue.tracks.indexOfFirst { it.id == track.id }
        if (queueIndex >= 0 && playbackQueue.currentIndex != queueIndex) {
            playbackQueue = playbackQueue.copy(currentIndex = queueIndex)
        }
        playbackBufferedFraction = 0f
        playerState = PlayerState(
            currentTrack = track,
            isPlaying = true,
            progressSeconds = (safeStartPositionMs / 1000L).toInt().coerceAtLeast(0),
            streamUrl = playbackUrl,
        )
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
    }

    fun startGaplessPlayback(
        track: Track,
        queue: PlaybackQueue,
        urls: List<String>,
        resumePositionMs: Long = 0L,
    ) {
        val startIndex = queue.currentIndex
            .takeIf { index -> index in queue.tracks.indices && queue.tracks[index].id == track.id }
            ?: queue.tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }
            ?: queue.currentIndex.coerceAtLeast(0)
        val previousEvent = activePlayEventState.value
        val isRestart = previousEvent?.trackId == track.id && resumePositionMs == 0L
        if (previousEvent != null && (previousEvent.trackId != track.id || isRestart)) {
            completeActivePlayEvent(force = true)
        }
        ensureActivePlayEvent(track, forceNew = isRestart)
        playbackQueue = queue.copy(currentIndex = startIndex)
        playbackBufferedFraction = 0f
        playerState = PlayerState(
            currentTrack = track,
            isPlaying = true,
            progressSeconds = (resumePositionMs / 1000L).toInt().coerceAtLeast(0),
            streamUrl = urls[startIndex],
        )
        val request = GaplessPlaybackRequest(
            queueKey = queue.playlistId
                ?: "${queue.sourceType.name}:${queue.sourceId.orEmpty()}:${queue.sourceTitle.orEmpty()}:${queue.tracks.joinToString(",") { it.id }}",
            trackIds = queue.tracks.map { it.id },
            urls = urls,
            startIndex = startIndex,
            resumePositionMs = resumePositionMs,
            queueIndices = queue.tracks.indices.toList(),
        )
        gaplessPlaybackRequest = request
        gaplessMediaQueueIndices = request.mediaIds.zip(request.queueIndices).toMap()
        gaplessMediaUrls = request.mediaIds.zip(request.urls).toMap()
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
    }

    fun gaplessQueueKey(queue: PlaybackQueue): String {
        return queue.playlistId
            ?: "${queue.sourceType.name}:${queue.sourceId.orEmpty()}:${queue.sourceTitle.orEmpty()}:${queue.tracks.joinToString(",") { it.id }}"
    }

    fun installGaplessPrefetch(
        queue: PlaybackQueue,
        nextTrack: Track,
        nextIndex: Int,
        nextUrl: String,
    ) {
        if (!queue.canSkip) {
            return
        }
        val currentTrack = playerState.currentTrack ?: return
        val currentUrl = playerState.streamUrl ?: return
        val currentIndex = queue.currentIndex.coerceIn(0, queue.tracks.lastIndex)
        if (queue.tracks.getOrNull(currentIndex)?.id != currentTrack.id) {
            return
        }
        if (nextIndex < 0) {
            return
        }
        fun addPrefetchedMediaItemAhead(): Boolean {
            if (exoPlayer.mediaItemCount <= 0) {
                return false
            }
            val mediaQueueIndices = gaplessMediaQueueIndices
            val currentMediaItemIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
            val alreadyQueuedAhead = ((currentMediaItemIndex + 1) until exoPlayer.mediaItemCount).any { mediaIndex ->
                val queuedMediaId = exoPlayer.getMediaItemAt(mediaIndex).mediaId
                mediaQueueIndices[queuedMediaId] == nextIndex
            }
            if (alreadyQueuedAhead) {
                return true
            }
            val mediaId = "${System.nanoTime()}:$nextIndex:${nextTrack.id}"
            val nextDistance = (nextIndex - currentIndex).floorMod(queue.tracks.size)
            val insertionMediaIndex = ((currentMediaItemIndex + 1) until exoPlayer.mediaItemCount)
                .firstOrNull { mediaIndex ->
                    val queuedMediaId = exoPlayer.getMediaItemAt(mediaIndex).mediaId
                    val queuedIndex = mediaQueueIndices[queuedMediaId] ?: return@firstOrNull false
                    val queuedDistance = (queuedIndex - currentIndex).floorMod(queue.tracks.size)
                    queuedDistance > nextDistance
                }
                ?: exoPlayer.mediaItemCount
            exoPlayer.addMediaItem(
                insertionMediaIndex,
                MediaItem.Builder()
                    .setUri(nextUrl)
                    .setMediaId(mediaId)
                    .build(),
            )
            gaplessMediaQueueIndices = gaplessMediaQueueIndices + (mediaId to nextIndex)
            gaplessMediaUrls = gaplessMediaUrls + (mediaId to nextUrl)
            return true
        }

        val existingRequest = gaplessPlaybackRequest
        if (existingRequest != null) {
            if (existingRequest.queueIndices == queue.tracks.indices.toList()) {
                return
            }
            if (addPrefetchedMediaItemAhead()) {
                return
            }
        } else if (addPrefetchedMediaItemAhead()) {
            return
        }
    }

    fun localOrCachedPlaybackUrl(trackId: String): String? {
        return musicRepository.localPlaybackUrl(trackId)
            ?: musicRepository.cachedPlaybackUrl(trackId)
    }

    fun localOrCachedPlaybackUrl(track: Track): String? {
        return localOrCachedPlaybackUrl(track.id)
            ?: track.serverPath
                .takeIf { track.downloadState == DownloadState.Downloaded && it.isNotBlank() }
                ?.let { path -> File(path).takeIf { it.exists() && it.length() > 0L }?.toURI()?.toString() }
    }

    fun enforceOfflinePlaybackAvailability() {
        cancelCrossfade()
        val currentTrack = playerState.currentTrack ?: return
        val currentStreamUrl = playerState.streamUrl ?: return
        val localPlaybackUrl = localOrCachedPlaybackUrl(currentTrack)
        val currentPositionSeconds = runCatching {
            (exoPlayer.currentPosition / 1000L).toInt().coerceAtLeast(0)
        }.getOrDefault(playerState.progressSeconds)
        if (localPlaybackUrl == null) {
            clearNowPlayingEvent(activePlayEventState.value)
            clearGaplessPlaybackState()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            playerState = playerState.copy(
                isPlaying = false,
                progressSeconds = currentPositionSeconds,
                streamUrl = null,
            )
            return
        }
        if (currentStreamUrl != localPlaybackUrl && !currentStreamUrl.startsWith("file:", ignoreCase = true)) {
            clearGaplessPlaybackState()
            playbackStartSerial += 1
            playerState = playerState.copy(
                progressSeconds = currentPositionSeconds,
                streamUrl = localPlaybackUrl,
            )
        }
    }

    fun prefetchTrackAssets(track: Track) {
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.TrackList)
        loadLyrics(track)
    }

    fun prefetchNextTrackUrl(queue: PlaybackQueue) {
        if (!queue.canSkip) {
            return
        }

        val currentIndex = queue.currentIndex.coerceIn(0, queue.tracks.lastIndex)
        val queuedIndices = linkedSetOf<Int>()
        for (step in 1..GAPLESS_PREFETCH_LOOKAHEAD) {
            val rawIndex = currentIndex + step
            val nextIndex = when {
                rawIndex < queue.tracks.size -> rawIndex
                repeatMode == PlaybackRepeatMode.Queue -> rawIndex % queue.tracks.size
                else -> break
            }
            if (!queuedIndices.add(nextIndex)) {
                break
            }
            val nextTrack = queue.tracks[nextIndex]
            prefetchTrackAssets(nextTrack)
            val cachedUrl = localOrCachedPlaybackUrl(nextTrack)
            val prefetchedUrl = prefetchedPlaybackUrls[nextTrack.id]
            when {
                cachedUrl != null -> {
                    installGaplessPrefetch(queue, nextTrack, nextIndex, cachedUrl)
                }
                prefetchedUrl != null -> {
                    installGaplessPrefetch(queue, nextTrack, nextIndex, prefetchedUrl)
                }
                !canUseServerRequests() || nextTrack.id in playbackUrlPrefetchesInProgress -> {
                    Unit
                }
                else -> {
                    playbackUrlPrefetchesInProgress = playbackUrlPrefetchesInProgress + nextTrack.id
                    scope.launch {
                        runCatching {
                            musicRepository.streamUrl(nextTrack.id)
                        }.onSuccess { streamUrl ->
                            prefetchedPlaybackUrls = prefetchedPlaybackUrls + (nextTrack.id to streamUrl)
                            accessToken = authRepository.accessToken()
                            installGaplessPrefetch(queue, nextTrack, nextIndex, streamUrl)
                        }
                        playbackUrlPrefetchesInProgress = playbackUrlPrefetchesInProgress - nextTrack.id
                    }
                }
            }
        }
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
        if (
            crossfadeJob?.isActive == true ||
            prepared.player === exoPlayer ||
            prepared.queueGeneration != playbackQueueGeneration
        ) {
            return
        }
        val queue = playbackQueue
        val nextTrack = queue.tracks.getOrNull(prepared.queueIndex) ?: return
        val fromPlayer = exoPlayer
        val toPlayer = prepared.player
        crossfadeJob = scope.launch {
            var completed = false
            try {
                completed = performCrossfade(
                    fromPlayer = fromPlayer,
                    toPlayer = toPlayer,
                    fadeDurationMs = fadeDurationMs,
                    onOverlapStarted = {
                        val previousEvent = activePlayEventState.value
                        completeActivePlayEvent(force = true)
                        artworkTransitionDirection = 1
                        clearGaplessPlaybackState()
                        gaplessMediaQueueIndices = mapOf(prepared.mediaId to prepared.queueIndex)
                        gaplessMediaUrls = mapOf(prepared.mediaId to prepared.url)
                        playbackQueue = queue.copy(currentIndex = prepared.queueIndex)
                        playerState = PlayerState(
                            currentTrack = nextTrack,
                            isPlaying = true,
                            progressSeconds = 0,
                            streamUrl = prepared.url,
                        )
                        ensureActivePlayEvent(nextTrack, forceNew = previousEvent?.trackId == nextTrack.id)
                        pendingTransitionArtworkTrackId = nextTrack.listArtworkKey()
                        preparedCrossfade = null
                        exoPlayer = toPlayer
                    },
                )
                if (completed) {
                    requestedNextPrefetch += 1
                }
            } finally {
                if (!completed) {
                    fromPlayer.volume = if (fromPlayer === exoPlayer) 1f else 0f
                    toPlayer.volume = if (toPlayer === exoPlayer) 1f else 0f
                    if (fromPlayer !== exoPlayer) {
                        fromPlayer.stop()
                        fromPlayer.clearMediaItems()
                    }
                    if (toPlayer !== exoPlayer) {
                        toPlayer.stop()
                        toPlayer.clearMediaItems()
                    }
                    exoPlayer.configurePlaybackAudioFocus(handleAudioFocus = true)
                }
                crossfadeJob = null
                crossfadePreparationSerial += 1
            }
        }
    }

    LaunchedEffect(
        exoPlayer,
        playerState.currentTrack?.id,
        playerState.streamUrl,
        playbackQueueGeneration,
        playbackQueue.currentIndex,
        playbackQueue.tracks.map { it.id },
        repeatMode,
        crossfadeSeconds,
        prefetchedPlaybackUrls,
        crossfadePreparationSerial,
    ) {
        if (
            crossfadeSeconds <= 0 ||
            playerState.streamUrl == null ||
            crossfadeJob?.isActive == true
        ) {
            if (crossfadeSeconds <= 0) {
                preparedCrossfade = null
                standbyExoPlayer.stop()
                standbyExoPlayer.clearMediaItems()
            }
            return@LaunchedEffect
        }
        val targetIndex = nextCrossfadeQueueIndex(playbackQueue) ?: return@LaunchedEffect
        val targetTrack = playbackQueue.tracks.getOrNull(targetIndex) ?: return@LaunchedEffect
        val targetUrl = localOrCachedPlaybackUrl(targetTrack)
            ?: prefetchedPlaybackUrls[targetTrack.id]
        if (targetUrl == null) {
            prefetchNextTrackUrl(playbackQueue)
            return@LaunchedEffect
        }
        val existing = preparedCrossfade
        if (
            existing?.player === standbyExoPlayer &&
            existing.queueGeneration == playbackQueueGeneration &&
            existing.queueIndex == targetIndex &&
            existing.trackId == targetTrack.id &&
            existing.url == targetUrl
        ) {
            return@LaunchedEffect
        }
        val mediaId = "crossfade:${System.nanoTime()}:$targetIndex:${targetTrack.id}"
        standbyExoPlayer.prepareCrossfadeItem(targetUrl, mediaId)
        preparedCrossfade = PreparedCrossfade(
            player = standbyExoPlayer,
            queueGeneration = playbackQueueGeneration,
            queueIndex = targetIndex,
            trackId = targetTrack.id,
            url = targetUrl,
            mediaId = mediaId,
        )
    }

    LaunchedEffect(
        exoPlayer,
        preparedCrossfade?.signature,
        playerState.currentTrack?.id,
        playerState.isPlaying,
        crossfadeSeconds,
    ) {
        while (
            playerState.isPlaying &&
            crossfadeSeconds > 0 &&
            crossfadeJob?.isActive != true
        ) {
            val prepared = preparedCrossfade
            if (
                prepared != null &&
                prepared.player.playbackState == Player.STATE_READY &&
                prepared.player !== exoPlayer
            ) {
                val durationMs = exoPlayer.duration
                val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val requestedFadeMs = crossfadeSeconds.toLong() * 1_000L
                val effectiveFadeMs = requestedFadeMs
                    .coerceAtMost((durationMs / 2L).coerceAtLeast(1L))
                val remainingMs = durationMs - positionMs
                if (durationMs > 0L && remainingMs in 1..effectiveFadeMs) {
                    beginPreparedCrossfade(prepared, effectiveFadeMs.coerceAtMost(remainingMs))
                    break
                }
            }
            delay(16L)
        }
    }

    fun seekPreparedQueueMediaItem(targetIndex: Int, direction: Int): Boolean {
        val queue = playbackQueue
        val targetTrack = queue.tracks.getOrNull(targetIndex) ?: return false
        if (exoPlayer.mediaItemCount <= 0) {
            return false
        }
        val targetMediaIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { mediaIndex ->
            val mediaId = exoPlayer.getMediaItemAt(mediaIndex).mediaId
            gaplessMediaQueueIndices[mediaId] == targetIndex
        } ?: return false
        val targetMediaId = exoPlayer.getMediaItemAt(targetMediaIndex).mediaId
        val targetUrl = gaplessMediaUrls[targetMediaId]
            ?: prefetchedPlaybackUrls[targetTrack.id]
            ?: localOrCachedPlaybackUrl(targetTrack)
            ?: return false
        val previousEvent = activePlayEventState.value
        completeActivePlayEvent(force = true)
        artworkTransitionDirection = if (direction < 0) -1 else 1
        playbackQueue = queue.copy(currentIndex = targetIndex)
        playbackBufferedFraction = 0f
        playerState = PlayerState(
            currentTrack = targetTrack,
            isPlaying = true,
            progressSeconds = 0,
            streamUrl = targetUrl,
        )
        ensureActivePlayEvent(targetTrack, forceNew = previousEvent?.trackId == targetTrack.id)
        pendingTransitionArtworkTrackId = targetTrack.listArtworkKey()
        exoPlayer.seekTo(targetMediaIndex, 0L)
        exoPlayer.play()
        requestedNextPrefetch += 1
        return true
    }

    LaunchedEffect(requestedNextPrefetch) {
        if (requestedNextPrefetch > 0) {
            prefetchNextTrackUrl(playbackQueue)
        }
    }

    fun playQueuedTrack(
        track: Track,
        queue: PlaybackQueue,
        resumePositionMs: Long = 0L,
        preferredIndex: Int? = null,
        allowResume: Boolean = false,
        newQueue: Boolean = false,
    ) {
        cancelCrossfade()
        if (newQueue) {
            playbackQueueGeneration += 1
        }
        val startPositionMs = if (allowResume) resumePositionMs.coerceAtLeast(0L) else 0L
        val preparedQueue = prepareQueueForPlayback(queue, track, shuffleEnabled)
        val currentIndex = preferredIndex
            ?.takeIf { index -> index in preparedQueue.tracks.indices && preparedQueue.tracks[index].id == track.id }
            ?: preparedQueue.currentIndex
                .takeIf { index -> index in preparedQueue.tracks.indices && preparedQueue.tracks[index].id == track.id }
            ?: preparedQueue.tracks.indexOfFirst { it.id == track.id }
        val updatedQueue = preparedQueue.copy(
            currentIndex = currentIndex.takeIf { it >= 0 } ?: preparedQueue.currentIndex,
        )
        val previousTrack = playerState.currentTrack
        val isDifferentQueueItem = previousTrack?.id != track.id ||
            playbackQueue.currentIndex != updatedQueue.currentIndex ||
            playbackQueue.playlistId != updatedQueue.playlistId ||
            playbackQueue.sourceType != updatedQueue.sourceType ||
            playbackQueue.sourceId != updatedQueue.sourceId
        if (isDifferentQueueItem) {
            clearGaplessPlaybackState()
            playerState = PlayerState(
                currentTrack = track,
                isPlaying = true,
                progressSeconds = 0,
                streamUrl = null,
            )
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
        playbackQueue = updatedQueue
        playerError = null
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
        if (updatedQueue.canSkip) {
            prefetchNextTrackUrl(updatedQueue)
        }
        val gaplessLocalUrls = if (updatedQueue.canSkip) {
            updatedQueue.tracks.map { queuedTrack ->
                localOrCachedPlaybackUrl(queuedTrack)
            }.takeIf { urls -> urls.all { it != null } }
                ?.filterNotNull()
        } else {
            null
        }
        if (gaplessLocalUrls != null) {
            startGaplessPlayback(
                track = track,
                queue = updatedQueue,
                urls = gaplessLocalUrls,
                resumePositionMs = startPositionMs,
            )
            return
        }

        val localPlaybackUrl = musicRepository.localPlaybackUrl(track.id)
            ?: track.serverPath
                .takeIf { track.downloadState == DownloadState.Downloaded && it.isNotBlank() }
                ?.let { path -> File(path).takeIf { it.exists() && it.length() > 0L }?.toURI()?.toString() }
        if (localPlaybackUrl != null) {
            startPlayback(
                track = track.copy(downloadState = DownloadState.Downloaded),
                playbackUrl = localPlaybackUrl,
                startPositionMs = startPositionMs,
            )
            prefetchNextTrackUrl(updatedQueue)
            return
        }

        val cachedPlaybackUrl = musicRepository.cachedPlaybackUrl(track.id)
        if (cachedPlaybackUrl != null) {
            startPlayback(
                track = track,
                playbackUrl = cachedPlaybackUrl,
                startPositionMs = startPositionMs,
            )
            prefetchNextTrackUrl(updatedQueue)
            return
        }

        prefetchedPlaybackUrls[track.id]?.let { prefetchedPlaybackUrl ->
            prefetchedPlaybackUrls = prefetchedPlaybackUrls - track.id
            startPlayback(
                track = track,
                playbackUrl = prefetchedPlaybackUrl,
                startPositionMs = startPositionMs,
            )
            prefetchNextTrackUrl(updatedQueue)
            return
        }

        if (!canUseServerRequests()) {
            playerError = "Track is not available offline"
            playerState = PlayerState(
                currentTrack = track,
                isPlaying = false,
                progressSeconds = (startPositionMs / 1000L).toInt().coerceAtLeast(0),
                streamUrl = null,
            )
            return
        }

        playerState = PlayerState(
            currentTrack = track,
            isPlaying = true,
            progressSeconds = (startPositionMs / 1000L).toInt().coerceAtLeast(0),
            streamUrl = null,
        )
        streamRequestSerial += 1
        val requestSerial = streamRequestSerial
        val requestedQueueIndex = updatedQueue.currentIndex
        scope.launch {
            runCatching {
                musicRepository.streamUrl(track.id)
            }.onSuccess { streamUrl ->
                if (
                    streamRequestSerial == requestSerial &&
                    playerState.currentTrack?.id == track.id &&
                    playbackQueue.currentIndex == requestedQueueIndex
                ) {
                    accessToken = authRepository.accessToken()
                    startPlayback(
                        track = track,
                        playbackUrl = streamUrl,
                        startPositionMs = startPositionMs,
                    )
                    prefetchNextTrackUrl(updatedQueue)
                }
            }.onFailure { error ->
                if (streamRequestSerial == requestSerial && playerState.currentTrack?.id == track.id) {
                    markServerUnavailable(error)
                    playerError = error.userMessage()
                    playerState = playerState.copy(isPlaying = false)
                }
            }
        }
    }

    fun selectTrack(track: Track, sourceTitle: String? = null) {
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

    fun selectRecentTrack(track: Track, sourceTracks: List<Track>) {
        val queueTracks = sourceTracks.takeIf { it.isNotEmpty() } ?: listOf(track)
        val startIndex = queueTracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
        playQueuedTrack(
            track = queueTracks.getOrNull(startIndex) ?: track,
            queue = PlaybackQueue(
                sourceType = PlaybackSourceType.Recent,
                sourceId = "recent-tracks",
                sourceTitle = "Latest tracks",
                tracks = queueTracks,
                sourceTracks = queueTracks,
                currentIndex = startIndex,
            ),
            preferredIndex = startIndex,
            newQueue = true,
        )
    }

    fun playPlaylistTrackAt(playlist: Playlist, playlistTracks: List<Track>, trackIndex: Int) {
        if (playlistTracks.isEmpty()) {
            return
        }
        val startIndex = trackIndex.coerceIn(playlistTracks.indices)
        val track = playlistTracks[startIndex]

        playQueuedTrack(
            track = track,
            queue = PlaybackQueue(
                playlistId = playlist.id,
                sourceType = PlaybackSourceType.Playlist,
                sourceId = playlist.id,
                sourceTitle = playlist.title,
                tracks = playlistTracks,
                currentIndex = startIndex,
            ),
            preferredIndex = startIndex,
            newQueue = true,
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
        if (playlistTracks.isEmpty()) {
            return
        }
        val randomizedTracks = playlistTracks.shuffled(Random).let { shuffledTracks ->
            if (shuffledTracks.size > 1 && shuffledTracks.map(Track::id) == playbackQueue.tracks.map(Track::id)) {
                shuffledTracks.drop(1) + shuffledTracks.first()
            } else {
                shuffledTracks
            }
        }
        shuffleEnabled = true
        userPreferencesStore.setShuffleEnabled(true)
        playQueuedTrack(
            track = randomizedTracks.first(),
            queue = PlaybackQueue(
                playlistId = playlist.id,
                sourceType = PlaybackSourceType.Playlist,
                sourceId = playlist.id,
                sourceTitle = playlist.title,
                tracks = randomizedTracks,
                sourceTracks = playlistTracks,
                isShuffled = true,
                currentIndex = 0,
            ),
            preferredIndex = 0,
            newQueue = true,
        )
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

    fun loadPlaylistTracks(playlist: Playlist, force: Boolean = false) {
        if (!canUseServerRequests() || playlist.id in playlistTrackLoadsInProgress) {
            return
        }
        val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        val offset = if (force) 0 else currentPlaylist.trackIds.size
        if (!force && currentPlaylist.trackIds.size >= currentPlaylist.trackCount) {
            val loadedTrackIds = tracks.map { it.id }.toSet()
            val hasMissingTrackModels = currentPlaylist.trackIds.any { it !in loadedTrackIds }
            if (!hasMissingTrackModels) {
                playlistTrackHasMoreById = playlistTrackHasMoreById + (playlist.id to false)
                return
            }
        }

        playlistTrackLoadsInProgress = playlistTrackLoadsInProgress + playlist.id
        scope.launch {
            runCatching {
                val loadedTrackIds = tracks.map { it.id }.toSet()
                val shouldReloadFromStart = !force &&
                    currentPlaylist.trackIds.any { it !in loadedTrackIds }
                if (playlist.isFavoritesPlaylist()) {
                    musicRepository.favoritesPlaylistPayloadTrackPage(
                        playlist = currentPlaylist,
                        trackLimit = DETAIL_TRACK_PAGE_LIMIT,
                        trackOffset = if (shouldReloadFromStart) 0 else offset,
                    )
                } else {
                    musicRepository.playlistPayloadTrackPage(
                        playlistId = playlist.id,
                        trackLimit = DETAIL_TRACK_PAGE_LIMIT,
                        trackOffset = if (shouldReloadFromStart) 0 else offset,
                    )
                }
            }.onSuccess { payload ->
                accessToken = authRepository.accessToken()
                val loadedTrackIds = tracks.map { it.id }.toSet()
                val shouldReloadFromStart = !force &&
                    currentPlaylist.trackIds.any { it !in loadedTrackIds }
                val updatedPlaylist = applyPlaylistTrackPage(
                    playlist = playlist,
                    payload = payload,
                    append = !force && !shouldReloadFromStart && offset > 0,
                )
                val loadedCount = payload.tracks.size
                val nextLoadedCount = updatedPlaylist?.trackIds?.size ?: currentPlaylist.trackIds.size
                val totalCount = updatedPlaylist?.trackCount ?: currentPlaylist.trackCount
                playlistTrackHasMoreById = playlistTrackHasMoreById + (
                    playlist.id to (loadedCount >= DETAIL_TRACK_PAGE_LIMIT && nextLoadedCount < totalCount)
                    )
            }.onFailure { error ->
                libraryError = error.userMessage()
            }
            playlistTrackLoadsInProgress = playlistTrackLoadsInProgress - playlist.id
        }
    }

    fun loadArtistAlbums(artist: LibraryArtist, force: Boolean = false) {
        val knownAlbums = albums.filter { album ->
            album.matchesArtistName(artist.name) ||
                tracks.any { track ->
                    (track.albumId == album.id || track.album == album.title) &&
                        track.matchesArtistName(artist.name)
                }
        }
        val localAlbums = tracks
            .filter { it.matchesArtistName(artist.name) }
            .downloadedAlbums()
        val localLooseTracks = tracks
            .filter { track ->
                track.albumId == null &&
                    track.downloadState == DownloadState.Downloaded &&
                    track.matchesArtistName(artist.name)
            }
            .sortedWith(compareBy<Track> { it.title.lowercase() }.thenBy { it.id })
        val fallbackAlbums = (knownAlbums + localAlbums).distinctBy { it.id }.sortedAlbumsForDisplay()
        val hasCachedArtistDetails = artist.name in albumsByArtist ||
            artist.name in appearsOnByArtist ||
            artist.name in looseTracksByArtist
        if (fallbackAlbums.isNotEmpty() && !hasCachedArtistDetails) {
            albumsByArtist = albumsByArtist + (artist.name to fallbackAlbums)
            appearsOnByArtist = appearsOnByArtist + (artist.name to emptyList())
        }
        if (localLooseTracks.isNotEmpty() && artist.name !in looseTracksByArtist) {
            looseTracksByArtist = looseTracksByArtist + (artist.name to localLooseTracks)
        }
        val artistId = artist.id.takeIf { it.isNotBlank() }
        if (!canUseServerRequests() || artist.name in artistAlbumLoadsInProgress || artistId == null) {
            return
        }

        artistAlbumLoadsInProgress = artistAlbumLoadsInProgress + artist.name
        scope.launch {
            runCatching {
                musicRepository.libraryArtistAlbums(artistId)
            }.onSuccess { loadedArtistAlbums ->
                accessToken = authRepository.accessToken()
                val savedAlbumIds = savedAlbums.map { it.id }.toSet()
                val loadedAlbums = loadedArtistAlbums.albums.map { album ->
                    album.copy(savedByCurrentUser = album.savedByCurrentUser || album.id in savedAlbumIds)
                }.sortedAlbumsForDisplay()
                val loadedAppearsOn = loadedArtistAlbums.appearsOn.map { album ->
                    album.copy(savedByCurrentUser = album.savedByCurrentUser || album.id in savedAlbumIds)
                }.sortedAlbumsForDisplay()
                val loadedLooseTracks = loadedArtistAlbums.tracks.sortedWith(
                    compareBy<Track> { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() },
                )
                albumsByArtist = albumsByArtist + (artist.name to loadedAlbums)
                appearsOnByArtist = appearsOnByArtist + (artist.name to loadedAppearsOn)
                looseTracksByArtist = looseTracksByArtist + (artist.name to loadedLooseTracks)
                albums = (albums + loadedAlbums + loadedAppearsOn).distinctBy { it.id }.sortedAlbumsForDisplay()
                mergeLoadedTracks(loadedLooseTracks)
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            artistAlbumLoadsInProgress = artistAlbumLoadsInProgress - artist.name
        }
    }

    fun loadSimilarArtists(artist: LibraryArtist, force: Boolean = false) {
        val artistId = artist.id.takeIf { it.isNotBlank() } ?: return
        if (
            !canUseServerRequests() ||
            artist.name in similarArtistLoadsInProgress ||
            (!force && artist.name in similarArtistsByArtist)
        ) {
            return
        }

        similarArtistLoadsInProgress = similarArtistLoadsInProgress + artist.name
        scope.launch {
            runCatching {
                val cachedArtistsById = (artists + searchResults.artists + similarArtistsByArtist.values.flatten())
                    .mapNotNull { cachedArtist -> cachedArtist.id?.let { id -> id to cachedArtist } }
                    .toMap()
                musicRepository.similarArtists(artistId = artistId, limit = 10, offset = 0)
                    .mapNotNull { similarArtist ->
                        val similarArtistId = similarArtist.id?.takeIf { it.isNotBlank() }
                        val cachedArtist = similarArtistId?.let(cachedArtistsById::get)
                        when {
                            cachedArtist != null && similarArtist.name == similarArtistId -> cachedArtist.copy(
                                similarity = similarArtist.similarity,
                            )
                            cachedArtist != null -> similarArtist.copy(
                                name = similarArtist.name.takeIf { it != similarArtistId } ?: cachedArtist.name,
                            )
                            similarArtistId != null && similarArtist.name == similarArtistId ->
                                musicRepository.libraryArtist(similarArtistId)
                                    ?.copy(similarity = similarArtist.similarity)
                                    ?: similarArtist.takeIf { it.name != similarArtistId }
                            else -> similarArtist
                        }
                    }
            }.onSuccess { loadedArtists ->
                accessToken = authRepository.accessToken()
                val normalizedArtists = loadedArtists
                    .distinctBy { it.id }
                    .manualSimilarArtistsFirst()
                similarArtistsByArtist = similarArtistsByArtist + (artist.name to normalizedArtists)
                artists = (artists + normalizedArtists)
                    .distinctBy { it.id }
                    .sortedArtistsForDisplay()
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            similarArtistLoadsInProgress = similarArtistLoadsInProgress - artist.name
        }
    }

    fun loadAlbumTracks(album: LibraryAlbum, force: Boolean = false) {
        val localTracks = tracks.filter { track ->
            track.albumId == album.id || (track.album == album.title && track.matchesAlbumArtist(album))
        }
        if (localTracks.isNotEmpty() && !canUseServerRequests()) {
            albumTracksById = albumTracksById + (album.id to localTracks.sortedBy { it.trackNumber ?: Int.MAX_VALUE })
            albumTrackHasMoreById = albumTrackHasMoreById + (album.id to false)
            return
        }
        val currentAlbumTracks = albumTracksById[album.id].orEmpty()
        val hasServerPagingState = album.id in albumTrackHasMoreById
        val shouldReloadFromStart = !force &&
            canUseServerRequests() &&
            currentAlbumTracks.isNotEmpty() &&
            !hasServerPagingState &&
            (album.trackCount <= 0 || currentAlbumTracks.size < album.trackCount)
        val offset = if (force || shouldReloadFromStart) 0 else currentAlbumTracks.size
        if ((!force && currentAlbumTracks.size >= album.trackCount && album.trackCount > 0) || !canUseServerRequests() || album.id in albumTrackLoadsInProgress) {
            if (!force && currentAlbumTracks.size >= album.trackCount && album.trackCount > 0) {
                albumTrackHasMoreById = albumTrackHasMoreById + (album.id to false)
            }
            return
        }

        albumTrackLoadsInProgress = albumTrackLoadsInProgress + album.id
        scope.launch {
            runCatching {
                musicRepository.albumTracksPage(
                    albumId = album.id,
                    limit = DETAIL_TRACK_PAGE_LIMIT,
                    offset = offset,
                )
            }.onSuccess { loadedTracks ->
                accessToken = authRepository.accessToken()
                val orderedTracks = loadedTracks.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
                val nextTracks = if (force || offset == 0) {
                    orderedTracks
                } else {
                    (currentAlbumTracks + orderedTracks).distinctBy { it.id }
                }
                albumTracksById = albumTracksById + (album.id to nextTracks)
                mergeLoadedTracks(orderedTracks)
                albumTrackHasMoreById = albumTrackHasMoreById + (
                    album.id to (orderedTracks.size >= DETAIL_TRACK_PAGE_LIMIT && (album.trackCount <= 0 || nextTracks.size < album.trackCount))
                    )
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            albumTrackLoadsInProgress = albumTrackLoadsInProgress - album.id
        }
    }

    fun loadMoreArtists() {
        if (
            !canUseServerRequests() ||
            artistListLoadingMore ||
            !artistListHasMore
        ) {
            return
        }

        artistListLoadingMore = true
        val offset = artistListNextOffset.coerceAtLeast(0)
        scope.launch {
            runCatching {
                musicRepository.libraryArtistsPageWithTotal(limit = SCREEN_PAGE_LIMIT, offset = offset)
            }.onSuccess { artistPage ->
                val loadedArtists = artistPage.artists
                accessToken = authRepository.accessToken()
                artists = (artists + loadedArtists)
                    .distinctBy { it.id }
                    .sortedArtistsForDisplay()
                artistListNextOffset = offset + loadedArtists.size
                artistListHasMore = loadedArtists.size >= SCREEN_PAGE_LIMIT
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            artistListLoadingMore = false
        }
    }

    fun loadMoreAlbums() {
        if (
            !canUseServerRequests() ||
            albumListLoadingMore ||
            !albumListHasMore
        ) {
            return
        }

        albumListLoadingMore = true
        val offset = albumListNextOffset.coerceAtLeast(0)
        scope.launch {
            runCatching {
                musicRepository.libraryAlbumsPage(limit = SCREEN_PAGE_LIMIT, offset = offset)
            }.onSuccess { loadedAlbums ->
                accessToken = authRepository.accessToken()
                val savedAlbumIds = savedAlbums.map { it.id }.toSet()
                albums = (albums + loadedAlbums.map { album ->
                    album.copy(savedByCurrentUser = album.savedByCurrentUser || album.id in savedAlbumIds)
                })
                    .distinctBy { it.id }
                    .sortedAlbumsForDisplay()
                albumListNextOffset = offset + loadedAlbums.size
                albumListHasMore = loadedAlbums.size >= SCREEN_PAGE_LIMIT
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            albumListLoadingMore = false
        }
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

    fun openArtistByName(artistName: String) {
        val normalizedName = artistName.trim()
        if (normalizedName.isBlank()) {
            return
        }
        val cachedArtist = resolveCachedArtist(normalizedName)
        val artistId = cachedArtist?.id?.takeIf { it.isNotBlank() }
        if (artistId == null) {
            libraryError = "Artist id is missing for $normalizedName."
            return
        }
        if (!canUseServerRequests()) {
            openArtist(cachedArtist)
            return
        }

        scope.launch {
            runCatching {
                musicRepository.libraryArtist(artistId)
            }.onSuccess { foundArtists ->
                accessToken = authRepository.accessToken()
                val resolvedArtist = foundArtists ?: cachedArtist
                artists = (artists + resolvedArtist)
                        .distinctBy { it.id }
                        .sortedArtistsForDisplay()
                openArtist(resolvedArtist)
            }.onFailure {
                openArtist(cachedArtist)
            }
        }
    }

    fun openAlbum(album: LibraryAlbum) {
        val wasSaved = album.savedByCurrentUser ||
            albums.any { it.id == album.id && it.savedByCurrentUser } ||
            savedAlbums.any { it.id == album.id && it.savedByCurrentUser }
        val wasOfflineEnabled = album.isOfflineEnabled ||
            album.id in offlineAlbumIds ||
            albums.any { it.id == album.id && it.isOfflineEnabled } ||
            savedAlbums.any { it.id == album.id && it.isOfflineEnabled }
        val cachedAlbum = album.copy(
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

    fun playAlbumFromTrack(album: LibraryAlbum, albumTracks: List<Track>, track: Track) {
        if (albumTracks.isEmpty()) {
            return
        }
        val startIndex = albumTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        playQueuedTrack(
            track = track,
            queue = PlaybackQueue(
                sourceType = PlaybackSourceType.Album,
                sourceId = album.id,
                sourceTitle = album.title,
                tracks = albumTracks,
                currentIndex = startIndex,
            ),
            preferredIndex = startIndex,
            newQueue = true,
        )
    }

    fun playAlbum(album: LibraryAlbum, albumTracks: List<Track>) {
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
                musicRepository.albumTracks(album.id)
            }.onSuccess { loadedTracks ->
                val orderedTracks = loadedTracks.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
                albumTracksById = albumTracksById + (album.id to orderedTracks)
                mergeLoadedTracks(orderedTracks)
                orderedTracks.firstOrNull()?.let { track ->
                    playAlbumFromTrack(album, orderedTracks, track)
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                playerError = error.userMessage()
            }
        }
    }

    fun toggleAlbumInLibrary(album: LibraryAlbum) {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before saving albums to Library."
            return
        }

        scope.launch {
            libraryError = null
            val nextSavedState = !album.savedByCurrentUser
            runCatching {
                if (nextSavedState) {
                    musicRepository.saveAlbum(album.id)
                } else {
                    musicRepository.unsaveAlbum(album.id)
                }
            }.onSuccess { serverAlbum ->
                val updatedAlbum = (serverAlbum ?: album).copy(
                    savedByCurrentUser = serverAlbum?.savedByCurrentUser ?: nextSavedState,
                    isOfflineEnabled = serverAlbum?.isOfflineEnabled == true ||
                        album.isOfflineEnabled ||
                        album.id in offlineAlbumIds,
                )
                albums = albums.updateAlbum(updatedAlbum)
                albumsByArtist = albumsByArtist.mapValues { (_, artistAlbums) ->
                    artistAlbums.updateAlbum(updatedAlbum)
                }
                appearsOnByArtist = appearsOnByArtist.mapValues { (_, artistAlbums) ->
                    artistAlbums.updateAlbum(updatedAlbum)
                }
                savedAlbums = if (updatedAlbum.savedByCurrentUser) {
                    (savedAlbums.filterNot { it.id == updatedAlbum.id } + updatedAlbum).sortedAlbumsForDisplay()
                } else {
                    savedAlbums.filterNot { it.id == updatedAlbum.id }
                }
                libraryCacheStore.saveLibrary(
                    playlists = playlists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )
                accessToken = authRepository.accessToken()
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
    }

    fun skipInQueue(direction: Int) {
        cancelCrossfade()
        val queue = playbackQueue
        val currentTrackId = playerState.currentTrack?.id
        if (!queue.canSkip) {
            return
        }

        if (direction < 0) {
            val currentPositionMs = runCatching { exoPlayer.currentPosition }
                .getOrDefault(playerState.progressSeconds.toLong() * 1000L)
            if (currentPositionMs >= 2_000L) {
                val currentTrack = playerState.currentTrack ?: return
                completeActivePlayEvent(force = true)
                playerState = playerState.copy(progressSeconds = 0)
                if (playerState.streamUrl != null) {
                    exoPlayer.seekTo(0L)
                    if (playerState.isPlaying) {
                        exoPlayer.play()
                    }
                }
                ensureActivePlayEvent(currentTrack, forceNew = true)
                return
            }
        }

        val indexFromQueue = queue.currentIndex
            .takeIf { it in queue.tracks.indices && queue.tracks[it].id == currentTrackId }
        val indexFromTrack = currentTrackId
            ?.let { trackId -> queue.tracks.indexOfFirst { it.id == trackId } }
            ?.takeIf { it in queue.tracks.indices }
        val currentIndex = indexFromQueue
            ?: indexFromTrack
            ?: queue.currentIndex.coerceIn(0, queue.tracks.lastIndex)
        val requestedIndex = currentIndex + direction
        val nextIndex = requestedIndex.floorMod(queue.tracks.size)
        artworkTransitionDirection = if (direction < 0) -1 else 1
        if (seekPreparedQueueMediaItem(nextIndex, direction)) {
            return
        }
        playQueuedTrack(
            track = queue.tracks[nextIndex],
            queue = queue.copy(currentIndex = nextIndex),
            preferredIndex = nextIndex,
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
        artworkTransitionDirection = if (index < queue.currentIndex) -1 else 1
        if (seekPreparedQueueMediaItem(index, artworkTransitionDirection)) {
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
        val currentTrack = playerState.currentTrack
        val queue = playbackQueue.takeIf { it.tracks.isNotEmpty() }
            ?: currentTrack?.let {
                PlaybackQueue(
                    sourceType = PlaybackSourceType.Search,
                    sourceId = "Queue",
                    sourceTitle = "Queue",
                    tracks = listOf(it),
                    sourceTracks = listOf(it),
                    currentIndex = 0,
                )
            }
            ?: PlaybackQueue(
                sourceType = PlaybackSourceType.Search,
                sourceId = "Queue",
                sourceTitle = "Queue",
                tracks = emptyList(),
                sourceTracks = emptyList(),
                currentIndex = -1,
            )
        val currentIndex = queue.currentIndex
            .takeIf { it in queue.tracks.indices }
            ?: currentTrack?.id
                ?.let { trackId -> queue.tracks.indexOfFirst { it.id == trackId } }
                ?.takeIf { it >= 0 }
            ?: queue.currentIndex.coerceIn(0, queue.tracks.lastIndex.coerceAtLeast(0))
        val insertionBase = if (queueInsertionAnchorTrackId == currentTrack?.id) {
            queueInsertionCursor?.coerceIn(currentIndex, queue.tracks.lastIndex.coerceAtLeast(currentIndex))
                ?: currentIndex
        } else {
            currentIndex
        }
        val insertionIndex = (insertionBase + 1).coerceIn(0, queue.tracks.size)
        val nextTracks = queue.tracks.toMutableList().apply {
            add(insertionIndex, track)
        }
        clearGaplessPlaybackState()
        playbackQueue = queue.copy(
            tracks = nextTracks,
            sourceTracks = nextTracks,
            currentIndex = when {
                queue.currentIndex < 0 -> 0
                insertionIndex <= queue.currentIndex -> queue.currentIndex + 1
                else -> queue.currentIndex
            },
            isShuffled = false,
        )
        queueInsertionAnchorTrackId = currentTrack?.id
        queueInsertionCursor = insertionIndex
        mergeLoadedTracks(listOf(track))
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.TrackList)
        libraryNotice = "Added to queue."
    }

    fun removeTrackFromQueueAt(index: Int) {
        cancelCrossfade()
        val queue = playbackQueue
        if (index !in queue.tracks.indices) {
            return
        }
        val nextTracks = queue.tracks.filterIndexed { itemIndex, _ -> itemIndex != index }
        if (nextTracks.isEmpty()) {
            clearNowPlayingEvent(activePlayEventState.value)
            activePlayEventState.value = null
            clearGaplessPlaybackState()
            playbackQueue = PlaybackQueue()
            playerState = PlayerState(null, isPlaying = false, progressSeconds = 0, streamUrl = null)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            return
        }
        val currentIndex = queue.currentIndex.coerceIn(0, queue.tracks.lastIndex)
        val nextCurrentIndex = when {
            index < currentIndex -> currentIndex - 1
            index == currentIndex -> currentIndex.coerceAtMost(nextTracks.lastIndex)
            else -> currentIndex
        }
        val nextQueue = queue.copy(
            tracks = nextTracks,
            sourceTracks = nextTracks,
            currentIndex = nextCurrentIndex,
            isShuffled = false,
        )
        clearGaplessPlaybackState()
        playbackQueue = nextQueue
        queueInsertionCursor = queueInsertionCursor?.let { cursor ->
            when {
                index < cursor -> cursor - 1
                index == cursor -> null
                else -> cursor
            }
        }
        if (index == currentIndex) {
            playQueuedTrack(
                track = nextTracks[nextCurrentIndex],
                queue = nextQueue,
                preferredIndex = nextCurrentIndex,
            )
        }
    }

    fun reorderQueueTracks(reorderedIndices: List<Int>) {
        cancelCrossfade()
        val queue = playbackQueue
        if (
            reorderedIndices.isEmpty() ||
            reorderedIndices.size != queue.tracks.size ||
            reorderedIndices.toSet().size != queue.tracks.size ||
            reorderedIndices.any { it !in queue.tracks.indices }
        ) {
            return
        }
        val reorderedTracks = reorderedIndices.map(queue.tracks::get)
        val currentTrackId = playerState.currentTrack?.id
        val activeOriginalIndex = queue.currentIndex
            .takeIf { it in queue.tracks.indices && queue.tracks[it].id == currentTrackId }
            ?: currentTrackId
                ?.let { trackId -> queue.tracks.indexOfFirst { it.id == trackId } }
                ?.takeIf { it >= 0 }
            ?: queue.currentIndex
        val nextIndex = reorderedIndices.indexOf(activeOriginalIndex)
            .takeIf { it >= 0 }
            ?: queue.currentIndex.coerceIn(0, reorderedTracks.lastIndex)
        val nextQueue = playbackQueue.copy(
            tracks = reorderedTracks,
            sourceTracks = reorderedTracks,
            currentIndex = nextIndex,
            isShuffled = false,
        )
        queueInsertionCursor = null
        clearGaplessPlaybackState()
        playbackQueue = nextQueue
    }

    fun openTrackArtist(track: Track) {
        val artistOptions = (track.artistReferences() + track.artistLogicNames().mapNotNull(::resolveCachedArtist))
            .distinctBy { it.id }
        when (artistOptions.size) {
            0 -> libraryError = "Artist id is missing for this track."
            1 -> openArtist(artistOptions.first())
            else -> artistChoices = artistOptions
        }
    }

    fun openAlbumArtist(album: LibraryAlbum) {
        val artistOptions = (
            album.artistReferences(albumTracksById[album.id].orEmpty() + tracks) +
                album.artistLogicNames().mapNotNull(::resolveCachedArtist)
            )
            .distinctBy { it.id }
        when (artistOptions.size) {
            0 -> libraryError = "Artist id is missing for this album."
            1 -> openArtist(artistOptions.first())
            else -> artistChoices = artistOptions
        }
    }

    fun openTrackAlbum(track: Track) {
        val albumId = track.albumId?.takeIf { it.isNotBlank() } ?: return
        openAlbum(
            LibraryAlbum(
                id = albumId,
                title = track.album,
                artist = track.albumArtist ?: track.artistLogicNames().firstOrNull() ?: track.artist,
                artistId = track.albumArtistId ?: track.artistId ?: track.artistIds.firstOrNull(),
                artistIds = (listOfNotNull(track.albumArtistId, track.artistId) + track.artistIds)
                    .filter { it.isNotBlank() }
                    .distinct(),
                releaseYear = track.releaseYear,
                genre = track.genre,
                trackCount = 0,
                accentColor = track.accentColor,
                artworkTrackId = track.id,
            ),
        )
    }

    fun applyPlaybackQueueOrderWithoutInterrupt(nextQueue: PlaybackQueue) {
        val currentMediaIndex = exoPlayer.currentMediaItemIndex
        val currentMediaItem = exoPlayer.currentMediaItem
        val currentMediaId = currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
        val currentMediaUrl = currentMediaItem?.localConfiguration?.uri?.toString()
            ?: playerState.streamUrl
        if (
            currentMediaItem != null &&
            currentMediaIndex >= 0 &&
            currentMediaIndex + 1 < exoPlayer.mediaItemCount
        ) {
            exoPlayer.removeMediaItems(currentMediaIndex + 1, exoPlayer.mediaItemCount)
        }
        gaplessPlaybackRequest = null
        gaplessMediaQueueIndices = currentMediaId?.let { mediaId ->
            mapOf(mediaId to nextQueue.currentIndex)
        }.orEmpty()
        gaplessMediaUrls = if (currentMediaId != null && currentMediaUrl != null) {
            mapOf(currentMediaId to currentMediaUrl)
        } else {
            emptyMap()
        }
        playbackQueue = nextQueue
        if (playerState.currentTrack != null && playerState.streamUrl != null && nextQueue.canSkip) {
            prefetchNextTrackUrl(nextQueue)
        }
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

    LaunchedEffect(playerState.currentTrack?.id) {
        queueInsertionAnchorTrackId = null
        queueInsertionCursor = null
    }

    fun setShowLyrics(enabled: Boolean) {
        showLyrics = enabled
        userPreferencesStore.setShowLyrics(enabled)
    }

    fun togglePlayback() {
        if (crossfadeJob?.isActive == true || preparedCrossfade != null) {
            cancelCrossfade()
        }
        val currentTrack = playerState.currentTrack
        if (!playerState.isPlaying && playerState.streamUrl == null && currentTrack != null) {
            val resumePositionMs = playerState.progressSeconds.toLong().coerceAtLeast(0L) * 1000L
            playQueuedTrack(
                track = currentTrack,
                queue = playbackQueue.takeIf { it.tracks.isNotEmpty() }
                    ?: PlaybackQueue(tracks = listOf(currentTrack), currentIndex = 0),
                resumePositionMs = resumePositionMs,
                allowResume = resumePositionMs > 0L,
            )
            return
        }

        val nextIsPlaying = !playerState.isPlaying
        val progressSeconds = if (playerState.isPlaying && playerState.streamUrl != null) {
            (exoPlayer.currentPosition / 1000L).toInt().coerceAtLeast(0)
        } else {
            playerState.progressSeconds
        }
        if (nextIsPlaying && currentTrack != null) {
            ensureActivePlayEvent(currentTrack)
        } else if (!nextIsPlaying) {
            clearNowPlayingEvent(activePlayEventState.value)
        }
        playerState = playerState.copy(
            isPlaying = nextIsPlaying,
            progressSeconds = progressSeconds,
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

    fun updateKnownTrackLikedState(trackId: String, isLiked: Boolean) {
        fun List<Track>.updatedLikedState(): List<Track> {
            return map { track ->
                if (track.id == trackId) track.copy(isLiked = isLiked) else track
            }
        }

        tracks = tracks.updatedLikedState()
        recentTracks = recentTracks.updatedLikedState()
        albumTracksById = albumTracksById.mapValues { (_, albumTracks) -> albumTracks.updatedLikedState() }
        looseTracksByArtist = looseTracksByArtist.mapValues { (_, artistTracks) -> artistTracks.updatedLikedState() }
        searchResults = searchResults.copy(tracks = searchResults.tracks.updatedLikedState())
        playbackQueue = playbackQueue.copy(
            tracks = playbackQueue.tracks.updatedLikedState(),
            sourceTracks = playbackQueue.sourceTracks.updatedLikedState(),
        )
        playerState.currentTrack?.takeIf { it.id == trackId }?.let { currentTrack ->
            playerState = playerState.copy(currentTrack = currentTrack.copy(isLiked = isLiked))
        }
    }

    suspend fun ensureTrackDownloaded(track: Track) {
        val promotedManifest = musicRepository.promoteCachedTrack(track.id)
        if (promotedManifest != null) {
            return
        }
        musicRepository.downloadTrack(track.id)
    }

    fun mergePlaylistPickerMetadata(loadedPlaylists: List<Playlist>) {
        playlistPickerPlaylists = loadedPlaylists.sanitizeClientPlaylists().filterNot { it.isFavoritesPlaylist() }
        playlists = playlists.mergePlaylistMetadata(loadedPlaylists)
        playlistMetadataLoaded = true
    }

    suspend fun findOrLoadFavoritesPlaylist(): Playlist? {
        playlists.firstOrNull { it.isFavoritesPlaylist() }?.let { return it }
        val loadedPlaylists = musicRepository.playlistsMetadata()
        mergePlaylistPickerMetadata(loadedPlaylists)
        return loadedPlaylists.firstOrNull { it.isFavoritesPlaylist() }
    }

    suspend fun loadFavoritesPlaylistForTrack(track: Track): Playlist? {
        val favoritePlaylist = findOrLoadFavoritesPlaylist() ?: return null
        val cachedHasTrack = track.id in favoritePlaylist.trackIds
        val cachedPlaylistTrackIds = favoritePlaylist.playlistTrackIdsForTrack(track.id)
        val cacheCoversPlaylist = favoritePlaylist.trackCount <= 0 ||
            favoritePlaylist.trackIds.size >= favoritePlaylist.trackCount
        if ((cachedHasTrack && cachedPlaylistTrackIds.isNotEmpty()) || (!cachedHasTrack && cacheCoversPlaylist)) {
            return favoritePlaylist
        }
        val payload = musicRepository.favoritesPlaylistPayload(favoritePlaylist)
        return applyPlaylistPayload(payload) ?: favoritePlaylist
    }

    fun toggleFavoriteTrack(track: Track) {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before editing Favorites."
            return
        }
        if (playlistAddInProgress) {
            return
        }

        playlistAddInProgress = true
        scope.launch {
            libraryError = null
            val previousPlaylists = playlists
            val previousTracks = tracks
            runCatching {
                var favoritePlaylist = loadFavoritesPlaylistForTrack(track)
                    ?: throw IllegalStateException("Favorites playlist was not found.")
                var playlistTrackIds = favoritePlaylist.playlistTrackIdsForTrack(track.id)
                val wasFavorite = track.isLiked ?: (track.id in favoritePlaylist.trackIds)
                if (wasFavorite && playlistTrackIds.isEmpty()) {
                    favoritePlaylist = applyPlaylistPayload(
                        musicRepository.favoritesPlaylistPayload(favoritePlaylist),
                    ) ?: favoritePlaylist
                    playlistTrackIds = favoritePlaylist.playlistTrackIdsForTrack(track.id)
                }
                val optimisticPlaylist = if (wasFavorite) {
                    favoritePlaylist.withoutFavoriteTrack(track.id)
                } else {
                    favoritePlaylist.withFavoriteTrack(track.id)
                }
                val optimisticPlaylists = playlists
                    .sanitizeClientPlaylists()
                    .updateOrAppendPlaylist(optimisticPlaylist)
                playlists = optimisticPlaylists
                updateKnownTrackLikedState(track.id, isLiked = !wasFavorite)
                if (tracks.none { it.id == track.id }) {
                    tracks = tracks + track.copy(isLiked = !wasFavorite)
                }
                loadArtwork(track.id, ArtworkImageSize.AlbumGrid)
                libraryCacheStore.saveLibrary(
                    playlists = optimisticPlaylists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )

                if (!wasFavorite) {
                    val serverPlaylist = musicRepository.addTrackToPlaylist(
                        playlistId = favoritePlaylist.id,
                        trackId = track.id,
                    )
                    serverPlaylist.normalizedFavoriteResponse(
                        localState = optimisticPlaylist,
                        trackId = track.id,
                        shouldContain = true,
                    )
                } else {
                    if (playlistTrackIds.isEmpty()) {
                        throw IllegalStateException("Favorites entry id was not loaded.")
                    }
                    var nextPlaylist = optimisticPlaylist
                    playlistTrackIds.forEach { playlistTrackId ->
                        nextPlaylist = musicRepository.removeTrackFromPlaylist(
                            playlistId = favoritePlaylist.id,
                            playlistTrackId = playlistTrackId,
                        ).normalizedFavoriteResponse(
                            localState = nextPlaylist,
                            trackId = track.id,
                            shouldContain = false,
                        )
                    }
                    nextPlaylist
                }
            }.onSuccess { updatedPlaylist ->
                accessToken = authRepository.accessToken()
                val nextPlaylists = playlists
                    .sanitizeClientPlaylists()
                    .updateOrAppendPlaylist(updatedPlaylist)
                playlists = nextPlaylists
                updateKnownTrackLikedState(track.id, isLiked = track.id in updatedPlaylist.trackIds)
                libraryCacheStore.saveLibrary(
                    playlists = nextPlaylists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )
            }.onFailure { error ->
                playlists = previousPlaylists
                tracks = previousTracks
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            playlistAddInProgress = false
        }
    }

    val currentTrackFavorite = playerState.currentTrack?.id?.let { trackId ->
        playlists.firstOrNull { it.isFavoritesPlaylist() }?.trackIds?.contains(trackId) == true
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

    LaunchedEffect(requestedQueueAdvance) {
        if (requestedQueueAdvance > 0) {
            skipInQueue(direction = 1)
        }
    }

    LaunchedEffect(requestedQueueWrapPause) {
        if (requestedQueueWrapPause > 0) {
            pauseAtQueueStart()
        }
    }

    LaunchedEffect(requestedCurrentTrackRestart) {
        if (requestedCurrentTrackRestart > 0) {
            val currentTrack = playerState.currentTrack ?: return@LaunchedEffect
            playQueuedTrack(
                track = currentTrack,
                queue = playbackQueue.takeIf { it.tracks.isNotEmpty() }
                    ?: PlaybackQueue(tracks = listOf(currentTrack), currentIndex = 0),
                resumePositionMs = 0L,
            )
        }
    }

    LaunchedEffect(account?.id, syncMode, tracks, playlists) {
        val savedPlayback = pendingPlaybackRestore ?: return@LaunchedEffect
        val restored = savedPlayback.restorePlayback(tracks = tracks, playlists = playlists)
            ?: return@LaunchedEffect
        playbackQueue = restored.queue
        playerState = restored.playerState
        restored.playerState.currentTrack?.let { track ->
            loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
        }
        pendingPlaybackRestore = null
    }

    fun loadPlaylistPickerPlaylists(force: Boolean = false) {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before editing playlists."
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
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before editing playlists."
            return
        }
        trackForPlaylistAdd = track
        duplicatePlaylistForAdd = null
        playlistPickerPlaylists = playlists.sanitizeClientPlaylists().filterNot { it.isFavoritesPlaylist() }.map { playlist ->
            playlist.copy(trackIds = emptyList(), playlistTrackIds = emptyList(), playlistTrackIdsByTrackId = emptyMap())
        }
        loadPlaylistPickerPlaylists(force = true)
    }

    LaunchedEffect(account?.id, offlineOnly, syncMode) {
        if (canUseServerRequests()) {
            loadPlaylistPickerPlaylists(force = false)
        }
    }

    LaunchedEffect(account?.id, offlineOnly, playlists.map { "${it.id}:${it.trackIds.size}:${it.trackCount}" }) {
        val favorites = playlists.firstOrNull { it.isFavoritesPlaylist() } ?: return@LaunchedEffect
        if (!canUseServerRequests() || favorites.trackCount <= favorites.trackIds.size) {
            return@LaunchedEffect
        }
        runCatching {
            musicRepository.favoritesPlaylistPayload(favorites)
        }.onSuccess { payload ->
            accessToken = authRepository.accessToken()
            applyPlaylistPayload(payload)
        }.onFailure { error ->
            if (error !is CancellationException) {
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
    }

    LaunchedEffect(account?.id, offlineOnly, syncMode, playerState.currentTrack?.id) {
        val currentTrack = playerState.currentTrack ?: return@LaunchedEffect
        if (!canUseServerRequests()) {
            return@LaunchedEffect
        }
        runCatching {
            loadFavoritesPlaylistForTrack(currentTrack)
        }.onFailure { error ->
            if (error !is CancellationException) {
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
    }

    fun createPlaylist(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            libraryError = "Playlist name is required."
            return
        }
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before creating playlists."
            return
        }

        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.createPlaylist(trimmedName)
            }.onSuccess {
                accessToken = authRepository.accessToken()
                loadLibrary()
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
    }

    fun updatePlaylistDetails(playlist: Playlist, name: String, description: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            libraryError = "Playlist name is required."
            return
        }
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before editing playlists."
            return
        }

        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.updatePlaylist(
                    playlistId = playlist.id,
                    name = trimmedName,
                    description = description,
                )
            }.onSuccess { updatedPlaylist ->
                accessToken = authRepository.accessToken()
                if (updatedPlaylist != null) {
                    val nextPlaylists = playlists.updatePlaylist(updatedPlaylist)
                    playlists = nextPlaylists
                    libraryCacheStore.saveLibrary(
                        playlists = nextPlaylists,
                        tracks = tracks,
                        savedAlbums = savedAlbums,
                    )
                } else {
                    loadLibrary()
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
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

    fun addTrackToPlaylist(playlist: Playlist, track: Track) {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before editing playlists."
            return
        }
        if (playlistAddInProgress) {
            return
        }

        playlistAddInProgress = true
        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.addTrackToPlaylist(
                    playlistId = playlist.id,
                    trackId = track.id,
                )
            }.onSuccess {
                accessToken = authRepository.accessToken()
                val updatedPlaylist = (it ?: playlist.copy(
                    trackIds = playlist.trackIds + track.id,
                    trackCount = playlist.trackCount.coerceAtLeast(playlist.trackIds.size) + 1,
                )).copy(
                    isOfflineEnabled = it?.isOfflineEnabled == true || playlist.isOfflineEnabled,
                    isFavorites = it?.isFavorites == true || playlist.isFavorites,
                )
                val nextPlaylists = playlists.updateOrAppendPlaylist(updatedPlaylist)
                playlists = nextPlaylists
                if (tracks.none { existingTrack -> existingTrack.id == track.id }) {
                    tracks = tracks + track
                }
                playlistPickerPlaylists = playlistPickerPlaylists.updateOrAppendPlaylist(
                    updatedPlaylist.copy(trackIds = emptyList(), playlistTrackIds = emptyList(), playlistTrackIdsByTrackId = emptyMap()),
                )
                libraryCacheStore.saveLibrary(
                    playlists = nextPlaylists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )
                if (playlist.isOfflineEnabled && track.downloadState != DownloadState.Downloaded) {
                    fun setAddedTrackDownloadState(downloadState: DownloadState) {
                        fun List<Track>.updatedDownloadState(): List<Track> {
                            return map { item ->
                                if (item.id == track.id) item.copy(downloadState = downloadState) else item
                            }
                        }

                        tracks = tracks.updatedDownloadState()
                        albumTracksById = albumTracksById.mapValues { (_, albumTracks) ->
                            albumTracks.updatedDownloadState()
                        }
                        playbackQueue = playbackQueue.copy(
                            tracks = playbackQueue.tracks.updatedDownloadState(),
                            sourceTracks = playbackQueue.sourceTracks.updatedDownloadState(),
                        )
                        playerState.currentTrack?.takeIf { it.id == track.id }?.let { currentTrack ->
                            playerState = playerState.copy(currentTrack = currentTrack.copy(downloadState = downloadState))
                        }
                        libraryCacheStore.saveLibrary(
                            playlists = playlists,
                            tracks = tracks,
                            savedAlbums = savedAlbums,
                        )
                    }

                    setAddedTrackDownloadState(DownloadState.Queued)
                    runCatching {
                        ensureTrackDownloaded(track)
                        cacheDownloadedAssets(track)
                    }.onSuccess {
                        setAddedTrackDownloadState(DownloadState.Downloaded)
                        refreshStorageStats()
                    }.onFailure {
                        setAddedTrackDownloadState(DownloadState.NotDownloaded)
                    }
                }
                trackForPlaylistAdd = null
                duplicatePlaylistForAdd = null
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
            playlistAddInProgress = false
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        if (playlist.isFavoritesPlaylist()) {
            return
        }
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before deleting playlists."
            return
        }

        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.deletePlaylist(playlist.id)
            }.onSuccess {
                accessToken = authRepository.accessToken()
                playlists = playlists.filterNot { it.id == playlist.id }
                playlistPickerPlaylists = playlistPickerPlaylists.filterNot { it.id == playlist.id }
                libraryCacheStore.saveLibrary(
                    playlists = playlists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )
                if (destination.playlistId == playlist.id) {
                    navigateTo(AppDestination(tab = AppTab.Library))
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
    }

    fun requestAddTrackToPlaylist(playlist: Playlist, track: Track, allowDuplicate: Boolean = false) {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before editing playlists."
            return
        }
        if (allowDuplicate) {
            addTrackToPlaylist(playlist, track)
            return
        }
        if (playlistAddInProgress) {
            return
        }

        playlistAddInProgress = true
        scope.launch {
            libraryError = null
            runCatching {
                loadPlaylistForMembershipCheck(playlist, track)
            }.onSuccess { checkedPlaylist ->
                if (track.id in checkedPlaylist.trackIds) {
                    duplicatePlaylistForAdd = checkedPlaylist
                    playlistAddInProgress = false
                } else {
                    playlistAddInProgress = false
                    addTrackToPlaylist(checkedPlaylist, track)
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
                playlistAddInProgress = false
            }
        }
    }

    fun removeTrackFromPlaylist(playlist: Playlist, playlistTrackId: String) {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before editing playlists."
            return
        }
        val removedIndex = playlist.playlistTrackIds.indexOf(playlistTrackId)
        val removedTrackId = playlist.trackIds.getOrNull(removedIndex)
            ?: playlist.playlistTrackIdsByTrackId.entries
                .firstOrNull { it.value == playlistTrackId }
                ?.key

        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.removeTrackFromPlaylist(
                    playlistId = playlist.id,
                    playlistTrackId = playlistTrackId,
                )
            }.onSuccess {
                accessToken = authRepository.accessToken()
                val nextPlaylists = playlists.map { item ->
                    if (item.id == playlist.id) {
                        item.copy(
                            trackIds = if (removedIndex >= 0) {
                                item.trackIds.filterIndexed { index, _ -> index != removedIndex }
                            } else {
                                item.trackIds
                            },
                            playlistTrackIds = item.playlistTrackIds.filterNot { it == playlistTrackId },
                            playlistTrackIdsByTrackId = item.playlistTrackIdsByTrackId.filterValues { it != playlistTrackId },
                        )
                    } else {
                        item
                    }
                }
                playlists = nextPlaylists
                removedTrackId?.let { trackId ->
                    val removedTrack = tracks.firstOrNull { it.id == trackId }
                    val stillInDownloadedPlaylist = nextPlaylists.any { item ->
                        trackId in item.trackIds && item.isOfflineEnabled
                    }
                    if (removedTrack?.downloadState == DownloadState.Downloaded && !stillInDownloadedPlaylist) {
                        musicRepository.removeDownloadedTrack(trackId)
                        tracks = tracks.map { track ->
                            if (track.id == trackId) track.copy(downloadState = DownloadState.NotDownloaded) else track
                        }
                        playbackQueue = playbackQueue.copy(
                            tracks = playbackQueue.tracks.map { track ->
                                if (track.id == trackId) track.copy(downloadState = DownloadState.NotDownloaded) else track
                            },
                        )
                        playerState.currentTrack?.takeIf { it.id == trackId }?.let { currentTrack ->
                            playerState = playerState.copy(
                                currentTrack = currentTrack.copy(downloadState = DownloadState.NotDownloaded),
                            )
                        }
                        refreshStorageStats()
                    }
                }
                libraryCacheStore.saveLibrary(
                    playlists = nextPlaylists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
    }

    fun reorderPlaylistTracks(playlist: Playlist, playlistTrackIds: List<String>) {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before editing playlists."
            return
        }
        if (playlistTrackIds.isEmpty() || playlistTrackIds.size != playlistTrackIds.distinct().size) {
            libraryError = "Could not reorder this playlist."
            return
        }

        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.reorderPlaylistTracks(
                    playlistId = playlist.id,
                    playlistTrackIds = playlistTrackIds,
                )
            }.onSuccess { updatedPlaylist ->
                accessToken = authRepository.accessToken()
                if (updatedPlaylist != null) {
                    val nextPlaylists = playlists.updatePlaylist(updatedPlaylist)
                    playlists = nextPlaylists
                    libraryCacheStore.saveLibrary(
                        playlists = nextPlaylists,
                        tracks = tracks,
                        savedAlbums = savedAlbums,
                    )
                } else {
                    loadLibrary()
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
    }

    fun updateTrackDownloadState(trackId: String, downloadState: DownloadState) {
        fun List<Track>.updatedDownloadState(): List<Track> {
            return map { track ->
                if (track.id == trackId) {
                    track.copy(downloadState = downloadState)
                } else {
                    track
                }
            }
        }

        tracks = tracks.updatedDownloadState()
        albumTracksById = albumTracksById.mapValues { (_, albumTracks) ->
            albumTracks.updatedDownloadState()
        }
        playbackQueue = playbackQueue.copy(
            tracks = playbackQueue.tracks.updatedDownloadState(),
            sourceTracks = playbackQueue.sourceTracks.updatedDownloadState(),
        )
        playerState.currentTrack?.takeIf { it.id == trackId }?.let { currentTrack ->
            playerState = playerState.copy(currentTrack = currentTrack.copy(downloadState = downloadState))
        }
    }

    fun updateAlbumOfflineFlag(albumId: String, enabled: Boolean) {
        fun LibraryAlbum.updated(): LibraryAlbum {
            return if (id == albumId) copy(isOfflineEnabled = enabled) else this
        }

        offlineAlbumIds = if (enabled) {
            offlineAlbumIds + albumId
        } else {
            offlineAlbumIds - albumId
        }
        if (enabled) {
            userPreferencesStore.addOfflineAlbumId(albumId)
        } else {
            userPreferencesStore.removeOfflineAlbumId(albumId)
        }
        albums = albums.map { it.updated() }
        savedAlbums = savedAlbums.map { it.updated() }
        albumsByArtist = albumsByArtist.mapValues { (_, artistAlbums) -> artistAlbums.map { it.updated() } }
        appearsOnByArtist = appearsOnByArtist.mapValues { (_, artistAlbums) -> artistAlbums.map { it.updated() } }
    }

    fun stopPlaylistDownload(playlist: Playlist) {
        playlistDownloadJobs[playlist.id]?.cancel()
        playlistDownloadJobs = playlistDownloadJobs - playlist.id
        val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        playlists = playlists.updatePlaylist(
            updatedPlaylist = currentPlaylist.copy(isOfflineEnabled = false),
            preserveOfflineFlag = false,
        )
        currentPlaylist.trackIds.forEach { trackId ->
            val currentState = tracks.firstOrNull { it.id == trackId }?.downloadState
            if (currentState == DownloadState.Queued) {
                updateTrackDownloadState(trackId, DownloadState.NotDownloaded)
            }
        }
        libraryCacheStore.saveLibrary(
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
        )
        refreshStorageStats()
    }

    fun stopAlbumDownload(album: LibraryAlbum, albumTracks: List<Track>) {
        albumDownloadJobs[album.id]?.cancel()
        albumDownloadJobs = albumDownloadJobs - album.id
        updateAlbumOfflineFlag(album.id, enabled = false)
        val affectedTrackIds = (albumTracks + albumTracksById[album.id].orEmpty())
            .map { it.id }
            .toSet()
        affectedTrackIds.forEach { trackId ->
            val currentState = tracks.firstOrNull { it.id == trackId }?.downloadState
            if (currentState == DownloadState.Queued) {
                updateTrackDownloadState(trackId, DownloadState.NotDownloaded)
            }
        }
        libraryCacheStore.saveLibrary(
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
        )
        refreshStorageStats()
    }

    fun downloadTrack(track: Track) {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before downloading tracks."
            return
        }
        if (track.downloadState == DownloadState.Downloaded) {
            return
        }

        updateTrackDownloadState(track.id, DownloadState.Queued)
        scope.launch {
            libraryError = null
            runCatching {
                ensureTrackDownloaded(track)
                cacheDownloadedAssets(track)
            }.onSuccess {
                accessToken = authRepository.accessToken()
                val updatedTracks = musicRepository.withOfflineState(tracks)
                tracks = updatedTracks
                val confirmedState = updatedTracks.firstOrNull { it.id == track.id }?.downloadState
                    ?: DownloadState.Downloaded
                updateTrackDownloadState(track.id, confirmedState)
                libraryCacheStore.saveLibrary(
                    playlists = playlists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )
                refreshStorageStats()
            }.onFailure { error ->
                updateTrackDownloadState(track.id, DownloadState.NotDownloaded)
                tracks = musicRepository.withOfflineState(tracks)
                libraryError = error.userMessage()
            }
        }
    }

    fun downloadPlaylist(playlist: Playlist) {
        if (playlistDownloadJobs[playlist.id]?.isActive == true) {
            stopPlaylistDownload(playlist)
            return
        }
        if (!canUseNetworkForCollectionDownloads()) {
            libraryError = if (canUseServerRequests()) {
                "Enable cellular downloads or connect to Wi-Fi before downloading playlists."
            } else {
                "Connect to the server before downloading playlists."
            }
            return
        }

        val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        val optimisticPlaylist = currentPlaylist.copy(isOfflineEnabled = true)
        playlists = playlists.updateOrAppendPlaylist(optimisticPlaylist)
        currentPlaylist.tracksFrom(tracks)
            .filter { it.downloadState != DownloadState.Downloaded }
            .forEach { track -> updateTrackDownloadState(track.id, DownloadState.Queued) }
        libraryCacheStore.saveLibrary(
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
        )

        val job = scope.launch {
            try {
                libraryError = null
                val source = loadPlaylistDownloadSource(
                    musicRepository = musicRepository,
                    playlist = optimisticPlaylist,
                    pageLimit = DETAIL_TRACK_PAGE_LIMIT,
                    mergePage = { loadedPlaylist, payload, append ->
                        applyPlaylistTrackPage(
                            playlist = loadedPlaylist,
                            payload = payload,
                            append = append,
                        ) ?: loadedPlaylist
                    },
                    fallbackTracks = { loadedPlaylist -> loadedPlaylist.tracksFrom(tracks) },
                )
                source.loadError?.let(::markServerUnavailable)
                if (source.loadError != null && source.tracks.isEmpty()) {
                    libraryError = source.loadError.userMessage()
                    return@launch
                }
                val offlinePlaylist = source.playlist.copy(isOfflineEnabled = true)
                playlists = playlists.updateOrAppendPlaylist(offlinePlaylist)
                val pendingTracks = source.tracks.filter { track ->
                    track.downloadState != DownloadState.Downloaded
                }
                if (pendingTracks.isEmpty()) {
                    libraryCacheStore.saveLibrary(
                        playlists = playlists,
                        tracks = tracks,
                        savedAlbums = savedAlbums,
                    )
                    return@launch
                }

                val result = downloadTracksSequentially(
                    tracks = pendingTracks,
                    canContinue = ::canUseNetworkForCollectionDownloads,
                    onQueued = { track -> updateTrackDownloadState(track.id, DownloadState.Queued) },
                    downloadTrackAssets = { track ->
                        ensureTrackDownloaded(track)
                        cacheDownloadedAssets(track)
                    },
                    onDownloaded = { track -> updateTrackDownloadState(track.id, DownloadState.Downloaded) },
                    onFailed = { track -> updateTrackDownloadState(track.id, DownloadState.NotDownloaded) },
                )
                if (result.interruptedByPolicy) {
                    return@launch
                }

                accessToken = authRepository.accessToken()
                val updatedTracks = musicRepository.withOfflineState(tracks)
                tracks = updatedTracks
                libraryCacheStore.saveLibrary(
                    playlists = playlists,
                    tracks = updatedTracks,
                    savedAlbums = savedAlbums,
                )
                refreshStorageStats()

                if (result.failedTrackIds.isNotEmpty()) {
                    libraryError = "Some playlist tracks were not downloaded."
                }
            } finally {
                playlistDownloadJobs = playlistDownloadJobs - playlist.id
            }
        }
        playlistDownloadJobs = playlistDownloadJobs + (playlist.id to job)
    }

    fun downloadAlbum(album: LibraryAlbum, albumTracks: List<Track>) {
        if (albumDownloadJobs[album.id]?.isActive == true) {
            stopAlbumDownload(album, albumTracks)
            return
        }
        if (!canUseNetworkForCollectionDownloads()) {
            libraryError = if (canUseServerRequests()) {
                "Enable cellular downloads or connect to Wi-Fi before downloading albums."
            } else {
                "Connect to the server before downloading albums."
            }
            return
        }

        val wasSaved = album.savedByCurrentUser ||
            albums.any { it.id == album.id && it.savedByCurrentUser } ||
            savedAlbums.any { it.id == album.id && it.savedByCurrentUser }
        val optimisticAlbum = album.copy(
            savedByCurrentUser = wasSaved,
            isOfflineEnabled = true,
        )
        updateAlbumOfflineFlag(album.id, enabled = true)
        albums = albums.updateOrAppendAlbum(optimisticAlbum)
        savedAlbums = savedAlbums.updateAlbum(optimisticAlbum.copy(savedByCurrentUser = wasSaved))
        albumsByArtist = albumsByArtist.mapValues { (_, artistAlbums) ->
            artistAlbums.updateOrAppendAlbum(optimisticAlbum)
        }
        appearsOnByArtist = appearsOnByArtist.mapValues { (_, artistAlbums) ->
            artistAlbums.updateOrAppendAlbum(optimisticAlbum)
        }
        (albumTracks.takeIf { it.isNotEmpty() } ?: albumTracksById[album.id].orEmpty())
            .filter { it.downloadState != DownloadState.Downloaded }
            .forEach { track -> updateTrackDownloadState(track.id, DownloadState.Queued) }
        libraryCacheStore.saveLibrary(
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
        )

        val job = scope.launch {
            try {
                libraryError = null
                val source = loadAlbumDownloadSource(
                    musicRepository = musicRepository,
                    album = album,
                    initialTracks = albumTracks,
                    pageLimit = DETAIL_TRACK_PAGE_LIMIT,
                )
                source.loadError?.let(::markServerUnavailable)
                if (source.loadError != null && source.tracks.isEmpty()) {
                    libraryError = source.loadError.userMessage()
                }
                val sourceTracks = source.tracks
                if (sourceTracks.isNotEmpty()) {
                    albumTracksById = albumTracksById + (album.id to sourceTracks)
                    mergeLoadedTracks(sourceTracks)
                }
                val pendingTracks = sourceTracks.filter { it.downloadState != DownloadState.Downloaded }
                val offlineAlbum = album.copy(
                    savedByCurrentUser = wasSaved,
                    isOfflineEnabled = true,
                )
                updateAlbumOfflineFlag(album.id, enabled = true)
                albums = albums.updateOrAppendAlbum(offlineAlbum)
                savedAlbums = savedAlbums.updateAlbum(
                    offlineAlbum.copy(savedByCurrentUser = wasSaved),
                )
                albumsByArtist = albumsByArtist.mapValues { (_, artistAlbums) ->
                    artistAlbums.updateOrAppendAlbum(offlineAlbum)
                }
                appearsOnByArtist = appearsOnByArtist.mapValues { (_, artistAlbums) ->
                    artistAlbums.updateOrAppendAlbum(offlineAlbum)
                }
                if (pendingTracks.isEmpty()) {
                    return@launch
                }

                val result = downloadTracksSequentially(
                    tracks = pendingTracks,
                    canContinue = ::canUseNetworkForCollectionDownloads,
                    onQueued = { track -> updateTrackDownloadState(track.id, DownloadState.Queued) },
                    downloadTrackAssets = { track ->
                        ensureTrackDownloaded(track)
                        cacheDownloadedAssets(track)
                    },
                    onDownloaded = { track -> updateTrackDownloadState(track.id, DownloadState.Downloaded) },
                    onFailed = { track -> updateTrackDownloadState(track.id, DownloadState.NotDownloaded) },
                )
                if (result.interruptedByPolicy) {
                    return@launch
                }

                accessToken = authRepository.accessToken()
                val updatedTracks = musicRepository.withOfflineState(tracks)
                tracks = updatedTracks
                albumTracksById = albumTracksById + (
                    album.id to sourceTracks.map { sourceTrack ->
                        updatedTracks.firstOrNull { it.id == sourceTrack.id } ?: sourceTrack
                    }
                    )
                libraryCacheStore.saveLibrary(
                    playlists = playlists,
                    tracks = updatedTracks,
                    savedAlbums = savedAlbums,
                )
                refreshStorageStats()
                if (result.failedTrackIds.isNotEmpty()) {
                    libraryError = "Some album tracks were not downloaded."
                }
            } finally {
                albumDownloadJobs = albumDownloadJobs - album.id
            }
        }
        albumDownloadJobs = albumDownloadJobs + (album.id to job)
    }

    fun resumePendingOfflineDownloads() {
        if (!canUseNetworkForCollectionDownloads()) {
            return
        }

        playlists
            .filter { playlist -> playlist.isOfflineEnabled }
            .forEach { playlist ->
                if (playlistDownloadJobs[playlist.id]?.isActive == true) {
                    return@forEach
                }
                val playlistTracks = playlist.tracksFrom(tracks)
                val downloadState = aggregateDownloadState(
                    isOfflineEnabled = playlist.isOfflineEnabled,
                    expectedTrackCount = playlist.trackCount.coerceAtLeast(playlist.trackIds.size),
                    loadedTrackCount = playlist.trackIds.size,
                    tracks = playlistTracks,
                )
                if (downloadState != DownloadState.Downloaded) {
                    downloadPlaylist(playlist)
                }
            }

        (albums + savedAlbums + albumsByArtist.values.flatten() + appearsOnByArtist.values.flatten())
            .distinctBy { it.id }
            .filter { album -> album.isOfflineEnabled || album.id in offlineAlbumIds }
            .forEach { album ->
                if (albumDownloadJobs[album.id]?.isActive == true) {
                    return@forEach
                }
                val albumTracks = albumTracksById[album.id].orEmpty()
                val downloadState = aggregateDownloadState(
                    isOfflineEnabled = true,
                    expectedTrackCount = album.trackCount,
                    loadedTrackCount = albumTracks.size,
                    tracks = albumTracks,
                )
                if (downloadState != DownloadState.Downloaded) {
                    downloadAlbum(album, albumTracks)
                }
            }
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

    LaunchedEffect(account?.id, offlineOnly) {
        while (true) {
            delay(5_000)
            resumePendingOfflineDownloads()
            delay(55_000)
        }
    }

    fun clearDownloads() {
        scope.launch {
            libraryError = null
            albumDownloadJobs.values.forEach { it.cancel() }
            playlistDownloadJobs.values.forEach { it.cancel() }
            albumDownloadJobs = emptyMap()
            playlistDownloadJobs = emptyMap()
            val shouldStopPlayback = playerState.streamUrl?.startsWith("file:", ignoreCase = true) == true
            val downloadedArtworkKeys = downloadedArtworkKeys()
            val downloadedArtworkCacheKeys = artworkCacheKeysFor(downloadedArtworkKeys)
            runCatching {
                musicRepository.clearDownloads()
                offlineLyricsStore.clear()
                artworkCacheStore.clearKeys(downloadedArtworkCacheKeys)
            }.onSuccess {
                artworkBitmaps = artworkBitmaps.filterKeys { artworkSourceKey(it) !in downloadedArtworkKeys }
                lyricsByTrackId = lyricsByTrackId.filterKeys { trackId -> tracks.none { it.id == trackId && it.downloadState == DownloadState.Downloaded } }
                tracks = tracks.map { track -> track.copy(downloadState = DownloadState.NotDownloaded) }
                albums = albums.map { album -> album.copy(isOfflineEnabled = false) }
                savedAlbums = savedAlbums.map { album -> album.copy(isOfflineEnabled = false) }
                albumsByArtist = albumsByArtist.mapValues { (_, artistAlbums) ->
                    artistAlbums.map { album -> album.copy(isOfflineEnabled = false) }
                }
                appearsOnByArtist = appearsOnByArtist.mapValues { (_, artistAlbums) ->
                    artistAlbums.map { album -> album.copy(isOfflineEnabled = false) }
                }
                offlineAlbumIds = emptySet()
                userPreferencesStore.clearOfflineAlbumIds()
                playlists = playlists.map { playlist -> playlist.copy(isOfflineEnabled = false) }
                playbackQueue = playbackQueue.copy(
                    tracks = playbackQueue.tracks.map { track ->
                        track.copy(downloadState = DownloadState.NotDownloaded)
                    },
                )
                val updatedCurrentTrack = playerState.currentTrack?.copy(downloadState = DownloadState.NotDownloaded)
                if (shouldStopPlayback) {
                    clearGaplessPlaybackState()
                    prefetchedPlaybackUrls = emptyMap()
                    playbackUrlPrefetchesInProgress = emptySet()
                    playerState = playerState.copy(
                        currentTrack = updatedCurrentTrack,
                        isPlaying = false,
                        streamUrl = null,
                    )
                    playbackStateStore.clear()
                } else {
                    playerState = playerState.copy(currentTrack = updatedCurrentTrack)
                }
                if (!canUseServerRequests()) {
                    artists = tracks.downloadedArtists()
                    albums = emptyList()
                }
                libraryCacheStore.saveLibrary(
                    playlists = playlists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )
                libraryNotice = "Downloads cleared."
                refreshStorageStats()
            }.onFailure { error ->
                libraryError = error.userMessage()
            }
        }
    }

    fun clearAppCache() {
        scope.launch {
            val downloadedTracks = tracks.filter { track -> track.downloadState == DownloadState.Downloaded }
            val downloadedTrackIds = downloadedTracks.map { it.id }.toSet()
            val offlinePlaylists = playlists.filter { playlist -> playlist.trackIds.any { it in downloadedTrackIds } }
            val retainedArtworkKeys = downloadedArtworkKeys(downloadedTracks)
            val retainedArtworkCacheKeys = artworkCacheKeysFor(retainedArtworkKeys)
            artworkCacheStore.clearExcept(retainedArtworkCacheKeys)
            musicRepository.clearMusicCache()
            libraryCacheStore.clear()
            if (downloadedTracks.isNotEmpty()) {
                libraryCacheStore.saveLibrary(
                    playlists = offlinePlaylists,
                    tracks = downloadedTracks,
                    savedAlbums = savedAlbums,
                )
            }
            playbackStateStore.clear()
            artworkBitmaps = artworkBitmaps.filterKeys { artworkSourceKey(it) in retainedArtworkKeys }
            artworkLoadsInProgress = emptySet()
            profileAvatarBitmap = null
            profileAvatarLoadKey = null
            prefetchedPlaybackUrls = emptyMap()
            libraryNotice = "Cache cleared."
            refreshStorageStats()
            account?.let(::loadProfileAvatar)
        }
    }

    fun connectLastFm() {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before linking Last.fm."
            return
        }

        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.lastFmAuthRequest()
            }.onSuccess { authRequest ->
                accessToken = authRepository.accessToken()
                pendingLastFmToken = authRequest.token
                waitingForLastFmSession = true
                lastFmAuthTokenStore.saveToken(authRequest.token)
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authRequest.url)))
                }.onFailure { error ->
                    libraryError = error.userMessage()
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
    }

    fun completeLastFmSession() {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before completing Last.fm setup."
            return
        }
        val token = pendingLastFmToken ?: lastFmAuthTokenStore.token()
        if (token.isNullOrBlank()) {
            libraryError = "Request a Last.fm token first."
            return
        }

        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.completeLastFmSession(token)
            }.onSuccess { connection ->
                accessToken = authRepository.accessToken()
                pendingLastFmToken = null
                waitingForLastFmSession = false
                lastFmAuthTokenStore.clear()
                lastFmConnection = connection.copy(pendingScrobbles = pendingPlayEventCount)
                userPreferencesStore.setLastFmConnection(lastFmConnection)
            }.onFailure { error ->
                markServerUnavailable(error)
                val existingConnection = runCatching { musicRepository.lastFmSession() }.getOrNull()
                if (
                    existingConnection?.state == ScrobbleState.Ready &&
                    !existingConnection.username.isNullOrBlank()
                ) {
                    accessToken = authRepository.accessToken()
                    pendingLastFmToken = null
                    waitingForLastFmSession = false
                    lastFmAuthTokenStore.clear()
                    lastFmConnection = existingConnection.copy(pendingScrobbles = pendingPlayEventCount)
                    userPreferencesStore.setLastFmConnection(lastFmConnection)
                } else {
                    libraryError = if (error.userMessage().contains("Unauthorized Token", ignoreCase = true)) {
                        pendingLastFmToken = null
                        waitingForLastFmSession = false
                        lastFmAuthTokenStore.clear()
                        "Last.fm token expired. Start Last.fm linking again."
                    } else {
                        error.userMessage()
                    }
                }
            }
        }
    }

    fun disconnectLastFm() {
        if (!canUseServerRequests()) {
            libraryError = "Connect to the server before unlinking Last.fm."
            return
        }

        scope.launch {
            libraryError = null
            runCatching {
                musicRepository.disconnectLastFm()
            }.onSuccess { connection ->
                accessToken = authRepository.accessToken()
                pendingLastFmToken = null
                waitingForLastFmSession = false
                lastFmAuthTokenStore.clear()
                lastFmConnection = connection.copy(pendingScrobbles = pendingPlayEventCount)
                userPreferencesStore.clearLastFmConnection()
            }.onFailure { error ->
                markServerUnavailable(error)
                libraryError = error.userMessage()
            }
        }
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

    LaunchedEffect(syncMode, offlineOnly) {
        if (offlineOnly || syncMode == SyncMode.Offline || syncMode == SyncMode.OfflineOnly) {
            enforceOfflinePlaybackAvailability()
        }
    }

    fun setUseLocalBackend(enabled: Boolean) {
        if (useLocalBackend == enabled) {
            return
        }

        clearNowPlayingEvent(activePlayEventState.value)
        useLocalBackend = enabled
        userPreferencesStore.setUseLocalBackend(enabled)
        authRepository.setApiBaseUrl(AppConfig.apiBaseUrl(enabled))
        apiBaseUrl = authRepository.apiBaseUrl()

        if (!offlineOnly) {
            syncMode = SyncMode.Offline
            account?.let { loadLibrary() }
        }
    }

    LaunchedEffect(account?.id, offlineOnly) {
        if (account != null && !offlineOnly) {
            loadLibrary()
        } else if (offlineOnly) {
            syncMode = SyncMode.OfflineOnly
            libraryError = null
        }
    }

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
                enforceOfflinePlaybackAvailability()
            }
        },
        onReconnected = {
            if (account != null && !offlineOnly && libraryLoadJob == null) {
                loadLibrary()
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

    BackHandler(enabled = fullPlayerOpen) {
        fullPlayerOpen = false
    }

    BackHandler(enabled = queueOpen) {
        queueOpen = false
    }

    BackHandler(enabled = account != null && backStack.isNotEmpty() && !fullPlayerOpen) {
        goBack()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                account == null -> SignInScreen(
                    isLoading = signingIn,
                    errorMessage = authError,
                    useLocalBackend = useLocalBackend,
                    onUseLocalBackendChange = ::setUseLocalBackend,
                    onGoogleSignIn = {
                        signingIn = true
                        authError = null
                        googleSignInLauncher.launch(googleSignInTokenProvider.signInIntent())
                    },
                    canContinueOffline = canContinueOffline,
                    onContinueOffline = ::continueOffline,
                )

                else -> MainShell(
                    account = account ?: OfflineAccount,
                    destination = destination,
                    playlists = playlists,
                    tracks = tracks,
                    recentTracks = recentTracks,
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
                    albumTrackLoadsInProgress = albumTrackLoadsInProgress,
                    playlistTrackLoadsInProgress = playlistTrackLoadsInProgress,
                    artistListLoadingMore = artistListLoadingMore,
                    albumListLoadingMore = albumListLoadingMore,
                    artistListHasMore = artistListHasMore,
                    albumListHasMore = albumListHasMore,
                    albumTrackHasMoreById = albumTrackHasMoreById,
                    playlistTrackHasMoreById = playlistTrackHasMoreById,
                    albumTracksById = albumTracksById,
                    searchQuery = searchQuery,
                    searchFocusRequestSerial = searchFocusRequestSerial,
                    searchResults = searchResults,
                    searchLoading = searchLoading,
                    recentItems = recentItems,
                    playerState = playerState,
                    playbackBufferedFraction = if (playerState.currentTrack?.id?.let { it in offlinePlayableTrackIds } == true) {
                        1f
                    } else {
                        playbackBufferedFraction
                    },
                    playerError = playerError,
                    fullPlayerOpen = fullPlayerOpen,
                    queueOpen = queueOpen,
                    artworkBitmap = artworkBitmaps.artworkBitmap(playerState.currentTrack?.listArtworkKey(), ArtworkImageSize.FullPlayer),
                    artworkBitmaps = artworkBitmaps,
                    profileAvatarBitmap = profileAvatarBitmap,
                    canSkipTracks = playbackQueue.canSkip,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    showLyrics = showLyrics,
                    currentLyrics = playerState.currentTrack?.id?.let(lyricsByTrackId::get),
                    currentLyricsUnavailable = playerState.currentTrack?.id
                        ?.let { it in lyricsUnavailableIds } == true,
                    currentLyricsLoading = playerState.currentTrack?.id
                        ?.let { it in lyricsLoadsInProgress } == true,
                    activePlaylistId = playbackQueue.playlistId,
                    activeAlbumId = playbackQueue.sourceId.takeIf { playbackQueue.sourceType == PlaybackSourceType.Album },
                    queueTracks = playbackQueue.tracks,
                    queueCurrentIndex = playbackQueue.currentIndex,
                    playbackQueueGeneration = playbackQueueGeneration,
                    artworkTransitionDirection = artworkTransitionDirection,
                    playerSourceLabel = playerState.currentTrack?.let {
                        when (playbackQueue.sourceType) {
                            PlaybackSourceType.Search -> "PLAYING FROM SEARCH"
                            PlaybackSourceType.Recent -> "PLAYING FROM LATEST TRACKS"
                            PlaybackSourceType.Playlist -> "PLAYING FROM PLAYLIST"
                            PlaybackSourceType.Album -> "PLAYING FROM ALBUM"
                        }
                    },
                    playerSourceDetail = playbackQueue.sourceTitle
                        ?: playbackQueue.playlistId?.let { playlistId ->
                            playlists.firstOrNull { it.id == playlistId }?.title
                        },
                    isLoading = libraryLoading,
                    errorMessage = libraryError.takeUnless { offlineOnly },
                    noticeMessage = libraryNotice,
                    apiBaseUrl = apiBaseUrl,
                    useLocalBackend = useLocalBackend,
                    canUseServerRequests = canUseServerRequests(),
                    syncMode = syncMode,
                    lastFmConnection = lastFmConnection,
                    pendingPlayEventCount = pendingPlayEventCount,
                    waitingForLastFmSession = waitingForLastFmSession,
                    scrobblingPaused = scrobblingPaused,
                    showLyricsSetting = showLyrics,
                    crossfadeSeconds = crossfadeSeconds,
                    equalizerAvailable = equalizerAvailable && exoPlayer.audioSessionId > 0,
                    offlineOnly = offlineOnly,
                    downloadUsingCellular = downloadUsingCellular,
                    downloadedSizeBytes = downloadedSizeBytes,
                    cacheSizeBytes = cacheSizeBytes,
                    onRetry = { loadLibrary(destination) },
                    onRefreshHome = { loadLibrary(destination) },
                    onRefreshLibrary = { loadLibrary(destination) },
                    onRefreshPlaylist = {
                        val selectedPlaylist = destination.playlistId?.let { playlistId ->
                            playlists.firstOrNull { it.id == playlistId }
                        }
                        if (selectedPlaylist != null) {
                            loadPlaylistTracks(selectedPlaylist, force = true)
                        } else {
                            loadLibrary(AppDestination(tab = AppTab.Library))
                        }
                    },
                    onRefreshArtist = { artist ->
                        loadArtistAlbums(artist, force = true)
                        loadSimilarArtists(artist, force = true)
                    },
                    onRefreshAlbum = { album -> loadAlbumTracks(album, force = true) },
                    onLoadMoreArtists = ::loadMoreArtists,
                    onLoadMoreAlbums = ::loadMoreAlbums,
                    onLoadMoreAlbumTracks = { album -> loadAlbumTracks(album, force = false) },
                    onLoadMorePlaylistTracks = { playlist -> loadPlaylistTracks(playlist, force = false) },
                    onUseLocalBackendChange = ::setUseLocalBackend,
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
                    onSelectTab = { tab ->
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
                    },
                    onSelectPlaylist = { playlist ->
                        navigateTo(AppDestination(tab = AppTab.Library, playlistId = playlist.id))
                        loadPlaylistTracks(playlist, force = playlist.isFavoritesPlaylist())
                    },
                    onShowAllArtists = {
                        val nextDestination = AppDestination(tab = AppTab.Home, homeRoute = HomeRoute.Artists)
                        navigateTo(nextDestination)
                        loadLibrary(nextDestination)
                    },
                    onSelectArtist = ::openArtist,
                    onSelectAlbum = ::openAlbum,
                    onPlayAlbum = ::playAlbum,
                    onPlayAlbumTrack = ::playAlbumFromTrack,
                    onToggleAlbumInLibrary = ::toggleAlbumInLibrary,
                    onDownloadAlbum = ::downloadAlbum,
                    onGoToAlbumArtist = ::openAlbumArtist,
                    onBack = ::goBack,
                    onSelectTrack = { track -> selectRecentTrack(track, recentTracks) },
                    onSearchQueryChange = { query -> searchQuery = query },
                    onClearRecentItems = ::clearRecentItems,
                    onRecentItemClick = { item ->
                        when (item.type) {
                            RecentLibraryItemType.Artist -> item.id?.let { artistId ->
                                val cachedArtist = (artists + searchResults.artists + similarArtistsByArtist.values.flatten())
                                    .firstOrNull { it.id == artistId }
                                    ?: LibraryArtist(id = artistId, name = item.title)
                                openArtist(cachedArtist)
                            } ?: run {
                                libraryError = "Artist id is missing for ${item.title}."
                            }
                            RecentLibraryItemType.Album -> openAlbum(
                                LibraryAlbum(
                                    id = item.id ?: item.title,
                                    title = item.title,
                                    artist = item.subtitle ?: "Unknown artist",
                                    accentColor = stableUiColor(item.id ?: item.title),
                                ),
                            )
                            RecentLibraryItemType.Track -> {
                                val track = (searchResults.tracks + tracks).firstOrNull { it.id == item.id }
                                if (track != null) {
                                    selectSearchTrack(track, searchQuery)
                                } else {
                                    searchQuery = item.title
                                }
                            }
                        }
                    },
                    onSelectSearchArtist = ::selectSearchArtist,
                    onSelectSearchAlbum = ::selectSearchAlbum,
                    onSelectSearchTrack = ::selectSearchTrack,
                    onOpenFullPlayer = {
                        playerState.currentTrack?.let { loadArtwork(it.listArtworkKey(), ArtworkImageSize.FullPlayer) }
                        fullPlayerOpen = true
                    },
                    onCloseFullPlayer = { fullPlayerOpen = false },
                    onOpenQueue = { queueOpen = true },
                    onCloseQueue = { queueOpen = false },
                    onSelectQueueTrack = ::playTrackFromCurrentQueueAt,
                    onRemoveQueueTrack = ::removeTrackFromQueueAt,
                    onReorderQueueTracks = ::reorderQueueTracks,
                    onAddCurrentTrackToPlaylist = {
                        playerState.currentTrack?.let(::openAddTrackToPlaylist)
                    },
                    onAddTrackToQueue = ::addTrackToQueue,
                    onGoToTrackArtist = ::openTrackArtist,
                    onGoToTrackAlbum = ::openTrackAlbum,
                    onToggleCurrentFavorite = {
                        playerState.currentTrack?.let(::toggleFavoriteTrack)
                    },
                    onToggleTrackFavorite = ::toggleFavoriteTrack,
                    onSkipPrevious = { skipInQueue(direction = -1) },
                    onSkipNext = { skipInQueue(direction = 1) },
                    onShuffleChange = ::setShuffleEnabled,
                    onRepeatModeChange = ::setRepeatMode,
                    onTogglePlayback = ::togglePlayback,
                    onSeek = ::seekTo,
                    onRefreshCurrentLyrics = {
                        playerState.currentTrack?.let(::refreshLyrics)
                    },
                    onSignOut = {
                        scope.launch {
                            signOutLocalSession()
                        }
                    },
                )
            }
            trackForPlaylistAdd?.let { track ->
                AddTrackToPlaylistDialog(
                    track = track,
                    playlists = playlistPickerPlaylists,
                    isLoading = playlistPickerLoading || playlistAddInProgress,
                    duplicatePlaylist = duplicatePlaylistForAdd,
                    onDismiss = {
                        trackForPlaylistAdd = null
                        duplicatePlaylistForAdd = null
                    },
                    onSelectPlaylist = { playlist -> requestAddTrackToPlaylist(playlist, track) },
                    onConfirmDuplicate = { playlist -> requestAddTrackToPlaylist(playlist, track, allowDuplicate = true) },
                    onDismissDuplicate = { duplicatePlaylistForAdd = null },
                )
            }
            if (artistChoices.isNotEmpty()) {
                ArtistChoiceDialog(
                    artists = artistChoices,
                    onDismiss = { artistChoices = emptyList() },
                    onSelectArtist = { artist ->
                        artistChoices = emptyList()
                        openArtist(artist)
                    },
                )
            }
        }
    }
}
