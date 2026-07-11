package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope

internal class LibraryDetailActionHost(
    private val scope: CoroutineScope,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val canAttemptMetadataRequest: () -> Boolean,
    private val hasNetworkConnection: () -> Boolean,
    private val canUseServerRequests: () -> Boolean,
    private val getSyncMode: () -> SyncMode,
    private val setSyncMode: (SyncMode) -> Unit,
    private val getPlaylistTrackLoadsInProgress: () -> Set<String>,
    private val setPlaylistTrackLoadsInProgress: (Set<String>) -> Unit,
    private val getPlaylistTrackHasMoreById: () -> Map<String, Boolean>,
    private val setPlaylistTrackHasMoreById: (Map<String, Boolean>) -> Unit,
    private val getPlaylists: () -> List<Playlist>,
    private val getTracks: () -> List<Track>,
    private val playlistIsFullyDownloaded: (Playlist) -> Boolean,
    private val applyPlaylistTrackPage: (Playlist, dev.teacode.tmusic.data.PlaylistPayload, Boolean) -> Playlist?,
    private val getArtistAlbumLoadsInProgress: () -> Set<String>,
    private val setArtistAlbumLoadsInProgress: (Set<String>) -> Unit,
    private val getAlbums: () -> List<LibraryAlbum>,
    private val setAlbums: (List<LibraryAlbum>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val getAlbumsByArtist: () -> Map<String, List<LibraryAlbum>>,
    private val setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    private val getAppearsOnByArtist: () -> Map<String, List<LibraryAlbum>>,
    private val setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    private val getLooseTracksByArtist: () -> Map<String, List<Track>>,
    private val setLooseTracksByArtist: (Map<String, List<Track>>) -> Unit,
    private val mergeLoadedTracks: (List<Track>) -> Unit,
    private val getSimilarArtistLoadsInProgress: () -> Set<String>,
    private val setSimilarArtistLoadsInProgress: (Set<String>) -> Unit,
    private val getSimilarArtistsByArtist: () -> Map<String, List<LibraryArtist>>,
    private val setSimilarArtistsByArtist: (Map<String, List<LibraryArtist>>) -> Unit,
    private val getArtists: () -> List<LibraryArtist>,
    private val setArtists: (List<LibraryArtist>) -> Unit,
    private val setArtistServerSortOption: (ArtistSortOption?) -> Unit,
    private val getAlbumTrackLoadsInProgress: () -> Set<String>,
    private val setAlbumTrackLoadsInProgress: (Set<String>) -> Unit,
    private val getAlbumTrackHasMoreById: () -> Map<String, Boolean>,
    private val setAlbumTrackHasMoreById: (Map<String, Boolean>) -> Unit,
    private val getAlbumTracksById: () -> Map<String, List<Track>>,
    private val setAlbumTracksById: (Map<String, List<Track>>) -> Unit,
    private val getLibraryPaging: () -> LibraryPagingState,
    private val setLibraryPaging: (LibraryPagingState) -> Unit,
    private val getArtistSortOption: () -> ArtistSortOption,
    private val setAccessToken: (String?) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setLibraryError: (String?) -> Unit,
) {
    fun loadPlaylistTracks(
        playlist: Playlist,
        force: Boolean = false,
        allowOfflineProbe: Boolean = false,
        onLoaded: () -> Unit = {},
    ) {
        loadPlaylistTracksAction(
            scope = scope,
            playlist = playlist,
            force = force,
            allowOfflineProbe = allowOfflineProbe,
            canAttemptMetadataRequest = canAttemptMetadataRequest,
            hasNetworkConnection = hasNetworkConnection,
            getSyncMode = getSyncMode,
            setSyncMode = setSyncMode,
            getPlaylistTrackLoadsInProgress = getPlaylistTrackLoadsInProgress,
            setPlaylistTrackLoadsInProgress = setPlaylistTrackLoadsInProgress,
            getPlaylistTrackHasMoreById = getPlaylistTrackHasMoreById,
            setPlaylistTrackHasMoreById = setPlaylistTrackHasMoreById,
            getPlaylists = getPlaylists,
            getTracks = getTracks,
            playlistIsFullyDownloaded = playlistIsFullyDownloaded,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            applyPlaylistTrackPage = applyPlaylistTrackPage,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
            onLoaded = onLoaded,
        )
    }

    fun loadArtistAlbums(artist: LibraryArtist, force: Boolean = false) {
        loadArtistAlbumsAction(
            scope = scope,
            artist = artist,
            canUseServerRequests = canUseServerRequests,
            getArtistAlbumLoadsInProgress = getArtistAlbumLoadsInProgress,
            setArtistAlbumLoadsInProgress = setArtistAlbumLoadsInProgress,
            getAlbums = getAlbums,
            setAlbums = setAlbums,
            getSavedAlbums = getSavedAlbums,
            getTracks = getTracks,
            getAlbumsByArtist = getAlbumsByArtist,
            setAlbumsByArtist = setAlbumsByArtist,
            getAppearsOnByArtist = getAppearsOnByArtist,
            setAppearsOnByArtist = setAppearsOnByArtist,
            getLooseTracksByArtist = getLooseTracksByArtist,
            setLooseTracksByArtist = setLooseTracksByArtist,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            mergeLoadedTracks = mergeLoadedTracks,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun loadSimilarArtists(artist: LibraryArtist, force: Boolean = false) {
        loadSimilarArtistsAction(
            scope = scope,
            artist = artist,
            force = force,
            canUseServerRequests = canUseServerRequests,
            getSimilarArtistLoadsInProgress = getSimilarArtistLoadsInProgress,
            setSimilarArtistLoadsInProgress = setSimilarArtistLoadsInProgress,
            getSimilarArtistsByArtist = getSimilarArtistsByArtist,
            setSimilarArtistsByArtist = setSimilarArtistsByArtist,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun loadAlbumTracks(album: LibraryAlbum, force: Boolean = false) {
        loadAlbumTracksAction(
            scope = scope,
            album = album,
            force = force,
            canAttemptMetadataRequest = canAttemptMetadataRequest,
            getSyncMode = getSyncMode,
            setSyncMode = setSyncMode,
            getAlbumTrackLoadsInProgress = getAlbumTrackLoadsInProgress,
            setAlbumTrackLoadsInProgress = setAlbumTrackLoadsInProgress,
            getAlbumTrackHasMoreById = getAlbumTrackHasMoreById,
            setAlbumTrackHasMoreById = setAlbumTrackHasMoreById,
            getAlbumTracksById = getAlbumTracksById,
            setAlbumTracksById = setAlbumTracksById,
            getTracks = getTracks,
            musicRepository = musicRepository,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            mergeLoadedTracks = mergeLoadedTracks,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun loadMoreArtists() {
        loadMoreArtistsAction(
            scope = scope,
            canUseServerRequests = canUseServerRequests,
            sortOption = getArtistSortOption(),
            getLibraryPaging = getLibraryPaging,
            setLibraryPaging = setLibraryPaging,
            getArtists = getArtists,
            setArtists = setArtists,
            setArtistServerSortOption = setArtistServerSortOption,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = setAccessToken,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun reloadArtists(sortOption: ArtistSortOption) {
        reloadArtistsAction(
            scope = scope,
            canUseServerRequests = canUseServerRequests,
            sortOption = sortOption,
            getLibraryPaging = getLibraryPaging,
            setLibraryPaging = setLibraryPaging,
            setArtists = setArtists,
            setArtistServerSortOption = setArtistServerSortOption,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = setAccessToken,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    fun loadMoreAlbums() {
        loadMoreAlbumsAction(
            scope = scope,
            canUseServerRequests = canUseServerRequests,
            getLibraryPaging = getLibraryPaging,
            setLibraryPaging = setLibraryPaging,
            getAlbums = getAlbums,
            setAlbums = setAlbums,
            getSavedAlbums = getSavedAlbums,
            musicRepository = musicRepository,
            authRepository = authRepository,
            setAccessToken = setAccessToken,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }
}
