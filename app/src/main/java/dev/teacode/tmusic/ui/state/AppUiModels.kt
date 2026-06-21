package dev.teacode.tmusic.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal enum class AppTab(
    val label: String,
    val icon: ImageVector,
) {
    Home("Home", Icons.Filled.Home),
    Search("Search", Icons.Filled.Search),
    Library("Library", Icons.Filled.LibraryMusic),
    Profile("Profile", Icons.Filled.Person),
}

internal enum class HomeRoute {
    Overview,
    Artists,
    Albums,
    Artist,
    Album,
}

internal const val SERVER_OFFLINE_FALLBACK_TIMEOUT_MS = 10_000L
internal const val SERVER_SYNC_HARD_TIMEOUT_MS = 60_000L
internal const val APP_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
internal const val ARTWORK_CACHE_LIMIT_BYTES = 128L * 1024L * 1024L
internal const val PENDING_PLAY_EVENT_SYNC_BATCH_SIZE = 20
internal const val HOME_ARTIST_PREVIEW_LIMIT = 12
internal const val HOME_RECENT_ALBUM_PAGE_LIMIT = 10
internal const val HOME_RECENT_ALBUM_MAX_COUNT = 50

internal data class RecentAlbumsPagingState(
    val loadingMore: Boolean = false,
    val nextOffset: Int = 0,
    val hasMore: Boolean = true,
)

internal data class LibraryPagingState(
    val artistLoadingMore: Boolean = false,
    val albumLoadingMore: Boolean = false,
    val artistNextOffset: Int = 0,
    val albumNextOffset: Int = 0,
    val artistHasMore: Boolean = true,
    val albumHasMore: Boolean = true,
)
internal const val SCREEN_PAGE_LIMIT = 50
internal const val DETAIL_TRACK_PAGE_LIMIT = 100
internal const val GAPLESS_PREFETCH_LOOKAHEAD = 4
internal const val MEDIA_ACTION_TOGGLE_FAVORITE = "dev.teacode.tmusic.action.TOGGLE_FAVORITE"

enum class ArtworkImageSize(val maxSizePx: Int) {
    TrackList(128),
    AlbumGrid(384),
    FullPlayer(1200),
}

internal data class AppDestination(
    val tab: AppTab = AppTab.Home,
    val playlistId: String? = null,
    val homeRoute: HomeRoute = HomeRoute.Overview,
    val artistId: String? = null,
    val artistName: String? = null,
    val albumId: String? = null,
)

internal data class ActivePlayEvent(
    val clientEventId: String,
    val trackId: String,
    val playedAt: String,
    val durationPlayedMs: Long,
)

enum class PlaybackRepeatMode {
    None,
    Queue,
    Track,
}

enum class DownloadBadgePlacement {
    End,
    BeforeTitle,
}

internal data class LoadedLibraryState(
    val account: Account?,
    val playlists: List<Playlist>? = null,
    val tracks: List<Track>? = null,
    val recentAlbums: List<LibraryAlbum>? = null,
    val trackCount: Int? = null,
    val artists: List<LibraryArtist>? = null,
    val albums: List<LibraryAlbum>? = null,
    val savedAlbums: List<LibraryAlbum>? = null,
    val lastFmConnection: LastFmConnection?,
)

enum class SyncMode {
    Offline,
    Syncing,
    Online,
    OfflineOnly,
}

internal val OfflineAccount = Account(
    id = "offline",
    displayName = "Offline mode",
    email = "offline@device",
)
