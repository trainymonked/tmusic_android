package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun downloadTrackAction(
    scope: CoroutineScope,
    track: Track,
    canUseMediaServerRequests: () -> Boolean,
    isOfflineOnly: () -> Boolean,
    getSyncMode: () -> SyncMode,
    getAccount: () -> Account?,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    libraryCacheStore: LibraryCacheStore,
    getPlaylists: () -> List<Playlist>,
    getTracks: () -> List<Track>,
    setTracks: (List<Track>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    mediaDisabledMessage: () -> String,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    ensureTrackDownloaded: suspend (Track) -> Unit,
    cacheDownloadedAssets: suspend (Track) -> Unit,
    disableMediaPlaybackForAccount: () -> Unit,
    setAccessToken: (String?) -> Unit,
    setLibraryError: (String?) -> Unit,
    refreshStorageStats: () -> Unit,
) {
    if (track.downloadState == DownloadState.Downloaded) {
        return
    }
    if (!canUseMediaServerRequests()) {
        setLibraryError(
            if (isOfflineOnly() || getSyncMode() == SyncMode.OfflineOnly) {
                "Offline only mode is enabled."
            } else {
                "Connect to the server before downloading tracks."
            },
        )
        return
    }
    if (getAccount()?.canPlayMedia == false && musicRepository.cachedPlaybackUrl(track.id) == null) {
        setLibraryError(mediaDisabledMessage())
        return
    }

    updateTrackDownloadState(track.id, DownloadState.Queued)
    scope.launch {
        setLibraryError(null)
        runCatching {
            ensureTrackDownloaded(track)
            cacheDownloadedAssets(track)
        }.onSuccess {
            setAccessToken(authRepository.accessToken())
            val updatedTracks = musicRepository.withOfflineState(getTracks())
            setTracks(updatedTracks)
            val confirmedState = updatedTracks.firstOrNull { it.id == track.id }?.downloadState
                ?: DownloadState.Downloaded
            updateTrackDownloadState(track.id, confirmedState)
            libraryCacheStore.saveLibrary(
                playlists = getPlaylists(),
                tracks = getTracks(),
                savedAlbums = getSavedAlbums(),
            )
            refreshStorageStats()
        }.onFailure { error ->
            if (error.isMediaPlaybackDisabledError()) {
                disableMediaPlaybackForAccount()
            }
            updateTrackDownloadState(track.id, DownloadState.NotDownloaded)
            setTracks(musicRepository.withOfflineState(getTracks()))
            setLibraryError(
                if (error.isMediaPlaybackDisabledError()) {
                    mediaDisabledMessage()
                } else {
                    error.userMessage()
                },
            )
        }
    }
}
