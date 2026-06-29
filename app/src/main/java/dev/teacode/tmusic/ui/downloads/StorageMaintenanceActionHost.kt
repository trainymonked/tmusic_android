package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.AppCacheStore
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal class StorageMaintenanceActionHost(
    private val scope: CoroutineScope,
    private val getAlbumDownloadJobs: () -> Map<String, Job>,
    private val setAlbumDownloadJobs: (Map<String, Job>) -> Unit,
    private val getPlaylistDownloadJobs: () -> Map<String, Job>,
    private val setPlaylistDownloadJobs: (Map<String, Job>) -> Unit,
    private val getPlayerState: () -> PlayerState,
    private val setPlayerState: (PlayerState) -> Unit,
    private val getPlaylists: () -> List<Playlist>,
    private val setPlaylists: (List<Playlist>) -> Unit,
    private val getTracks: () -> List<Track>,
    private val setTracks: (List<Track>) -> Unit,
    private val getAlbums: () -> List<LibraryAlbum>,
    private val setAlbums: (List<LibraryAlbum>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    private val getAlbumsByArtist: () -> Map<String, List<LibraryAlbum>>,
    private val setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    private val getAppearsOnByArtist: () -> Map<String, List<LibraryAlbum>>,
    private val setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    private val setOfflineAlbumIds: (Set<String>) -> Unit,
    private val userPreferencesStore: UserPreferencesStore,
    private val getPlaybackQueue: () -> PlaybackQueue,
    private val setPlaybackQueue: (PlaybackQueue) -> Unit,
    private val clearGaplessPlaybackState: () -> Unit,
    private val setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    private val setPlaybackUrlPrefetchesInProgress: (Set<String>) -> Unit,
    private val playbackStateStore: PlaybackStateStore,
    private val canUseServerRequests: () -> Boolean,
    private val setArtists: (List<LibraryArtist>) -> Unit,
    private val getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    private val setArtworkBitmaps: (Map<String, ImageBitmap>) -> Unit,
    private val getLyricsByTrackId: () -> Map<String, TrackLyrics>,
    private val setLyricsByTrackId: (Map<String, TrackLyrics>) -> Unit,
    private val musicRepository: RemoteMusicRepository,
    private val offlineLyricsStore: OfflineLyricsStore,
    private val artworkCacheStore: ArtworkCacheStore,
    private val libraryCacheStore: LibraryCacheStore,
    private val setLibraryNotice: (String?) -> Unit,
    private val setLibraryError: (String?) -> Unit,
    private val refreshStorageStats: () -> Unit,
    private val getAccount: () -> Account?,
    private val setArtworkLoadsInProgress: (Set<String>) -> Unit,
    private val setProfileAvatarBitmap: (ImageBitmap?) -> Unit,
    private val setProfileAvatarLoadKey: (String?) -> Unit,
    private val appCacheStore: AppCacheStore,
    private val retainedTrackIds: () -> Set<String>,
    private val retainedPlaybackCacheKeys: () -> Set<String>,
    private val clearPlaybackCache: () -> Unit,
    private val clearPlaybackCacheExcept: (Set<String>) -> Unit,
    private val playbackCacheDirName: String,
    private val loadProfileAvatar: (Account) -> Unit,
) {
    fun clearDownloads() {
        clearDownloadsAction(
            scope = scope,
            getAlbumDownloadJobs = getAlbumDownloadJobs,
            setAlbumDownloadJobs = setAlbumDownloadJobs,
            getPlaylistDownloadJobs = getPlaylistDownloadJobs,
            setPlaylistDownloadJobs = setPlaylistDownloadJobs,
            getPlayerState = getPlayerState,
            setPlayerState = setPlayerState,
            getPlaylists = getPlaylists,
            setPlaylists = setPlaylists,
            getTracks = getTracks,
            setTracks = setTracks,
            getAlbums = getAlbums,
            setAlbums = setAlbums,
            getSavedAlbums = getSavedAlbums,
            setSavedAlbums = setSavedAlbums,
            getAlbumsByArtist = getAlbumsByArtist,
            setAlbumsByArtist = setAlbumsByArtist,
            getAppearsOnByArtist = getAppearsOnByArtist,
            setAppearsOnByArtist = setAppearsOnByArtist,
            setOfflineAlbumIds = setOfflineAlbumIds,
            userPreferencesStore = userPreferencesStore,
            getPlaybackQueue = getPlaybackQueue,
            setPlaybackQueue = setPlaybackQueue,
            clearGaplessPlaybackState = clearGaplessPlaybackState,
            setPrefetchedPlaybackUrls = setPrefetchedPlaybackUrls,
            setPlaybackUrlPrefetchesInProgress = setPlaybackUrlPrefetchesInProgress,
            playbackStateStore = playbackStateStore,
            canUseServerRequests = canUseServerRequests,
            setArtists = setArtists,
            getArtworkBitmaps = getArtworkBitmaps,
            setArtworkBitmaps = setArtworkBitmaps,
            getLyricsByTrackId = getLyricsByTrackId,
            setLyricsByTrackId = setLyricsByTrackId,
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            artworkCacheStore = artworkCacheStore,
            libraryCacheStore = libraryCacheStore,
            setLibraryNotice = setLibraryNotice,
            setLibraryError = setLibraryError,
            refreshStorageStats = refreshStorageStats,
        )
    }

    fun clearAppCache() {
        clearAppCacheAction(
            scope = scope,
            getAccount = getAccount,
            getPlaylists = getPlaylists,
            getTracks = getTracks,
            getSavedAlbums = getSavedAlbums,
            getArtworkBitmaps = getArtworkBitmaps,
            setArtworkBitmaps = setArtworkBitmaps,
            setArtworkLoadsInProgress = setArtworkLoadsInProgress,
            setProfileAvatarBitmap = setProfileAvatarBitmap,
            setProfileAvatarLoadKey = setProfileAvatarLoadKey,
            setPrefetchedPlaybackUrls = setPrefetchedPlaybackUrls,
            setLibraryNotice = setLibraryNotice,
            musicRepository = musicRepository,
            appCacheStore = appCacheStore,
            artworkCacheStore = artworkCacheStore,
            libraryCacheStore = libraryCacheStore,
            playbackStateStore = playbackStateStore,
            retainedTrackIds = retainedTrackIds(),
            retainedPlaybackCacheKeys = retainedPlaybackCacheKeys(),
            clearPlaybackCache = clearPlaybackCache,
            clearPlaybackCacheExcept = clearPlaybackCacheExcept,
            playbackCacheDirName = playbackCacheDirName,
            refreshStorageStats = refreshStorageStats,
            loadProfileAvatar = loadProfileAvatar,
        )
    }
}
