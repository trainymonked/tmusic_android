package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

internal class PlaylistMutationActionHost(
    private val scope: CoroutineScope,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val libraryCacheStore: LibraryCacheStore,
    private val canUseServerRequests: () -> Boolean,
    private val getDestination: () -> AppDestination,
    private val getPlaylistAddInProgress: () -> Boolean,
    private val setPlaylistAddInProgress: (Boolean) -> Unit,
    private val getPlaylists: () -> List<Playlist>,
    private val setPlaylists: (List<Playlist>) -> Unit,
    private val getPlaylistPickerPlaylists: () -> List<Playlist>,
    private val setPlaylistPickerPlaylists: (List<Playlist>) -> Unit,
    private val getTracks: () -> List<Track>,
    private val setTracks: (List<Track>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val setAccessToken: (String?) -> Unit,
    private val enqueueLibraryMutation: (String, JSONObject) -> Unit,
    private val saveLibraryCache: () -> Unit,
    private val loadLibrary: () -> Unit,
    private val navigateTo: (AppDestination) -> Unit,
    private val loadPlaylistForMembershipCheck: suspend (Playlist, Track) -> Playlist,
    private val ensureTrackDownloaded: suspend (Track) -> Unit,
    private val cacheDownloadedAssets: suspend (Track) -> Unit,
    private val updateTrackDownloadState: (String, DownloadState) -> Unit,
    private val refreshStorageStats: () -> Unit,
    private val setTrackForPlaylistAdd: (Track?) -> Unit,
    private val setDuplicatePlaylistForAdd: (Playlist?) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setLibraryError: (String?) -> Unit,
) {
    fun createPlaylist(name: String) {
        createPlaylistAction(
            scope = scope,
            name = name,
            canUseServerRequests = canUseServerRequests,
            getPlaylists = getPlaylists,
            setPlaylists = setPlaylists,
            getPlaylistPickerPlaylists = getPlaylistPickerPlaylists,
            setPlaylistPickerPlaylists = setPlaylistPickerPlaylists,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = setAccessToken,
            enqueueLibraryMutation = enqueueLibraryMutation,
            saveLibraryCache = saveLibraryCache,
            loadLibrary = loadLibrary,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun updatePlaylistDetails(playlist: Playlist, name: String) {
        updatePlaylistDetailsAction(
            scope = scope,
            playlist = playlist,
            name = name,
            canUseServerRequests = canUseServerRequests,
            getPlaylists = getPlaylists,
            setPlaylists = setPlaylists,
            getPlaylistPickerPlaylists = getPlaylistPickerPlaylists,
            setPlaylistPickerPlaylists = setPlaylistPickerPlaylists,
            getTracks = getTracks,
            getSavedAlbums = getSavedAlbums,
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            enqueueLibraryMutation = enqueueLibraryMutation,
            saveLibraryCache = saveLibraryCache,
            loadLibrary = loadLibrary,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun deletePlaylist(playlist: Playlist) {
        deletePlaylistAction(
            scope = scope,
            playlist = playlist,
            destination = getDestination(),
            canUseServerRequests = canUseServerRequests,
            getPlaylists = getPlaylists,
            setPlaylists = setPlaylists,
            getPlaylistPickerPlaylists = getPlaylistPickerPlaylists,
            setPlaylistPickerPlaylists = setPlaylistPickerPlaylists,
            getTracks = getTracks,
            getSavedAlbums = getSavedAlbums,
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            enqueueLibraryMutation = enqueueLibraryMutation,
            saveLibraryCache = saveLibraryCache,
            navigateTo = navigateTo,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun addTrackToPlaylist(playlist: Playlist, track: Track) {
        addTrackToPlaylistAction(
            scope = scope,
            playlist = playlist,
            track = track,
            canUseServerRequests = canUseServerRequests,
            getPlaylistAddInProgress = getPlaylistAddInProgress,
            setPlaylistAddInProgress = setPlaylistAddInProgress,
            getPlaylists = getPlaylists,
            setPlaylists = setPlaylists,
            getPlaylistPickerPlaylists = getPlaylistPickerPlaylists,
            setPlaylistPickerPlaylists = setPlaylistPickerPlaylists,
            getTracks = getTracks,
            setTracks = setTracks,
            getSavedAlbums = getSavedAlbums,
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            enqueueLibraryMutation = enqueueLibraryMutation,
            saveLibraryCache = saveLibraryCache,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            ensureTrackDownloaded = ensureTrackDownloaded,
            cacheDownloadedAssets = cacheDownloadedAssets,
            updateTrackDownloadState = updateTrackDownloadState,
            refreshStorageStats = refreshStorageStats,
            setTrackForPlaylistAdd = setTrackForPlaylistAdd,
            setDuplicatePlaylistForAdd = setDuplicatePlaylistForAdd,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun requestAddTrackToPlaylist(
        playlist: Playlist,
        track: Track,
        allowDuplicate: Boolean = false,
    ) {
        requestAddTrackToPlaylistAction(
            scope = scope,
            playlist = playlist,
            track = track,
            allowDuplicate = allowDuplicate,
            canUseServerRequests = canUseServerRequests,
            getPlaylists = getPlaylists,
            getPlaylistAddInProgress = getPlaylistAddInProgress,
            setPlaylistAddInProgress = setPlaylistAddInProgress,
            loadPlaylistForMembershipCheck = loadPlaylistForMembershipCheck,
            addTrackToPlaylist = ::addTrackToPlaylist,
            setDuplicatePlaylistForAdd = setDuplicatePlaylistForAdd,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun removeTrackFromPlaylist(playlist: Playlist, playlistTrackId: String, trackId: String) {
        removeTrackFromPlaylistAction(
            scope = scope,
            playlist = playlist,
            playlistTrackId = playlistTrackId,
            trackId = trackId,
            canUseServerRequests = canUseServerRequests,
            getPlaylists = getPlaylists,
            setPlaylists = setPlaylists,
            getTracks = getTracks,
            getSavedAlbums = getSavedAlbums,
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            enqueueLibraryMutation = enqueueLibraryMutation,
            saveLibraryCache = saveLibraryCache,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            updateTrackDownloadState = updateTrackDownloadState,
            refreshStorageStats = refreshStorageStats,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun reorderPlaylistTracks(playlist: Playlist, playlistTrackIds: List<String>) {
        reorderPlaylistTracksAction(
            playlist = playlist,
            playlistTrackIds = playlistTrackIds,
            playlists = getPlaylists(),
            tracks = getTracks(),
            savedAlbums = getSavedAlbums(),
            scope = scope,
            musicRepository = musicRepository,
            authRepository = authRepository,
            libraryCacheStore = libraryCacheStore,
            canUseServerRequests = canUseServerRequests,
            updatePlaylists = setPlaylists,
            enqueueLibraryMutation = enqueueLibraryMutation,
            saveLibraryCache = saveLibraryCache,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
            setAccessToken = setAccessToken,
        )
    }
}
