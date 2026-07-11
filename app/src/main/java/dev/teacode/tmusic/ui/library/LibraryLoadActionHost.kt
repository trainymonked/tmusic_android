package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal class LibraryLoadActionHost(
    private val scope: CoroutineScope,
    private val getDefaultDestination: () -> AppDestination,
    private val getArtistSortOption: () -> ArtistSortOption,
    private val getLibraryLoadSerial: () -> Int,
    private val setLibraryLoadSerial: (Int) -> Unit,
    private val getLibraryLoadJob: () -> Job?,
    private val setLibraryLoadJob: (Job?) -> Unit,
    private val getLibraryLoading: () -> Boolean,
    private val setLibraryLoading: (Boolean) -> Unit,
    private val getOfflineOnly: () -> Boolean,
    private val getAccount: () -> Account?,
    private val setAccount: (Account?) -> Unit,
    private val authRepository: RemoteAuthRepository,
    private val musicRepository: RemoteMusicRepository,
    private val getSyncMode: () -> SyncMode,
    private val setSyncMode: (SyncMode) -> Unit,
    private val getPlaylists: () -> List<Playlist>,
    private val setPlaylists: (List<Playlist>) -> Unit,
    private val getTracks: () -> List<Track>,
    private val setTracks: (List<Track>) -> Unit,
    private val getRecentAlbums: () -> List<LibraryAlbum>,
    private val setRecentAlbums: (List<LibraryAlbum>) -> Unit,
    private val getDatabaseTrackCount: () -> Int?,
    private val setDatabaseTrackCount: (Int?) -> Unit,
    private val getArtists: () -> List<LibraryArtist>,
    private val setArtists: (List<LibraryArtist>) -> Unit,
    private val setArtistServerSortOption: (ArtistSortOption?) -> Unit,
    private val getAlbums: () -> List<LibraryAlbum>,
    private val setAlbums: (List<LibraryAlbum>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    private val getOfflineAlbumIds: () -> Set<String>,
    private val getLibraryPaging: () -> LibraryPagingState,
    private val setLibraryPaging: (LibraryPagingState) -> Unit,
    private val getRecentAlbumsPaging: () -> RecentAlbumsPagingState,
    private val setRecentAlbumsPaging: (RecentAlbumsPagingState) -> Unit,
    private val libraryCacheStore: LibraryCacheStore,
    private val setAccessToken: (String?) -> Unit,
    private val getPendingPlayEventCount: () -> Int,
    private val setLastFmConnection: (LastFmConnection) -> Unit,
    private val userPreferencesStore: UserPreferencesStore,
    private val lastFmAuthTokenStore: LastFmAuthTokenStore,
    private val setPendingLastFmToken: (String?) -> Unit,
    private val setWaitingForLastFmSession: (Boolean) -> Unit,
    private val signOutLocalSession: suspend (String?) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val hasNetworkConnection: () -> Boolean,
    private val setLibraryError: (String?) -> Unit,
) {
    fun loadLibrary(targetDestination: AppDestination = getDefaultDestination()) {
        loadLibraryAction(
            scope = scope,
            targetDestination = targetDestination,
            artistSortOption = getArtistSortOption(),
            getLibraryLoadSerial = getLibraryLoadSerial,
            setLibraryLoadSerial = setLibraryLoadSerial,
            getLibraryLoadJob = getLibraryLoadJob,
            setLibraryLoadJob = setLibraryLoadJob,
            getLibraryLoading = getLibraryLoading,
            setLibraryLoading = setLibraryLoading,
            getOfflineOnly = getOfflineOnly,
            getAccount = getAccount,
            setAccount = setAccount,
            authRepository = authRepository,
            musicRepository = musicRepository,
            getSyncMode = getSyncMode,
            setSyncMode = setSyncMode,
            getPlaylists = getPlaylists,
            setPlaylists = setPlaylists,
            getTracks = getTracks,
            setTracks = setTracks,
            getRecentAlbums = getRecentAlbums,
            setRecentAlbums = setRecentAlbums,
            getDatabaseTrackCount = getDatabaseTrackCount,
            setDatabaseTrackCount = setDatabaseTrackCount,
            getArtists = getArtists,
            setArtists = setArtists,
            setArtistServerSortOption = setArtistServerSortOption,
            getAlbums = getAlbums,
            setAlbums = setAlbums,
            getSavedAlbums = getSavedAlbums,
            setSavedAlbums = setSavedAlbums,
            getOfflineAlbumIds = getOfflineAlbumIds,
            getLibraryPaging = getLibraryPaging,
            setLibraryPaging = setLibraryPaging,
            getRecentAlbumsPaging = getRecentAlbumsPaging,
            setRecentAlbumsPaging = setRecentAlbumsPaging,
            libraryCacheStore = libraryCacheStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            getPendingPlayEventCount = getPendingPlayEventCount,
            setLastFmConnection = setLastFmConnection,
            userPreferencesStore = userPreferencesStore,
            lastFmAuthTokenStore = lastFmAuthTokenStore,
            setPendingLastFmToken = setPendingLastFmToken,
            setWaitingForLastFmSession = setWaitingForLastFmSession,
            signOutLocalSession = signOutLocalSession,
            markServerUnavailable = markServerUnavailable,
            hasNetworkConnection = hasNetworkConnection,
            setLibraryError = setLibraryError,
        )
    }
}
