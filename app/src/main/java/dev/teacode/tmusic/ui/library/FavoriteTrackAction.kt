package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
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
    updateKnownTrackLikedState: (String, Boolean) -> Unit,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    ensureTrackDownloaded: suspend (Track) -> Unit,
    cacheDownloadedAssets: suspend (Track) -> Unit,
    refreshStorageStats: () -> Unit,
    getFavoriteSyncTrackIds: () -> Set<String>,
    setFavoriteSyncTrackIds: (Set<String>) -> Unit,
    getPendingFavoriteStates: () -> Map<String, Boolean>,
    loadArtwork: (String, ArtworkImageSize) -> Unit,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests() || track.id in getPendingFavoriteStates()) {
        val favoritePlaylist = getPlaylists().favoritePlaylistForLocalMutation()
        val wasFavorite = currentFavoriteState(
            track = track,
            playlists = getPlaylists(),
            tracks = getTracks(),
        )
        val nextPlaylist = if (wasFavorite) {
            favoritePlaylist.withoutFavoriteTrack(track)
        } else {
            favoritePlaylist.withFavoriteTrack(track)
        }
        val nextPlaylists = getPlaylists()
            .sanitizeClientPlaylists()
            .updateOrAppendPlaylist(nextPlaylist)
        setPlaylists(nextPlaylists)
        updateKnownTrackLikedState(track.id, !wasFavorite)
        if (!wasFavorite && getTracks().none { it.id == track.id }) {
            setTracks(listOf(track.copy(isLiked = true)) + getTracks())
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
    if (track.id in getFavoriteSyncTrackIds()) {
        return
    }
    setFavoriteSyncTrackIds(getFavoriteSyncTrackIds() + track.id)
    val optimisticPlaylist = if (wasFavorite) {
        localFavoritePlaylist.withoutFavoriteTrack(track)
    } else {
        localFavoritePlaylist.withFavoriteTrack(track)
    }
    val optimisticPlaylists = previousPlaylists
        .sanitizeClientPlaylists()
        .updateOrAppendPlaylist(optimisticPlaylist)
    setPlaylists(optimisticPlaylists)
    updateKnownTrackLikedState(track.id, !wasFavorite)
    if (!wasFavorite && previousTracks.none { it.id == track.id }) {
        setTracks(listOf(track.copy(isLiked = true)) + getTracks())
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

    scope.launch {
        try {
            setLibraryError(null)
            runCatching {
                syncFavoriteTrackState(
                    track = track,
                    shouldBeFavorite = shouldBeFavorite,
                    favoritePlaylistSnapshot = localFavoritePlaylist,
                    getPlaylists = getPlaylists,
                    musicRepository = musicRepository,
                    mergePlaylistPickerMetadata = mergePlaylistPickerMetadata,
                )
            }.onSuccess { updatedPlaylist ->
                setAccessToken(refreshAccessToken())
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
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                val previousFavoritePlaylist = previousPlaylists.firstOrNull { it.isFavoritesPlaylist() }
                val rollbackPlaylists = previousFavoritePlaylist
                    ?.let { playlist -> getPlaylists().sanitizeClientPlaylists().updateOrAppendPlaylist(playlist) }
                    ?: getPlaylists().sanitizeClientPlaylists().filterNot { it.isFavoritesPlaylist() }
                val previousTrack = previousTracks.firstOrNull { it.id == track.id }
                val rollbackTracks = if (previousTrack == null) {
                    getTracks().filterNot { it.id == track.id }
                } else {
                    getTracks().map { currentTrack ->
                        if (currentTrack.id == track.id) previousTrack else currentTrack
                    }
                }
                setPlaylists(rollbackPlaylists)
                setTracks(rollbackTracks)
                updateKnownTrackLikedState(track.id, wasFavorite)
                markServerUnavailable(error)
                setLibraryError(error.userMessage())
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
    val favoritePlaylist = playlists.firstOrNull { it.isFavoritesPlaylist() }
    val knownTrackState = tracks.firstOrNull { it.id == track.id }?.isLiked
    if (knownTrackState != null) {
        return knownTrackState
    }
    if (favoritePlaylist != null && track.id in favoritePlaylist.trackIds) {
        return true
    }
    return track.isLiked == true
}

private suspend fun syncFavoriteTrackState(
    track: Track,
    shouldBeFavorite: Boolean,
    favoritePlaylistSnapshot: Playlist,
    getPlaylists: () -> List<Playlist>,
    musicRepository: RemoteMusicRepository,
    mergePlaylistPickerMetadata: (List<Playlist>) -> Unit,
): Playlist {
    val favoriteMetadata = if (favoritePlaylistSnapshot.id != "favorites") {
        favoritePlaylistSnapshot
    } else {
        findOrLoadFavoritesPlaylistAction(
            getPlaylists = getPlaylists,
            musicRepository = musicRepository,
            mergePlaylistPickerMetadata = mergePlaylistPickerMetadata,
        ) ?: throw IllegalStateException("Favorites playlist was not found.")
    }
    var favoritePlaylist = favoritePlaylistSnapshot.copy(
        id = favoriteMetadata.id,
        title = favoriteMetadata.title,
        isOfflineEnabled = favoriteMetadata.isOfflineEnabled,
        isPublic = favoriteMetadata.isPublic,
        isFavorites = true,
        trackCount = favoritePlaylistSnapshot.trackCount.coerceAtLeast(favoritePlaylistSnapshot.trackIds.size),
        totalDurationSeconds = favoriteMetadata.totalDurationSeconds,
        updatedAt = favoriteMetadata.updatedAt,
    )
    val serverOptimisticPlaylist = if (shouldBeFavorite) {
        favoritePlaylist.withFavoriteTrack(track)
    } else {
        favoritePlaylist.withoutFavoriteTrack(track)
    }
    if (shouldBeFavorite) {
        val serverPlaylist = musicRepository.addTrackToPlaylist(
            playlistId = favoritePlaylist.id,
            trackId = track.id,
        )
        return serverPlaylist.normalizedFavoriteResponse(
            localState = serverOptimisticPlaylist,
            track = track,
            shouldContain = true,
        )
    }

    var playlistTrackIds = favoritePlaylistSnapshot.playlistTrackIdsForTrack(track.id)
    if (playlistTrackIds.isEmpty()) {
        favoritePlaylist = musicRepository.favoritesPlaylistPayload()
            .playlists
            .firstOrNull()
            ?: favoritePlaylist
        playlistTrackIds = favoritePlaylist.playlistTrackIdsForTrack(track.id)
    }
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
                track = track,
                shouldContain = false,
            )
        } catch (error: TMusicApiException) {
            if (error.isNotFound()) {
                nextPlaylist.withoutFavoriteTrack(track)
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

private suspend fun findOrLoadFavoritesPlaylistAction(
    getPlaylists: () -> List<Playlist>,
    musicRepository: RemoteMusicRepository,
    mergePlaylistPickerMetadata: (List<Playlist>) -> Unit,
): Playlist? {
    getPlaylists().firstOrNull { playlist ->
        playlist.isFavoritesPlaylist() && playlist.id != "favorites"
    }?.let { return it }
    val loadedPlaylists = musicRepository.playlistsMetadata()
    mergePlaylistPickerMetadata(loadedPlaylists)
    return loadedPlaylists.firstOrNull { it.isFavoritesPlaylist() }
}
