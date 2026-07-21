package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun createPlaylistAction(
    scope: CoroutineScope,
    name: String,
    canUseServerRequests: () -> Boolean,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getPlaylistPickerPlaylists: () -> List<Playlist>,
    setPlaylistPickerPlaylists: (List<Playlist>) -> Unit,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setAccessToken: (String?) -> Unit,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    loadLibrary: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val trimmedName = name.trim()
    if (trimmedName.isBlank()) {
        setLibraryError("Playlist name is required.")
        return
    }
    if (!canUseServerRequests()) {
        val localPlaylistId = "local-playlist:${UUID.randomUUID()}"
        val localPlaylist = Playlist(
            id = localPlaylistId,
            title = trimmedName,
            trackIds = emptyList(),
            isOfflineEnabled = false,
        )
        setPlaylists(getPlaylists().updateOrAppendPlaylist(localPlaylist))
        setPlaylistPickerPlaylists(getPlaylistPickerPlaylists().updateOrAppendPlaylist(localPlaylist))
        enqueueLibraryMutation(
            "playlist.create",
            JSONObject()
                .put("clientPlaylistId", localPlaylistId)
                .put("name", trimmedName),
        )
        saveLibraryCache()
        return
    }

    scope.launch {
        setLibraryError(null)
        runCatching {
            musicRepository.createPlaylist(trimmedName)
        }.onSuccess {
            setAccessToken(authRepository.accessToken())
            loadLibrary()
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
    }
}

internal fun updatePlaylistDetailsAction(
    scope: CoroutineScope,
    playlist: Playlist,
    name: String,
    canUseServerRequests: () -> Boolean,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getPlaylistPickerPlaylists: () -> List<Playlist>,
    setPlaylistPickerPlaylists: (List<Playlist>) -> Unit,
    getTracks: () -> List<Track>,
    getSavedAlbums: () -> List<LibraryAlbum>,
    musicRepository: RemoteMusicRepository,
    libraryCacheStore: LibraryCacheStore,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    loadLibrary: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val trimmedName = name.trim()
    if (trimmedName.isBlank()) {
        setLibraryError("Playlist name is required.")
        return
    }
    if (!canUseServerRequests()) {
        val updatedPlaylist = playlist.copy(title = trimmedName)
        setPlaylists(getPlaylists().updatePlaylist(updatedPlaylist))
        setPlaylistPickerPlaylists(getPlaylistPickerPlaylists().updatePlaylist(updatedPlaylist))
        enqueueLibraryMutation(
            "playlist.update",
            JSONObject()
                .put("playlistId", playlist.id)
                .put("name", trimmedName),
        )
        saveLibraryCache()
        return
    }

    scope.launch {
        setLibraryError(null)
        runCatching {
            musicRepository.updatePlaylist(
                playlistId = playlist.id,
                name = trimmedName,
            )
        }.onSuccess { updatedPlaylist ->
            setAccessToken(refreshAccessToken())
            if (updatedPlaylist != null) {
                val nextPlaylists = getPlaylists().updatePlaylist(updatedPlaylist)
                setPlaylists(nextPlaylists)
                libraryCacheStore.saveLibrary(
                    playlists = nextPlaylists,
                    tracks = getTracks(),
                    savedAlbums = getSavedAlbums(),
                )
            } else {
                loadLibrary()
            }
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
    }
}

internal fun deletePlaylistAction(
    scope: CoroutineScope,
    playlist: Playlist,
    destination: AppDestination,
    canUseServerRequests: () -> Boolean,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getPlaylistPickerPlaylists: () -> List<Playlist>,
    setPlaylistPickerPlaylists: (List<Playlist>) -> Unit,
    getTracks: () -> List<Track>,
    getSavedAlbums: () -> List<LibraryAlbum>,
    getOfflineAlbumIds: () -> Set<String>,
    getAlbumTracksById: () -> Map<String, List<Track>>,
    musicRepository: RemoteMusicRepository,
    libraryCacheStore: LibraryCacheStore,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    refreshStorageStats: () -> Unit,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    navigateTo: (AppDestination) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (playlist.isFavoritesPlaylist()) {
        return
    }
    if (!canUseServerRequests()) {
        val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
        val nextPlaylists = getPlaylists().filterNot { it.id == playlist.id }
        setPlaylists(nextPlaylists)
        setPlaylistPickerPlaylists(getPlaylistPickerPlaylists().filterNot { it.id == playlist.id })
        enqueueLibraryMutation("playlist.delete", JSONObject().put("playlistId", playlist.id))
        saveLibraryCache()
        if (currentPlaylist.isOfflineEnabled) {
            scope.launch {
                removePlaylistDownloadsNoLongerRequired(
                    playlist = currentPlaylist,
                    playlists = nextPlaylists,
                    tracks = getTracks(),
                    offlineAlbumIds = getOfflineAlbumIds(),
                    albumTracksById = getAlbumTracksById(),
                    musicRepository = musicRepository,
                    updateTrackDownloadState = updateTrackDownloadState,
                )
                saveLibraryCache()
                refreshStorageStats()
            }
        }
        if (destination.playlistId == playlist.id) {
            navigateTo(AppDestination(tab = AppTab.Library))
        }
        return
    }

    scope.launch {
        setLibraryError(null)
        runCatching {
            musicRepository.deletePlaylist(playlist.id)
        }.onSuccess {
            setAccessToken(refreshAccessToken())
            val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
            val nextPlaylists = getPlaylists().filterNot { it.id == playlist.id }
            setPlaylists(nextPlaylists)
            setPlaylistPickerPlaylists(getPlaylistPickerPlaylists().filterNot { it.id == playlist.id })
            if (currentPlaylist.isOfflineEnabled) {
                removePlaylistDownloadsNoLongerRequired(
                    playlist = currentPlaylist,
                    playlists = nextPlaylists,
                    tracks = getTracks(),
                    offlineAlbumIds = getOfflineAlbumIds(),
                    albumTracksById = getAlbumTracksById(),
                    musicRepository = musicRepository,
                    updateTrackDownloadState = updateTrackDownloadState,
                )
            }
            libraryCacheStore.saveLibrary(
                playlists = nextPlaylists,
                tracks = getTracks(),
                savedAlbums = getSavedAlbums(),
            )
            refreshStorageStats()
            if (destination.playlistId == playlist.id) {
                navigateTo(AppDestination(tab = AppTab.Library))
            }
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
    }
}

private suspend fun removePlaylistDownloadsNoLongerRequired(
    playlist: Playlist,
    playlists: List<Playlist>,
    tracks: List<Track>,
    offlineAlbumIds: Set<String>,
    albumTracksById: Map<String, List<Track>>,
    musicRepository: RemoteMusicRepository,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
) {
    val deletePlan = playlistDownloadDeletePlan(
        playlist = playlist,
        playlists = playlists,
        tracks = tracks,
        albumTracksById = albumTracksById,
        offlineAlbumIds = offlineAlbumIds,
    )
    deletePlan.queuedTrackIds.forEach { trackId ->
        updateTrackDownloadState(trackId, DownloadState.NotDownloaded)
    }
    deletePlan.downloadedTrackIdsToCache.forEach { trackId ->
        musicRepository.removeDownloadedTrack(trackId)
        updateTrackDownloadState(trackId, DownloadState.NotDownloaded)
    }
}

internal fun requestAddTrackToPlaylistAction(
    scope: CoroutineScope,
    playlist: Playlist,
    track: Track,
    allowDuplicate: Boolean,
    canUseServerRequests: () -> Boolean,
    getPlaylists: () -> List<Playlist>,
    getPlaylistAddInProgress: () -> Boolean,
    setPlaylistAddInProgress: (Boolean) -> Unit,
    loadPlaylistForMembershipCheck: suspend (Playlist, Track) -> Playlist,
    addTrackToPlaylist: (Playlist, Track) -> Unit,
    setDuplicatePlaylistForAdd: (Playlist?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests()) {
        val checkedPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
        if (!allowDuplicate && track.id in checkedPlaylist.trackIds) {
            setDuplicatePlaylistForAdd(checkedPlaylist)
        } else {
            addTrackToPlaylist(checkedPlaylist, track)
        }
        return
    }
    if (allowDuplicate) {
        addTrackToPlaylist(playlist, track)
        return
    }
    if (getPlaylistAddInProgress()) {
        return
    }

    setPlaylistAddInProgress(true)
    scope.launch {
        setLibraryError(null)
        runCatching {
            loadPlaylistForMembershipCheck(playlist, track)
        }.onSuccess { checkedPlaylist ->
            if (track.id in checkedPlaylist.trackIds) {
                setDuplicatePlaylistForAdd(checkedPlaylist)
                setPlaylistAddInProgress(false)
            } else {
                setPlaylistAddInProgress(false)
                addTrackToPlaylist(checkedPlaylist, track)
            }
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
            setPlaylistAddInProgress(false)
        }
    }
}
