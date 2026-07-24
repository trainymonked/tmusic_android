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
    getOfflineAlbumIds: () -> Set<String>,
    getAlbumTracksById: () -> Map<String, List<Track>>,
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
        val currentPlaylists = getPlaylists()
        val offlineAlbumIds = getOfflineAlbumIds()
        val offlineIndex = offlineLibraryIndex(
            playlists = currentPlaylists,
            tracks = normalizedTracks,
            offlineAlbumIds = offlineAlbumIds,
            albumTracksById = getAlbumTracksById(),
            extraTracks = listOfNotNull(currentPlayerState.currentTrack),
        )
        val downloadedTracks = offlineIndex.downloadedTracks
        val currentTrackId = currentPlayerState.currentTrack?.id
        val currentTrackIds = setOfNotNull(currentTrackId)
        // Clearing cache must never remove files that the user explicitly downloaded.
        val retainedTrackIds = downloadedTracks.mapTo(linkedSetOf()) { track -> track.id } + currentTrackIds
        val retainedDownloadedTracks = downloadedTracks
        val currentTrackDownloaded = currentTrackId != null && currentTrackId in retainedDownloadedTracks.map { it.id }
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
        val offlinePlaylists = currentPlaylists.filter { playlist -> playlist.isOfflineEnabled }
        val retainedPlaylists = offlinePlaylists.distinctBy { playlist -> playlist.id }
        val offlineSavedAlbums = getSavedAlbums().filter { album ->
            album.isOfflineEnabled || album.id in offlineAlbumIds
        }
        val currentArtworkKeys = currentPlayerState.currentTrack?.let { track ->
            setOfNotNull(
                track.listArtworkKey(),
                track.albumId?.let(::albumArtworkKey),
            )
        }.orEmpty()
        val retainedArtworkKeys = downloadedArtworkKeys(offlinePlaylists, retainedDownloadedTracks) + currentArtworkKeys
        val retainedArtworkCacheKeys = artworkCacheKeysFor(retainedArtworkKeys)
        artworkCacheStore.clearExcept(retainedArtworkCacheKeys)
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
        if (retainedPlaylists.isNotEmpty() || retainedDownloadedTracks.isNotEmpty() || offlineSavedAlbums.isNotEmpty()) {
            libraryCacheStore.saveLibrary(
                playlists = retainedPlaylists,
                tracks = retainedDownloadedTracks,
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
