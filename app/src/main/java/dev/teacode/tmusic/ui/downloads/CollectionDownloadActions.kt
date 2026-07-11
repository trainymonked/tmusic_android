package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.MusicRepository
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class CollectionDownloadDeletePlan(
    val queuedTrackIds: Set<String>,
    val downloadedTrackIdsToCache: Set<String>,
)

internal class CollectionDownloadPauseRegistry {
    private val playlistIds = mutableSetOf<String>()
    private val albumIds = mutableSetOf<String>()

    fun pausePlaylist(id: String) {
        playlistIds += id
    }

    fun resumePlaylist(id: String) {
        playlistIds -= id
    }

    fun isPlaylistPaused(id: String): Boolean = id in playlistIds

    fun pauseAlbum(id: String) {
        albumIds += id
    }

    fun resumeAlbum(id: String) {
        albumIds -= id
    }

    fun isAlbumPaused(id: String): Boolean = id in albumIds

    fun clear() {
        playlistIds.clear()
        albumIds.clear()
    }
}

private val collectionDownloadPauseRegistry = CollectionDownloadPauseRegistry()
private const val COLLECTION_DOWNLOAD_TRACK_PAGE_LIMIT = 500

internal fun resumePlaylistDownloadPause(id: String) {
    collectionDownloadPauseRegistry.resumePlaylist(id)
}

internal fun resumeAlbumDownloadPause(id: String) {
    collectionDownloadPauseRegistry.resumeAlbum(id)
}

internal fun isPlaylistDownloadPaused(id: String): Boolean {
    return collectionDownloadPauseRegistry.isPlaylistPaused(id)
}

internal fun isAlbumDownloadPaused(id: String): Boolean {
    return collectionDownloadPauseRegistry.isAlbumPaused(id)
}

internal fun clearCollectionDownloadPauses() {
    collectionDownloadPauseRegistry.clear()
}

internal fun playlistDownloadDeletePlan(
    playlist: Playlist,
    playlists: List<Playlist>,
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
    offlineAlbumIds: Set<String>,
): CollectionDownloadDeletePlan {
    return collectionDownloadDeletePlan(
        trackIds = playlist.trackIds.toSet(),
        tracks = tracks,
        isRequiredByOtherCollection = { trackId ->
            trackIsRequiredByDownloadedCollection(
                trackId = trackId,
                playlists = playlists,
                albumTracksById = albumTracksById,
                offlineAlbumIds = offlineAlbumIds,
                excludingPlaylistId = playlist.id,
            )
        },
    )
}

internal fun albumDownloadDeletePlan(
    album: LibraryAlbum,
    albumTracks: List<Track>,
    tracks: List<Track>,
    playlists: List<Playlist>,
    albumTracksById: Map<String, List<Track>>,
    offlineAlbumIds: Set<String>,
): CollectionDownloadDeletePlan {
    val trackIds = (albumTracks + albumTracksById[album.id].orEmpty())
        .map { it.id }
        .toSet()
    return collectionDownloadDeletePlan(
        trackIds = trackIds,
        tracks = tracks,
        isRequiredByOtherCollection = { trackId ->
            trackIsRequiredByDownloadedCollection(
                trackId = trackId,
                playlists = playlists,
                albumTracksById = albumTracksById,
                offlineAlbumIds = offlineAlbumIds,
                excludingAlbumId = album.id,
            )
        },
    )
}

internal fun queuedPlaylistDownloadTrackIds(
    playlist: Playlist,
    tracks: List<Track>,
): Set<String> = playlist.trackIds
    .filter { trackId -> tracks.firstOrNull { it.id == trackId }?.downloadState == DownloadState.Queued }
    .toSet()

internal fun queuedAlbumDownloadTrackIds(
    album: LibraryAlbum,
    albumTracks: List<Track>,
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
): Set<String> = (albumTracks + albumTracksById[album.id].orEmpty())
    .map { it.id }
    .filter { trackId -> tracks.firstOrNull { it.id == trackId }?.downloadState == DownloadState.Queued }
    .toSet()

internal fun Map<String, Job>.activeDownloadIds(): Set<String> {
    return filterValues { job -> job.isActive }.keys
}

private fun Playlist.cachedDownloadSourceOrNull(tracks: List<Track>): PlaylistDownloadSource? {
    if (trackIds.isEmpty()) {
        return null
    }
    if (trackCount > 0 && trackIds.size < trackCount) {
        return null
    }
    val tracksById = tracks.associateBy { it.id }
    val playlistTracks = trackIds.map { trackId -> tracksById[trackId] ?: return null }
    return PlaylistDownloadSource(
        playlist = this,
        tracks = playlistTracks,
    )
}

