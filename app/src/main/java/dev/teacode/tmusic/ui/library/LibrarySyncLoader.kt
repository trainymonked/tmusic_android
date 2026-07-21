package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.ScrobbleState
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal suspend fun fetchLibraryState(
    targetDestination: AppDestination,
    artistSortOption: ArtistSortOption,
    cachedPlaylists: List<Playlist>,
    authRepository: RemoteAuthRepository,
    musicRepository: RemoteMusicRepository,
): LoadedLibraryState {
    val loadedAccount = authRepository.currentAccount()
    var loadedPlaylists: List<Playlist>? = null
    var loadedTracks: List<Track>? = null
    var loadedRecentAlbums: List<LibraryAlbum>? = null
    var loadedHomeArtists: List<LibraryArtist>? = null
    var loadedArtists: List<LibraryArtist>? = null
    var loadedAlbums: List<LibraryAlbum>? = null
    var loadedSavedAlbums: List<LibraryAlbum>? = null

    val baseLibrary = musicRepository.libraryPage(
        playlistLimit = SCREEN_PAGE_LIMIT,
        trackLimit = 0,
    )
    loadedPlaylists = baseLibrary.playlists
    loadedTracks = baseLibrary.tracks
    if (shouldLoadFullFavoritesPayload(cachedPlaylists, loadedPlaylists.orEmpty())) {
        val favoritesPayload = musicRepository.favoritesPlaylistPayload()
        if (favoritesPayload.playlists.isNotEmpty()) {
            loadedPlaylists = loadedPlaylists.orEmpty().mergeLoadedPlaylists(favoritesPayload.playlists)
        }
        if (favoritesPayload.tracks.isNotEmpty()) {
            loadedTracks = (loadedTracks.orEmpty() + favoritesPayload.tracks).distinctBy { it.id }
        }
    }
    loadedSavedAlbums = musicRepository.savedAlbumsPage(limit = SCREEN_PAGE_LIMIT)

    when (targetDestination.tab) {
        AppTab.Home -> when (targetDestination.homeRoute) {
            HomeRoute.Overview -> {
                val artistPage = musicRepository.libraryArtistsPageWithTotal(
                    limit = HOME_ARTIST_PREVIEW_LIMIT,
                    sortOption = ArtistSortOption.TrackCount,
                )
                loadedHomeArtists = artistPage.artists
                loadedRecentAlbums = runCatching {
                    musicRepository.recentAlbums(limit = HOME_RECENT_ALBUM_PAGE_LIMIT)
                }.getOrNull()
            }
            HomeRoute.Artists -> {
                val artistPage = musicRepository.libraryArtistsPageWithTotal(
                    limit = SCREEN_PAGE_LIMIT,
                    sortOption = artistSortOption,
                )
                loadedArtists = artistPage.artists
            }
            HomeRoute.Albums -> {
                loadedAlbums = musicRepository.libraryAlbumsPage(limit = SCREEN_PAGE_LIMIT)
            }
            HomeRoute.Artist,
            HomeRoute.Album -> Unit
        }
        AppTab.Library -> Unit
        AppTab.Search,
        AppTab.Profile -> Unit
    }

    return LoadedLibraryState(
        account = loadedAccount,
        playlists = loadedPlaylists,
        tracks = loadedTracks,
        recentAlbums = loadedRecentAlbums,
        trackCount = if (targetDestination.isHomeOverview()) {
            runCatching { musicRepository.tracksCount() }.getOrNull()
        } else {
            null
        },
        homeArtists = loadedHomeArtists,
        artists = loadedArtists,
        albums = loadedAlbums,
        savedAlbums = loadedSavedAlbums,
        lastFmConnection = loadedAccount?.lastFmConnection,
    )
}

