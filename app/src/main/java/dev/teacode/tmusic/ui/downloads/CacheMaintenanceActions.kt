package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.AppCacheStore
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun clearAppCacheAction(
    scope: CoroutineScope,
    getAccount: () -> Account?,
    getPlaylists: () -> List<Playlist>,
    getTracks: () -> List<Track>,
    getSavedAlbums: () -> List<LibraryAlbum>,
    getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    setArtworkBitmaps: (Map<String, ImageBitmap>) -> Unit,
    setArtworkLoadsInProgress: (Set<String>) -> Unit,
    setProfileAvatarBitmap: (ImageBitmap?) -> Unit,
    setProfileAvatarLoadKey: (String?) -> Unit,
    setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    setLibraryNotice: (String?) -> Unit,
    musicRepository: RemoteMusicRepository,
    appCacheStore: AppCacheStore,
    artworkCacheStore: ArtworkCacheStore,
    libraryCacheStore: LibraryCacheStore,
    playbackStateStore: PlaybackStateStore,
    retainedTrackIds: Set<String>,
    retainedPlaybackCacheKeys: Set<String>,
    clearPlaybackCache: () -> Unit,
    clearPlaybackCacheExcept: (Set<String>) -> Unit,
    playbackCacheDirName: String,
    refreshStorageStats: () -> Unit,
    loadProfileAvatar: (Account) -> Unit,
) {
    scope.launch {
        val downloadedTracks = getTracks().filter { track -> track.downloadState == DownloadState.Downloaded }
        val downloadedTrackIds = downloadedTracks.map { it.id }.toSet()
        val offlinePlaylists = getPlaylists().filter { playlist -> playlist.trackIds.any { it in downloadedTrackIds } }
        val retainedArtworkKeys = downloadedArtworkKeys(getPlaylists(), downloadedTracks)
        val retainedArtworkCacheKeys = artworkCacheKeysFor(retainedArtworkKeys)
        artworkCacheStore.clearExcept(retainedArtworkCacheKeys)
        if (retainedTrackIds.isEmpty()) {
            musicRepository.clearMusicCache()
        } else {
            musicRepository.clearMusicCache(retainedTrackIds = retainedTrackIds)
        }
        if (retainedPlaybackCacheKeys.isEmpty()) {
            clearPlaybackCache()
        } else {
            clearPlaybackCacheExcept(retainedPlaybackCacheKeys)
        }
        appCacheStore.clearAndroidCache(excludedCacheDirNames = setOf(playbackCacheDirName))
        libraryCacheStore.clear()
        if (downloadedTracks.isNotEmpty()) {
            libraryCacheStore.saveLibrary(
                playlists = offlinePlaylists,
                tracks = downloadedTracks,
                savedAlbums = getSavedAlbums(),
            )
        }
        playbackStateStore.clear()
        setArtworkBitmaps(getArtworkBitmaps().filterKeys { artworkSourceKey(it) in retainedArtworkKeys })
        setArtworkLoadsInProgress(emptySet())
        setProfileAvatarBitmap(null)
        setProfileAvatarLoadKey(null)
        setPrefetchedPlaybackUrls(emptyMap())
        setLibraryNotice("Cache cleared.")
        refreshStorageStats()
        getAccount()?.let(loadProfileAvatar)
    }
}
