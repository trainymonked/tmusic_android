package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.auth.GoogleSignInTokenProvider
import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.PendingLibraryMutationStore
import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.ScrobbleState
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.Job

internal suspend fun signOutLocalSessionAction(
    message: String?,
    authRepository: RemoteAuthRepository,
    googleSignInTokenProvider: GoogleSignInTokenProvider,
    userPreferencesStore: UserPreferencesStore,
    libraryCacheStore: LibraryCacheStore,
    playbackStateStore: PlaybackStateStore,
    pendingPlayEventStore: PendingPlayEventStore,
    pendingLibraryMutationStore: PendingLibraryMutationStore,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
    getLibraryLoadSerial: () -> Int,
    setLibraryLoadSerial: (Int) -> Unit,
    getLibraryLoadJob: () -> Job?,
    setLibraryLoadJob: (Job?) -> Unit,
    setAccount: (Account?) -> Unit,
    setAccessToken: (String?) -> Unit,
    setCanContinueOffline: (Boolean) -> Unit,
    setOfflineOnly: (Boolean) -> Unit,
    setSyncMode: (SyncMode) -> Unit,
    setLibraryLoading: (Boolean) -> Unit,
    setLibraryError: (String?) -> Unit,
    setLibraryNotice: (String?) -> Unit,
    setPlaylists: (List<Playlist>) -> Unit,
    setTracks: (List<Track>) -> Unit,
    setArtists: (List<LibraryArtist>) -> Unit,
    setAlbums: (List<LibraryAlbum>) -> Unit,
    setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    setRecentAlbums: (List<LibraryAlbum>) -> Unit,
    setDatabaseTrackCount: (Int?) -> Unit,
    setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    setLooseTracksByArtist: (Map<String, List<Track>>) -> Unit,
    setSimilarArtistsByArtist: (Map<String, List<LibraryArtist>>) -> Unit,
    resetLibraryPaging: () -> Unit,
    setRecentAlbumsPaging: (RecentAlbumsPagingState) -> Unit,
    setAlbumTrackHasMoreById: (Map<String, Boolean>) -> Unit,
    setPlaylistTrackHasMoreById: (Map<String, Boolean>) -> Unit,
    setAlbumTracksById: (Map<String, List<Track>>) -> Unit,
    setSearchResults: (LibrarySearchResults) -> Unit,
    setPlayerState: (PlayerState) -> Unit,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    clearGaplessPlaybackState: () -> Unit,
    clearCrossfadeState: () -> Unit,
    setActivePlayEvent: (ActivePlayEvent?) -> Unit,
    setPendingPlayEventCount: (Int) -> Unit,
    setPendingPlayEventSyncProgress: (Pair<Int, Int>?) -> Unit,
    setPendingLibraryMutationCount: (Int) -> Unit,
    setPendingLastFmToken: (String?) -> Unit,
    setWaitingForLastFmSession: (Boolean) -> Unit,
    setLastFmConnection: (LastFmConnection) -> Unit,
    setScrobblingPaused: (Boolean) -> Unit,
    setShuffleEnabled: (Boolean) -> Unit,
    setRepeatMode: (PlaybackRepeatMode) -> Unit,
    setProfileAvatarBitmap: (ImageBitmap?) -> Unit,
    setProfileAvatarLoadKey: (String?) -> Unit,
    setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    setPlaybackUrlPrefetchesInProgress: (Set<String>) -> Unit,
    setTrackForPlaylistAdd: (Track?) -> Unit,
    setPlaylistPickerPlaylists: (List<Playlist>) -> Unit,
    setPlaylistPickerLoading: (Boolean) -> Unit,
    setPlaylistMetadataLoaded: (Boolean) -> Unit,
    setDuplicatePlaylistForAdd: (Playlist?) -> Unit,
    setPlaylistAddInProgress: (Boolean) -> Unit,
    setFullPlayerOpen: (Boolean) -> Unit,
    setQueueOpen: (Boolean) -> Unit,
    setDestination: (AppDestination) -> Unit,
    setBackStack: (List<AppDestination>) -> Unit,
    setAuthError: (String?) -> Unit,
) {
    setLibraryLoadSerial(getLibraryLoadSerial() + 1)
    getLibraryLoadJob()?.cancel()
    setLibraryLoadJob(null)
    authRepository.signOut()
    googleSignInTokenProvider.signOut()
    libraryCacheStore.clear()
    playbackStateStore.clear()
    pendingPlayEventStore.clear()
    pendingLibraryMutationStore.clear()
    setAccount(null)
    setAccessToken(null)
    setCanContinueOffline(false)
    setOfflineOnly(false)
    userPreferencesStore.setOfflineOnly(false)
    setSyncMode(SyncMode.Offline)
    setLibraryLoading(false)
    setLibraryError(null)
    setLibraryNotice(null)
    setPlaylists(emptyList())
    setTracks(emptyList())
    setArtists(emptyList())
    setAlbums(emptyList())
    setSavedAlbums(emptyList())
    setRecentAlbums(emptyList())
    setDatabaseTrackCount(null)
    setAlbumsByArtist(emptyMap())
    setAppearsOnByArtist(emptyMap())
    setLooseTracksByArtist(emptyMap())
    setSimilarArtistsByArtist(emptyMap())
    resetLibraryPaging()
    setRecentAlbumsPaging(RecentAlbumsPagingState())
    setAlbumTrackHasMoreById(emptyMap())
    setPlaylistTrackHasMoreById(emptyMap())
    setAlbumTracksById(emptyMap())
    setSearchResults(LibrarySearchResults(emptyList(), emptyList(), emptyList()))
    setPlayerState(PlayerState(null, isPlaying = false, progressSeconds = 0, streamUrl = null))
    setPlaybackQueue(PlaybackQueue())
    clearGaplessPlaybackState()
    clearCrossfadeState()
    setActivePlayEvent(null)
    setPendingPlayEventCount(0)
    setPendingPlayEventSyncProgress(null)
    setPendingLibraryMutationCount(0)
    setPendingLastFmToken(null)
    setWaitingForLastFmSession(false)
    lastFmAuthTokenStore.clear()
    userPreferencesStore.clearLastFmConnection()
    userPreferencesStore.setScrobblingPaused(false)
    userPreferencesStore.setShuffleEnabled(false)
    userPreferencesStore.setPlaybackRepeatMode(PlaybackRepeatMode.None.name)
    setLastFmConnection(
        LastFmConnection(
            username = null,
            state = ScrobbleState.NeedsAuth,
            pendingScrobbles = 0,
        ),
    )
    setScrobblingPaused(false)
    setShuffleEnabled(false)
    setRepeatMode(PlaybackRepeatMode.None)
    setProfileAvatarBitmap(null)
    setProfileAvatarLoadKey(null)
    setPrefetchedPlaybackUrls(emptyMap())
    setPlaybackUrlPrefetchesInProgress(emptySet())
    setTrackForPlaylistAdd(null)
    setPlaylistPickerPlaylists(emptyList())
    setPlaylistPickerLoading(false)
    setPlaylistMetadataLoaded(false)
    setDuplicatePlaylistForAdd(null)
    setPlaylistAddInProgress(false)
    setFullPlayerOpen(false)
    setQueueOpen(false)
    setDestination(AppDestination(AppTab.Home))
    setBackStack(emptyList())
    setAuthError(message)
}
