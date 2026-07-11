package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.AppCacheStore
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun clearAppCacheAction(
    scope: CoroutineScope,
    getPlaylists: () -> List<Playlist>,
    getTracks: () -> List<Track>,
    updateTrackDownloadState: (String, DownloadState) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    getPlayerState: () -> PlayerState,
    getActivePlaybackCacheKey: () -> String?,
    setPlayerState: (PlayerState) -> Unit,
    getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    setArtworkBitmaps: (Map<String, ImageBitmap>) -> Unit,
    setArtworkLoadsInProgress: (Set<String>) -> Unit,
    setProfileAvatarBitmap: (ImageBitmap?) -> Unit,
    setProfileAvatarLoadKey: (String?) -> Unit,
    setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    setPlaybackUrlPrefetchesInProgress: (Set<String>) -> Unit,
    getLyricsByTrackId: () -> Map<String, TrackLyrics>,
    setLyricsByTrackId: (Map<String, TrackLyrics>) -> Unit,
    setLibraryNotice: (String?) -> Unit,
    musicRepository: RemoteMusicRepository,
    appCacheStore: AppCacheStore,
    artworkCacheStore: ArtworkCacheStore,
    offlineLyricsStore: OfflineLyricsStore,
    libraryCacheStore: LibraryCacheStore,
    playbackStateStore: PlaybackStateStore,
    clearPlaybackCache: () -> Unit,
    clearPlaybackCacheExcept: (Set<String>) -> Unit,
    playbackCacheDirName: String,
    refreshStorageStats: () -> Unit,
) {
    scope.launch {
        getTracks()
            .filter { track -> track.downloadState == DownloadState.Queued }
            .forEach { track -> updateTrackDownloadState(track.id, DownloadState.NotDownloaded) }
        val normalizedTracks = getTracks()
        val currentPlayerState = getPlayerState()
        val downloadedTracks = (
            normalizedTracks.filter { track -> track.downloadState == DownloadState.Downloaded } +
                listOfNotNull(
                    currentPlayerState.currentTrack
                        ?.takeIf { track -> track.downloadState == DownloadState.Downloaded },
                )
        ).distinctBy { track -> track.id }
        val downloadedTrackIds = downloadedTracks.map { track -> track.id }.toSet()
        val currentTrackId = currentPlayerState.currentTrack?.id
        val currentTrackIds = setOfNotNull(currentTrackId)
        val retainedTrackIds = downloadedTrackIds + currentTrackIds
        val currentTrackDownloaded = currentTrackId != null && currentTrackId in downloadedTrackIds
        if (!currentPlayerState.isPlaying && currentTrackDownloaded) {
            val localStreamUrl = currentTrackId?.let(musicRepository::localPlaybackUrl)
            if (localStreamUrl != null && localStreamUrl != currentPlayerState.streamUrl) {
                setPlayerState(currentPlayerState.copy(streamUrl = localStreamUrl))
            }
        }
        val currentPlaybackCacheKeys = if (currentTrackId != null) {
            buildSet {
                getActivePlaybackCacheKey()?.let { cacheKey -> add(cacheKey) }
                currentPlayerState.streamUrl?.let { streamUrl ->
                    add(streamUrl)
                    currentTrackId
                        ?.let { trackId -> playbackMediaCacheKey(trackId, streamUrl) }
                        ?.let { cacheKey -> add(cacheKey) }
                }
            }
        } else {
            emptySet()
        }
        val currentPlaylists = getPlaylists()
        val offlinePlaylists = currentPlaylists.filter { playlist -> playlist.isOfflineEnabled }
        val retainedPlaylists = offlinePlaylists.distinctBy { playlist -> playlist.id }
        val offlineSavedAlbums = getSavedAlbums().filter { album -> album.isOfflineEnabled }
        val currentArtworkKeys = currentPlayerState.currentTrack?.let { track ->
            setOfNotNull(
                track.listArtworkKey(),
                track.albumId?.let(::albumArtworkKey),
            )
        }.orEmpty()
        val retainedArtworkKeys = downloadedArtworkKeys(offlinePlaylists, downloadedTracks) + currentArtworkKeys
        val retainedArtworkCacheKeys = artworkCacheKeysFor(retainedArtworkKeys)
        artworkCacheStore.clearExcept(retainedArtworkCacheKeys)
        musicRepository.removeDownloadsExcept(retainedTrackIds)
        offlineLyricsStore.clearExcept(retainedTrackIds)
        setLyricsByTrackId(getLyricsByTrackId().filterKeys { trackId -> trackId in retainedTrackIds })
        if (currentTrackIds.isEmpty()) {
            musicRepository.clearMusicCache()
        } else {
            musicRepository.clearMusicCache(retainedTrackIds = currentTrackIds)
        }
        if (currentPlaybackCacheKeys.isEmpty()) {
            clearPlaybackCache()
        } else {
            clearPlaybackCacheExcept(currentPlaybackCacheKeys)
        }
        appCacheStore.clearAndroidCache(excludedCacheDirNames = setOf(playbackCacheDirName))
        libraryCacheStore.clear()
        if (retainedPlaylists.isNotEmpty() || downloadedTracks.isNotEmpty() || offlineSavedAlbums.isNotEmpty()) {
            libraryCacheStore.saveLibrary(
                playlists = retainedPlaylists,
                tracks = downloadedTracks,
                savedAlbums = offlineSavedAlbums,
            )
        }
        playbackStateStore.clear()
        setArtworkBitmaps(getArtworkBitmaps().filterKeys { artworkSourceKey(it) in retainedArtworkKeys })
        setArtworkLoadsInProgress(emptySet())
        setProfileAvatarBitmap(null)
        setProfileAvatarLoadKey(null)
        setPrefetchedPlaybackUrls(emptyMap())
        setPlaybackUrlPrefetchesInProgress(emptySet())
        setLibraryNotice("Cache cleared.")
        refreshStorageStats()
    }
}
