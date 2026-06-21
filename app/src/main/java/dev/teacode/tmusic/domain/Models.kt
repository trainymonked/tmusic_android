package dev.teacode.tmusic.domain

enum class DownloadState {
    NotDownloaded,
    Queued,
    Downloaded,
}

enum class ScrobbleState {
    Disabled,
    NeedsAuth,
    Ready,
    Error,
}

data class Account(
    val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String? = null,
    val lastFmConnection: LastFmConnection? = null,
    val canPlayMedia: Boolean = true,
)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val serverPath: String,
    val accentColor: Long,
    val downloadState: DownloadState,
    val playCount: Int,
    val artistId: String? = null,
    val artistIds: List<String> = emptyList(),
    val artists: List<LibraryArtist> = emptyList(),
    val albumId: String? = null,
    val albumArtist: String? = null,
    val albumArtistId: String? = null,
    val albumArtists: List<LibraryArtist> = emptyList(),
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val releaseYear: Int? = null,
    val genre: String? = null,
    val isLiked: Boolean? = null,
    val foundInLyrics: Boolean = false,
)

data class Playlist(
    val id: String,
    val title: String,
    val trackIds: List<String>,
    val isOfflineEnabled: Boolean,
    val isPublic: Boolean = false,
    val playlistTrackIds: List<String> = emptyList(),
    val playlistTrackIdsByTrackId: Map<String, String> = emptyMap(),
    val isFavorites: Boolean = false,
    val trackCount: Int = trackIds.size,
    val totalDurationSeconds: Int? = null,
    val updatedAt: String? = null,
)

data class PlayerState(
    val currentTrack: Track?,
    val isPlaying: Boolean,
    val progressSeconds: Int,
    val streamUrl: String?,
)

data class TrackLyrics(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val instrumental: Boolean = false,
)

data class LastFmConnection(
    val username: String?,
    val state: ScrobbleState,
    val pendingScrobbles: Int,
)

data class ArtistSimilarity(
    val source: String,
    val score: Int? = null,
    val sharedGenres: List<String> = emptyList(),
) {
    val isManual: Boolean
        get() = source.equals("manual", ignoreCase = true)
}

data class LibraryArtist(
    val name: String,
    val id: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
    val representativeAlbumId: String? = null,
    val similarity: ArtistSimilarity? = null,
)

data class LibraryArtistAlbums(
    val albums: List<LibraryAlbum>,
    val appearsOn: List<LibraryAlbum> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val all: List<LibraryAlbum>
        get() = (albums + appearsOn).distinctBy { it.id }
}

data class RecentAlbumChange(
    val type: String,
    val latestTrackCreatedAt: String? = null,
    val latestTrackUpdatedAt: String? = null,
)

data class LibraryAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val artistIds: List<String> = emptyList(),
    val artists: List<LibraryArtist> = emptyList(),
    val releaseYear: Int? = null,
    val genre: String? = null,
    val trackCount: Int = 0,
    val accentColor: Long,
    val artworkTrackId: String? = null,
    val savedByCurrentUser: Boolean = false,
    val isOfflineEnabled: Boolean = false,
    val hasArtwork: Boolean = false,
    val totalDurationSeconds: Int? = null,
    val recentChangeType: String? = null,
    val isNewAlbum: Boolean = false,
    val recentChange: RecentAlbumChange? = null,
    val userAlbumCreatedAt: String? = null,
)

data class LibrarySearchResults(
    val artists: List<LibraryArtist>,
    val albums: List<LibraryAlbum>,
    val tracks: List<Track>,
    val playlists: List<Playlist> = emptyList(),
)

enum class RecentLibraryItemType {
    Artist,
    Album,
    Track,
}

data class RecentLibraryItem(
    val type: RecentLibraryItemType,
    val title: String,
    val subtitle: String? = null,
    val id: String? = null,
)

data class LastFmAuthRequest(
    val token: String,
    val url: String,
)

data class TrackDownloadInfo(
    val trackId: String,
    val url: String,
    val etag: String?,
    val checksumSha256: String?,
)

data class OfflineTrackManifest(
    val trackId: String,
    val etag: String?,
    val checksumSha256: String?,
    val localPath: String,
)
