package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal suspend fun fetchLibraryState(
    targetDestination: AppDestination,
    authRepository: RemoteAuthRepository,
    musicRepository: RemoteMusicRepository,
): LoadedLibraryState {
    val loadedAccount = authRepository.currentAccount()
    var loadedPlaylists: List<Playlist>? = null
    var loadedTracks: List<Track>? = null
    var loadedArtists: List<LibraryArtist>? = null
    var loadedAlbums: List<LibraryAlbum>? = null
    var loadedSavedAlbums: List<LibraryAlbum>? = null

    when (targetDestination.tab) {
        AppTab.Home -> when (targetDestination.homeRoute) {
            HomeRoute.Overview -> {
                val artistPage = musicRepository.libraryArtistsPageWithTotal(limit = HOME_ARTIST_PREVIEW_LIMIT)
                loadedArtists = artistPage.artists.filterOwnReleaseArtists()
                loadedTracks = musicRepository.recentTracks(limit = 25)
            }
            HomeRoute.Artists -> {
                val artistPage = musicRepository.libraryArtistsPageWithTotal(limit = SCREEN_PAGE_LIMIT)
                loadedArtists = artistPage.artists.filterOwnReleaseArtists()
            }
            HomeRoute.Albums -> {
                loadedAlbums = musicRepository.libraryAlbumsPage(limit = SCREEN_PAGE_LIMIT)
            }
            HomeRoute.Artist,
            HomeRoute.Album -> Unit
        }
        AppTab.Library -> {
            val loadedLibrary = musicRepository.libraryPage(
                playlistLimit = SCREEN_PAGE_LIMIT,
                trackLimit = 0,
            )
            loadedPlaylists = loadedLibrary.playlists
            loadedTracks = loadedLibrary.tracks
            loadedSavedAlbums = musicRepository.savedAlbumsPage(limit = SCREEN_PAGE_LIMIT)
        }
        AppTab.Search,
        AppTab.Profile -> Unit
    }

    return LoadedLibraryState(
        account = loadedAccount,
        playlists = loadedPlaylists,
        tracks = loadedTracks,
        recentTracks = if (targetDestination.isHomeOverview()) loadedTracks else null,
        trackCount = if (targetDestination.isHomeOverview()) {
            runCatching { musicRepository.tracksCount() }.getOrNull()
        } else {
            null
        },
        artists = loadedArtists,
        albums = loadedAlbums,
        savedAlbums = loadedSavedAlbums,
        lastFmConnection = loadedAccount?.lastFmConnection,
    )
}
