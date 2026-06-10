package dev.teacode.tmusic.domain

interface AuthRepository {
    suspend fun signInWithGoogle(idToken: String): Result<Account>
    suspend fun signOut()
    suspend fun currentAccount(): Account?
}

interface MusicRepository {
    suspend fun libraryArtists(): List<LibraryArtist>
    suspend fun libraryAlbums(artistId: String? = null): List<LibraryAlbum>
    suspend fun libraryArtistAlbums(artistId: String): LibraryArtistAlbums
    suspend fun savedAlbums(): List<LibraryAlbum>
    suspend fun saveAlbum(albumId: String): LibraryAlbum?
    suspend fun unsaveAlbum(albumId: String): LibraryAlbum?
    suspend fun albumTracks(albumId: String): List<Track>
    suspend fun search(query: String, limit: Int = 10): LibrarySearchResults
    suspend fun playlists(): List<Playlist>
    suspend fun tracks(): List<Track>
    suspend fun playlistTracks(playlistId: String): List<Track>
    suspend fun streamUrl(trackId: String): String
    suspend fun lyrics(trackId: String): TrackLyrics?
    suspend fun refreshLyrics(trackId: String): TrackLyrics?
    suspend fun downloadTrack(trackId: String): OfflineTrackManifest
    suspend fun promoteCachedTrack(trackId: String): OfflineTrackManifest?
    fun localPlaybackUrl(trackId: String): String?
    fun cachedPlaybackUrl(trackId: String): String?
    suspend fun artworkUrl(trackId: String): String
    suspend fun albumArtworkUrl(albumId: String): String
    suspend fun artistArtworkUrl(artistId: String, size: Int): String
    suspend fun playlistArtworkUrl(playlistId: String, size: Int): String
    suspend fun createPlaylist(name: String): Playlist
    suspend fun updatePlaylist(playlistId: String, name: String): Playlist?
    suspend fun deletePlaylist(playlistId: String)
    suspend fun addTrackToPlaylist(playlistId: String, trackId: String): Playlist?
    suspend fun removeTrackFromPlaylist(playlistId: String, playlistTrackId: String): Playlist?
    suspend fun reorderPlaylistTracks(playlistId: String, playlistTrackIds: List<String>): Playlist?
    suspend fun sendPlayEvent(
        clientEventId: String,
        trackId: String,
        playedAt: String,
        durationPlayedMs: Long,
        completed: Boolean,
        source: String,
    )
    suspend fun lastFmAuthRequest(): LastFmAuthRequest
    suspend fun lastFmSession(): LastFmConnection
    suspend fun completeLastFmSession(token: String): LastFmConnection
    suspend fun disconnectLastFm(): LastFmConnection
    suspend fun sendNowPlaying(trackId: String)
    suspend fun removeDownloadedTrack(trackId: String)
    suspend fun musicCacheSizeBytes(): Long
    suspend fun clearMusicCache()
}

interface OfflineRepository {
    suspend fun enablePlaylistOffline(playlistId: String)
    suspend fun removeDownloadedTrack(trackId: String)
    suspend fun downloadedTracks(): List<Track>
}
