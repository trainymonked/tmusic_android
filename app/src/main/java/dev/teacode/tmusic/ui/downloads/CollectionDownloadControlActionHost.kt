package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal class CollectionDownloadControlActionHost(
    private val scope: CoroutineScope,
    private val musicRepository: RemoteMusicRepository,
    private val libraryCacheStore: LibraryCacheStore,
    private val userPreferencesStore: UserPreferencesStore,
    private val getOfflineAlbumIds: () -> Set<String>,
    private val setOfflineAlbumIds: (Set<String>) -> Unit,
    private val getAlbums: () -> List<LibraryAlbum>,
    private val setAlbums: (List<LibraryAlbum>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    private val getAlbumsByArtist: () -> Map<String, List<LibraryAlbum>>,
    private val setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    private val getAppearsOnByArtist: () -> Map<String, List<LibraryAlbum>>,
    private val setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    private val getPlaylists: () -> List<Playlist>,
    private val setPlaylists: (List<Playlist>) -> Unit,
    private val getTracks: () -> List<Track>,
    private val getAlbumTracksById: () -> Map<String, List<Track>>,
    private val getPlaylistDownloadJobs: () -> Map<String, Job>,
    private val setPlaylistDownloadJobs: (Map<String, Job>) -> Unit,
    private val getAlbumDownloadJobs: () -> Map<String, Job>,
    private val setAlbumDownloadJobs: (Map<String, Job>) -> Unit,
    private val setDownloadUsingCellularState: (Boolean) -> Unit,
    private val canUseNetworkForCollectionDownloads: () -> Boolean,
    private val playlistIsFullyDownloaded: (Playlist) -> Boolean,
    private val hasCachedArtwork: (String) -> Boolean,
    private val updateTrackDownloadState: (String, DownloadState) -> Unit,
    private val refreshStorageStats: () -> Unit,
    private val downloadPlaylist: (Playlist) -> Unit,
    private val downloadAlbum: (LibraryAlbum, List<Track>) -> Unit,
) {
    fun updateAlbumOfflineFlag(albumId: String, enabled: Boolean) {
        if (enabled) {
            userPreferencesStore.addOfflineAlbumId(albumId)
        } else {
            userPreferencesStore.removeOfflineAlbumId(albumId)
        }
        val update = albumOfflineFlagUpdate(
            albumId = albumId,
            enabled = enabled,
            offlineAlbumIds = getOfflineAlbumIds(),
            albums = getAlbums(),
            savedAlbums = getSavedAlbums(),
            albumsByArtist = getAlbumsByArtist(),
            appearsOnByArtist = getAppearsOnByArtist(),
        )
        setOfflineAlbumIds(update.offlineAlbumIds)
        setAlbums(update.albums)
        setSavedAlbums(update.savedAlbums)
        setAlbumsByArtist(update.albumsByArtist)
        setAppearsOnByArtist(update.appearsOnByArtist)
    }

    fun pausePlaylistDownload(playlist: Playlist) {
        pausePlaylistDownloadAction(
            playlist = playlist,
            playlistDownloadJobs = getPlaylistDownloadJobs(),
            setPlaylistDownloadJobs = setPlaylistDownloadJobs,
            playlists = getPlaylists(),
            tracks = getTracks(),
            albumTracksById = getAlbumTracksById(),
            savedAlbums = getSavedAlbums(),
            libraryCacheStore = libraryCacheStore,
            updateTrackDownloadState = updateTrackDownloadState,
            refreshStorageStats = refreshStorageStats,
        )
    }

    fun pauseAlbumDownload(album: LibraryAlbum, albumTracks: List<Track>) {
        pauseAlbumDownloadAction(
            album = album,
            albumTracks = albumTracks,
            albumDownloadJobs = getAlbumDownloadJobs(),
            setAlbumDownloadJobs = setAlbumDownloadJobs,
            tracks = getTracks(),
            playlists = getPlaylists(),
            savedAlbums = getSavedAlbums(),
            albumTracksById = getAlbumTracksById(),
            libraryCacheStore = libraryCacheStore,
            updateTrackDownloadState = updateTrackDownloadState,
            refreshStorageStats = refreshStorageStats,
        )
    }

    fun deletePlaylistDownload(playlist: Playlist) {
        deletePlaylistDownloadAction(
            scope = scope,
            playlist = playlist,
            playlistDownloadJobs = getPlaylistDownloadJobs(),
            setPlaylistDownloadJobs = setPlaylistDownloadJobs,
            playlists = getPlaylists(),
            setPlaylists = setPlaylists,
            tracks = getTracks(),
            savedAlbums = getSavedAlbums(),
            albumTracksById = getAlbumTracksById(),
            offlineAlbumIds = getOfflineAlbumIds(),
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            updateTrackDownloadState = updateTrackDownloadState,
            refreshStorageStats = refreshStorageStats,
        )
    }

    fun deleteAlbumDownload(album: LibraryAlbum, albumTracks: List<Track>) {
        deleteAlbumDownloadAction(
            scope = scope,
            album = album,
            albumTracks = albumTracks,
            albumDownloadJobs = getAlbumDownloadJobs(),
            setAlbumDownloadJobs = setAlbumDownloadJobs,
            tracks = getTracks(),
            playlists = getPlaylists(),
            savedAlbums = getSavedAlbums(),
            albumTracksById = getAlbumTracksById(),
            offlineAlbumIds = getOfflineAlbumIds(),
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            updateAlbumOfflineFlag = ::updateAlbumOfflineFlag,
            updateTrackDownloadState = updateTrackDownloadState,
            refreshStorageStats = refreshStorageStats,
        )
    }

    fun resumePendingOfflineDownloads() {
        resumePendingOfflineDownloadsAction(
            canUseNetworkForCollectionDownloads = canUseNetworkForCollectionDownloads,
            playlists = getPlaylists(),
            playlistDownloadJobs = getPlaylistDownloadJobs(),
            playlistIsFullyDownloaded = playlistIsFullyDownloaded,
            hasCachedArtwork = hasCachedArtwork,
            tracks = getTracks(),
            albums = getAlbums(),
            savedAlbums = getSavedAlbums(),
            albumsByArtist = getAlbumsByArtist(),
            appearsOnByArtist = getAppearsOnByArtist(),
            offlineAlbumIds = getOfflineAlbumIds(),
            albumDownloadJobs = getAlbumDownloadJobs(),
            albumTracksById = getAlbumTracksById(),
            downloadPlaylist = downloadPlaylist,
            downloadAlbum = downloadAlbum,
        )
    }

    fun pauseCollectionDownloadsForNetworkPolicy() {
        getPlaylistDownloadJobs().values.forEach(Job::cancel)
        getAlbumDownloadJobs().values.forEach(Job::cancel)
    }

    fun setDownloadUsingCellular(enabled: Boolean) {
        setDownloadUsingCellularState(enabled)
        userPreferencesStore.setDownloadUsingCellular(enabled)
        if (canUseNetworkForCollectionDownloads()) {
            resumePendingOfflineDownloads()
        } else {
            pauseCollectionDownloadsForNetworkPolicy()
        }
    }
}
