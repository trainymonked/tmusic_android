package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal data class MergedLibraryState(
    val playlists: List<Playlist>,
    val tracks: List<Track>,
    val recentAlbums: List<LibraryAlbum>,
    val databaseTrackCount: Int?,
    val artists: List<LibraryArtist>,
    val albums: List<LibraryAlbum>,
    val savedAlbums: List<LibraryAlbum>,
    val artistListNextOffset: Int?,
    val artistListHasMore: Boolean?,
    val albumListNextOffset: Int?,
    val albumListHasMore: Boolean?,
)

internal fun LoadedLibraryState.mergeWithCachedLibrary(
    targetDestination: AppDestination,
    cachedPlaylists: List<Playlist>,
    cachedTracks: List<Track>,
    cachedRecentAlbums: List<LibraryAlbum>,
    cachedTrackCount: Int?,
    cachedArtists: List<LibraryArtist>,
    cachedAlbums: List<LibraryAlbum>,
    cachedSavedAlbums: List<LibraryAlbum>,
    offlineAlbumIds: Set<String>,
): MergedLibraryState {
    val loadedSavedAlbumIds = savedAlbums.orEmpty().map { it.id }.toSet()
    val nextPlaylists = playlists?.let(cachedPlaylists::mergeLoadedPlaylists) ?: cachedPlaylists
    val nextTracks = tracks?.let(cachedTracks::mergeLoadedTracks) ?: cachedTracks
    val nextRecentAlbums = recentAlbums ?: cachedRecentAlbums
    val nextArtists = artists ?: cachedArtists
    val nextAlbums = albums
        ?.map { album ->
            val existingAlbum = cachedAlbums.firstOrNull { it.id == album.id }
                ?: cachedSavedAlbums.firstOrNull { it.id == album.id }
            album.copy(
                artistId = album.artistId ?: existingAlbum?.artistId,
                artistIds = album.artistIds.ifEmpty { existingAlbum?.artistIds.orEmpty() },
                artists = album.artists.ifEmpty { existingAlbum?.artists.orEmpty() },
                savedByCurrentUser = album.savedByCurrentUser ||
                    album.id in loadedSavedAlbumIds ||
                    existingAlbum?.savedByCurrentUser == true,
                isOfflineEnabled = album.isOfflineEnabled ||
                    album.id in offlineAlbumIds ||
                    existingAlbum?.isOfflineEnabled == true,
            )
        }
        ?.sortedAlbumsForDisplay()
        ?: cachedAlbums
    val nextSavedAlbums = savedAlbums
        ?.map { album ->
            val existingAlbum = cachedAlbums.firstOrNull { it.id == album.id }
                ?: cachedSavedAlbums.firstOrNull { it.id == album.id }
            album.copy(
                artistId = album.artistId ?: existingAlbum?.artistId,
                artistIds = album.artistIds.ifEmpty { existingAlbum?.artistIds.orEmpty() },
                artists = album.artists.ifEmpty { existingAlbum?.artists.orEmpty() },
                savedByCurrentUser = true,
                isOfflineEnabled = album.isOfflineEnabled ||
                    album.id in offlineAlbumIds ||
                    existingAlbum?.isOfflineEnabled == true,
            )
        }
        ?: cachedSavedAlbums

    val loadedArtistCount = artists?.size
    val loadedAlbumCount = albums?.size
    return MergedLibraryState(
        playlists = nextPlaylists,
        tracks = nextTracks,
        recentAlbums = nextRecentAlbums,
        databaseTrackCount = trackCount ?: cachedTrackCount,
        artists = nextArtists,
        albums = nextAlbums,
        savedAlbums = nextSavedAlbums,
        artistListNextOffset = loadedArtistCount
            ?.takeIf { targetDestination.tab == AppTab.Home && targetDestination.homeRoute == HomeRoute.Artists },
        artistListHasMore = loadedArtistCount
            ?.takeIf { targetDestination.tab == AppTab.Home && targetDestination.homeRoute == HomeRoute.Artists }
            ?.let { it >= SCREEN_PAGE_LIMIT },
        albumListNextOffset = loadedAlbumCount
            ?.takeIf { targetDestination.tab == AppTab.Home && targetDestination.homeRoute == HomeRoute.Albums },
        albumListHasMore = loadedAlbumCount
            ?.takeIf { targetDestination.tab == AppTab.Home && targetDestination.homeRoute == HomeRoute.Albums }
            ?.let { it >= SCREEN_PAGE_LIMIT },
    )
}
