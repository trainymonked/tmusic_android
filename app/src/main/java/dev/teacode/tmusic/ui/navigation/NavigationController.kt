package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.RecentLibraryItemType
import dev.teacode.tmusic.domain.Track

internal class NavigationController(
    private val appState: TMusicAppMutableState,
    private val addRecentItem: (RecentLibraryItem) -> Unit,
    private val loadLibrary: (AppDestination) -> Unit,
    private val loadArtists: (ArtistSortOption) -> Unit,
    private val loadFullFavoritesPlaylist: (Playlist) -> Unit,
    private val loadPlaylistTracks: (Playlist, Boolean) -> Unit,
    private val loadArtwork: (String, ArtworkImageSize) -> Unit,
    private val resolveCachedArtist: (String) -> LibraryArtist?,
    private val openArtist: (LibraryArtist) -> Unit,
    private val openAlbum: (LibraryAlbum) -> Unit,
    private val selectTrack: (Track, String?) -> Unit,
) {
    fun navigateTo(next: AppDestination) {
        if (next != appState.destination) {
            appState.backStack = appState.backStack + appState.destination
            appState.destination = next
        }
    }

    fun goBack() {
        if (appState.backStack.isNotEmpty()) {
            appState.destination = appState.backStack.last()
            appState.backStack = appState.backStack.dropLast(1)
            if (appState.destination.isHomeOverview()) {
                restoreArtistList(ArtistSortOption.TrackCount)
            }
        }
    }

    fun refreshCurrentPlaylist() {
        val selectedPlaylist = appState.destination.playlistId?.let { playlistId ->
            appState.playlists.firstOrNull { it.id == playlistId }
        }
        if (selectedPlaylist != null) {
            if (selectedPlaylist.isFavoritesPlaylist()) {
                loadFullFavoritesPlaylist(selectedPlaylist)
            } else {
                loadPlaylistTracks(selectedPlaylist, true)
            }
        } else {
            loadLibrary(AppDestination(tab = AppTab.Library))
        }
    }

    fun selectTab(tab: AppTab) {
        val nextDestination = AppDestination(tab = tab)
        if (appState.destination == nextDestination) {
            when (tab) {
                AppTab.Home, AppTab.Library -> loadLibrary(nextDestination)
                AppTab.Search -> {
                    appState.searchQuery = ""
                    appState.searchFocusRequestSerial += 1
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
        if (playlist.isFavoritesPlaylist()) {
            loadFullFavoritesPlaylist(playlist)
        } else {
            loadPlaylistTracks(playlist, true)
        }
    }

    fun showAllArtists() {
        val nextDestination = AppDestination(tab = AppTab.Home, homeRoute = HomeRoute.Artists)
        navigateTo(nextDestination)
        val sortOption = appState.artistSortOption
        if (appState.artistServerSortOption == sortOption && appState.artists.isNotEmpty()) {
            return
        }
        cacheCurrentArtistList()
        val cachedArtists = appState.artistListCache[sortOption]
        if (cachedArtists != null) {
            restoreArtistList(sortOption)
            return
        }
        loadArtists(sortOption)
    }

    private fun cacheCurrentArtistList() {
        val currentSortOption = appState.artistServerSortOption ?: return
        if (appState.artists.isEmpty()) {
            return
        }
        val nextOffset = if (appState.destination.isHomeOverview()) {
            appState.artists.size
        } else {
            appState.libraryPaging.artistNextOffset
        }
        val hasMore = if (appState.destination.isHomeOverview()) {
            appState.artists.size >= HOME_ARTIST_PREVIEW_LIMIT
        } else {
            appState.libraryPaging.artistHasMore
        }
        appState.artistListCache = appState.artistListCache.withArtistListCache(
            sortOption = currentSortOption,
            artists = appState.artists,
            nextOffset = nextOffset,
            hasMore = hasMore,
        )
    }

    private fun restoreArtistList(sortOption: ArtistSortOption) {
        val cachedArtists = appState.artistListCache[sortOption] ?: return
        appState.artists = cachedArtists.artists
        appState.artistServerSortOption = sortOption
        appState.libraryPaging = appState.libraryPaging.copy(
            artistNextOffset = cachedArtists.nextOffset,
            artistHasMore = cachedArtists.hasMore,
            artistLoadingMore = false,
        )
    }

    fun openFullPlayerFromMiniPlayer() {
        appState.playerState.currentTrack?.let { track ->
            loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
        }
        appState.fullPlayerOpen = true
    }

    fun handleRecentItemClick(item: RecentLibraryItem) {
        handleRecentItemClickAction(
            item = item,
            artists = appState.artists,
            albums = appState.albums,
            savedAlbums = appState.savedAlbums,
            searchResults = appState.searchResults,
            similarArtistsByArtist = appState.similarArtistsByArtist,
            albumsByArtist = appState.albumsByArtist,
            appearsOnByArtist = appState.appearsOnByArtist,
            tracks = appState.tracks,
            searchQuery = appState.searchQuery,
            setSearchQuery = { appState.searchQuery = it },
            resolveCachedArtist = resolveCachedArtist,
            openArtist = openArtist,
            openAlbum = openAlbum,
            selectSearchTrack = ::selectSearchTrack,
            setLibraryError = { appState.libraryError = it },
        )
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
}
