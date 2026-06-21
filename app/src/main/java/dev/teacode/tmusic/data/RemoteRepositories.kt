package dev.teacode.tmusic.data

import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.AuthRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LastFmAuthRequest
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibraryArtistAlbums
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.MusicRepository
import dev.teacode.tmusic.domain.OfflineTrackManifest
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import java.net.HttpURLConnection

class RemoteAuthRepository(
    private val apiClient: TMusicApiClient,
    private val sessionStore: SessionStore,
) : AuthRepository {
    fun cachedAccount(): Account? {
        return sessionStore.account()
    }

    fun hasSession(): Boolean {
        return sessionStore.tokens() != null
    }

    fun accessToken(): String? {
        return sessionStore.tokens()?.accessToken
    }

    fun setApiBaseUrl(baseUrl: String) {
        apiClient.setBaseUrl(baseUrl)
    }

    suspend fun appUpdateConfig(): AppUpdateInfo? {
        return apiClient.appUpdateConfig()
    }

    override suspend fun signInWithGoogle(idToken: String): Result<Account> {
        return runCatching {
            val session = apiClient.signInWithGoogle(idToken)
            sessionStore.saveSession(session)
            session.user
        }
    }

    override suspend fun signOut() {
        try {
            apiClient.logout()
        } finally {
            sessionStore.clear()
        }
    }

    override suspend fun currentAccount(): Account? {
        if (sessionStore.tokens() == null) {
            return null
        }

        return try {
            apiClient.me().also(sessionStore::saveAccount)
        } catch (error: Throwable) {
            if (
                error is TMusicApiException &&
                (error.statusCode == HttpURLConnection.HTTP_NOT_FOUND ||
                    error.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED)
            ) {
                sessionStore.clear()
            }
            throw error
        }
    }
}