internal fun loadLibraryAction(
    scope: CoroutineScope,
    targetDestination: AppDestination,
    artistSortOption: ArtistSortOption,
    getLibraryLoadSerial: () -> Int,
    setLibraryLoadSerial: (Int) -> Unit,
    getLibraryLoadJob: () -> Job?,
    setLibraryLoadJob: (Job?) -> Unit,
    getLibraryLoading: () -> Boolean,
    setLibraryLoading: (Boolean) -> Unit,
    getOfflineOnly: () -> Boolean,
    getAccount: () -> dev.teacode.tmusic.domain.Account?,
    setAccount: (dev.teacode.tmusic.domain.Account?) -> Unit,
    authRepository: RemoteAuthRepository,
    musicRepository: RemoteMusicRepository,
    getSyncMode: () -> SyncMode,
    setSyncMode: (SyncMode) -> Unit,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getTracks: () -> List<Track>,
    setTracks: (List<Track>) -> Unit,
    getRecentAlbums: () -> List<LibraryAlbum>,
    setRecentAlbums: (List<LibraryAlbum>) -> Unit,
    getDatabaseTrackCount: () -> Int?,
    setDatabaseTrackCount: (Int?) -> Unit,
    getHomeArtists: () -> List<LibraryArtist>,
    setHomeArtists: (List<LibraryArtist>) -> Unit,
    getArtists: () -> List<LibraryArtist>,
    setArtists: (List<LibraryArtist>) -> Unit,
    setArtistServerSortOption: (ArtistSortOption?) -> Unit,
    getAlbums: () -> List<LibraryAlbum>,
    setAlbums: (List<LibraryAlbum>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    getOfflineAlbumIds: () -> Set<String>,
    getLibraryPaging: () -> LibraryPagingState,
    setLibraryPaging: (LibraryPagingState) -> Unit,
    getRecentAlbumsPaging: () -> RecentAlbumsPagingState,
    setRecentAlbumsPaging: (RecentAlbumsPagingState) -> Unit,
    libraryCacheStore: LibraryCacheStore,
    getPendingFavoriteStates: () -> Map<String, Boolean>,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    getPendingPlayEventCount: () -> Int,
    setLastFmConnection: (LastFmConnection) -> Unit,
    userPreferencesStore: UserPreferencesStore,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
    setPendingLastFmToken: (String?) -> Unit,
    setWaitingForLastFmSession: (Boolean) -> Unit,
    signOutLocalSession: suspend (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    hasNetworkConnection: () -> Boolean,
    setLibraryError: (String?) -> Unit,
) {
    val loadSerial = getLibraryLoadSerial() + 1
    setLibraryLoadSerial(loadSerial)
    getLibraryLoadJob()?.cancel()
    val timeoutJob = scope.launch {
        delay(SERVER_OFFLINE_FALLBACK_TIMEOUT_MS)
        if (getLibraryLoadSerial() == loadSerial && getLibraryLoading()) {
            setAccount(getAccount() ?: authRepository.cachedAccount() ?: OfflineAccount)
            val message = if (getPlaylists().isEmpty() && getTracks().isEmpty()) {
                "Sync is taking longer than ${SERVER_OFFLINE_FALLBACK_TIMEOUT_MS / 1000} seconds. Cached library is empty."
            } else {
                "Sync is taking longer than ${SERVER_OFFLINE_FALLBACK_TIMEOUT_MS / 1000} seconds. Showing cached data while it continues."
            }
            setLibraryLoading(false)
            setLibraryError(message)
        }
    }
    val loadJob = scope.launch {
        if (getOfflineOnly()) {
            setSyncMode(SyncMode.OfflineOnly)
            setLibraryError(null)
            setLibraryLoading(false)
            timeoutJob.cancel()
            if (getLibraryLoadSerial() == loadSerial) {
                setLibraryLoadJob(null)
            }
            return@launch
        }

        if (!hasNetworkConnection()) {
            setSyncMode(SyncMode.Offline)
            setLibraryError(
                if (getPlaylists().isEmpty() && getTracks().isEmpty()) {
                    "Offline library is empty."
                } else {
                    "Showing offline data."
                },
            )
            setLibraryLoading(false)
            timeoutJob.cancel()
            if (getLibraryLoadSerial() == loadSerial) {
                setLibraryLoadJob(null)
            }
            return@launch
        }

        authRepository.cachedAccount()?.let { cachedAccount ->
            if (getAccount() == null || getAccount() == OfflineAccount) {
                setAccount(cachedAccount)
            }
        }

        if (getSyncMode() == SyncMode.Online) {
            setSyncMode(SyncMode.Syncing)
        }
        setLibraryLoading(true)
        setLibraryError(null)
        try {
            runCatching {
                withTimeout(SERVER_SYNC_HARD_TIMEOUT_MS) {
                    fetchLibraryState(
                        targetDestination = targetDestination,
                        artistSortOption = artistSortOption,
                        cachedPlaylists = getPlaylists(),
                        authRepository = authRepository,
                        musicRepository = musicRepository,
                    )
                }
            }.onSuccess { loadedState ->
                if (getLibraryLoadSerial() != loadSerial) {
                    return@onSuccess
                }
                loadedState.account?.let(setAccount)
                val mergedLibrary = loadedState.mergeWithCachedLibrary(
                    targetDestination = targetDestination,
                    cachedPlaylists = getPlaylists(),
                    cachedTracks = getTracks(),
                    cachedRecentAlbums = getRecentAlbums(),
                    cachedTrackCount = getDatabaseTrackCount(),
                    cachedArtists = getArtists(),
                    cachedAlbums = getAlbums(),
                    cachedSavedAlbums = getSavedAlbums(),
                    offlineAlbumIds = getOfflineAlbumIds(),
                )
                val nextTracks = mergedLibrary.tracks.withPendingFavoriteStates(getPendingFavoriteStates())
                setPlaylists(mergedLibrary.playlists)
                setTracks(nextTracks)
                setRecentAlbums(mergedLibrary.recentAlbums)
                setDatabaseTrackCount(mergedLibrary.databaseTrackCount)
                setHomeArtists(loadedState.homeArtists ?: getHomeArtists())
                setArtists(mergedLibrary.artists)
                if (loadedState.artists != null) {
                    setArtistServerSortOption(targetDestination.artistServerSortOption(artistSortOption))
                }
                setAlbums(mergedLibrary.albums)
                setSavedAlbums(mergedLibrary.savedAlbums)
                mergedLibrary.artistListNextOffset?.let { offset ->
                    setLibraryPaging(getLibraryPaging().copy(artistNextOffset = offset))
                }
                mergedLibrary.artistListHasMore?.let { hasMore ->
                    setLibraryPaging(getLibraryPaging().copy(artistHasMore = hasMore))
                }
                mergedLibrary.albumListNextOffset?.let { offset ->
                    setLibraryPaging(getLibraryPaging().copy(albumNextOffset = offset))
                }
                mergedLibrary.albumListHasMore?.let { hasMore ->
                    setLibraryPaging(getLibraryPaging().copy(albumHasMore = hasMore))
                }
                if (targetDestination.isHomeOverview() && loadedState.recentAlbums != null) {
                    setRecentAlbumsPaging(
                        getRecentAlbumsPaging().copy(
                            nextOffset = mergedLibrary.recentAlbums.size,
                            hasMore = mergedLibrary.recentAlbums.size >= HOME_RECENT_ALBUM_PAGE_LIMIT &&
                                mergedLibrary.recentAlbums.size < HOME_RECENT_ALBUM_MAX_COUNT,
                        ),
                    )
                }
                if (loadedState.playlists != null || loadedState.tracks != null) {
                    libraryCacheStore.saveLibrary(
                        playlists = mergedLibrary.playlists,
                        tracks = nextTracks,
                        savedAlbums = mergedLibrary.savedAlbums,
                        homeArtists = loadedState.homeArtists,
                        recentAlbums = loadedState.recentAlbums,
                        databaseTrackCount = loadedState.trackCount,
                    )
                }
                setSyncMode(SyncMode.Online)
                setAccessToken(refreshAccessToken())
                loadedState.lastFmConnection?.let { connection ->
                    val nextConnection = connection.copy(pendingScrobbles = getPendingPlayEventCount())
                    setLastFmConnection(nextConnection)
                    userPreferencesStore.setLastFmConnection(nextConnection)
                    if (nextConnection.state == ScrobbleState.Ready && !nextConnection.username.isNullOrBlank()) {
                        setPendingLastFmToken(null)
                        setWaitingForLastFmSession(false)
                        lastFmAuthTokenStore.clear()
                    }
                }
            }.onFailure { error ->
                if (error.isUnauthorizedError()) {
                    signOutLocalSession(error.unauthorizedSessionMessage())
                    return@onFailure
                }
                if (error.isDeletedAccountError()) {
                    signOutLocalSession("Account was removed. Sign in again.")
                    return@onFailure
                }
                if (error is TimeoutCancellationException) {
                    if (getLibraryLoadSerial() != loadSerial) {
                        return@onFailure
                    }
                    setAccount(getAccount() ?: authRepository.cachedAccount() ?: OfflineAccount)
                    if (getSyncMode() != SyncMode.Online) {
                        setSyncMode(SyncMode.Offline)
                    }
                    val message = if (getPlaylists().isEmpty() && getTracks().isEmpty()) {
                        "Server sync exceeded ${SERVER_SYNC_HARD_TIMEOUT_MS / 1000} seconds. Cached library is empty."
                    } else {
                        "Server sync exceeded ${SERVER_SYNC_HARD_TIMEOUT_MS / 1000} seconds. Showing cached data."
                    }
                    setLibraryError(message)
                    return@onFailure
                }
                if (error is CancellationException) {
                    return@launch
                }
                if (getLibraryLoadSerial() != loadSerial) {
                    return@onFailure
                }
                if (error.isAppUpdateRequiredError()) {
                    markServerUnavailable(error)
                    setLibraryError(null)
                    return@onFailure
                }
                setAccount(getAccount() ?: authRepository.cachedAccount() ?: OfflineAccount)
                markServerUnavailable(error)
                setLibraryError(
                    if (getPlaylists().isEmpty() && getTracks().isEmpty()) {
                        "Library sync failed. Cached library is empty. ${error.userMessage()}"
                    } else {
                        "Library sync failed. Showing cached data. ${error.userMessage()}"
                    },
                )
            }
        } finally {
            timeoutJob.cancel()
            if (getLibraryLoadSerial() == loadSerial) {
                setLibraryLoading(false)
                setLibraryLoadJob(null)
            }
        }
    }
    setLibraryLoadJob(loadJob)
}

private fun AppDestination.artistServerSortOption(currentSortOption: ArtistSortOption): ArtistSortOption? {
    return when {
        tab == AppTab.Home && homeRoute == HomeRoute.Overview -> ArtistSortOption.TrackCount
        tab == AppTab.Home && homeRoute == HomeRoute.Artists -> currentSortOption
        else -> null
    }
}

private fun shouldLoadFullFavoritesPayload(
    cachedPlaylists: List<Playlist>,
    serverPlaylists: List<Playlist>,
): Boolean {
    val serverFavorites = serverPlaylists.firstOrNull { it.isFavoritesPlaylist() }
    val cachedFavorites = cachedPlaylists.firstOrNull { cachedPlaylist ->
        cachedPlaylist.isFavoritesPlaylist() ||
            serverFavorites?.let { cachedPlaylist.id == it.id } == true
    } ?: return true
    if (serverFavorites == null) {
        return cachedFavorites.trackIds.size > cachedFavorites.playlistTrackIdsByTrackId.size
    }
    if (serverFavorites.trackCount <= 0) {
        return cachedFavorites.trackIds.size > cachedFavorites.playlistTrackIdsByTrackId.size
    }
    return cachedFavorites.playlistTrackIdsByTrackId.size < serverFavorites.trackCount
}
