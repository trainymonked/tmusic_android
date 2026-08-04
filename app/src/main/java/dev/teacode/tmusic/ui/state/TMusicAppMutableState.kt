package dev.teacode.tmusic.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.ScrobbleState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

internal class TMusicAppMutableState(
    initialState: InitialAppState,
    userPreferencesStore: UserPreferencesStore,
    initialAccessToken: String?,
    initialEqualizerAvailable: Boolean,
    initialPendingPlayEventCount: Int,
    initialPendingLibraryMutationCount: Int,
    initialPendingLastFmToken: String?,
) {
    var account by mutableStateOf(initialState.account)
    var signingIn by mutableStateOf(false)
    var authError by mutableStateOf<String?>(null)
    var accessToken by mutableStateOf(initialAccessToken)
    var canContinueOffline by mutableStateOf(initialState.canContinueOffline)
    var useLocalBackend by mutableStateOf(userPreferencesStore.useLocalBackend())
    var offlineOnly by mutableStateOf(initialState.offlineOnly && initialState.canContinueOffline)
    var syncMode by mutableStateOf(
        when {
            initialState.offlineOnly && initialState.canContinueOffline -> SyncMode.OfflineOnly
            else -> SyncMode.Syncing
        },
    )
    var destination by mutableStateOf(AppDestination())
    var backStack by mutableStateOf<List<AppDestination>>(emptyList())
    var playerState by mutableStateOf(initialState.playerState)
    var playerError by mutableStateOf<String?>(null)
    var fullPlayerOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var playbackStartSerial by mutableStateOf(0L)
    var playbackBufferedFraction by mutableStateOf(0f)
    val artworkBitmaps = mutableStateMapOf<String, ImageBitmap>()
    var artworkLoadsInProgress by mutableStateOf<Set<String>>(emptySet())
    var lyricsByTrackId by mutableStateOf<Map<String, TrackLyrics>>(emptyMap())
    var lyricsLoadsInProgress by mutableStateOf<Set<String>>(emptySet())
    var lyricsUnavailableIds by mutableStateOf<Set<String>>(emptySet())
    var profileAvatarBitmap by mutableStateOf<ImageBitmap?>(null)
    var profileAvatarLoadKey by mutableStateOf<String?>(null)
    var prefetchedPlaybackUrls by mutableStateOf<Map<String, String>>(emptyMap())
    var playbackUrlPrefetchesInProgress by mutableStateOf<Set<String>>(emptySet())
    var nextTrackPrefetchJob: Job? = null
    var nextTrackPrefetchSerial = 0L
    var gaplessPlaybackRequest by mutableStateOf<GaplessPlaybackRequest?>(null)
    var gaplessMediaQueueIndices by mutableStateOf<Map<String, Int>>(emptyMap())
    var gaplessMediaUrls by mutableStateOf<Map<String, String>>(emptyMap())
    var streamRequestSerial by mutableStateOf(0L)
    val queueStartRequestSerial = AtomicLong(0L)
    var pendingTransitionArtworkTrackId by mutableStateOf<String?>(null)
    var playbackQueueGeneration by mutableStateOf(0L)
    var playbackQueue by mutableStateOf(initialState.playbackQueue)
    var pendingPlaybackRestore by mutableStateOf(initialState.savedPlayback)
    var requestedQueueAdvance by mutableStateOf(0)
    var requestedQueueWrapPause by mutableStateOf(0)
    var requestedCurrentTrackRestart by mutableStateOf(0)
    var requestedNextPrefetch by mutableStateOf(0)
    var completingPlayEventIds by mutableStateOf<Set<String>>(emptySet())
    var nowPlayingEventIds by mutableStateOf<Set<String>>(emptySet())
    var nowPlayingTrackIdsInFlight by mutableStateOf<Set<String>>(emptySet())
    var nowPlayingTrackId by mutableStateOf<String?>(null)
    var scrobblingPaused by mutableStateOf(false)
    var shuffleEnabled by mutableStateOf(userPreferencesStore.shuffleEnabled())
    var showOnlyActiveSyncedLyrics by mutableStateOf(true)
    var centerSyncedLyrics by mutableStateOf(false)
    var animatedPlayerBackground by mutableStateOf(userPreferencesStore.animatedPlayerBackground())
    var downloadUsingCellular by mutableStateOf(userPreferencesStore.downloadUsingCellular())
    var showEnableCellularDownloadDialog by mutableStateOf(false)
    var crossfadeSeconds by mutableStateOf(userPreferencesStore.crossfadeSeconds())
    var equalizerAvailable by mutableStateOf(initialEqualizerAvailable)
    var preparedCrossfade by mutableStateOf<PreparedCrossfade?>(null)
    var crossfadeJob by mutableStateOf<Job?>(null)
    var crossfadePreparationSerial by mutableStateOf(0L)
    var repeatMode by mutableStateOf(
        runCatching {
            PlaybackRepeatMode.valueOf(userPreferencesStore.playbackRepeatMode())
        }.getOrDefault(PlaybackRepeatMode.None),
    )
    var pendingPlayEventCount by mutableStateOf(initialPendingPlayEventCount)
    var pendingPlayEventSyncProgress by mutableStateOf<Pair<Int, Int>?>(null)
    var pendingLibraryMutationCount by mutableStateOf(initialPendingLibraryMutationCount)
    var pendingLastFmToken by mutableStateOf(initialPendingLastFmToken)
    var waitingForLastFmSession by mutableStateOf(initialPendingLastFmToken != null)
    var lastFmConnection by mutableStateOf(
        (
            userPreferencesStore.lastFmConnection()
                ?: LastFmConnection(
                    username = null,
                    state = ScrobbleState.NeedsAuth,
                    pendingScrobbles = 0,
                )
            ).copy(pendingScrobbles = initialPendingPlayEventCount),
    )
    val activePlayEventState: MutableState<ActivePlayEvent?> = mutableStateOf(initialState.activePlayEvent)
    var playlists by mutableStateOf(initialState.cachedLibrary.playlists.sanitizeClientPlaylists())
    var tracks by mutableStateOf(initialState.tracks)
    var recentAlbums by mutableStateOf(initialState.cachedLibrary.recentAlbums)
    var databaseTrackCount by mutableStateOf(initialState.cachedLibrary.databaseTrackCount)
    var offlineAlbumIds by mutableStateOf(userPreferencesStore.offlineAlbumIds())
    var homeArtists by mutableStateOf(
        initialState.cachedLibrary.homeArtists.ifEmpty { initialState.tracks.downloadedArtists() },
    )
    var artists by mutableStateOf(initialState.tracks.downloadedArtists())
    var albums by mutableStateOf(initialState.tracks.downloadedAlbums(offlineAlbumIds))
    var savedAlbums by mutableStateOf(
        initialState.cachedLibrary.savedAlbums.map { album ->
            album.copy(
                savedByCurrentUser = true,
                isOfflineEnabled = album.isOfflineEnabled || album.id in offlineAlbumIds,
            )
        },
    )
    var albumsByArtist by mutableStateOf<Map<String, List<LibraryAlbum>>>(emptyMap())
    var appearsOnByArtist by mutableStateOf<Map<String, List<LibraryAlbum>>>(emptyMap())
    var looseTracksByArtist by mutableStateOf<Map<String, List<Track>>>(emptyMap())
    var similarArtistsByArtist by mutableStateOf<Map<String, List<LibraryArtist>>>(emptyMap())
    var artistAlbumLoadsInProgress by mutableStateOf<Set<String>>(emptySet())
    var similarArtistLoadsInProgress by mutableStateOf<Set<String>>(emptySet())
    var albumTrackLoadsInProgress by mutableStateOf<Set<String>>(emptySet())
    var playlistTrackLoadsInProgress by mutableStateOf<Set<String>>(emptySet())
    var albumDownloadJobs by mutableStateOf<Map<String, Job>>(emptyMap())
    var playlistDownloadJobs by mutableStateOf<Map<String, Job>>(emptyMap())
    var libraryPaging by mutableStateOf(LibraryPagingState())
    var recentAlbumsPaging by mutableStateOf(RecentAlbumsPagingState())
    var artistSortOption by mutableStateOf(ArtistSortOption.Name)
    var artistServerSortOption by mutableStateOf<ArtistSortOption?>(null)
    var artistListCache by mutableStateOf<Map<ArtistSortOption, ArtistListCacheEntry>>(emptyMap())
    var albumTrackHasMoreById by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var playlistTrackHasMoreById by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var albumTracksById by mutableStateOf<Map<String, List<Track>>>(emptyMap())
    var searchQuery by mutableStateOf("")
    var searchFocusRequestSerial by mutableStateOf(0)
    var searchResults by mutableStateOf(LibrarySearchResults(emptyList(), emptyList(), emptyList()))
    var searchLoading by mutableStateOf(false)
    var searchTrackOffset by mutableStateOf(0)
    var searchHasMore by mutableStateOf(false)
    var favoriteSyncTrackIds by mutableStateOf<Set<String>>(emptySet())
    var recentItems by mutableStateOf(userPreferencesStore.recentLibraryItems())
    var libraryLoading by mutableStateOf(
        initialState.account != null && !(initialState.offlineOnly && initialState.canContinueOffline),
    )
    var libraryLoadSerial by mutableStateOf(0)
    var libraryLoadJob by mutableStateOf<Job?>(null)
    var libraryError by mutableStateOf<String?>(null)
    var libraryNotice by mutableStateOf<String?>(null)
    var trackForPlaylistAdd by mutableStateOf<Track?>(null)
    var artistChoices by mutableStateOf<List<LibraryArtist>>(emptyList())
    var playlistPickerPlaylists by mutableStateOf<List<Playlist>>(emptyList())
    var playlistPickerLoading by mutableStateOf(false)
    var playlistMetadataLoaded by mutableStateOf(false)
    var duplicatePlaylistForAdd by mutableStateOf<Playlist?>(null)
    var playlistAddInProgress by mutableStateOf(false)
    var libraryMutationSyncInProgress by mutableStateOf(false)
    var downloadedSizeBytes by mutableStateOf(0L)
    var cacheSizeBytes by mutableStateOf(0L)
    var queueInsertionAnchorTrackId by mutableStateOf<String?>(null)
    var queueInsertionCursor by mutableStateOf<Int?>(null)

    fun replaceArtworkBitmaps(bitmaps: Map<String, ImageBitmap>) {
        artworkBitmaps.keys
            .filter { key -> key !in bitmaps }
            .forEach { key -> artworkBitmaps.remove(key) }
        bitmaps.forEach { (key, bitmap) ->
            if (artworkBitmaps[key] !== bitmap) {
                artworkBitmaps[key] = bitmap
            }
        }
    }

    fun removeArtworkBitmapsForSource(artworkKey: String) {
        artworkBitmaps.keys
            .filter { key -> artworkSourceKey(key) == artworkKey }
            .forEach { key -> artworkBitmaps.remove(key) }
    }
}

@Composable
internal fun TMusicAppStateContent(
    state: TMusicAppMutableState,
    content: @Composable TMusicAppMutableState.() -> Unit,
) {
    state.content()
}
