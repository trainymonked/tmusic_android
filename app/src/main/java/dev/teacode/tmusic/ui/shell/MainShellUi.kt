package dev.teacode.tmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.ScrobbleState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.launch

@Composable
internal fun MainShell(
    account: Account,
    destination: AppDestination,
    playlists: List<Playlist>,
    tracks: List<Track>,
    recentAlbums: List<LibraryAlbum>,
    databaseTrackCount: Int?,
    offlineAlbumIds: Set<String>,
    offlinePlayableTrackIds: Set<String>,
    artists: List<LibraryArtist>,
    albums: List<LibraryAlbum>,
    savedAlbums: List<LibraryAlbum>,
    albumsByArtist: Map<String, List<LibraryAlbum>>,
    appearsOnByArtist: Map<String, List<LibraryAlbum>>,
    looseTracksByArtist: Map<String, List<Track>>,
    similarArtistsByArtist: Map<String, List<LibraryArtist>>,
    artistAlbumLoadsInProgress: Set<String>,
    similarArtistLoadsInProgress: Set<String>,
    albumTrackLoadsInProgress: Set<String>,
    playlistTrackLoadsInProgress: Set<String>,
    artistListLoadingMore: Boolean,
    albumListLoadingMore: Boolean,
    recentAlbumsLoadingMore: Boolean,
    artistListHasMore: Boolean,
    albumListHasMore: Boolean,
    recentAlbumsHasMore: Boolean,
    albumTrackHasMoreById: Map<String, Boolean>,
    playlistTrackHasMoreById: Map<String, Boolean>,
    albumTracksById: Map<String, List<Track>>,
    searchQuery: String,
    searchFocusRequestSerial: Int,
    searchResults: LibrarySearchResults,
    searchLoading: Boolean,
    searchHasMore: Boolean,
    recentItems: List<RecentLibraryItem>,
    playerState: PlayerState,
    playbackBufferedFraction: Float,
    playerError: String?,
    fullPlayerOpen: Boolean,
    queueOpen: Boolean,
    artworkBitmap: ImageBitmap?,
    artworkBitmaps: Map<String, ImageBitmap>,
    profileAvatarBitmap: ImageBitmap?,
    canSkipTracks: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: PlaybackRepeatMode,
    showLyrics: Boolean,
    currentLyrics: TrackLyrics?,
    currentLyricsUnavailable: Boolean,
    currentLyricsLoading: Boolean,
    activePlaylistId: String?,
    activeAlbumId: String?,
    queueTracks: List<Track>,
    manualQueueFlags: List<Boolean>,
    queueCurrentIndex: Int,
    playbackQueueGeneration: Long,
    playerSourceLabel: String?,
    playerSourceDetail: String?,
    isLoading: Boolean,
    errorMessage: String?,
    noticeMessage: String?,
    useLocalBackend: Boolean,
    canUseServerRequests: Boolean,
    syncMode: SyncMode,
    lastFmConnection: LastFmConnection,
    pendingPlayEventCount: Int,
    pendingPlayEventSyncProgress: Pair<Int, Int>?,
    waitingForLastFmSession: Boolean,
    scrobblingPaused: Boolean,
    showLyricsSetting: Boolean,
    crossfadeSeconds: Int,
    equalizerAvailable: Boolean,
    offlineOnly: Boolean,
    downloadUsingCellular: Boolean,
    downloadedSizeBytes: Long,
    cacheSizeBytes: Long,
    appUpdateController: AppUpdateController,
    appVersionName: String,
    onRetry: () -> Unit,
    onRefreshHome: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshPlaylist: () -> Unit,
    onRefreshArtist: (LibraryArtist) -> Unit,
    onRefreshAlbum: (LibraryAlbum) -> Unit,
    onLoadMoreArtists: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onLoadMoreRecentAlbums: () -> Unit,
    onLoadMoreAlbumTracks: (LibraryAlbum) -> Unit,
    onLoadMorePlaylistTracks: (Playlist) -> Unit,
    onUseLocalBackendChange: (Boolean) -> Unit,
    onOfflineOnlyChange: (Boolean) -> Unit,
    onScrobblingPausedChange: (Boolean) -> Unit,
    onShowLyricsChange: (Boolean) -> Unit,
    onCrossfadeSecondsChange: (Int) -> Unit,
    onDownloadUsingCellularChange: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onUpdatePlaylist: (Playlist, String) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onAddTrackToPlaylistClick: (Track) -> Unit,
    onDownloadPlaylist: (Playlist) -> Unit,
    onDeletePlaylistDownload: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist, List<Track>) -> Unit,
    onShufflePlayPlaylist: (Playlist, List<Track>) -> Unit,
    onPlayPlaylistTrack: (Playlist, List<Track>, Int) -> Unit,
    onRemoveTrackFromPlaylist: (Playlist, String, String) -> Unit,
    onReorderPlaylistTracks: (Playlist, List<String>) -> Unit,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onConnectLastFm: () -> Unit,
    onCompleteLastFmSession: () -> Unit,
    onDisconnectLastFm: () -> Unit,
    onSyncLastFmUpdates: () -> Unit,
    onClearDownloads: () -> Unit,
    onClearCache: () -> Unit,
    onCheckUpdates: () -> Unit,
    onSelectTab: (AppTab) -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onShowAllArtists: () -> Unit,
    onSelectArtist: (LibraryArtist) -> Unit,
    onSelectAlbum: (LibraryAlbum) -> Unit,
    onPlayAlbum: (LibraryAlbum, List<Track>) -> Unit,
    onPlayAlbumTrack: (LibraryAlbum, List<Track>, Track) -> Unit,
    onToggleAlbumInLibrary: (LibraryAlbum) -> Unit,
    onDownloadAlbum: (LibraryAlbum, List<Track>) -> Unit,
    onDeleteAlbumDownload: (LibraryAlbum, List<Track>) -> Unit,
    onGoToAlbumArtist: (LibraryAlbum) -> Unit,
    onBack: () -> Unit,
    onSelectTrack: (Track) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onLoadMoreSearchTracks: () -> Unit,
    onClearRecentItems: () -> Unit,
    onRecentItemClick: (RecentLibraryItem) -> Unit,
    onSelectSearchArtist: (LibraryArtist) -> Unit,
    onSelectSearchAlbum: (LibraryAlbum) -> Unit,
    onSelectSearchTrack: (Track, String) -> Unit,
    onOpenFullPlayer: () -> Unit,
    onCloseFullPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    onCloseQueue: () -> Unit,
    onSelectQueueTrack: (Int) -> Unit,
    onRemoveQueueTrack: (Int) -> Unit,
    onReorderQueueTracks: (List<Int>) -> Unit,
    onAddCurrentTrackToPlaylist: () -> Unit,
    onAddTrackToQueue: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onGoToTrackAlbum: (Track) -> Unit,
    onToggleCurrentFavorite: () -> Unit,
    onToggleTrackFavorite: (Track) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSwipePreviousTrack: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (PlaybackRepeatMode) -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Int) -> Unit,
    onRefreshCurrentLyrics: () -> Unit,
    onSignOut: () -> Unit,
) {
    val onlineMode = canUseServerRequests
    val canPlayRemoteTracks = account.canPlayMedia
    val lastFmConnected = lastFmConnection.state == ScrobbleState.Ready &&
        !lastFmConnection.username.isNullOrBlank()
    val topErrorMessage = playerError ?: errorMessage
    val cleanPlaylists = playlists.sanitizeClientPlaylists()
    val knownTrackLikeStates = (tracks + searchResults.tracks + albumTracksById.values.flatten() + looseTracksByArtist.values.flatten() + queueTracks)
        .mapNotNull { track -> track.isLiked?.let { isLiked -> track.id to isLiked } }
        .toMap()
    val favoriteTrackIds = (
        cleanPlaylists.firstOrNull { it.isFavoritesPlaylist() }?.trackIds?.toSet().orEmpty() +
            knownTrackLikeStates.filterValues { it }.keys
        ) - knownTrackLikeStates.filterValues { !it }.keys
    val locallyPlayableTrackIds = (tracks + albumTracksById.values.flatten())
        .filter { track -> track.downloadState == DownloadState.Downloaded || track.id in offlinePlayableTrackIds }
        .map { it.id }
        .toSet()
    val offlineAvailableTrackIds = locallyPlayableTrackIds
    val mediaPlayableTrackIds = if (account.canPlayMedia) {
        offlineAvailableTrackIds
    } else {
        emptySet()
    }
    val downloadedTrackCount = tracks.count { it.downloadState == DownloadState.Downloaded }
    val downloadedTrackIds = tracks
        .filter { it.downloadState == DownloadState.Downloaded }
        .map { it.id }
        .toSet()
    val visibleTracks = when {
        onlineMode -> tracks
        offlineOnly -> tracks.filter { it.id in offlineAvailableTrackIds }
        else -> tracks
    }
    val visiblePlaylists = when {
        onlineMode -> cleanPlaylists
        else -> cleanPlaylists.filter { playlist ->
            playlist.isFavoritesPlaylist() ||
                playlist.isOfflineEnabled
        }
    }
    val downloadedAlbums = tracks.downloadedAlbums(offlineAlbumIds)
    val downloadedAlbumTracksById = tracks.downloadedAlbumTracksById(offlineAlbumIds)
    val allVisibleArtists = if (onlineMode) {
        artists.sortedArtistsForDisplay()
    } else {
        artists.sortedArtistsForDisplay().ifEmpty { tracks.downloadedArtists() }
    }
    val visibleArtists = if (onlineMode) {
        allVisibleArtists.filterOwnReleaseArtists()
    } else {
        allVisibleArtists
    }
    val offlineLibraryAlbums = (albums + savedAlbums + downloadedAlbums)
        .filter { album ->
            album.savedByCurrentUser ||
                album.isOfflineEnabled ||
                album.id in offlineAlbumIds
        }
        .distinctBy { it.id }
        .sortedAlbumsForDisplay()
    val savedAlbumIdsForUi = savedAlbums
        .filter { it.savedByCurrentUser }
        .map { it.id }
        .toSet()
    val visibleAlbums = if (onlineMode) {
        albums
            .map { album ->
                album.copy(
                    savedByCurrentUser = album.savedByCurrentUser || album.id in savedAlbumIdsForUi,
                    isOfflineEnabled = album.isOfflineEnabled || album.id in offlineAlbumIds,
                )
            }
            .sortedAlbumsForDisplay()
    } else {
        offlineLibraryAlbums
    }
    val cachedAlbumTracksById = tracks.cachedAlbumTracksById()
    val visibleAlbumTracksById = if (onlineMode) {
        cachedAlbumTracksById + albumTracksById
    } else {
        downloadedAlbumTracksById + cachedAlbumTracksById + albumTracksById
    }
    val visibleSavedAlbums = when {
        onlineMode -> savedAlbums
        else -> (savedAlbums.filter { album ->
            album.savedByCurrentUser || album.isOfflineEnabled || album.id in offlineAlbumIds
        }.map { album ->
            album.copy(isOfflineEnabled = album.isOfflineEnabled || album.id in offlineAlbumIds)
        } + offlineLibraryAlbums)
            .distinctBy { it.id }
    }
    val homeRecentAlbums = if (onlineMode || recentAlbums.isNotEmpty()) {
        recentAlbums
    } else {
        visibleAlbums.take(HOME_RECENT_ALBUM_MAX_COUNT)
    }
    val showHomeLoadingSkeleton = destination.tab == AppTab.Home &&
        destination.homeRoute == HomeRoute.Overview &&
        isLoading &&
        (syncMode == SyncMode.Syncing || (visibleArtists.isEmpty() && homeRecentAlbums.isEmpty()))
    val selectedHomeArtist = if (destination.tab == AppTab.Home && destination.homeRoute == HomeRoute.Artist) {
        destination.artistId?.let { artistId ->
            visibleArtists.firstOrNull { it.id == artistId }
                ?: searchResults.artists.firstOrNull { it.id == artistId }
                ?: similarArtistsByArtist.values.flatten().firstOrNull { it.id == artistId }
                ?: artists.firstOrNull { it.id == artistId }
        } ?: destination.artistName?.let { name ->
            visibleArtists.firstOrNull { it.name == name }
                ?: searchResults.artists.firstOrNull { it.name == name }
                ?: similarArtistsByArtist.values.flatten().firstOrNull { it.name == name }
        }
    } else {
        null
    }
    val selectedHomeAlbum = if (destination.tab == AppTab.Home && destination.homeRoute == HomeRoute.Album) {
        destination.albumId?.let { albumId ->
            visibleAlbums.firstOrNull { it.id == albumId }
                ?: visibleSavedAlbums.firstOrNull { it.id == albumId }
                ?: albumsByArtist.values.flatten().firstOrNull { it.id == albumId }
                ?: appearsOnByArtist.values.flatten().firstOrNull { it.id == albumId }
                ?: albums.firstOrNull { it.id == albumId }
                ?: savedAlbums.firstOrNull { it.id == albumId }
                ?: searchResults.albums.firstOrNull { it.id == albumId }
                ?: (tracks + albumTracksById.values.flatten())
                    .firstOrNull { it.albumId == albumId }
                    ?.navigationAlbum()
        }?.let { album ->
            album.copy(
                savedByCurrentUser = album.savedByCurrentUser || album.id in savedAlbumIdsForUi,
                isOfflineEnabled = album.isOfflineEnabled || album.id in offlineAlbumIds,
            )
        }
    } else {
        null
    }
    val selectedHomeAlbumTracks = selectedHomeAlbum?.let { album ->
        val hasServerTrackState = album.id in albumTracksById || album.id in albumTrackHasMoreById
        when {
            onlineMode && !hasServerTrackState -> emptyList()
            onlineMode -> albumTracksById[album.id].orEmpty()
            else -> visibleAlbumTracksById[album.id].orEmpty()
        }
    }.orEmpty()
    val selectedLibraryPlaylist = if (destination.tab == AppTab.Library) {
        destination.playlistId?.let { id ->
            (visiblePlaylists + searchResults.playlists).firstOrNull { it.id == id }
        }
    } else {
        null
    }
    val currentTrackFavorite = playerState.currentTrack?.id?.let { trackId ->
        trackId in favoriteTrackIds
    } == true
    val miniPreviousTrack = queueTracks.takeIf { it.isNotEmpty() }?.let { queuedTracks ->
        val currentIndex = queueCurrentIndex.takeIf { it in queuedTracks.indices } ?: return@let null
        queuedTracks[(currentIndex - 1 + queuedTracks.size) % queuedTracks.size]
    }
    val miniNextTrack = queueTracks.takeIf { it.isNotEmpty() }?.let { queuedTracks ->
        val currentIndex = queueCurrentIndex.takeIf { it in queuedTracks.indices } ?: return@let null
        queuedTracks[(currentIndex + 1) % queuedTracks.size]
    }

    LaunchedEffect(onlineMode, destination.tab, destination.homeRoute, selectedHomeArtist?.name) {
        if (onlineMode && destination.tab == AppTab.Home && destination.homeRoute == HomeRoute.Artist) {
            selectedHomeArtist?.let(onRefreshArtist)
        }
    }

    LaunchedEffect(onlineMode, destination.tab, destination.homeRoute, selectedHomeAlbum?.id) {
        if (onlineMode && destination.tab == AppTab.Home && destination.homeRoute == HomeRoute.Album) {
            selectedHomeAlbum?.let(onRefreshAlbum)
        }
    }

    val listStates = remember { mutableMapOf<String, LazyListState>() }
    fun listStateFor(key: String): LazyListState {
        return listStates.getOrPut(key) { LazyListState() }
    }

    var shellHeightPx by remember { mutableFloatStateOf(1f) }
    var fullPlayerGestureProgress by remember { mutableStateOf<Float?>(null) }
    var fullPlayerRevealSettling by remember { mutableStateOf(false) }
    val fullPlayerRevealAnimation = remember { Animatable(0f) }
    val playerRevealScope = rememberCoroutineScope()
    val effectiveFullPlayerRevealProgress = when {
        fullPlayerRevealSettling -> fullPlayerRevealAnimation.value
        fullPlayerGestureProgress != null -> fullPlayerGestureProgress ?: 0f
        fullPlayerOpen -> 1f
        else -> 0f
    }.coerceIn(0f, 1f)
    fun openFullPlayerWithRevealAnimation() {
        if (fullPlayerOpen) {
            onOpenFullPlayer()
            return
        }
        playerRevealScope.launch {
            fullPlayerRevealAnimation.stop()
            val startProgress = effectiveFullPlayerRevealProgress
            fullPlayerGestureProgress = null
            fullPlayerRevealAnimation.snapTo(startProgress)
            fullPlayerRevealSettling = true
            try {
                fullPlayerRevealAnimation.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 210,
                        easing = FastOutSlowInEasing,
                    ),
                )
                onOpenFullPlayer()
            } finally {
                fullPlayerRevealSettling = false
            }
        }
    }
    fun closeFullPlayerWithRevealAnimation() {
        if (!fullPlayerOpen && effectiveFullPlayerRevealProgress <= 0f) {
            onCloseFullPlayer()
            return
        }
        playerRevealScope.launch {
            fullPlayerRevealAnimation.stop()
            val startProgress = effectiveFullPlayerRevealProgress
            fullPlayerGestureProgress = null
            fullPlayerRevealAnimation.snapTo(startProgress)
            fullPlayerRevealSettling = true
            try {
                fullPlayerRevealAnimation.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = 210,
                        easing = FastOutSlowInEasing,
                    ),
                )
                onCloseFullPlayer()
            } finally {
                fullPlayerRevealSettling = false
            }
        }
    }

    BackHandler(enabled = fullPlayerOpen && !queueOpen) {
        closeFullPlayerWithRevealAnimation()
    }

    Scaffold(
        bottomBar = {
            MainBottomBar(
                visible = effectiveFullPlayerRevealProgress < 0.999f,
                fullPlayerRevealProgress = effectiveFullPlayerRevealProgress,
                selectedTab = destination.tab,
                playerState = playerState,
                artworkBitmap = artworkBitmap,
                queueGeneration = playbackQueueGeneration,
                canSkipTracks = canSkipTracks,
                previousTrack = miniPreviousTrack,
                nextTrack = miniNextTrack,
                onOpenFullPlayer = ::openFullPlayerWithRevealAnimation,
                onExpandDragStart = {
                    playerRevealScope.launch {
                        fullPlayerRevealAnimation.stop()
                    }
                    fullPlayerRevealSettling = false
                    fullPlayerGestureProgress = 0f
                },
                onExpandDrag = { upwardDeltaPx ->
                    if (!fullPlayerOpen) {
                        fullPlayerGestureProgress = (
                            (fullPlayerGestureProgress ?: 0f) + upwardDeltaPx / shellHeightPx
                            ).coerceIn(0f, 1f)
                    }
                },
                onExpandDragEnd = {
                    if (!fullPlayerOpen) {
                        val startProgress = fullPlayerGestureProgress ?: 0f
                        val shouldOpen = startProgress >= 0.3f
                        playerRevealScope.launch {
                            fullPlayerRevealAnimation.snapTo(startProgress)
                            fullPlayerRevealSettling = true
                            try {
                                fullPlayerRevealAnimation.animateTo(
                                    targetValue = if (shouldOpen) 1f else 0f,
                                    animationSpec = tween(
                                        durationMillis = 210,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                                if (shouldOpen) {
                                    onOpenFullPlayer()
                                }
                            } finally {
                                fullPlayerGestureProgress = null
                                fullPlayerRevealSettling = false
                            }
                        }
                    }
                },
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onTogglePlayback = onTogglePlayback,
                onSelectTab = onSelectTab,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    shellHeightPx = size.height.toFloat().coerceAtLeast(1f)
                },
        ) {
            if (showHomeLoadingSkeleton) {
                HomeLoadingSkeleton(modifier = Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (destination.tab) {
                    AppTab.Home -> {
                    val selectedArtist = selectedHomeArtist
                    val selectedAlbum = selectedHomeAlbum
                    when (destination.homeRoute) {
                        HomeRoute.Overview -> HomeScreen(
                            artists = visibleArtists,
                            recentAlbums = homeRecentAlbums,
                            databaseTrackCount = databaseTrackCount,
                            offlineTrackCount = mediaPlayableTrackIds.size,
                            artworkBitmaps = artworkBitmaps,
                            listState = listStateFor("home-overview"),
                            syncMode = syncMode,
                            isLoading = isLoading,
                            isLoadingMoreRecentAlbums = recentAlbumsLoadingMore,
                            canLoadMoreRecentAlbums = onlineMode && recentAlbumsHasMore,
                            onRefresh = onRefreshHome,
                            onLoadMoreRecentAlbums = onLoadMoreRecentAlbums,
                            onRequestArtwork = onRequestArtwork,
                            onShowAllArtists = onShowAllArtists,
                            onSelectArtist = onSelectArtist,
                            onSelectAlbum = onSelectAlbum,
                        )
                        HomeRoute.Artists -> ArtistsScreen(
                            artists = visibleArtists,
                            artworkBitmaps = artworkBitmaps,
                            listState = listStateFor("home-artists"),
                            isRefreshing = isLoading,
                            isLoadingMore = artistListLoadingMore,
                            canLoadMore = onlineMode && artistListHasMore,
                            offlineNotice = if (!onlineMode) "Offline. Showing cached artists." else null,
                            onRefresh = onRefreshHome,
                            onLoadMore = onLoadMoreArtists,
                            onRequestArtwork = onRequestArtwork,
                            onSelectArtist = onSelectArtist,
                        )
                        HomeRoute.Albums -> AlbumsScreen(
                            albums = visibleAlbums,
                            tracks = visibleTracks,
                            albumTracksById = visibleAlbumTracksById,
                            artworkBitmaps = artworkBitmaps,
                            listState = listStateFor("home-albums"),
                            isRefreshing = isLoading,
                            isLoadingMore = albumListLoadingMore,
                            canLoadMore = onlineMode && albumListHasMore,
                            onBack = onBack,
                            onRefresh = onRefreshHome,
                            onLoadMore = onLoadMoreAlbums,
                            onRequestArtwork = onRequestArtwork,
                            onSelectAlbum = onSelectAlbum,
                        )
                        HomeRoute.Artist -> ArtistScreen(
                            artist = selectedArtist,
                            albums = selectedArtist?.id?.let(albumsByArtist::get).orEmpty(),
                            appearsOn = selectedArtist?.id?.let(appearsOnByArtist::get).orEmpty(),
                            looseTracks = selectedArtist?.id?.let(looseTracksByArtist::get).orEmpty(),
                            similarArtists = selectedArtist?.id?.let(similarArtistsByArtist::get).orEmpty(),
                            isSimilarArtistsLoading = selectedArtist?.id?.let { it in similarArtistLoadsInProgress } == true,
                            isLoading = selectedArtist?.id?.let { it in artistAlbumLoadsInProgress } == true,
                            isRefreshing = selectedArtist?.id?.let { it in artistAlbumLoadsInProgress } == true,
                            tracks = visibleTracks,
                            albumTracksById = visibleAlbumTracksById,
                            artworkBitmaps = artworkBitmaps,
                            listState = listStateFor("artist-${selectedArtist?.id.orEmpty()}"),
                            onRefresh = {
                                selectedArtist?.let(onRefreshArtist)
                            },
                            onRequestArtwork = onRequestArtwork,
                            onSelectAlbum = onSelectAlbum,
                            onSelectArtist = onSelectArtist,
                            offlineNotice = if (!onlineMode) "Offline. Showing cached artist data." else null,
                            onAddTrackToPlaylist = onAddTrackToPlaylistClick,
                            onAddTrackToQueue = onAddTrackToQueue,
                            onGoToTrackArtist = onGoToTrackArtist,
                            onGoToTrackAlbum = onGoToTrackAlbum,
                            favoriteTrackIds = favoriteTrackIds,
                            onToggleTrackFavorite = onToggleTrackFavorite,
                            onSelectTrack = { track ->
                                onSelectTrack(track)
                            },
                        )
                        HomeRoute.Album -> AlbumScreen(
                            album = selectedAlbum,
                            tracks = selectedHomeAlbumTracks,
                            isLoading = selectedAlbum?.id?.let { it in albumTrackLoadsInProgress } == true,
                            isRefreshing = selectedAlbum?.id?.let { it in albumTrackLoadsInProgress } == true,
                            artworkBitmaps = artworkBitmaps,
                            listState = listStateFor("album-${selectedAlbum?.id.orEmpty()}"),
                            onRequestArtwork = onRequestArtwork,
                            isActiveAlbum = selectedAlbum?.id == activeAlbumId,
                            currentTrackId = playerState.currentTrack?.id,
                            isPlaybackPlaying = playerState.isPlaying,
                            canPlayFromNetwork = canPlayRemoteTracks,
                            canDownload = account.canPlayMedia,
                            offlinePlayableTrackIds = mediaPlayableTrackIds,
                            onRefresh = {
                                selectedAlbum?.let(onRefreshAlbum)
                            },
                            isLoadingMore = selectedAlbum?.id?.let { it in albumTrackLoadsInProgress } == true,
                            canLoadMore = selectedAlbum?.let { album ->
                                onlineMode &&
                                    albumTrackHasMoreById[album.id] != false &&
                                    (album.trackCount <= 0 || selectedHomeAlbumTracks.size < album.trackCount)
                            } == true,
                            onLoadMore = {
                                selectedAlbum?.let(onLoadMoreAlbumTracks)
                            },
                            offlineNotice = if (!onlineMode && selectedAlbum?.isOfflineEnabled != true) {
                                "Offline. Showing cached album data."
                            } else {
                                null
                            },
                            onTogglePlayback = onTogglePlayback,
                            onPlayAlbum = {
                                selectedAlbum?.let { album ->
                                    val albumTracks = selectedHomeAlbumTracks
                                    val playableAlbumTracks = if (canPlayRemoteTracks) {
                                        albumTracks
                                    } else {
                                        albumTracks.filter { it.id in mediaPlayableTrackIds }
                                    }
                                    onPlayAlbum(album, playableAlbumTracks)
                                }
                            },
                            onAddAlbumToLibrary = {
                                selectedAlbum?.let(onToggleAlbumInLibrary)
                            },
                            onDownloadAlbum = {
                                selectedAlbum?.let { album -> onDownloadAlbum(album, selectedHomeAlbumTracks) }
                            },
                            onDeleteAlbumDownload = {
                                selectedAlbum?.let { album -> onDeleteAlbumDownload(album, selectedHomeAlbumTracks) }
                            },
                            onGoToAlbumArtist = onGoToAlbumArtist,
                            onSelectTrack = { track ->
                                selectedAlbum?.let { album ->
                                    onPlayAlbumTrack(album, selectedHomeAlbumTracks, track)
                                }
                            },
                            onAddTrackToPlaylist = onAddTrackToPlaylistClick,
                            onAddTrackToQueue = onAddTrackToQueue,
                            onGoToTrackArtist = onGoToTrackArtist,
                            onGoToTrackAlbum = onGoToTrackAlbum,
                            favoriteTrackIds = favoriteTrackIds,
                            onToggleTrackFavorite = onToggleTrackFavorite,
                        )
                    }
                }

                    AppTab.Library -> {
                    val selectedPlaylist = selectedLibraryPlaylist
                    if (selectedPlaylist == null) {
                        LibraryScreen(
                            playlists = visiblePlaylists,
                            tracks = visibleTracks,
                            savedAlbums = visibleSavedAlbums,
                            albumTracksById = visibleAlbumTracksById,
                            artworkBitmaps = artworkBitmaps,
                            listState = listStateFor("library"),
                            isRefreshing = isLoading,
                            offlineOnly = offlineOnly,
                            onRefresh = onRefreshLibrary,
                            onRequestArtwork = onRequestArtwork,
                            onSelectAlbum = onSelectAlbum,
                            onSelectPlaylist = onSelectPlaylist,
                            onCreatePlaylist = onCreatePlaylist,
                        )
                    } else {
                        val displayPlaylist = selectedPlaylist
                        val playlistTrackSource = if (canPlayRemoteTracks) visibleTracks else tracks
                        val rawPlaylistTracks = remember(
                            displayPlaylist.trackIds,
                            playlistTrackSource,
                        ) {
                            displayPlaylist.tracksFrom(playlistTrackSource)
                        }
                        val hasPlaylistTrackState = displayPlaylist.id in playlistTrackHasMoreById ||
                            rawPlaylistTracks.size >= displayPlaylist.trackCount
                        val playlistTracks = if (onlineMode && !hasPlaylistTrackState) {
                            emptyList()
                        } else {
                            rawPlaylistTracks
                        }
                        val playlistPlaybackTracks = if (canPlayRemoteTracks) {
                            playlistTracks
                        } else {
                            playlistTracks.filter { it.id in mediaPlayableTrackIds }
                        }
                        PlaylistScreen(
                            playlist = displayPlaylist,
                            tracks = playlistTracks,
                            canDownload = true,
                            canDownloadMedia = account.canPlayMedia,
                            isRefreshing = isLoading || selectedPlaylist.id in playlistTrackLoadsInProgress,
                            onRefresh = onRefreshPlaylist,
                            onSelectTrack = { sourceIndex ->
                                val playbackIndex = playbackIndexForSourceIndex(
                                    sourceTracks = playlistTracks,
                                    playbackTracks = playlistPlaybackTracks,
                                    sourceIndex = sourceIndex,
                                )
                                if (playbackIndex >= 0) {
                                    onPlayPlaylistTrack(selectedPlaylist, playlistPlaybackTracks, playbackIndex)
                                }
                            },
                            onDownloadPlaylist = onDownloadPlaylist,
                            onDeletePlaylistDownload = onDeletePlaylistDownload,
                            onAddTrackToPlaylist = onAddTrackToPlaylistClick,
                            onAddTrackToQueue = onAddTrackToQueue,
                            onGoToTrackArtist = onGoToTrackArtist,
                            onGoToTrackAlbum = onGoToTrackAlbum,
                            favoriteTrackIds = favoriteTrackIds,
                            onToggleTrackFavorite = onToggleTrackFavorite,
                            onRemoveTrack = { playlistTrackId, trackId ->
                                onRemoveTrackFromPlaylist(selectedPlaylist, playlistTrackId, trackId)
                            },
                            onReorderTracks = { playlistTrackIds ->
                                onReorderPlaylistTracks(selectedPlaylist, playlistTrackIds)
                            },
                            onUpdatePlaylist = { name ->
                                onUpdatePlaylist(selectedPlaylist, name)
                            },
                            onDeletePlaylist = { onDeletePlaylist(selectedPlaylist) },
                            onPlayPlaylist = { onPlayPlaylist(selectedPlaylist, playlistPlaybackTracks) },
                            onShufflePlayPlaylist = { onShufflePlayPlaylist(selectedPlaylist, playlistPlaybackTracks) },
                            isActivePlaylist = activePlaylistId == selectedPlaylist.id,
                            currentTrackId = playerState.currentTrack?.id,
                            isPlaybackPlaying = playerState.isPlaying,
                            canPlayFromNetwork = canPlayRemoteTracks,
                            offlinePlayableTrackIds = mediaPlayableTrackIds,
                            downloadedTrackIds = downloadedTrackIds,
                            onTogglePlayback = onTogglePlayback,
                            artworkBitmaps = artworkBitmaps,
                            onRequestArtwork = onRequestArtwork,
                            isLoadingMore = selectedPlaylist.id in playlistTrackLoadsInProgress,
                            canLoadMore = onlineMode &&
                                playlistTrackHasMoreById[selectedPlaylist.id] != false &&
                                selectedPlaylist.trackIds.size < selectedPlaylist.trackCount,
                            onLoadMore = { onLoadMorePlaylistTracks(selectedPlaylist) },
                            offlineNotice = if (!onlineMode && !displayPlaylist.isOfflineEnabled) {
                                "Offline. Showing cached playlist data."
                            } else {
                                null
                            },
                        )
                    }
                }

                    AppTab.Search -> SearchScreen(
                    query = searchQuery,
                    focusRequestSerial = searchFocusRequestSerial,
                    results = searchResults,
                    playlists = visiblePlaylists,
                    offlineTracks = visibleTracks,
                    albumTracksById = visibleAlbumTracksById,
                    recentItems = recentItems,
                    isSearching = searchLoading,
                    canLoadMoreTracks = onlineMode && searchHasMore,
                    onlineMode = onlineMode,
                    artworkBitmaps = artworkBitmaps,
                    listState = listStateFor("search"),
                    onRequestArtwork = onRequestArtwork,
                    onQueryChange = onSearchQueryChange,
                    onLoadMoreTracks = onLoadMoreSearchTracks,
                    onClearRecentItems = onClearRecentItems,
                    onRecentItemClick = onRecentItemClick,
                    onSelectArtist = onSelectSearchArtist,
                    onSelectAlbum = onSelectSearchAlbum,
                    onSelectPlaylist = onSelectPlaylist,
                    onAddTrackToPlaylistClick = onAddTrackToPlaylistClick,
                    onAddTrackToQueue = onAddTrackToQueue,
                    onGoToTrackArtist = onGoToTrackArtist,
                    onGoToTrackAlbum = onGoToTrackAlbum,
                    favoriteTrackIds = favoriteTrackIds,
                    onToggleTrackFavorite = onToggleTrackFavorite,
                    onSelectTrack = onSelectSearchTrack,
                )

                    AppTab.Profile -> ProfileScreen(
                    account = account,
                    avatarBitmap = profileAvatarBitmap,
                    useLocalBackend = useLocalBackend,
                    canUseNetwork = canUseServerRequests,
                    syncMode = syncMode,
                    lastFmConnection = lastFmConnection,
                    pendingPlayEventCount = pendingPlayEventCount,
                    pendingPlayEventSyncProgress = pendingPlayEventSyncProgress,
                    waitingForLastFmSession = waitingForLastFmSession,
                    scrobblingPaused = scrobblingPaused,
                    showLyrics = showLyricsSetting,
                    crossfadeSeconds = crossfadeSeconds,
                    equalizerAvailable = equalizerAvailable,
                    offlineOnly = offlineOnly,
                    downloadUsingCellular = downloadUsingCellular,
                    downloadedTrackCount = downloadedTrackCount,
                    downloadedSizeBytes = downloadedSizeBytes,
                    cacheSizeBytes = cacheSizeBytes,
                    appUpdateController = appUpdateController,
                    appVersionName = appVersionName,
                    onUseLocalBackendChange = onUseLocalBackendChange,
                    onOfflineOnlyChange = onOfflineOnlyChange,
                    onScrobblingPausedChange = onScrobblingPausedChange,
                    onShowLyricsChange = onShowLyricsChange,
                    onCrossfadeSecondsChange = onCrossfadeSecondsChange,
                    onDownloadUsingCellularChange = onDownloadUsingCellularChange,
                    onOpenEqualizer = onOpenEqualizer,
                    onConnectLastFm = onConnectLastFm,
                    onCompleteLastFmSession = onCompleteLastFmSession,
                    onDisconnectLastFm = onDisconnectLastFm,
                    onSyncLastFmUpdates = onSyncLastFmUpdates,
                    onClearDownloads = onClearDownloads,
                    onClearCache = onClearCache,
                    onCheckUpdates = onCheckUpdates,
                    onSignOut = onSignOut,
                )
                }
            }
            if (!topErrorMessage.isNullOrBlank()) {
                TopErrorBanner(
                    message = topErrorMessage,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(3f),
                )
            }
            if (!noticeMessage.isNullOrBlank()) {
                TopNoticeBanner(
                    message = noticeMessage,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(4f),
                )
            }
            PlayerOverlayHost(
                fullPlayerOpen = fullPlayerOpen,
                fullPlayerRevealProgress = effectiveFullPlayerRevealProgress,
                shellHeightPx = shellHeightPx,
                queueOpen = queueOpen,
                playerState = playerState,
                artworkBitmap = artworkBitmap,
                artworkBitmaps = artworkBitmaps,
                playbackBufferedFraction = playbackBufferedFraction,
                canSkipTracks = canSkipTracks,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                showLyrics = showLyrics,
                currentLyrics = currentLyrics,
                currentLyricsUnavailable = currentLyricsUnavailable,
                currentLyricsLoading = currentLyricsLoading,
                playerSourceLabel = playerSourceLabel,
                playerSourceDetail = playerSourceDetail,
                currentTrackFavorite = currentTrackFavorite,
                canPlayRemoteTracks = canPlayRemoteTracks,
                offlineAvailableTrackIds = mediaPlayableTrackIds,
                queueTracks = queueTracks,
                manualQueueFlags = manualQueueFlags,
                queueCurrentIndex = queueCurrentIndex,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onSwipePrevious = onSwipePreviousTrack,
                onShuffleChange = onShuffleChange,
                onRepeatModeChange = onRepeatModeChange,
                onToggleCurrentFavorite = onToggleCurrentFavorite,
                onAddCurrentTrackToPlaylist = onAddCurrentTrackToPlaylist,
                onGoToTrackArtist = onGoToTrackArtist,
                onGoToTrackAlbum = onGoToTrackAlbum,
                onRefreshCurrentLyrics = onRefreshCurrentLyrics.takeIf { account.canPlayMedia },
                onOpenQueue = onOpenQueue,
                onCloseQueue = onCloseQueue,
                onCloseFullPlayer = onCloseFullPlayer,
                onCollapseDragStart = {
                    playerRevealScope.launch {
                        fullPlayerRevealAnimation.stop()
                    }
                    fullPlayerRevealSettling = false
                    fullPlayerGestureProgress = 1f
                },
                onCollapseDrag = { downwardDeltaPx ->
                    fullPlayerGestureProgress = (
                        (fullPlayerGestureProgress ?: 1f) - downwardDeltaPx / shellHeightPx
                        ).coerceIn(0f, 1f)
                },
                onCollapseDragEnd = {
                    val startProgress = fullPlayerGestureProgress ?: 1f
                    val shouldClose = startProgress <= 0.65f
                    playerRevealScope.launch {
                        fullPlayerRevealAnimation.snapTo(startProgress)
                        fullPlayerRevealSettling = true
                        try {
                            fullPlayerRevealAnimation.animateTo(
                                targetValue = if (shouldClose) 0f else 1f,
                                animationSpec = tween(
                                    durationMillis = 240,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                            if (shouldClose) {
                                onCloseFullPlayer()
                            }
                        } finally {
                            fullPlayerGestureProgress = null
                            fullPlayerRevealSettling = false
                        }
                    }
                },
                onTogglePlayback = onTogglePlayback,
                onSeek = onSeek,
                onSelectQueueTrack = onSelectQueueTrack,
                onRemoveQueueTrack = onRemoveQueueTrack,
                onReorderQueueTracks = onReorderQueueTracks,
                onRequestArtwork = onRequestArtwork,
            )
        }
    }
}