internal fun pausePlaylistDownloadAction(
    playlist: Playlist,
    playlistDownloadJobs: Map<String, Job>,
    setPlaylistDownloadJobs: (Map<String, Job>) -> Unit,
    playlists: List<Playlist>,
    tracks: List<Track>,
    savedAlbums: List<LibraryAlbum>,
    libraryCacheStore: LibraryCacheStore,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    refreshStorageStats: () -> Unit,
) {
    playlistDownloadJobs[playlist.id]?.cancel()
    setPlaylistDownloadJobs(playlistDownloadJobs - playlist.id)
    val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
    if (!currentPlaylist.isOfflineEnabled) {
        collectionDownloadPauseRegistry.resumePlaylist(playlist.id)
        refreshStorageStats()
        return
    }
    collectionDownloadPauseRegistry.pausePlaylist(playlist.id)
    val queuedTrackIds = queuedPlaylistDownloadTrackIds(currentPlaylist, tracks)
    queuedTrackIds.forEach { trackId -> updateTrackDownloadState(trackId, DownloadState.NotDownloaded) }
    val tracksForCache = tracks.map { track ->
        if (track.id in queuedTrackIds) {
            track.copy(downloadState = DownloadState.NotDownloaded)
        } else {
            track
        }
    }
    libraryCacheStore.saveLibrary(
        playlists = playlists,
        tracks = tracksForCache,
        savedAlbums = savedAlbums,
    )
    refreshStorageStats()
}

internal fun pauseAlbumDownloadAction(
    album: LibraryAlbum,
    albumTracks: List<Track>,
    albumDownloadJobs: Map<String, Job>,
    setAlbumDownloadJobs: (Map<String, Job>) -> Unit,
    tracks: List<Track>,
    playlists: List<Playlist>,
    savedAlbums: List<LibraryAlbum>,
    albumTracksById: Map<String, List<Track>>,
    libraryCacheStore: LibraryCacheStore,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    refreshStorageStats: () -> Unit,
) {
    albumDownloadJobs[album.id]?.cancel()
    setAlbumDownloadJobs(albumDownloadJobs - album.id)
    collectionDownloadPauseRegistry.pauseAlbum(album.id)
    queuedAlbumDownloadTrackIds(album, albumTracks, tracks, albumTracksById)
        .forEach { trackId -> updateTrackDownloadState(trackId, DownloadState.NotDownloaded) }
    libraryCacheStore.saveLibrary(
        playlists = playlists,
        tracks = tracks,
        savedAlbums = savedAlbums,
    )
    refreshStorageStats()
}