class RemoteMusicRepository(
    private val apiClient: TMusicApiClient,
    private val offlineTrackStore: OfflineTrackStore,
) : MusicRepository {
    suspend fun library(): CachedLibrary {
        val playlistPayload = apiClient.playlistsPayload()

        return CachedLibrary(
            playlists = playlistPayload.playlists,
            tracks = withOfflineState(playlistPayload.tracks),
        )
    }

    suspend fun libraryPage(
        playlistLimit: Int = 50,
        playlistOffset: Int = 0,
        trackLimit: Int = 50,
    ): CachedLibrary {
        val playlistPayload = apiClient.playlistsPayloadPage(
            limit = playlistLimit,
            offset = playlistOffset,
            trackLimit = trackLimit,
        )

        return CachedLibrary(
            playlists = playlistPayload.playlists,
            tracks = withOfflineState(playlistPayload.tracks),
        )
    }

    override suspend fun libraryArtists(): List<LibraryArtist> {
        return apiClient.libraryArtists()
    }

    suspend fun libraryArtistsPage(limit: Int = 50, offset: Int = 0): List<LibraryArtist> {
        return apiClient.libraryArtistsPage(limit = limit, offset = offset)
    }

    suspend fun libraryArtistsPageWithTotal(limit: Int = 50, offset: Int = 0): LibraryArtistsPage {
        return apiClient.libraryArtistsPageWithTotal(limit = limit, offset = offset)
    }

    suspend fun libraryArtist(artistId: String): LibraryArtist? {
        return apiClient.libraryArtist(artistId)
    }

    override suspend fun libraryAlbums(artistId: String?): List<LibraryAlbum> {
        return apiClient.libraryAlbums(artistId)
    }

    suspend fun libraryAlbumsPage(limit: Int = 50, offset: Int = 0): List<LibraryAlbum> {
        return apiClient.libraryAlbumsPage(limit = limit, offset = offset)
    }

    suspend fun recentAlbums(limit: Int = 10, offset: Int = 0): List<LibraryAlbum> {
        return apiClient.recentAlbums(limit = limit, offset = offset)
    }

    override suspend fun libraryArtistAlbums(artistId: String): LibraryArtistAlbums {
        val result = apiClient.libraryArtistAlbums(artistId)
        return result.copy(tracks = withOfflineState(result.tracks))
    }

    override suspend fun savedAlbums(): List<LibraryAlbum> {
        return apiClient.savedAlbums()
    }

    suspend fun savedAlbumsPage(limit: Int = 50, offset: Int = 0): List<LibraryAlbum> {
        return apiClient.savedAlbumsPage(limit = limit, offset = offset)
    }

    override suspend fun saveAlbum(albumId: String): LibraryAlbum? {
        return apiClient.saveAlbum(albumId)
    }

    override suspend fun unsaveAlbum(albumId: String): LibraryAlbum? {
        return apiClient.unsaveAlbum(albumId)
    }

    override suspend fun albumTracks(albumId: String): List<Track> {
        return withOfflineState(apiClient.albumTracks(albumId))
    }

    suspend fun similarArtists(
        artistId: String,
        limit: Int = 10,
        offset: Int = 0,
    ): List<LibraryArtist> {
        return apiClient.similarArtists(artistId = artistId, limit = limit, offset = offset)
    }

    suspend fun albumTracksPage(
        albumId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): List<Track> {
        return withOfflineState(apiClient.albumTracksPage(albumId = albumId, limit = limit, offset = offset))
    }

    override suspend fun search(query: String, limit: Int, offset: Int): LibrarySearchResults {
        val results = apiClient.librarySearch(query, limit, offset)
        return results.copy(tracks = withOfflineState(results.tracks))
    }

    override suspend fun playlists(): List<Playlist> {
        return apiClient.playlists()
    }

    suspend fun playlistsMetadata(): List<Playlist> {
        return apiClient.playlistsMetadata()
    }

    override suspend fun tracks(): List<Track> {
        return withOfflineState(apiClient.tracks())
    }

    suspend fun recentTracks(limit: Int = 50, offset: Int = 0): List<Track> {
        return withOfflineState(apiClient.recentTracks(limit = limit, offset = offset))
    }

    suspend fun track(trackId: String): Track {
        return withOfflineState(listOf(apiClient.track(trackId))).first()
    }

    suspend fun tracksCount(): Int {
        return apiClient.tracksCount()
    }

    suspend fun playlistPayload(playlistId: String): PlaylistPayload {
        val payload = apiClient.playlistPayload(playlistId)
        return payload.copy(tracks = withOfflineState(payload.tracks))
    }

    suspend fun favoritesPlaylistPayload(playlist: Playlist? = null): PlaylistPayload {
        val payload = apiClient.favoritesPlaylistPayload(
            fallbackPlaylistId = playlist?.id,
            fallbackIsOfflineEnabled = playlist?.isOfflineEnabled == true,
        )
        return payload.copy(tracks = withOfflineState(payload.tracks))
    }

    suspend fun playlistPayloadTrackPage(
        playlistId: String,
        trackLimit: Int = 100,
        trackOffset: Int = 0,
    ): PlaylistPayload {
        val payload = apiClient.playlistPayloadTrackPage(
            playlistId = playlistId,
            trackLimit = trackLimit,
            trackOffset = trackOffset,
        )
        return payload.copy(tracks = withOfflineState(payload.tracks))
    }

    suspend fun favoritesPlaylistPayloadTrackPage(
        playlist: Playlist? = null,
        trackLimit: Int = 100,
        trackOffset: Int = 0,
    ): PlaylistPayload {
        val payload = apiClient.favoritesPlaylistPayloadPage(
            fallbackPlaylistId = playlist?.id,
            fallbackIsOfflineEnabled = playlist?.isOfflineEnabled == true,
            trackLimit = trackLimit,
            trackOffset = trackOffset,
        )
        return payload.copy(tracks = withOfflineState(payload.tracks))
    }

    override suspend fun playlistTracks(playlistId: String): List<Track> {
        val payload = runCatching { playlistPayload(playlistId) }.getOrNull()
        if (payload != null && payload.tracks.isNotEmpty()) {
            return payload.tracks
        }

        val playlist = playlists().firstOrNull { it.id == playlistId } ?: return emptyList()
        val tracksById = tracks().associateBy { it.id }
        return playlist.trackIds.mapNotNull(tracksById::get)
    }

    override suspend fun streamUrl(trackId: String): String {
        return apiClient.streamUrl(trackId)
    }

    override suspend fun lyrics(trackId: String) = apiClient.lyrics(trackId)

    override suspend fun refreshLyrics(trackId: String) = apiClient.refreshLyrics(trackId)

    override suspend fun downloadTrack(trackId: String): OfflineTrackManifest {
        return offlineTrackStore.download(apiClient.trackDownloadInfo(trackId))
    }

    override suspend fun promoteCachedTrack(trackId: String): OfflineTrackManifest? {
        return offlineTrackStore.promoteCachedTrack(trackId)
    }

    suspend fun clearDownloads() {
        offlineTrackStore.clear()
    }

    suspend fun downloadsSizeBytes(): Long {
        return offlineTrackStore.sizeBytes()
    }

    override suspend fun removeDownloadedTrack(trackId: String) {
        offlineTrackStore.moveToCache(trackId, MUSIC_CACHE_LIMIT_BYTES)
    }

    override fun localPlaybackUrl(trackId: String): String? {
        return offlineTrackStore.localPlaybackUrl(trackId)
    }

    override fun cachedPlaybackUrl(trackId: String): String? {
        return offlineTrackStore.cachedPlaybackUrl(trackId)
    }

    override suspend fun musicCacheSizeBytes(): Long {
        return offlineTrackStore.cacheSizeBytes()
    }

    override suspend fun clearMusicCache() {
        offlineTrackStore.clearCache()
    }

    suspend fun clearMusicCache(retainedTrackIds: Set<String>) {
        offlineTrackStore.clearCacheExcept(retainedTrackIds)
    }

    override suspend fun artworkUrl(trackId: String): String {
        return apiClient.artworkUrl(trackId)
    }

    override suspend fun albumArtworkUrl(albumId: String): String {
        return apiClient.albumArtworkUrl(albumId)
    }

    override suspend fun artistArtworkUrl(artistId: String, size: Int): String {
        return apiClient.artistArtworkUrl(artistId, size = size)
    }

    override suspend fun playlistArtworkUrl(playlistId: String, size: Int): String {
        return apiClient.playlistArtworkUrl(playlistId, size = size)
    }

    override suspend fun createPlaylist(name: String): Playlist {
        return apiClient.createPlaylist(name)
    }

    override suspend fun updatePlaylist(playlistId: String, name: String): Playlist? {
        return apiClient.updatePlaylist(
            playlistId = playlistId,
            name = name,
        )
    }

    override suspend fun deletePlaylist(playlistId: String) {
        apiClient.deletePlaylist(playlistId)
    }

    override suspend fun addTrackToPlaylist(playlistId: String, trackId: String): Playlist? {
        return apiClient.addTrackToPlaylist(
            playlistId = playlistId,
            trackId = trackId,
        )
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, playlistTrackId: String): Playlist? {
        return apiClient.removeTrackFromPlaylist(
            playlistId = playlistId,
            playlistTrackId = playlistTrackId,
        )
    }

    override suspend fun reorderPlaylistTracks(playlistId: String, playlistTrackIds: List<String>): Playlist? {
        return apiClient.reorderPlaylistTracks(
            playlistId = playlistId,
            playlistTrackIds = playlistTrackIds,
        )
    }

    override suspend fun movePlaylistTrack(
        playlistId: String,
        playlistTrackId: String,
        position: Int,
    ): Playlist? {
        return apiClient.movePlaylistTrack(
            playlistId = playlistId,
            playlistTrackId = playlistTrackId,
            position = position,
        )
    }

    override suspend fun sendPlayEvent(
        clientEventId: String,
        trackId: String,
        playedAt: String,
        durationPlayedMs: Long,
        completed: Boolean,
        source: String,
    ) {
        apiClient.sendPlayEvent(
            clientEventId = clientEventId,
            trackId = trackId,
            playedAt = playedAt,
            durationPlayedMs = durationPlayedMs,
            completed = completed,
            source = source,
        )
    }

    suspend fun syncPlayEvents(events: List<PendingPlayEvent>): Set<String> {
        return apiClient.syncPlayEvents(events)
    }

    suspend fun syncLibraryMutations(mutations: List<PendingLibraryMutation>): Set<String> {
        return apiClient.syncLibraryMutations(mutations)
    }

    override suspend fun lastFmAuthRequest(): LastFmAuthRequest {
        return apiClient.lastFmAuthRequest()
    }

    override suspend fun lastFmSession(): LastFmConnection {
        return apiClient.lastFmSession()
    }

    override suspend fun completeLastFmSession(token: String): LastFmConnection {
        return apiClient.completeLastFmSession(token)
    }

    override suspend fun disconnectLastFm(): LastFmConnection {
        return apiClient.disconnectLastFm()
    }

    override suspend fun sendNowPlaying(trackId: String) {
        apiClient.sendNowPlaying(trackId)
    }

    fun withOfflineState(tracks: List<Track>): List<Track> {
        return tracks.map { track ->
            val manifest = offlineTrackStore.manifest(track.id)
            if (manifest == null) {
                track.copy(downloadState = DownloadState.NotDownloaded)
            } else {
                track.copy(
                    serverPath = manifest.localPath,
                    downloadState = DownloadState.Downloaded,
                )
            }
        }
    }

    private companion object {
        const val MUSIC_CACHE_LIMIT_BYTES = 4L * 1024L * 1024L * 1024L
    }
}
