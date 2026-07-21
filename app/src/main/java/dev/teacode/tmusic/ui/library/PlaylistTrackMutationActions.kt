package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.TMusicApiException
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun addTrackToPlaylistAction(
    scope: CoroutineScope,
    playlist: Playlist,
    track: Track,
    canUseServerRequests: () -> Boolean,
    getPlaylistAddInProgress: () -> Boolean,
    setPlaylistAddInProgress: (Boolean) -> Unit,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getPlaylistPickerPlaylists: () -> List<Playlist>,
    setPlaylistPickerPlaylists: (List<Playlist>) -> Unit,
    getTracks: () -> List<Track>,
    setTracks: (List<Track>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    musicRepository: RemoteMusicRepository,
    libraryCacheStore: LibraryCacheStore,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    ensureTrackDownloaded: suspend (Track) -> Unit,
    cacheDownloadedAssets: suspend (Track) -> Unit,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    refreshStorageStats: () -> Unit,
    setTrackForPlaylistAdd: (Track?) -> Unit,
    setDuplicatePlaylistForAdd: (Playlist?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests()) {
        val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
        val localPlaylistTrackId = "local-playlist-track:${UUID.randomUUID()}"
        val updatedPlaylist = currentPlaylist.copy(
            trackIds = currentPlaylist.trackIds + track.id,
            playlistTrackIds = currentPlaylist.playlistTrackIds + localPlaylistTrackId,
            playlistTrackIdsByTrackId = currentPlaylist.playlistTrackIdsByTrackId + (track.id to localPlaylistTrackId),
            trackCount = maxOf(currentPlaylist.trackCount + 1, currentPlaylist.trackIds.size + 1),
        )
        setPlaylists(getPlaylists().updateOrAppendPlaylist(updatedPlaylist))
        setPlaylistPickerPlaylists(
            getPlaylistPickerPlaylists().updateOrAppendPlaylist(updatedPlaylist.emptyTrackMembership()),
        )
        if (getTracks().none { existingTrack -> existingTrack.id == track.id }) {
            setTracks(getTracks() + track)
        }
        enqueueLibraryMutation(
            "playlist.track.add",
            JSONObject()
                .put("playlistId", playlist.id)
                .put("trackId", track.id)
                .put("clientPlaylistTrackId", localPlaylistTrackId),
        )
        saveLibraryCache()
        setTrackForPlaylistAdd(null)
        setDuplicatePlaylistForAdd(null)
        return
    }
    if (getPlaylistAddInProgress()) {
        return
    }

    setPlaylistAddInProgress(true)
    scope.launch {
        setLibraryError(null)
        runCatching {
            musicRepository.addTrackToPlaylist(
                playlistId = playlist.id,
                trackId = track.id,
            )
        }.onSuccess { serverPlaylist ->
            setAccessToken(refreshAccessToken())
            val updatedPlaylist = (serverPlaylist ?: playlist.copy(
                trackIds = playlist.trackIds + track.id,
                trackCount = playlist.trackCount.coerceAtLeast(playlist.trackIds.size) + 1,
            )).copy(
                isOfflineEnabled = serverPlaylist?.isOfflineEnabled == true || playlist.isOfflineEnabled,
                isFavorites = serverPlaylist?.isFavorites == true || playlist.isFavorites,
            )
            val nextPlaylists = getPlaylists().updateOrAppendPlaylist(updatedPlaylist)
            setPlaylists(nextPlaylists)
            if (getTracks().none { existingTrack -> existingTrack.id == track.id }) {
                setTracks(getTracks() + track)
            }
            setPlaylistPickerPlaylists(
                getPlaylistPickerPlaylists().updateOrAppendPlaylist(updatedPlaylist.emptyTrackMembership()),
            )
            libraryCacheStore.saveLibrary(
                playlists = nextPlaylists,
                tracks = getTracks(),
                savedAlbums = getSavedAlbums(),
            )
            if (playlist.isOfflineEnabled && track.downloadState != DownloadState.Downloaded) {
                updateTrackDownloadState(track.id, DownloadState.Queued)
                libraryCacheStore.saveLibrary(
                    playlists = getPlaylists(),
                    tracks = getTracks(),
                    savedAlbums = getSavedAlbums(),
                )
                runCatching {
                    ensureTrackDownloaded(track)
                    cacheDownloadedAssets(track)
                }.onSuccess {
                    updateTrackDownloadState(track.id, DownloadState.Downloaded)
                    refreshStorageStats()
                }.onFailure {
                    updateTrackDownloadState(track.id, DownloadState.NotDownloaded)
                }
            }
            setTrackForPlaylistAdd(null)
            setDuplicatePlaylistForAdd(null)
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setPlaylistAddInProgress(false)
    }
}

internal fun removeTrackFromPlaylistAction(
    scope: CoroutineScope,
    playlist: Playlist,
    playlistTrackId: String,
    trackId: String,
    canUseServerRequests: () -> Boolean,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getTracks: () -> List<Track>,
    getSavedAlbums: () -> List<LibraryAlbum>,
    getOfflineAlbumIds: () -> Set<String>,
    getAlbumTracksById: () -> Map<String, List<Track>>,
    musicRepository: RemoteMusicRepository,
    libraryCacheStore: LibraryCacheStore,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    refreshStorageStats: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    fun currentPlaylist(): Playlist {
        return getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
    }

    if (!canUseServerRequests()) {
        val currentPlaylist = currentPlaylist()
        val effectivePlaylistTrackId = playlistTrackId
        val removedIndex = currentPlaylist.playlistTrackIds.indexOf(effectivePlaylistTrackId)
        val removedTrackId = currentPlaylist.trackIds.getOrNull(removedIndex)
        val updatedPlaylist = currentPlaylist.withoutPlaylistTrackId(effectivePlaylistTrackId, trackId)
        setPlaylists(getPlaylists().updatePlaylist(updatedPlaylist))
        enqueueLibraryMutation(
            "playlist.track.remove",
            JSONObject()
                .put("playlistId", playlist.id)
                .put("playlistTrackId", effectivePlaylistTrackId)
                .put("trackId", removedTrackId),
        )
        saveLibraryCache()
        removedTrackId?.let { removedTrackId ->
            scope.launch {
                if (removePlaylistTrackDownloadIfUnretained(
                        trackId = removedTrackId,
                        playlists = getPlaylists(),
                        tracks = getTracks(),
                        offlineAlbumIds = getOfflineAlbumIds(),
                        albumTracksById = getAlbumTracksById(),
                        musicRepository = musicRepository,
                        updateTrackDownloadState = updateTrackDownloadState,
                    )
                ) {
                    saveLibraryCache()
                    refreshStorageStats()
                }
            }
        }
        return
    }

    scope.launch {
        setLibraryError(null)
        val previousPlaylists = getPlaylists()
        val latestPlaylist = currentPlaylist()
        val effectivePlaylistTrackId = playlistTrackId
        val removedIndex = latestPlaylist.playlistTrackIds.indexOf(effectivePlaylistTrackId)
        val removedTrackId = latestPlaylist.trackIds.getOrNull(removedIndex)
        setPlaylists(
            getPlaylists().updatePlaylist(
                latestPlaylist.withoutPlaylistTrackId(effectivePlaylistTrackId, trackId),
            ),
        )
        runCatching {
            musicRepository.removeTrackFromPlaylist(
                playlistId = playlist.id,
                playlistTrackId = effectivePlaylistTrackId,
            )
        }.onSuccess { serverPlaylist ->
            setAccessToken(refreshAccessToken())
            val locallyRemovedPlaylist = currentPlaylist().withoutPlaylistTrackId(effectivePlaylistTrackId, trackId)
            val updatedPlaylist = serverPlaylist?.copy(
                trackIds = locallyRemovedPlaylist.trackIds,
                playlistTrackIds = locallyRemovedPlaylist.playlistTrackIds,
                playlistTrackIdsByTrackId = locallyRemovedPlaylist.playlistTrackIdsByTrackId,
                isOfflineEnabled = serverPlaylist.isOfflineEnabled || locallyRemovedPlaylist.isOfflineEnabled,
                isFavorites = serverPlaylist.isFavorites || locallyRemovedPlaylist.isFavorites,
            ) ?: locallyRemovedPlaylist
            val nextPlaylists = getPlaylists().updatePlaylist(updatedPlaylist)
            setPlaylists(nextPlaylists)
            removedTrackId?.let { trackId ->
                if (removePlaylistTrackDownloadIfUnretained(
                        trackId = trackId,
                        playlists = nextPlaylists,
                        tracks = getTracks(),
                        offlineAlbumIds = getOfflineAlbumIds(),
                        albumTracksById = getAlbumTracksById(),
                        musicRepository = musicRepository,
                        updateTrackDownloadState = updateTrackDownloadState,
                    )
                ) {
                    refreshStorageStats()
                }
            }
            libraryCacheStore.saveLibrary(
                playlists = nextPlaylists,
                tracks = getTracks(),
                savedAlbums = getSavedAlbums(),
            )
        }.onFailure { error ->
            if (error is TMusicApiException && error.isNotFound()) {
                val nextPlaylists = getPlaylists().updatePlaylist(
                    currentPlaylist().withoutPlaylistTrackId(effectivePlaylistTrackId, trackId),
                )
                setPlaylists(nextPlaylists)
                libraryCacheStore.saveLibrary(
                    playlists = nextPlaylists,
                    tracks = getTracks(),
                    savedAlbums = getSavedAlbums(),
                )
            } else {
                setPlaylists(previousPlaylists)
                markServerUnavailable(error)
                setLibraryError(error.userMessage())
            }
        }
    }
}

private suspend fun removePlaylistTrackDownloadIfUnretained(
    trackId: String,
    playlists: List<Playlist>,
    tracks: List<Track>,
    offlineAlbumIds: Set<String>,
    albumTracksById: Map<String, List<Track>>,
    musicRepository: RemoteMusicRepository,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
): Boolean {
    val offlineIndex = offlineLibraryIndex(
        playlists = playlists,
        tracks = tracks,
        offlineAlbumIds = offlineAlbumIds,
        albumTracksById = albumTracksById,
    )
    val track = offlineIndex.tracksById[trackId] ?: return false
    if (track.downloadState != DownloadState.Downloaded || offlineIndex.isRequiredByCollection(trackId)) {
        return false
    }
    musicRepository.removeDownloadedTrack(trackId)
    updateTrackDownloadState(trackId, DownloadState.NotDownloaded)
    return true
}

private fun Playlist.emptyTrackMembership(): Playlist {
    return copy(trackIds = emptyList(), playlistTrackIds = emptyList(), playlistTrackIdsByTrackId = emptyMap())
}

private fun TMusicApiException.isNotFound(): Boolean {
    return statusCode == 404 || message?.contains("not found", ignoreCase = true) == true
}