internal fun deletePlaylistDownloadAction(
    scope: CoroutineScope,
    playlist: Playlist,
    playlistDownloadJobs: Map<String, Job>,
    setPlaylistDownloadJobs: (Map<String, Job>) -> Unit,
    playlists: List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    tracks: List<Track>,
    savedAlbums: List<LibraryAlbum>,
    albumTracksById: Map<String, List<Track>>,
    offlineAlbumIds: Set<String>,
    musicRepository: MusicRepository,
    libraryCacheStore: LibraryCacheStore,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    refreshStorageStats: () -> Unit,
) {
    scope.launch {
        playlistDownloadJobs[playlist.id]?.cancel()
        setPlaylistDownloadJobs(playlistDownloadJobs - playlist.id)
        collectionDownloadPauseRegistry.resumePlaylist(playlist.id)
        val currentPlaylist = playlists.firstOrNull { it.id == playlist.id } ?: playlist
        val nextPlaylists = playlists.updatePlaylist(
            updatedPlaylist = currentPlaylist.copy(isOfflineEnabled = false),
            preserveOfflineFlag = false,
        )
        setPlaylists(nextPlaylists)
        val deletePlan = playlistDownloadDeletePlan(
            playlist = currentPlaylist,
            playlists = nextPlaylists,
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
        val removedTrackIds = deletePlan.queuedTrackIds + deletePlan.downloadedTrackIdsToCache
        val tracksForCache = tracks.map { track ->
            if (track.id in removedTrackIds) {
                track.copy(downloadState = DownloadState.NotDownloaded)
            } else {
                track
            }
        }
        libraryCacheStore.saveLibrary(
            playlists = nextPlaylists,
            tracks = tracksForCache,
            savedAlbums = savedAlbums,
        )
        refreshStorageStats()
    }
}

internal fun deleteAlbumDownloadAction(
    scope: CoroutineScope,
    album: LibraryAlbum,
    albumTracks: List<Track>,
    albumDownloadJobs: Map<String, Job>,
    setAlbumDownloadJobs: (Map<String, Job>) -> Unit,
    tracks: List<Track>,
    playlists: List<Playlist>,
    savedAlbums: List<LibraryAlbum>,
    albumTracksById: Map<String, List<Track>>,
    offlineAlbumIds: Set<String>,
    musicRepository: MusicRepository,
    libraryCacheStore: LibraryCacheStore,
    updateAlbumOfflineFlag: (String, Boolean) -> Unit,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    refreshStorageStats: () -> Unit,
) {
    scope.launch {
        albumDownloadJobs[album.id]?.cancel()
        setAlbumDownloadJobs(albumDownloadJobs - album.id)
        collectionDownloadPauseRegistry.resumeAlbum(album.id)
        updateAlbumOfflineFlag(album.id, false)
        val nextOfflineAlbumIds = offlineAlbumIds - album.id
        val deletePlan = albumDownloadDeletePlan(
            album = album,
            albumTracks = albumTracks,
            tracks = tracks,
            playlists = playlists,
            albumTracksById = albumTracksById,
            offlineAlbumIds = nextOfflineAlbumIds,
        )
        deletePlan.queuedTrackIds.forEach { trackId ->
            updateTrackDownloadState(trackId, DownloadState.NotDownloaded)
        }
        deletePlan.downloadedTrackIdsToCache.forEach { trackId ->
            musicRepository.removeDownloadedTrack(trackId)
            updateTrackDownloadState(trackId, DownloadState.NotDownloaded)
        }
        libraryCacheStore.saveLibrary(
            playlists = playlists,
            tracks = tracks,
            savedAlbums = savedAlbums,
        )
        refreshStorageStats()
    }
}

internal fun downloadPlaylistAction(
    scope: CoroutineScope,
    playlist: Playlist,
    getPlaylistDownloadJobs: () -> Map<String, Job>,
    setPlaylistDownloadJobs: (Map<String, Job>) -> Unit,
    pausePlaylistDownload: (Playlist) -> Unit,
    canUseNetworkForCollectionDownloads: () -> Boolean,
    isOfflineOnly: () -> Boolean,
    getSyncMode: () -> SyncMode,
    canUseMediaServerRequests: () -> Boolean,
    getAccount: () -> Account?,
    mediaDisabledMessage: () -> String,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getTracks: () -> List<Track>,
    setTracks: (List<Track>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    musicRepository: RemoteMusicRepository,
    libraryCacheStore: LibraryCacheStore,
    applyPlaylistTrackPage: (Playlist, dev.teacode.tmusic.data.PlaylistPayload, Boolean) -> Playlist?,
    markServerUnavailable: (Throwable) -> Unit,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    ensureTrackDownloaded: suspend (Track) -> Unit,
    cacheDownloadedAssets: suspend (Track) -> Unit,
    disableMediaPlaybackForAccount: () -> Unit,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    setLibraryError: (String?) -> Unit,
    requestEnableCellularDownloads: () -> Unit,
    refreshStorageStats: () -> Unit,
) {
    if (getPlaylistDownloadJobs()[playlist.id]?.isActive == true) {
        pausePlaylistDownload(playlist)
        return
    }
    collectionDownloadPauseRegistry.resumePlaylist(playlist.id)
    if (!canUseNetworkForCollectionDownloads()) {
        if (
            !isOfflineOnly() &&
            getSyncMode() != SyncMode.OfflineOnly &&
            canUseMediaServerRequests() &&
            getAccount()?.canPlayMedia != false
        ) {
            requestEnableCellularDownloads()
        } else {
            setLibraryError(
                when {
                    isOfflineOnly() || getSyncMode() == SyncMode.OfflineOnly -> "Offline only mode is enabled."
                    !canUseMediaServerRequests() -> "Connect to the server before downloading playlists."
                    getAccount()?.canPlayMedia == false -> mediaDisabledMessage()
                    else -> "Enable cellular downloads or connect to Wi-Fi before downloading playlists."
                },
            )
        }
        return
    }

    val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
    val optimisticPlaylist = currentPlaylist.copy(isOfflineEnabled = true)
    val optimisticPlaylists = getPlaylists().updateOrAppendPlaylist(optimisticPlaylist)
    setPlaylists(optimisticPlaylists)
    libraryCacheStore.saveLibrary(
        playlists = optimisticPlaylists,
        tracks = getTracks(),
        savedAlbums = getSavedAlbums(),
    )

    val job = scope.launch {
        try {
            setLibraryError(null)
            val source = optimisticPlaylist.cachedDownloadSourceOrNull(getTracks())
                ?: loadPlaylistDownloadSource(
                    musicRepository = musicRepository,
                    playlist = optimisticPlaylist,
                    pageLimit = COLLECTION_DOWNLOAD_TRACK_PAGE_LIMIT,
                    mergePage = { loadedPlaylist, payload, append ->
                        payload.mergePlaylistTrackPage(
                            playlist = optimisticPlaylist,
                            currentPlaylist = loadedPlaylist,
                            append = append,
                        ).playlists.firstOrNull() ?: loadedPlaylist
                    },
                    fallbackTracks = { loadedPlaylist -> loadedPlaylist.tracksFrom(getTracks()) },
                )
            currentCoroutineContext().ensureActive()
            if (getPlaylists().firstOrNull { it.id == playlist.id }?.isOfflineEnabled != true) {
                return@launch
            }
            source.loadError?.let(markServerUnavailable)
            if (source.loadError != null && source.tracks.isEmpty()) {
                setLibraryError(source.loadError.userMessage())
                return@launch
            }
            val offlinePlaylist = source.playlist.copy(isOfflineEnabled = true)
            val mergedTracks = musicRepository.withOfflineState(
                (getTracks() + source.tracks.withKnownTrackMetadata(getTracks()))
                    .associateBy { it.id }
                    .values
                    .toList(),
            ).withKnownTrackMetadata(getTracks())
            setTracks(mergedTracks)
            setPlaylists(getPlaylists().updateOrAppendPlaylist(offlinePlaylist))
            libraryCacheStore.saveLibrary(
                playlists = getPlaylists(),
                tracks = mergedTracks,
                savedAlbums = getSavedAlbums(),
            )
            val sourceTracksById = mergedTracks.associateBy { it.id }
            val pendingTracks = offlinePlaylist.trackIds
                .mapNotNull(sourceTracksById::get)
                .filter { track ->
                track.downloadState != DownloadState.Downloaded
            }
            if (pendingTracks.isEmpty()) {
                libraryCacheStore.saveLibrary(
                    playlists = getPlaylists(),
                    tracks = getTracks(),
                    savedAlbums = getSavedAlbums(),
                )
                return@launch
            }

            val result = downloadTracksSequentially(
                tracks = pendingTracks,
                canContinue = {
                    canUseNetworkForCollectionDownloads() &&
                        getPlaylists().firstOrNull { it.id == playlist.id }?.isOfflineEnabled == true
                },
                onQueued = { track -> updateTrackDownloadState(track.id, DownloadState.Queued) },
                downloadTrackAssets = { track ->
                    ensureTrackDownloaded(track)
                    cacheDownloadedAssets(track)
                },
                onDownloaded = { track ->
                    updateTrackDownloadState(track.id, DownloadState.Downloaded)
                    refreshStorageStats()
                },
                onFailed = { track -> updateTrackDownloadState(track.id, DownloadState.NotDownloaded) },
                isFatalFailure = { error ->
                    if (error.isMediaPlaybackDisabledError()) {
                        disableMediaPlaybackForAccount()
                        setLibraryError(mediaDisabledMessage())
                        true
                    } else {
                        false
                    }
                },
            )
            if (result.interruptedByPolicy) {
                return@launch
            }
            currentCoroutineContext().ensureActive()
            if (getPlaylists().firstOrNull { it.id == playlist.id }?.isOfflineEnabled != true) {
                return@launch
            }

            setAccessToken(refreshAccessToken())
            val updatedTracks = musicRepository.withOfflineState(getTracks())
            setTracks(updatedTracks)
            libraryCacheStore.saveLibrary(
                playlists = getPlaylists(),
                tracks = updatedTracks,
                savedAlbums = getSavedAlbums(),
            )
            refreshStorageStats()

            if (result.failedTrackIds.isNotEmpty()) {
                setLibraryError("Some playlist tracks were not downloaded.")
            }
        } finally {
            setPlaylistDownloadJobs(getPlaylistDownloadJobs() - playlist.id)
        }
    }
    setPlaylistDownloadJobs(getPlaylistDownloadJobs() + (playlist.id to job))
}

internal fun downloadAlbumAction(
    scope: CoroutineScope,
    album: LibraryAlbum,
    albumTracks: List<Track>,
    getAlbumDownloadJobs: () -> Map<String, Job>,
    setAlbumDownloadJobs: (Map<String, Job>) -> Unit,
    pauseAlbumDownload: (LibraryAlbum, List<Track>) -> Unit,
    canUseNetworkForCollectionDownloads: () -> Boolean,
    isOfflineOnly: () -> Boolean,
    getSyncMode: () -> SyncMode,
    canUseMediaServerRequests: () -> Boolean,
    getAccount: () -> Account?,
    mediaDisabledMessage: () -> String,
    getTracks: () -> List<Track>,
    setTracks: (List<Track>) -> Unit,
    getPlaylists: () -> List<Playlist>,
    getSavedAlbums: () -> List<LibraryAlbum>,
    setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    getAlbums: () -> List<LibraryAlbum>,
    setAlbums: (List<LibraryAlbum>) -> Unit,
    getAlbumsByArtist: () -> Map<String, List<LibraryAlbum>>,
    setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    getAppearsOnByArtist: () -> Map<String, List<LibraryAlbum>>,
    setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    getAlbumTracksById: () -> Map<String, List<Track>>,
    setAlbumTracksById: (Map<String, List<Track>>) -> Unit,
    musicRepository: RemoteMusicRepository,
    libraryCacheStore: LibraryCacheStore,
    markServerUnavailable: (Throwable) -> Unit,
    mergeLoadedTracks: (List<Track>) -> Unit,
    updateAlbumOfflineFlag: (String, Boolean) -> Unit,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    ensureTrackDownloaded: suspend (Track) -> Unit,
    cacheDownloadedAssets: suspend (Track) -> Unit,
    disableMediaPlaybackForAccount: () -> Unit,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    setLibraryError: (String?) -> Unit,
    requestEnableCellularDownloads: () -> Unit,
    refreshStorageStats: () -> Unit,
) {
    if (getAlbumDownloadJobs()[album.id]?.isActive == true) {
        pauseAlbumDownload(album, albumTracks)
        return
    }
    collectionDownloadPauseRegistry.resumeAlbum(album.id)
    if (!canUseNetworkForCollectionDownloads()) {
        if (
            !isOfflineOnly() &&
            getSyncMode() != SyncMode.OfflineOnly &&
            canUseMediaServerRequests() &&
            getAccount()?.canPlayMedia != false
        ) {
            requestEnableCellularDownloads()
        } else {
            setLibraryError(
                when {
                    isOfflineOnly() || getSyncMode() == SyncMode.OfflineOnly -> "Offline only mode is enabled."
                    !canUseMediaServerRequests() -> "Connect to the server before downloading albums."
                    getAccount()?.canPlayMedia == false -> mediaDisabledMessage()
                    else -> "Enable cellular downloads or connect to Wi-Fi before downloading albums."
                },
            )
        }
        return
    }

    val wasSaved = album.savedByCurrentUser ||
        getAlbums().any { it.id == album.id && it.savedByCurrentUser } ||
        getSavedAlbums().any { it.id == album.id && it.savedByCurrentUser }
    val optimisticAlbum = album.copy(
        savedByCurrentUser = wasSaved,
        isOfflineEnabled = true,
    )
    updateAlbumOfflineFlag(album.id, true)
    setAlbums(getAlbums().updateOrAppendAlbum(optimisticAlbum))
    setSavedAlbums(getSavedAlbums().updateAlbum(optimisticAlbum.copy(savedByCurrentUser = wasSaved)))
    setAlbumsByArtist(
        getAlbumsByArtist().mapValues { (_, artistAlbums) ->
            artistAlbums.updateOrAppendAlbum(optimisticAlbum)
        },
    )
    setAppearsOnByArtist(
        getAppearsOnByArtist().mapValues { (_, artistAlbums) ->
            artistAlbums.updateOrAppendAlbum(optimisticAlbum)
        },
    )
    (albumTracks.takeIf { it.isNotEmpty() } ?: getAlbumTracksById()[album.id].orEmpty())
        .filter { it.downloadState != DownloadState.Downloaded }
        .forEach { track -> updateTrackDownloadState(track.id, DownloadState.Queued) }
    libraryCacheStore.saveLibrary(
        playlists = getPlaylists(),
        tracks = getTracks(),
        savedAlbums = getSavedAlbums(),
    )

    val job = scope.launch {
        try {
            setLibraryError(null)
            val source = loadAlbumDownloadSource(
                musicRepository = musicRepository,
                album = album,
                initialTracks = albumTracks,
                pageLimit = COLLECTION_DOWNLOAD_TRACK_PAGE_LIMIT,
            )
            source.loadError?.let(markServerUnavailable)
            if (source.loadError != null && source.tracks.isEmpty()) {
                setLibraryError(source.loadError.userMessage())
            }
            val sourceTracks = source.tracks
            if (sourceTracks.isNotEmpty()) {
                setAlbumTracksById(getAlbumTracksById() + (album.id to sourceTracks))
                mergeLoadedTracks(sourceTracks)
            }
            val pendingTracks = sourceTracks.filter { it.downloadState != DownloadState.Downloaded }
            val offlineAlbum = album.copy(
                savedByCurrentUser = wasSaved,
                isOfflineEnabled = true,
            )
            updateAlbumOfflineFlag(album.id, true)
            setAlbums(getAlbums().updateOrAppendAlbum(offlineAlbum))
            setSavedAlbums(getSavedAlbums().updateAlbum(offlineAlbum.copy(savedByCurrentUser = wasSaved)))
            setAlbumsByArtist(
                getAlbumsByArtist().mapValues { (_, artistAlbums) ->
                    artistAlbums.updateOrAppendAlbum(offlineAlbum)
                },
            )
            setAppearsOnByArtist(
                getAppearsOnByArtist().mapValues { (_, artistAlbums) ->
                    artistAlbums.updateOrAppendAlbum(offlineAlbum)
                },
            )
            if (pendingTracks.isEmpty()) {
                return@launch
            }

            val result = downloadTracksSequentially(
                tracks = pendingTracks,
                canContinue = canUseNetworkForCollectionDownloads,
                onQueued = { track -> updateTrackDownloadState(track.id, DownloadState.Queued) },
                downloadTrackAssets = { track ->
                    ensureTrackDownloaded(track)
                    cacheDownloadedAssets(track)
                },
                onDownloaded = { track ->
                    updateTrackDownloadState(track.id, DownloadState.Downloaded)
                    refreshStorageStats()
                },
                onFailed = { track -> updateTrackDownloadState(track.id, DownloadState.NotDownloaded) },
                isFatalFailure = { error ->
                    if (error.isMediaPlaybackDisabledError()) {
                        disableMediaPlaybackForAccount()
                        setLibraryError(mediaDisabledMessage())
                        true
                    } else {
                        false
                    }
                },
            )
            if (result.interruptedByPolicy) {
                return@launch
            }

            setAccessToken(refreshAccessToken())
            val updatedTracks = musicRepository.withOfflineState(getTracks())
            setTracks(updatedTracks)
            setAlbumTracksById(
                getAlbumTracksById() + (
                    album.id to sourceTracks.map { sourceTrack ->
                        updatedTracks.firstOrNull { it.id == sourceTrack.id } ?: sourceTrack
                    }
                    ),
            )
            libraryCacheStore.saveLibrary(
                playlists = getPlaylists(),
                tracks = updatedTracks,
                savedAlbums = getSavedAlbums(),
            )
            refreshStorageStats()
            if (result.failedTrackIds.isNotEmpty()) {
                setLibraryError("Some album tracks were not downloaded.")
            }
        } finally {
            setAlbumDownloadJobs(getAlbumDownloadJobs() - album.id)
        }
    }
    setAlbumDownloadJobs(getAlbumDownloadJobs() + (album.id to job))
}

internal fun clearDownloadsAction(
    scope: CoroutineScope,
    getAlbumDownloadJobs: () -> Map<String, Job>,
    setAlbumDownloadJobs: (Map<String, Job>) -> Unit,
    getPlaylistDownloadJobs: () -> Map<String, Job>,
    setPlaylistDownloadJobs: (Map<String, Job>) -> Unit,
    getPlayerState: () -> PlayerState,
    setPlayerState: (PlayerState) -> Unit,
    getPlaylists: () -> List<Playlist>,
    setPlaylists: (List<Playlist>) -> Unit,
    getTracks: () -> List<Track>,
    setTracks: (List<Track>) -> Unit,
    getAlbums: () -> List<LibraryAlbum>,
    setAlbums: (List<LibraryAlbum>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    getAlbumsByArtist: () -> Map<String, List<LibraryAlbum>>,
    setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    getAppearsOnByArtist: () -> Map<String, List<LibraryAlbum>>,
    setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    setOfflineAlbumIds: (Set<String>) -> Unit,
    userPreferencesStore: UserPreferencesStore,
    getPlaybackQueue: () -> PlaybackQueue,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    clearGaplessPlaybackState: () -> Unit,
    setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    setPlaybackUrlPrefetchesInProgress: (Set<String>) -> Unit,
    playbackStateStore: PlaybackStateStore,
    canUseServerRequests: () -> Boolean,
    setArtists: (List<dev.teacode.tmusic.domain.LibraryArtist>) -> Unit,
    getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    setArtworkBitmaps: (Map<String, ImageBitmap>) -> Unit,
    getLyricsByTrackId: () -> Map<String, TrackLyrics>,
    setLyricsByTrackId: (Map<String, TrackLyrics>) -> Unit,
    musicRepository: RemoteMusicRepository,
    offlineLyricsStore: OfflineLyricsStore,
    artworkCacheStore: ArtworkCacheStore,
    libraryCacheStore: LibraryCacheStore,
    setLibraryNotice: (String?) -> Unit,
    setLibraryError: (String?) -> Unit,
    refreshStorageStats: () -> Unit,
) {
    scope.launch {
        setLibraryError(null)
        getAlbumDownloadJobs().values.forEach { it.cancel() }
        getPlaylistDownloadJobs().values.forEach { it.cancel() }
        setAlbumDownloadJobs(emptyMap())
        setPlaylistDownloadJobs(emptyMap())
        collectionDownloadPauseRegistry.clear()
        val currentPlayerState = getPlayerState()
        val retainedTrackIds = setOfNotNull(currentPlayerState.currentTrack?.id)
        val shouldStopPlayback = currentPlayerState.streamUrl?.startsWith("file:", ignoreCase = true) == true
        val currentPlaylists = getPlaylists()
        val currentTracks = getTracks()
        val downloadedArtworkKeys = downloadedArtworkKeys(currentPlaylists, currentTracks)
        val downloadedArtworkCacheKeys = artworkCacheKeysFor(downloadedArtworkKeys)
        runCatching {
            musicRepository.clearDownloads(retainedTrackIds = retainedTrackIds)
            offlineLyricsStore.clear()
            artworkCacheStore.clearKeys(downloadedArtworkCacheKeys)
            if (shouldStopPlayback) {
                currentPlayerState.currentTrack?.id?.let(musicRepository::cachedPlaybackUrl)
            } else {
                null
            }
        }.onSuccess { retainedStreamUrl ->
            setArtworkBitmaps(
                getArtworkBitmaps().filterKeys { artworkSourceKey(it) !in downloadedArtworkKeys },
            )
            setLyricsByTrackId(
                getLyricsByTrackId().filterKeys { trackId ->
                    currentTracks.none { it.id == trackId && it.downloadState == DownloadState.Downloaded }
                },
            )
            val nextTracks = currentTracks.map { track -> track.copy(downloadState = DownloadState.NotDownloaded) }
            val nextAlbums = getAlbums().map { album -> album.copy(isOfflineEnabled = false) }
            val nextSavedAlbums = getSavedAlbums().map { album -> album.copy(isOfflineEnabled = false) }
            val nextAlbumsByArtist = getAlbumsByArtist().mapValues { (_, artistAlbums) ->
                artistAlbums.map { album -> album.copy(isOfflineEnabled = false) }
            }
            val nextAppearsOnByArtist = getAppearsOnByArtist().mapValues { (_, artistAlbums) ->
                artistAlbums.map { album -> album.copy(isOfflineEnabled = false) }
            }
            val nextPlaylists = currentPlaylists.map { playlist -> playlist.copy(isOfflineEnabled = false) }
            setTracks(nextTracks)
            setAlbums(nextAlbums)
            setSavedAlbums(nextSavedAlbums)
            setAlbumsByArtist(nextAlbumsByArtist)
            setAppearsOnByArtist(nextAppearsOnByArtist)
            setOfflineAlbumIds(emptySet())
            userPreferencesStore.clearOfflineAlbumIds()
            setPlaylists(nextPlaylists)
            setPlaybackQueue(
                getPlaybackQueue().copy(
                    tracks = getPlaybackQueue().tracks.map { track ->
                        track.copy(downloadState = DownloadState.NotDownloaded)
                    },
                ),
            )
            val updatedCurrentTrack = currentPlayerState.currentTrack?.copy(
                downloadState = DownloadState.NotDownloaded,
            )
            if (shouldStopPlayback && retainedStreamUrl == null) {
                clearGaplessPlaybackState()
                setPrefetchedPlaybackUrls(emptyMap())
                setPlaybackUrlPrefetchesInProgress(emptySet())
                setPlayerState(
                    currentPlayerState.copy(
                        currentTrack = updatedCurrentTrack,
                        isPlaying = false,
                        streamUrl = null,
                    ),
                )
                playbackStateStore.clear()
            } else {
                setPlayerState(
                    currentPlayerState.copy(
                        currentTrack = updatedCurrentTrack,
                        streamUrl = retainedStreamUrl ?: currentPlayerState.streamUrl,
                    ),
                )
            }
            if (!canUseServerRequests()) {
                setArtists(nextTracks.downloadedArtists())
                setAlbums(emptyList())
            }
            libraryCacheStore.saveLibrary(
                playlists = nextPlaylists,
                tracks = nextTracks,
                savedAlbums = nextSavedAlbums,
            )
            setLibraryNotice("Downloads cleared.")
            refreshStorageStats()
        }.onFailure { error ->
            setLibraryError(error.userMessage())
        }
    }
}

internal fun resumePendingOfflineDownloadsAction(
    canUseNetworkForCollectionDownloads: () -> Boolean,
    playlists: List<Playlist>,
    playlistDownloadJobs: Map<String, Job>,
    playlistIsFullyDownloaded: (Playlist) -> Boolean,
    tracks: List<Track>,
    albums: List<LibraryAlbum>,
    savedAlbums: List<LibraryAlbum>,
    albumsByArtist: Map<String, List<LibraryAlbum>>,
    appearsOnByArtist: Map<String, List<LibraryAlbum>>,
    offlineAlbumIds: Set<String>,
    albumDownloadJobs: Map<String, Job>,
    albumTracksById: Map<String, List<Track>>,
    downloadPlaylist: (Playlist) -> Unit,
    downloadAlbum: (LibraryAlbum, List<Track>) -> Unit,
) {
    if (!canUseNetworkForCollectionDownloads()) {
        return
    }

    playlists
        .filter { playlist -> playlist.isOfflineEnabled }
        .forEach { playlist ->
            if (isPlaylistDownloadPaused(playlist.id)) {
                return@forEach
            }
            if (playlistDownloadJobs[playlist.id]?.isActive == true) {
                return@forEach
            }
            if (playlistIsFullyDownloaded(playlist)) {
                return@forEach
            }
            val playlistTracks = playlist.tracksFrom(tracks)
            val downloadState = aggregateDownloadState(
                isOfflineEnabled = playlist.isOfflineEnabled,
                expectedTrackCount = playlist.trackCount.coerceAtLeast(playlist.trackIds.size),
                loadedTrackCount = playlist.trackIds.size,
                tracks = playlistTracks,
            )
            if (downloadState != DownloadState.Downloaded) {
                downloadPlaylist(playlist)
            }
        }

    (albums + savedAlbums + albumsByArtist.values.flatten() + appearsOnByArtist.values.flatten())
        .distinctBy { it.id }
        .filter { album -> album.isOfflineEnabled || album.id in offlineAlbumIds }
        .forEach { album ->
            if (isAlbumDownloadPaused(album.id)) {
                return@forEach
            }
            if (albumDownloadJobs[album.id]?.isActive == true) {
                return@forEach
            }
            val albumTracks = albumTracksById[album.id].orEmpty()
            val downloadState = aggregateDownloadState(
                isOfflineEnabled = true,
                expectedTrackCount = album.trackCount,
                loadedTrackCount = albumTracks.size,
                tracks = albumTracks,
            )
            if (downloadState != DownloadState.Downloaded) {
                downloadAlbum(album, albumTracks)
            }
        }
}

private fun collectionDownloadDeletePlan(
    trackIds: Set<String>,
    tracks: List<Track>,
    isRequiredByOtherCollection: (String) -> Boolean,
): CollectionDownloadDeletePlan {
    val tracksById = tracks.associateBy { it.id }
    val queuedTrackIds = trackIds
        .filter { trackId ->
            tracksById[trackId]?.downloadState == DownloadState.Queued &&
                !isRequiredByOtherCollection(trackId)
        }
        .toSet()
    val downloadedTrackIdsToCache = trackIds
        .filter { trackId ->
            tracksById[trackId]?.downloadState == DownloadState.Downloaded &&
                !isRequiredByOtherCollection(trackId)
        }
        .toSet()
    return CollectionDownloadDeletePlan(
        queuedTrackIds = queuedTrackIds,
        downloadedTrackIdsToCache = downloadedTrackIdsToCache,
    )
}

private fun trackIsRequiredByDownloadedCollection(
    trackId: String,
    playlists: List<Playlist>,
    albumTracksById: Map<String, List<Track>>,
    offlineAlbumIds: Set<String>,
    excludingPlaylistId: String? = null,
    excludingAlbumId: String? = null,
): Boolean {
    return playlists.any { playlist ->
        playlist.id != excludingPlaylistId &&
            playlist.isOfflineEnabled &&
            trackId in playlist.trackIds
    } || offlineAlbumIds.any { albumId ->
        albumId != excludingAlbumId &&
            albumTracksById[albumId].orEmpty().any { track -> track.id == trackId }
    }
}
