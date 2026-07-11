package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

internal class FavoriteTrackActionHost(
    private val scope: CoroutineScope,
    private val canUseServerRequests: () -> Boolean,
    private val getPlaylists: () -> List<Playlist>,
    private val setPlaylists: (List<Playlist>) -> Unit,
    private val getTracks: () -> List<Track>,
    private val setTracks: (List<Track>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val getOfflineAlbumIds: () -> Set<String>,
    private val getAlbumTracksById: () -> Map<String, List<Track>>,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val libraryCacheStore: LibraryCacheStore,
    private val setAccessToken: (String?) -> Unit,
    private val mergePlaylistPickerMetadata: (List<Playlist>) -> Unit,
    private val updateKnownTrackLikedState: (String, Boolean) -> Unit,
    private val updateTrackDownloadState: (String, DownloadState) -> Unit,
    private val onTrackMovedToCache: (String, String?) -> Unit,
    private val ensureTrackDownloaded: suspend (Track) -> Unit,
    private val cacheDownloadedAssets: suspend (Track) -> Unit,
    private val refreshStorageStats: () -> Unit,
    private val getFavoriteSyncTrackIds: () -> Set<String>,
    private val setFavoriteSyncTrackIds: (Set<String>) -> Unit,
    private val loadArtwork: (String, ArtworkImageSize) -> Unit,
    private val enqueueLibraryMutation: (String, JSONObject) -> Unit,
    private val saveLibraryCache: () -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setLibraryError: (String?) -> Unit,
) {
    fun toggleFavoriteTrack(track: Track) {
        toggleFavoriteTrackAction(
            scope = scope,
            track = track,
            canUseServerRequests = canUseServerRequests,
            getPlaylists = getPlaylists,
            setPlaylists = setPlaylists,
            getTracks = getTracks,
            setTracks = setTracks,
            getSavedAlbums = getSavedAlbums,
            getOfflineAlbumIds = getOfflineAlbumIds,
            getAlbumTracksById = getAlbumTracksById,
            musicRepository = musicRepository,
            libraryCacheStore = libraryCacheStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            mergePlaylistPickerMetadata = mergePlaylistPickerMetadata,
            updateKnownTrackLikedState = updateKnownTrackLikedState,
            updateTrackDownloadState = updateTrackDownloadState,
            onTrackMovedToCache = onTrackMovedToCache,
            ensureTrackDownloaded = ensureTrackDownloaded,
            cacheDownloadedAssets = cacheDownloadedAssets,
            refreshStorageStats = refreshStorageStats,
            getFavoriteSyncTrackIds = getFavoriteSyncTrackIds,
            setFavoriteSyncTrackIds = setFavoriteSyncTrackIds,
            loadArtwork = loadArtwork,
            enqueueLibraryMutation = enqueueLibraryMutation,
            saveLibraryCache = saveLibraryCache,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }
}
