package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope

internal class AlbumLibraryActionHost(
    private val scope: CoroutineScope,
    private val canUseServerRequests: () -> Boolean,
    private val getOfflineAlbumIds: () -> Set<String>,
    private val getAlbums: () -> List<LibraryAlbum>,
    private val setAlbums: (List<LibraryAlbum>) -> Unit,
    private val getAlbumsByArtist: () -> Map<String, List<LibraryAlbum>>,
    private val setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    private val getAppearsOnByArtist: () -> Map<String, List<LibraryAlbum>>,
    private val setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    private val getPlaylists: () -> List<Playlist>,
    private val getTracks: () -> List<Track>,
    private val musicRepository: RemoteMusicRepository,
    private val libraryCacheStore: LibraryCacheStore,
    private val enqueueLibraryMutation: (String, org.json.JSONObject) -> Unit,
    private val saveLibraryCache: () -> Unit,
    private val refreshAccessToken: () -> String?,
    private val setAccessToken: (String?) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setLibraryError: (String?) -> Unit,
) {
    fun toggleAlbumInLibrary(album: LibraryAlbum) {
        toggleAlbumInLibraryAction(
            scope = scope,
            album = album,
            canUseServerRequests = canUseServerRequests,
            getOfflineAlbumIds = getOfflineAlbumIds,
            getAlbums = getAlbums,
            setAlbums = setAlbums,
            getAlbumsByArtist = getAlbumsByArtist,
            setAlbumsByArtist = setAlbumsByArtist,
            getAppearsOnByArtist = getAppearsOnByArtist,
            setAppearsOnByArtist = setAppearsOnByArtist,
            getSavedAlbums = getSavedAlbums,
            setSavedAlbums = setSavedAlbums,
            getPlaylists = getPlaylists,
            getTracks = getTracks,
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            enqueueLibraryMutation = enqueueLibraryMutation,
            saveLibraryCache = saveLibraryCache,
            refreshAccessToken = refreshAccessToken,
            setAccessToken = setAccessToken,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }
}
