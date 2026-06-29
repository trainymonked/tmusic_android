package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.PlaylistPayload
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun loadPlaylistTracksAction(
    scope: CoroutineScope,
    playlist: Playlist,
    force: Boolean,
    canAttemptMetadataRequest: () -> Boolean,
    getSyncMode: () -> SyncMode,
    setSyncMode: (SyncMode) -> Unit,
    getPlaylistTrackLoadsInProgress: () -> Set<String>,
    setPlaylistTrackLoadsInProgress: (Set<String>) -> Unit,
    getPlaylistTrackHasMoreById: () -> Map<String, Boolean>,
    setPlaylistTrackHasMoreById: (Map<String, Boolean>) -> Unit,
    getPlaylists: () -> List<Playlist>,
    getTracks: () -> List<Track>,
    playlistIsFullyDownloaded: (Playlist) -> Boolean,
    musicRepository: RemoteMusicRepository,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    applyPlaylistTrackPage: (Playlist, PlaylistPayload, Boolean) -> Playlist?,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canAttemptMetadataRequest() || playlist.id in getPlaylistTrackLoadsInProgress()) {
        return
    }
    if (getSyncMode() == SyncMode.Offline) {
        setSyncMode(SyncMode.Syncing)
    }
    val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
    if (!force && playlistIsFullyDownloaded(currentPlaylist)) {
        setPlaylistTrackHasMoreById(getPlaylistTrackHasMoreById() + (playlist.id to false))
        return
    }
    val offset = if (force) 0 else currentPlaylist.trackIds.size
    if (!force && currentPlaylist.trackIds.size >= currentPlaylist.trackCount) {
        val loadedTrackIds = getTracks().map { it.id }.toSet()
        val hasMissingTrackModels = currentPlaylist.trackIds.any { it !in loadedTrackIds }
        if (!hasMissingTrackModels) {
            setPlaylistTrackHasMoreById(getPlaylistTrackHasMoreById() + (playlist.id to false))
            return
        }
    }

    setPlaylistTrackLoadsInProgress(getPlaylistTrackLoadsInProgress() + playlist.id)
    scope.launch {
        runCatching {
            val loadedTrackIds = getTracks().map { it.id }.toSet()
            val shouldReloadFromStart = !force &&
                currentPlaylist.trackIds.any { it !in loadedTrackIds }
            if (playlist.isFavoritesPlaylist()) {
                musicRepository.favoritesPlaylistPayloadTrackPage(
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
            setAccessToken(refreshAccessToken())
            setSyncMode(SyncMode.Online)
            val loadedTrackIds = getTracks().map { it.id }.toSet()
            val shouldReloadFromStart = !force &&
                currentPlaylist.trackIds.any { it !in loadedTrackIds }
            val updatedPlaylist = applyPlaylistTrackPage(
                playlist,
                payload,
                !force && !shouldReloadFromStart && offset > 0,
            )
            val loadedCount = payload.tracks.size
            val nextLoadedCount = updatedPlaylist?.trackIds?.size ?: currentPlaylist.trackIds.size
            val totalCount = updatedPlaylist?.trackCount ?: currentPlaylist.trackCount
            setPlaylistTrackHasMoreById(
                getPlaylistTrackHasMoreById() + (
                    playlist.id to (loadedCount >= DETAIL_TRACK_PAGE_LIMIT && nextLoadedCount < totalCount)
                    ),
            )
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setPlaylistTrackLoadsInProgress(getPlaylistTrackLoadsInProgress() - playlist.id)
    }
}

internal fun loadAlbumTracksAction(
    scope: CoroutineScope,
    album: LibraryAlbum,
    force: Boolean,
    canAttemptMetadataRequest: () -> Boolean,
    getSyncMode: () -> SyncMode,
    setSyncMode: (SyncMode) -> Unit,
    getAlbumTrackLoadsInProgress: () -> Set<String>,
    setAlbumTrackLoadsInProgress: (Set<String>) -> Unit,
    getAlbumTrackHasMoreById: () -> Map<String, Boolean>,
    setAlbumTrackHasMoreById: (Map<String, Boolean>) -> Unit,
    getAlbumTracksById: () -> Map<String, List<Track>>,
    setAlbumTracksById: (Map<String, List<Track>>) -> Unit,
    getTracks: () -> List<Track>,
    musicRepository: RemoteMusicRepository,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    mergeLoadedTracks: (List<Track>) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val localTracks = getTracks().filter { track ->
        track.albumId == album.id || (track.album == album.title && track.matchesAlbumArtist(album))
    }
    if (localTracks.isNotEmpty() && !canAttemptMetadataRequest()) {
        setAlbumTracksById(
            getAlbumTracksById() + (album.id to localTracks.sortedBy { it.trackNumber ?: Int.MAX_VALUE }),
        )
        setAlbumTrackHasMoreById(getAlbumTrackHasMoreById() + (album.id to false))
        return
    }
    val currentAlbumTracks = getAlbumTracksById()[album.id].orEmpty()
    val hasServerPagingState = album.id in getAlbumTrackHasMoreById()
    val shouldReloadFromStart = !force &&
        canAttemptMetadataRequest() &&
        currentAlbumTracks.isNotEmpty() &&
        !hasServerPagingState &&
        (album.trackCount <= 0 || currentAlbumTracks.size < album.trackCount)
    val offset = if (force || shouldReloadFromStart) 0 else currentAlbumTracks.size
    if (
        (!force && currentAlbumTracks.size >= album.trackCount && album.trackCount > 0) ||
        !canAttemptMetadataRequest() ||
        album.id in getAlbumTrackLoadsInProgress()
    ) {
        if (!force && currentAlbumTracks.size >= album.trackCount && album.trackCount > 0) {
            setAlbumTrackHasMoreById(getAlbumTrackHasMoreById() + (album.id to false))
        }
        return
    }

    if (getSyncMode() == SyncMode.Offline) {
        setSyncMode(SyncMode.Syncing)
    }
    setAlbumTrackLoadsInProgress(getAlbumTrackLoadsInProgress() + album.id)
    scope.launch {
        runCatching {
            musicRepository.albumTracksPage(
                albumId = album.id,
                limit = DETAIL_TRACK_PAGE_LIMIT,
                offset = offset,
            )
        }.onSuccess { loadedTracks ->
            setAccessToken(refreshAccessToken())
            setSyncMode(SyncMode.Online)
            val orderedTracks = loadedTracks.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
            val nextTracks = if (force || offset == 0) {
                orderedTracks
            } else {
                (currentAlbumTracks + orderedTracks).distinctBy { it.id }
            }
            setAlbumTracksById(getAlbumTracksById() + (album.id to nextTracks))
            mergeLoadedTracks(orderedTracks)
            setAlbumTrackHasMoreById(
                getAlbumTrackHasMoreById() + (
                    album.id to (
                        orderedTracks.size >= DETAIL_TRACK_PAGE_LIMIT &&
                            (album.trackCount <= 0 || nextTracks.size < album.trackCount)
                        )
                    ),
            )
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setAlbumTrackLoadsInProgress(getAlbumTrackLoadsInProgress() - album.id)
    }
}
