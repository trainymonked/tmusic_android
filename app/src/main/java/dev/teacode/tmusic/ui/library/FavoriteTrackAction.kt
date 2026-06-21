package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.PlaylistPayload
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.TMusicApiException
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun toggleFavoriteTrackAction(
    scope: CoroutineScope,
    track: Track,
    canUseServerRequests: () -> Boolean,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getTracks: () -> List<Track>,
    setTracks: (List<Track>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    musicRepository: RemoteMusicRepository,
    libraryCacheStore: LibraryCacheStore,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    mergePlaylistPickerMetadata: (List<Playlist>) -> Unit,
    applyPlaylistPayload: (PlaylistPayload) -> Playlist?,
    updateKnownTrackLikedState: (String, Boolean) -> Unit,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    ensureTrackDownloaded: suspend (Track) -> Unit,
    cacheDownloadedAssets: suspend (Track) -> Unit,
    refreshStorageStats: () -> Unit,
    getFavoriteSyncTrackIds: () -> Set<String>,
    setFavoriteSyncTrackIds: (Set<String>) -> Unit,
    loadArtwork: (String, ArtworkImageSize) -> Unit,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests()) {
        val favoritePlaylist = getPlaylists().favoritePlaylistForLocalMutation()
        val wasFavorite = currentFavoriteState(
            track = track,
            playlists = getPlaylists(),
            tracks = getTracks(),
        )
        val nextPlaylist = if (wasFavorite) {
            favoritePlaylist.withoutFavoriteTrack(track.id)
        } else {
            favoritePlaylist.withFavoriteTrack(track.id)
        }
        val nextPlaylists = getPlaylists()
            .sanitizeClientPlaylists()
            .updateOrAppendPlaylist(nextPlaylist)
        setPlaylists(nextPlaylists)
        updateKnownTrackLikedState(track.id, !wasFavorite)
        if (getTracks().none { it.id == track.id }) {
            setTracks(getTracks() + track.copy(isLiked = !wasFavorite))
        }
        enqueueLibraryMutation(
            "favorite.set",
            JSONObject()
                .put("trackId", track.id)
                .put("liked", !wasFavorite),
        )
        saveLibraryCache()
        return
    }

    val previousPlaylists = getPlaylists()
    val previousTracks = getTracks()
    val localFavoritePlaylist = previousPlaylists.favoritePlaylistForLocalMutation()
    val wasFavorite = currentFavoriteState(
        track = track,
        playlists = previousPlaylists,
        tracks = previousTracks,
    )
    val shouldBeFavorite = !wasFavorite
    val optimisticPlaylist = if (wasFavorite) {
        localFavoritePlaylist.withoutFavoriteTrack(track.id)
    } else {
        localFavoritePlaylist.withFavoriteTrack(track.id)
    }
    val optimisticPlaylists = previousPlaylists
        .sanitizeClientPlaylists()
        .updateOrAppendPlaylist(optimisticPlaylist)
    setPlaylists(optimisticPlaylists)
    updateKnownTrackLikedState(track.id, !wasFavorite)
    if (previousTracks.none { it.id == track.id }) {
        setTracks(previousTracks + track.copy(isLiked = !wasFavorite))
    }
    loadArtwork(track.id, ArtworkImageSize.AlbumGrid)
    libraryCacheStore.saveLibrary(
        playlists = optimisticPlaylists,
        tracks = getTracks(),
        savedAlbums = getSavedAlbums(),
    )
    if (shouldBeFavorite && localFavoritePlaylist.isOfflineEnabled && track.downloadState != DownloadState.Downloaded) {
        updateTrackDownloadState(track.id, DownloadState.Queued)
        scope.launch {
            runCatching {
                ensureTrackDownloaded(track)
                cacheDownloadedAssets(track)
            }.onSuccess {
                updateTrackDownloadState(track.id, DownloadState.Downloaded)
                refreshStorageStats()
                saveLibraryCache()
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                updateTrackDownloadState(track.id, DownloadState.NotDownloaded)
                markServerUnavailable(error)
                setLibraryError(error.userMessage())
            }
        }
    }

    if (track.id in getFavoriteSyncTrackIds()) {
        return
    }
    setFavoriteSyncTrackIds(getFavoriteSyncTrackIds() + track.id)
    scope.launch {
        try {
            var syncedState: Boolean? = null
            while (true) {
                val desiredFavoriteState = currentFavoriteState(track, getPlaylists(), getTracks())
                if (desiredFavoriteState == syncedState) {
                    break
                }
                setLibraryError(null)
                runCatching {
                    syncFavoriteTrackState(
                        track = track,
                        shouldBeFavorite = desiredFavoriteState,
                        getPlaylists = getPlaylists,
                        musicRepository = musicRepository,
                        mergePlaylistPickerMetadata = mergePlaylistPickerMetadata,
                        applyPlaylistPayload = applyPlaylistPayload,
                    )
                }.onSuccess { updatedPlaylist ->
                    setAccessToken(refreshAccessToken())
                    syncedState = desiredFavoriteState
                    if (currentFavoriteState(track, getPlaylists(), getTracks()) == desiredFavoriteState) {
                        val nextPlaylists = getPlaylists()
                            .sanitizeClientPlaylists()
                            .updateOrAppendPlaylist(updatedPlaylist)
                        setPlaylists(nextPlaylists)
                        updateKnownTrackLikedState(track.id, track.id in updatedPlaylist.trackIds)
                        libraryCacheStore.saveLibrary(
                            playlists = nextPlaylists,
                            tracks = getTracks(),
                            savedAlbums = getSavedAlbums(),
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                    if (currentFavoriteState(track, getPlaylists(), getTracks()) == desiredFavoriteState) {
                        setPlaylists(previousPlaylists)
                        setTracks(previousTracks)
                    }
                    markServerUnavailable(error)
                    setLibraryError(error.userMessage())
                    break
                }
                if (currentFavoriteState(track, getPlaylists(), getTracks()) == syncedState) {
                    break
                }
            }
        } finally {
            setFavoriteSyncTrackIds(getFavoriteSyncTrackIds() - track.id)
        }
    }
}

private fun currentFavoriteState(
    track: Track,
    playlists: List<Playlist>,
    tracks: List<Track>,
): Boolean {
    val knownTrackState = tracks.firstOrNull { it.id == track.id }?.isLiked
    if (knownTrackState != null) {
        return knownTrackState
    }
    val favoritePlaylist = playlists.firstOrNull { it.isFavoritesPlaylist() }
    if (favoritePlaylist != null) {
        return track.id in favoritePlaylist.trackIds
    }
    return track.isLiked == true
}

private suspend fun syncFavoriteTrackState(
    track: Track,
    shouldBeFavorite: Boolean,
    getPlaylists: () -> List<Playlist>,
    musicRepository: RemoteMusicRepository,
    mergePlaylistPickerMetadata: (List<Playlist>) -> Unit,
    applyPlaylistPayload: (PlaylistPayload) -> Playlist?,
): Playlist {
    var favoritePlaylist = loadFavoritesPlaylistForTrackAction(
        track = track,
        getPlaylists = getPlaylists,
        musicRepository = musicRepository,
        mergePlaylistPickerMetadata = mergePlaylistPickerMetadata,
        applyPlaylistPayload = applyPlaylistPayload,
    ) ?: throw IllegalStateException("Favorites playlist was not found.")
    val serverOptimisticPlaylist = if (shouldBeFavorite) {
        favoritePlaylist.withFavoriteTrack(track.id)
    } else {
        favoritePlaylist.withoutFavoriteTrack(track.id)
    }
    if (shouldBeFavorite) {
        if (track.id in favoritePlaylist.trackIds) {
            return serverOptimisticPlaylist
        }
        val serverPlaylist = musicRepository.addTrackToPlaylist(
            playlistId = favoritePlaylist.id,
            trackId = track.id,
        )
        return serverPlaylist.normalizedFavoriteResponse(
            localState = serverOptimisticPlaylist,
            trackId = track.id,
            shouldContain = true,
        )
    }

    favoritePlaylist = applyPlaylistPayload(
        musicRepository.favoritesPlaylistPayload(favoritePlaylist),
    ) ?: favoritePlaylist
    val playlistTrackIds = favoritePlaylist.playlistTrackIdsForTrack(track.id)
    if (playlistTrackIds.isEmpty()) {
        return serverOptimisticPlaylist
    }
    var nextPlaylist = serverOptimisticPlaylist
    playlistTrackIds.forEach { playlistTrackId ->
        nextPlaylist = try {
            musicRepository.removeTrackFromPlaylist(
                playlistId = favoritePlaylist.id,
                playlistTrackId = playlistTrackId,
            ).normalizedFavoriteResponse(
                localState = nextPlaylist,
                trackId = track.id,
                shouldContain = false,
            )
        } catch (error: TMusicApiException) {
            if (error.isNotFound()) {
                nextPlaylist.withoutFavoriteTrack(track.id)
            } else {
                throw error
            }
        }
    }
    return nextPlaylist
}

private fun TMusicApiException.isNotFound(): Boolean {
    return statusCode == 404 || message?.contains("not found", ignoreCase = true) == true
}

internal suspend fun loadFavoritesPlaylistForTrackAction(
    track: Track,
    getPlaylists: () -> List<Playlist>,
    musicRepository: RemoteMusicRepository,
    mergePlaylistPickerMetadata: (List<Playlist>) -> Unit,
    applyPlaylistPayload: (PlaylistPayload) -> Playlist?,
): Playlist? {
    val favoritePlaylist = findOrLoadFavoritesPlaylistAction(
        getPlaylists = getPlaylists,
        musicRepository = musicRepository,
        mergePlaylistPickerMetadata = mergePlaylistPickerMetadata,
    ) ?: return null
    val cachedHasTrack = track.id in favoritePlaylist.trackIds
    val cachedPlaylistTrackIds = favoritePlaylist.playlistTrackIdsForTrack(track.id)
    val cacheCoversPlaylist = favoritePlaylist.trackCount <= 0 ||
        favoritePlaylist.trackIds.size >= favoritePlaylist.trackCount
    if ((cachedHasTrack && cachedPlaylistTrackIds.isNotEmpty()) || (!cachedHasTrack && cacheCoversPlaylist)) {
        return favoritePlaylist
    }
    val payload = musicRepository.favoritesPlaylistPayload(favoritePlaylist)
    return applyPlaylistPayload(payload) ?: favoritePlaylist
}

private suspend fun findOrLoadFavoritesPlaylistAction(
    getPlaylists: () -> List<Playlist>,
    musicRepository: RemoteMusicRepository,
    mergePlaylistPickerMetadata: (List<Playlist>) -> Unit,
): Playlist? {
    getPlaylists().firstOrNull { it.isFavoritesPlaylist() }?.let { return it }
    val loadedPlaylists = musicRepository.playlistsMetadata()
    mergePlaylistPickerMetadata(loadedPlaylists)
    return loadedPlaylists.firstOrNull { it.isFavoritesPlaylist() }
}
