package dev.teacode.tmusic.data

import android.util.Log
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.ArtistSimilarity
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LastFmAuthRequest
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibraryArtistAlbums
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.ScrobbleState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackDownloadInfo
import dev.teacode.tmusic.domain.TrackLyrics
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class TMusicApiException(
    val statusCode: Int?,
    message: String,
) : Exception(message)

data class PlaylistPayload(
    val playlists: List<Playlist>,
    val tracks: List<Track>,
)

data class LibraryArtistsPage(
    val artists: List<LibraryArtist>,
    val totalCount: Int?,
)

private const val ARTWORK_SIZE_PX = 1200
private const val API_LOG_TAG = "TMusicApi"

class TMusicApiClient(
    initialBaseUrl: String,
    private val sessionStore: SessionStore,
) {
    @Volatile
    private var baseUrl: String = initialBaseUrl.trimEnd('/')

    fun baseUrl(): String {
        return baseUrl
    }

    fun setBaseUrl(nextBaseUrl: String) {
        baseUrl = nextBaseUrl.trimEnd('/')
    }

    suspend fun signInWithGoogle(idToken: String): AuthSession {
        val body = JSONObject()
            .put("idToken", idToken)
            .toString()

        val responseBody = request(
            method = "POST",
            path = "/auth/google",
            body = body,
            authenticated = false,
        )
        val root = JSONObject(responseBody).payloadObject()
        val user = root.optJSONObject("user")
            ?: root.optJSONObject("account")
            ?: JSONObject()

        return AuthSession(
            accessToken = root.requireString("accessToken"),
            refreshToken = root.requireString("refreshToken"),
            user = user.toAccount(),
        )
    }

    suspend fun me(): Account {
        val body = request(
            method = "GET",
            path = "/auth/me",
            authenticated = true,
        )
        val root = JSONObject(body).payloadObject()
        val user = root.optJSONObject("user")
            ?: root.optJSONObject("account")
            ?: root

        return user.toAccount()
    }

    suspend fun playlists(): List<Playlist> {
        return playlistsPayload().playlists
    }

    suspend fun playlistsMetadata(): List<Playlist> {
        val playlists = mutableListOf<Playlist>()
        var offset = 0
        while (true) {
            val body = request(
                method = "GET",
                path = "/playlists?limit=$PLAYLIST_PAGE_LIMIT&offset=$offset&trackLimit=0&trackOffset=0",
                authenticated = true,
            )
            val page = body.toPlaylistPayload().playlists
            playlists += page
            if (page.size < PLAYLIST_PAGE_LIMIT) {
                break
            }
            offset += PLAYLIST_PAGE_LIMIT
        }
        return playlists.distinctBy { it.id }
    }

    suspend fun playlistsPayload(): PlaylistPayload {
        val pages = mutableListOf<PlaylistPayload>()
        var offset = 0
        while (true) {
            val body = request(
                method = "GET",
                path = "/playlists?limit=$PLAYLIST_PAGE_LIMIT&offset=$offset" +
                    "&trackLimit=$PLAYLIST_TRACK_PAGE_LIMIT&trackOffset=0",
                authenticated = true,
            )
            val page = body.toPlaylistPayload()
            pages += page
            if (page.playlists.size < PLAYLIST_PAGE_LIMIT) {
                break
            }
            offset += PLAYLIST_PAGE_LIMIT
        }

        return pages.mergePlaylistPayloads()
    }

    suspend fun playlistsPayloadPage(
        limit: Int = PLAYLIST_PAGE_LIMIT,
        offset: Int = 0,
        trackLimit: Int = PLAYLIST_TRACK_PAGE_LIMIT,
        trackOffset: Int = 0,
    ): PlaylistPayload {
        val safeLimit = limit.coerceIn(1, PLAYLIST_PAGE_LIMIT)
        val safeTrackLimit = trackLimit.coerceIn(0, PLAYLIST_DETAIL_TRACK_PAGE_LIMIT)
        val body = request(
            method = "GET",
            path = "/playlists?limit=$safeLimit&offset=${offset.coerceAtLeast(0)}" +
                "&trackLimit=$safeTrackLimit&trackOffset=${trackOffset.coerceAtLeast(0)}",
            authenticated = true,
        )
        return body.toPlaylistPayload()
    }

    suspend fun playlistPayload(playlistId: String): PlaylistPayload {
        val pages = mutableListOf<PlaylistPayload>()
        var trackOffset = 0
        while (true) {
            val body = request(
                method = "GET",
                path = "/playlists/${playlistId.pathSegment()}?trackLimit=$PLAYLIST_DETAIL_TRACK_PAGE_LIMIT" +
                    "&trackOffset=$trackOffset",
                authenticated = true,
            )
            val page = body.toPlaylistPayload()
            pages += page
            val loadedTrackCount = pages.flatMap { payload -> payload.playlists.firstOrNull()?.trackIds.orEmpty() }.size
            val totalTrackCount = pages.asSequence()
                .mapNotNull { payload -> payload.playlists.firstOrNull()?.trackCount?.takeIf { it > 0 } }
                .firstOrNull()
            if (totalTrackCount != null && loadedTrackCount >= totalTrackCount) {
                break
            }
            val playlistTrackCount = page.playlists.firstOrNull()?.trackIds.orEmpty().size
            val pageSize = maxOf(page.tracks.size, playlistTrackCount)
            if (pageSize < PLAYLIST_DETAIL_TRACK_PAGE_LIMIT) {
                break
            }
            trackOffset += PLAYLIST_DETAIL_TRACK_PAGE_LIMIT
        }

        return pages.mergeSinglePlaylistPayload()
    }

    suspend fun playlistPayloadTrackPage(
        playlistId: String,
        trackLimit: Int = PLAYLIST_DETAIL_TRACK_PAGE_LIMIT,
        trackOffset: Int = 0,
    ): PlaylistPayload {
        val safeTrackLimit = trackLimit.coerceIn(1, PLAYLIST_DETAIL_TRACK_PAGE_LIMIT)
        val body = request(
            method = "GET",
            path = "/playlists/${playlistId.pathSegment()}?trackLimit=$safeTrackLimit" +
                "&trackOffset=${trackOffset.coerceAtLeast(0)}",
            authenticated = true,
        )
        return body.toPlaylistPayload()
    }

    suspend fun favoritesPlaylistPayload(
        fallbackPlaylistId: String? = null,
        fallbackIsOfflineEnabled: Boolean = false,
    ): PlaylistPayload {
        val pages = mutableListOf<PlaylistPayload>()
        var offset = 0
        while (true) {
            val page = favoritesPlaylistPayloadPage(
                fallbackPlaylistId = fallbackPlaylistId,
                fallbackIsOfflineEnabled = fallbackIsOfflineEnabled,
                trackLimit = PLAYLIST_DETAIL_TRACK_PAGE_LIMIT,
                trackOffset = offset,
            )
            pages += page
            val loadedTrackCount = pages.flatMap { payload -> payload.playlists.firstOrNull()?.trackIds.orEmpty() }.size
            val totalTrackCount = pages.asSequence()
                .mapNotNull { payload -> payload.playlists.firstOrNull()?.trackCount?.takeIf { it > 0 } }
                .firstOrNull()
            if (totalTrackCount != null && loadedTrackCount >= totalTrackCount) {
                break
            }
            val pageSize = maxOf(page.tracks.size, page.playlists.firstOrNull()?.trackIds.orEmpty().size)
            if (pageSize < PLAYLIST_DETAIL_TRACK_PAGE_LIMIT) {
                break
            }
            offset += PLAYLIST_DETAIL_TRACK_PAGE_LIMIT
        }

        return pages.mergeSinglePlaylistPayload()
    }

    suspend fun favoritesPlaylistPayloadPage(
        fallbackPlaylistId: String? = null,
        fallbackIsOfflineEnabled: Boolean = false,
        trackLimit: Int = PLAYLIST_DETAIL_TRACK_PAGE_LIMIT,
        trackOffset: Int = 0,
    ): PlaylistPayload {
        val safeTrackLimit = trackLimit.coerceIn(1, PLAYLIST_DETAIL_TRACK_PAGE_LIMIT)
        val body = request(
            method = "GET",
            path = "/playlists/favorites?limit=$safeTrackLimit&offset=${trackOffset.coerceAtLeast(0)}",
            authenticated = true,
        )
        return body.toFavoritesPlaylistPayload(
            fallbackPlaylistId = fallbackPlaylistId,
            fallbackIsOfflineEnabled = fallbackIsOfflineEnabled,
        )
    }

    suspend fun libraryArtists(): List<LibraryArtist> {
        return pagedList(
            path = "/library/artists",
            limit = LIBRARY_PAGE_LIMIT,
            parse = String::toLibraryArtists,
        ).distinctBy { it.name.lowercase() }
    }

    suspend fun libraryArtistsPage(
        limit: Int = LIBRARY_PAGE_LIMIT,
        offset: Int = 0,
    ): List<LibraryArtist> {
        return libraryArtistsPageWithTotal(limit = limit, offset = offset).artists
    }

    suspend fun libraryArtistsPageWithTotal(
        limit: Int = LIBRARY_PAGE_LIMIT,
        offset: Int = 0,
    ): LibraryArtistsPage {
        val body = request(
            method = "GET",
            path = "/library/artists".withPagination(
                limit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT),
                offset = offset.coerceAtLeast(0),
            ),
            authenticated = true,
        )
        return body.toLibraryArtistsPage()
    }

    suspend fun libraryArtist(artistId: String): LibraryArtist? {
        val body = request(
            method = "GET",
            path = "/library/artists/${artistId.pathSegment()}",
            authenticated = true,
        ).trim()
        if (body.isBlank()) {
            return null
        }
        val root = JSONObject(body).payloadObject()
        val artist = root.optJSONObject("artist")
            ?: root.optJSONObject("libraryArtist")
            ?: root
        return artist.toLibraryArtist()
    }

    suspend fun libraryAlbums(artistId: String? = null): List<LibraryAlbum> {
        if (!artistId.isNullOrBlank()) {
            return pagedList(
                path = "/library/albums?artistId=${artistId.queryValue()}",
                limit = LIBRARY_PAGE_LIMIT,
                parse = String::toLibraryAlbums,
            ).distinctBy { it.id }
        }

        return pagedList(
            path = "/library/albums",
            limit = LIBRARY_PAGE_LIMIT,
            parse = String::toLibraryAlbums,
        ).distinctBy { it.id }
    }

    suspend fun libraryAlbumsPage(
        limit: Int = LIBRARY_PAGE_LIMIT,
        offset: Int = 0,
    ): List<LibraryAlbum> {
        return pagedListPage(
            path = "/library/albums",
            limit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT),
            offset = offset,
            parse = String::toLibraryAlbums,
        ).distinctBy { it.id }
    }

    suspend fun libraryArtistAlbums(artistId: String): LibraryArtistAlbums {
        val albums = linkedMapOf<String, LibraryAlbum>()
        val appearsOn = linkedMapOf<String, LibraryAlbum>()
        val tracks = linkedMapOf<String, Track>()
        var albumOffset = 0
        var appearsOnOffset = 0
        var trackOffset = 0
        var albumsDone = false
        var appearsOnDone = false
        var tracksDone = false
        var requestCount = 0

        while (!(albumsDone && appearsOnDone && tracksDone) && requestCount < LIBRARY_ARTIST_ALBUMS_MAX_REQUESTS) {
            requestCount += 1
            val body = request(
                method = "GET",
                path = "/library/artists/${artistId.pathSegment()}/albums" +
                    "?albumLimit=$LIBRARY_PAGE_LIMIT&albumOffset=$albumOffset" +
                    "&appearsOnLimit=$LIBRARY_PAGE_LIMIT&appearsOnOffset=$appearsOnOffset" +
                    "&trackLimit=$LIBRARY_PAGE_LIMIT&trackOffset=$trackOffset",
                authenticated = true,
            )
            val page = body.toLibraryArtistAlbums()
            val newAlbums = page.albums.count { albums.putIfAbsent(it.id, it) == null }
            val newAppearsOn = page.appearsOn.count { appearsOn.putIfAbsent(it.id, it) == null }
            val newTracks = page.tracks.count { tracks.putIfAbsent(it.id, it) == null }

            albumsDone = albumsDone || page.albums.size < LIBRARY_PAGE_LIMIT || newAlbums == 0
            appearsOnDone = appearsOnDone || page.appearsOn.size < LIBRARY_PAGE_LIMIT || newAppearsOn == 0
            tracksDone = tracksDone || page.tracks.size < LIBRARY_PAGE_LIMIT || newTracks == 0

            if (!albumsDone) {
                albumOffset += page.albums.size
            }
            if (!appearsOnDone) {
                appearsOnOffset += page.appearsOn.size
            }
            if (!tracksDone) {
                trackOffset += page.tracks.size
            }
            if (newAlbums == 0 && newAppearsOn == 0 && newTracks == 0) {
                break
            }
        }

        return LibraryArtistAlbums(
            albums = albums.values.toList(),
            appearsOn = appearsOn.values.toList(),
            tracks = tracks.values.toList(),
        )
    }

    suspend fun similarArtists(
        artistId: String,
        limit: Int = 10,
        offset: Int = 0,
    ): List<LibraryArtist> {
        val body = request(
            method = "GET",
            path = "/library/artists/${artistId.pathSegment()}/similar" +
                "?limit=${limit.coerceIn(1, LIBRARY_PAGE_LIMIT)}&offset=${offset.coerceAtLeast(0)}",
            authenticated = true,
        )

        return body.toSimilarArtists()
    }

    suspend fun savedAlbums(): List<LibraryAlbum> {
        return pagedList(
            path = "/library/me/albums",
            limit = LIBRARY_PAGE_LIMIT,
            parse = String::toLibraryAlbums,
        ).distinctBy { it.id }.map { it.copy(savedByCurrentUser = true) }
    }

    suspend fun savedAlbumsPage(
        limit: Int = LIBRARY_PAGE_LIMIT,
        offset: Int = 0,
    ): List<LibraryAlbum> {
        return pagedListPage(
            path = "/library/me/albums",
            limit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT),
            offset = offset,
            parse = String::toLibraryAlbums,
        ).distinctBy { it.id }.map { it.copy(savedByCurrentUser = true) }
    }

    suspend fun saveAlbum(albumId: String): LibraryAlbum? {
        val body = request(
            method = "POST",
            path = "/library/albums/${albumId.pathSegment()}/save",
            authenticated = true,
        ).trim()

        if (body.isBlank()) {
            return null
        }

        return body.toLibraryAlbumPayload()?.copy(savedByCurrentUser = true)
    }

    suspend fun unsaveAlbum(albumId: String): LibraryAlbum? {
        val body = request(
            method = "DELETE",
            path = "/library/albums/${albumId.pathSegment()}/save",
            authenticated = true,
        ).trim()

        if (body.isBlank()) {
            return null
        }

        return body.toLibraryAlbumPayload()?.copy(savedByCurrentUser = false)
    }

    suspend fun albumTracks(albumId: String): List<Track> {
        return pagedList(
            path = "/library/albums/${albumId.pathSegment()}/tracks",
            limit = TRACK_LIST_PAGE_LIMIT,
            parse = String::toTracks,
        ).distinctBy { it.id }
    }

    suspend fun albumTracksPage(
        albumId: String,
        limit: Int = TRACK_LIST_PAGE_LIMIT,
        offset: Int = 0,
    ): List<Track> {
        return pagedListPage(
            path = "/library/albums/${albumId.pathSegment()}/tracks",
            limit = limit.coerceIn(1, TRACK_LIST_PAGE_LIMIT),
            offset = offset,
            parse = String::toTracks,
        ).distinctBy { it.id }
    }

    suspend fun librarySearch(query: String, limit: Int): LibrarySearchResults {
        val safeLimit = limit.coerceIn(1, TRACK_SEARCH_PAGE_LIMIT)
        val tracks = tracks(query = query, limit = safeLimit, offset = 0)
        val libraryResults = runCatching {
            val body = request(
                method = "GET",
                path = "/library/search?q=${query.queryValue()}&limit=$safeLimit",
                authenticated = true,
            )
            body.toLibrarySearchResults()
        }.getOrDefault(LibrarySearchResults(emptyList(), emptyList(), emptyList()))

        return libraryResults.copy(tracks = tracks)
    }

    suspend fun tracks(): List<Track> {
        return pagedList(
            path = "/tracks",
            limit = TRACK_CATALOG_PAGE_LIMIT,
            parse = String::toTracks,
        ).distinctBy { it.id }
    }

    suspend fun recentTracks(limit: Int = 50): List<Track> {
        val safeLimit = limit.coerceIn(1, TRACK_CATALOG_PAGE_LIMIT)
        val body = request(
            method = "GET",
            path = "/tracks/recent?limit=$safeLimit&offset=0",
            authenticated = true,
        )
        return body.toTracks().distinctBy { it.id }
    }

    suspend fun tracksCount(): Int {
        val body = request(
            method = "GET",
            path = "/tracks/count",
            authenticated = true,
        )
        val root = JSONObject(body).payloadObject()
        return root.optionalInt("count") ?: 0
    }

    private suspend fun tracks(query: String, limit: Int, offset: Int): List<Track> {
        val body = request(
            method = "GET",
            path = "/tracks?q=${query.queryValue()}&limit=${limit.coerceAtLeast(1)}&offset=${offset.coerceAtLeast(0)}",
            authenticated = true,
        )

        return body.toTracks().distinctBy { it.id }
    }

    suspend fun streamUrl(trackId: String): String {
        val body = request(
            method = "GET",
            path = "/tracks/${trackId.pathSegment()}/stream-url",
            authenticated = true,
        ).trim()

        if (body.startsWith("{")) {
            val root = JSONObject(body).payloadObject()
            return root.optionalMediaUrl()
                ?.toAbsoluteUrl(baseUrl)
                ?: throw TMusicApiException(null, "The stream URL response does not contain a playback URL.")
        }

        return body.trim('"')
            .toAbsoluteUrl(baseUrl)
    }

    suspend fun lyrics(trackId: String): TrackLyrics? {
        val body = runCatching {
            request(
                method = "GET",
                path = "/tracks/${trackId.pathSegment()}/lyrics",
                authenticated = true,
            ).trim()
        }.getOrElse { error ->
            if (error is TMusicApiException && error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return null
            }
            throw error
        }

        if (body.isBlank()) {
            return null
        }
        return body.toTrackLyricsPayload()
    }

    suspend fun refreshLyrics(trackId: String): TrackLyrics? {
        val body = runCatching {
            request(
                method = "POST",
                path = "/tracks/${trackId.pathSegment()}/lyrics/refresh",
                authenticated = true,
            ).trim()
        }.getOrElse { error ->
            if (error is TMusicApiException && error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return null
            }
            throw error
        }

        if (body.isBlank()) {
            return null
        }
        return body.toTrackLyricsPayload()
    }

    suspend fun createPlaylist(name: String): Playlist {
        val body = JSONObject()
            .put("name", name)
            .toString()
        val responseBody = request(
            method = "POST",
            path = "/playlists",
            body = body,
            authenticated = true,
        )
        val root = JSONObject(responseBody).payloadObject()
        val playlist = root.optJSONObject("playlist") ?: root

        return playlist.toPlaylist()
    }

    suspend fun updatePlaylist(playlistId: String, name: String, description: String): Playlist? {
        val body = JSONObject()
            .put("name", name)
            .put("description", description)
            .toString()
        val responseBody = request(
            method = "PATCH",
            path = "/playlists/${playlistId.pathSegment()}",
            body = body,
            authenticated = true,
        ).trim()

        if (responseBody.isBlank()) {
            return null
        }

        val root = JSONObject(responseBody).payloadObject()
        return root.playlistUpdateOrNull()
    }

    suspend fun deletePlaylist(playlistId: String) {
        request(
            method = "DELETE",
            path = "/playlists/${playlistId.pathSegment()}",
            authenticated = true,
        )
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String): Playlist? {
        val body = JSONObject()
            .put("trackId", trackId)
            .toString()
        val responseBody = request(
            method = "POST",
            path = "/playlists/${playlistId.pathSegment()}/tracks",
            body = body,
            authenticated = true,
        ).trim()

        if (responseBody.isBlank()) {
            return null
        }

        val root = JSONObject(responseBody).payloadObject()
        return root.playlistUpdateOrNull()
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, playlistTrackId: String): Playlist? {
        val responseBody = request(
            method = "DELETE",
            path = "/playlists/${playlistId.pathSegment()}/tracks/${playlistTrackId.pathSegment()}",
            body = null,
            authenticated = true,
        ).trim()

        if (responseBody.isBlank()) {
            return null
        }

        val root = JSONObject(responseBody).payloadObject()
        val playlist = root.optJSONObject("playlist") ?: root
        return playlist.toPlaylist()
    }

    suspend fun reorderPlaylistTracks(playlistId: String, playlistTrackIds: List<String>): Playlist? {
        val body = JSONObject()
            .put("playlistTrackIds", JSONArray(playlistTrackIds))
            .toString()
        val responseBody = request(
            method = "PUT",
            path = "/playlists/${playlistId.pathSegment()}/tracks/reorder",
            body = body,
            authenticated = true,
        ).trim()

        if (responseBody.isBlank()) {
            return null
        }

        val root = JSONObject(responseBody).payloadObject()
        val playlist = root.optJSONObject("playlist") ?: root
        return playlist.toPlaylist()
    }

    suspend fun trackDownloadInfo(trackId: String): TrackDownloadInfo {
        val body = request(
            method = "GET",
            path = "/tracks/${trackId.pathSegment()}/download-url",
            authenticated = true,
        )
        val root = JSONObject(body).payloadObject()

        return TrackDownloadInfo(
            trackId = trackId,
            url = root.optionalMediaUrl()?.toAbsoluteUrl(baseUrl)
                ?: throw TMusicApiException(null, "The download URL response does not contain downloadUrl or url."),
            etag = root.optionalString("etag", "eTag", "ETag"),
            checksumSha256 = root.optionalString("checksumSha256", "sha256", "checksum"),
        )
    }

    suspend fun artworkUrl(trackId: String): String {
        val body = request(
            method = "GET",
            path = "/tracks/${trackId.pathSegment()}/artwork-url",
            authenticated = true,
        ).trim()

        if (!body.startsWith("{")) {
            return body.trim('"')
                .toAbsoluteUrl(baseUrl)
        }

        val root = JSONObject(body).payloadObject()
        return (
            root.artworkUrl()?.toAbsoluteUrl(baseUrl)
                ?: root.optionalString("artworkUrl", "url", "href")?.toAbsoluteUrl(baseUrl)
            )
            ?: throw TMusicApiException(null, "The artwork response does not contain artworkUrl or url.")
    }

    suspend fun albumArtworkUrl(albumId: String): String {
        val body = request(
            method = "GET",
            path = "/library/albums/${albumId.pathSegment()}/artwork-url",
            authenticated = true,
        ).trim()

        if (!body.startsWith("{")) {
            return body.trim('"')
                .toAbsoluteUrl(baseUrl)
        }

        val root = JSONObject(body).payloadObject()
        return (
            root.artworkUrl()?.toAbsoluteUrl(baseUrl)
                ?: root.optionalString("artworkUrl", "url", "href")?.toAbsoluteUrl(baseUrl)
            )
            ?: throw TMusicApiException(null, "The album artwork response does not contain artworkUrl or url.")
    }

    suspend fun artistArtworkUrl(artistId: String, size: Int): String {
        val safeSize = size.coerceIn(1, ARTWORK_SIZE_PX)
        val body = request(
            method = "GET",
            path = "/library/artists/${artistId.pathSegment()}/artwork-url?size=$safeSize",
            authenticated = true,
        ).trim()

        if (!body.startsWith("{")) {
            return body.trim('"')
                .toAbsoluteUrl(baseUrl)
        }

        val root = JSONObject(body).payloadObject()
        return (
            root.artworkUrl()?.toAbsoluteUrl(baseUrl)
                ?: root.optionalString("artworkUrl", "url", "href")?.toAbsoluteUrl(baseUrl)
            )
            ?: throw TMusicApiException(null, "The artist artwork response does not contain artworkUrl or url.")
    }

    suspend fun playlistArtworkUrl(playlistId: String, size: Int): String {
        val safeSize = size.coerceIn(1, ARTWORK_SIZE_PX)
        val body = request(
            method = "GET",
            path = "/playlists/${playlistId.pathSegment()}/artwork-url?size=$safeSize",
            authenticated = true,
        ).trim()

        if (!body.startsWith("{")) {
            return body.trim('"')
                .toAbsoluteUrl(baseUrl)
        }

        val root = JSONObject(body).payloadObject()
        return (
            root.artworkUrl()?.toAbsoluteUrl(baseUrl)
                ?: root.optionalString("artworkUrl", "url", "href")?.toAbsoluteUrl(baseUrl)
            )
            ?: throw TMusicApiException(null, "The playlist artwork response does not contain artworkUrl or url.")
    }


    suspend fun lastFmAuthRequest(): LastFmAuthRequest {
        val body = request(
            method = "GET",
            path = "/lastfm/auth-url",
            authenticated = true,
        ).trim()
        if (!body.startsWith("{")) {
            val url = body.trim('"').toAbsoluteUrl(baseUrl)
            return LastFmAuthRequest(
                token = url.queryParameter("token")
                    ?: throw TMusicApiException(null, "The Last.fm auth URL does not contain token."),
                url = url,
            )
        }
        val root = JSONObject(body).payloadObject()
        val url = root.optionalString("authUrl", "url", "href")?.toAbsoluteUrl(baseUrl)
            ?: throw TMusicApiException(null, "The Last.fm auth response does not contain authUrl or url.")
        return LastFmAuthRequest(
            token = root.optionalString("token", "lastFmToken")
                ?: url.queryParameter("token")
                ?: throw TMusicApiException(null, "The Last.fm auth response does not contain token."),
            url = url,
        )
    }

    suspend fun sendPlayEvent(
        clientEventId: String,
        trackId: String,
        playedAt: String,
        durationPlayedMs: Long,
        completed: Boolean,
        source: String,
    ) {
        val body = JSONObject()
            .put("clientEventId", clientEventId)
            .put("trackId", trackId)
            .put("playedAt", playedAt)
            .put("completed", completed)
            .put("source", source)
            .apply {
                if (completed) {
                    put("durationPlayedMs", durationPlayedMs)
                }
            }
            .toString()

        request(
            method = "POST",
            path = "/lastfm/play-events",
            body = body,
            authenticated = true,
        )
    }

    suspend fun syncPlayEvents(events: List<PendingPlayEvent>): Set<String> {
        if (events.isEmpty()) {
            return emptySet()
        }
        val body = JSONObject()
            .put(
                "events",
                JSONArray().apply {
                    events.forEach { event -> put(event.toJson()) }
                },
            )
            .toString()

        val responseBody = request(
            method = "POST",
            path = "/lastfm/play-events/sync",
            body = body,
            authenticated = true,
        )
        return responseBody.syncedClientEventIds(defaultIds = events.map { it.clientEventId }.toSet())
    }

    private suspend fun <T> pagedList(
        path: String,
        limit: Int,
        parse: (String) -> List<T>,
    ): List<T> {
        val values = mutableListOf<T>()
        var offset = 0
        while (true) {
            val body = request(
                method = "GET",
                path = path.withPagination(limit = limit, offset = offset),
                authenticated = true,
            )
            val page = parse(body)
            values += page
            if (page.size < limit) {
                break
            }
            offset += limit
        }
        return values
    }

    private suspend fun <T> pagedListPage(
        path: String,
        limit: Int,
        offset: Int,
        parse: (String) -> List<T>,
    ): List<T> {
        val body = request(
            method = "GET",
            path = path.withPagination(limit = limit, offset = offset.coerceAtLeast(0)),
            authenticated = true,
        )
        return parse(body)
    }

    suspend fun lastFmSession(): LastFmConnection {
        val body = request(
            method = "GET",
            path = "/lastfm/session",
            authenticated = true,
        ).trim()
        if (body.isBlank()) {
            return LastFmConnection(
                username = null,
                state = ScrobbleState.NeedsAuth,
                pendingScrobbles = 0,
            )
        }

        val root = JSONObject(body).payloadObject()
        val session = root.optJSONObject("session")
            ?: root.optJSONObject("lastfm")
            ?: root.optJSONObject("lastFm")
            ?: root
        return session.toLastFmConnection()
    }

    suspend fun completeLastFmSession(token: String): LastFmConnection {
        val requestBody = JSONObject()
            .put("token", token)
            .toString()
        val body = request(
            method = "POST",
            path = "/lastfm/session",
            body = requestBody,
            authenticated = true,
        )
        if (body.isBlank()) {
            return LastFmConnection(
                username = null,
                state = ScrobbleState.Ready,
                pendingScrobbles = 0,
            )
        }

        val root = JSONObject(body).payloadObject()
        val session = root.optJSONObject("session")
            ?: root.optJSONObject("lastfm")
            ?: root.optJSONObject("lastFm")
            ?: root
        return session.toLastFmConnection()
    }

    suspend fun disconnectLastFm(): LastFmConnection {
        val body = request(
            method = "DELETE",
            path = "/lastfm/session",
            body = null,
            authenticated = true,
        ).trim()
        if (body.isBlank()) {
            return LastFmConnection(
                username = null,
                state = ScrobbleState.NeedsAuth,
                pendingScrobbles = 0,
            )
        }

        val root = JSONObject(body).payloadObject()
        val session = root.optJSONObject("session")
            ?: root.optJSONObject("lastfm")
            ?: root.optJSONObject("lastFm")
            ?: root
        return session.toLastFmConnection()
    }

    suspend fun sendNowPlaying(trackId: String) {
        request(
            method = "POST",
            path = "/lastfm/now-playing/${trackId.pathSegment()}",
            body = null,
            authenticated = true,
        )
    }

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        authenticated: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val requestLabel = "$method ${baseUrl + path}"
        Log.d(
            API_LOG_TAG,
            "$requestLabel start auth=$authenticated bodyBytes=${body?.toByteArray(Charsets.UTF_8)?.size ?: 0}",
        )
        val firstToken = if (authenticated) {
            sessionStore.tokens()?.accessToken
                ?: throw TMusicApiException(null, "No access token is available.")
        } else {
            null
        }

        var response = execute(
            method = method,
            path = path,
            body = body,
            accessToken = firstToken,
        )

        if (authenticated && response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            Log.d(API_LOG_TAG, "$requestLabel -> HTTP 401, refreshing token")
            refreshTokens()
            response = execute(
                method = method,
                path = path,
                body = body,
                accessToken = sessionStore.tokens()?.accessToken,
            )
        }

        if (response.statusCode !in 200..299) {
            Log.w(
                API_LOG_TAG,
                "$requestLabel -> HTTP ${response.statusCode}: ${response.body.sanitizedForLog().take(400)}",
            )
            throw TMusicApiException(
                statusCode = response.statusCode,
                message = response.errorMessage(),
            )
        }

        Log.d(API_LOG_TAG, "$requestLabel -> HTTP ${response.statusCode} (${response.body.length} chars)")
        response.body
    }

    private fun refreshTokens() {
        val currentTokens = sessionStore.tokens()
            ?: throw TMusicApiException(null, "No refresh token is available.")
        val body = JSONObject()
            .put("refreshToken", currentTokens.refreshToken)
            .toString()
        val response = execute(
            method = "POST",
            path = "/auth/refresh",
            body = body,
            accessToken = null,
        )

        if (response.statusCode !in 200..299) {
            sessionStore.clear()
            throw TMusicApiException(
                statusCode = response.statusCode,
                message = response.errorMessage(),
            )
        }

        val root = JSONObject(response.body).payloadObject()
        sessionStore.saveTokens(
            accessToken = root.requireString("accessToken"),
            refreshToken = root.optionalString("refreshToken") ?: currentTokens.refreshToken,
        )
    }

    private fun execute(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
    ): ApiResponse {
        val url = baseUrl + path
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }

            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
            }
        }

        return try {
            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..399) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            ApiResponse(
                statusCode = statusCode,
                body = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
            )
        } catch (error: Exception) {
            Log.e(API_LOG_TAG, "$method $url failed: ${error.javaClass.simpleName}: ${error.message}", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val LIBRARY_PAGE_LIMIT = 50
        const val PLAYLIST_PAGE_LIMIT = 50
        const val PLAYLIST_TRACK_PAGE_LIMIT = 50
        const val PLAYLIST_DETAIL_TRACK_PAGE_LIMIT = 100
        const val TRACK_LIST_PAGE_LIMIT = 100
        const val TRACK_CATALOG_PAGE_LIMIT = 500
        const val TRACK_SEARCH_PAGE_LIMIT = 100
        const val LIBRARY_ARTIST_ALBUMS_MAX_REQUESTS = 200

        val ACCENT_COLORS = longArrayOf(
            0xFF111111,
            0xFF2A2A2A,
            0xFF444444,
            0xFF5E5E5E,
            0xFF787878,
            0xFF929292,
        )

        fun accentColorFor(id: String): Long {
            val index = abs(id.hashCode()) % ACCENT_COLORS.size
            return ACCENT_COLORS[index]
        }
    }
}

private data class ApiResponse(
    val statusCode: Int,
    val body: String,
) {
    fun errorMessage(): String {
        if (body.isBlank()) {
            return "Request failed with HTTP $statusCode."
        }

        return runCatching {
            val root = JSONObject(body)
            root.optionalString("message", "error") ?: "Request failed with HTTP $statusCode."
        }.getOrElse {
            body
        }
    }
}

private fun JSONObject.payloadObject(): JSONObject {
    return optJSONObject("data") ?: this
}

private fun String.sanitizedForLog(): String {
    return replace(Regex("(?i)\"(accessToken|refreshToken|idToken|token)\"\\s*:\\s*\"[^\"]*\"")) { match ->
        val key = match.groupValues[1]
        "\"$key\":\"***\""
    }
}

private fun JSONObject.requireString(name: String): String {
    val value = optString(name)
    if (value.isBlank()) {
        throw TMusicApiException(null, "The response does not contain $name.")
    }
    return value
}

private fun JSONObject.optionalString(vararg names: String): String? {
    for (name in names) {
        if (has(name)) {
            val value = optString(name)
            if (value.isMeaningfulString()) {
                return value
            }
        }
    }
    return null
}

private fun JSONObject.optionalMediaUrl(): String? {
    for (name in listOf(
        "streamUrl",
        "playbackUrl",
        "signedUrl",
        "downloadUrl",
        "fileUrl",
        "mediaUrl",
        "audioUrl",
        "url",
        "href",
        "src",
    )) {
        when (val value = opt(name)) {
            is String -> if (value.isNotBlank()) return value
            is JSONObject -> value.optionalMediaUrl()?.let { return it }
        }
    }

    for (name in listOf("stream", "playback", "download", "file", "media", "audio", "track", "data")) {
        when (val value = opt(name)) {
            is String -> if (value.isNotBlank()) return value
            is JSONObject -> value.optionalMediaUrl()?.let { return it }
        }
    }

    return null
}

private fun JSONObject.optionalInt(vararg names: String): Int? {
    for (name in names) {
        if (has(name) && !isNull(name)) {
            return optInt(name)
        }
    }
    return null
}

private fun JSONObject.optionalBoolean(vararg names: String): Boolean? {
    for (name in names) {
        if (has(name) && !isNull(name)) {
            return optBoolean(name)
        }
    }
    return null
}

private fun JSONObject.paginationTotalCount(): Int? {
    return optionalInt("total")
}

private fun JSONObject.artworkUrl(): String? {
    for (name in listOf("artworkUrl", "coverUrl", "imageUrl", "url")) {
        if (!has(name) || isNull(name)) {
            continue
        }
        when (val value = opt(name)) {
            is String -> if (value.isNotBlank()) return value
            is JSONObject -> value.artworkObjectUrl()
                ?.let { return it }
            is JSONArray -> value.firstArtworkUrl()?.let { return it }
        }
    }
    return null
}

private fun JSONObject.hasArtworkMetadata(): Boolean {
    if (has("artwork") && !isNull("artwork")) {
        return true
    }

    return optionalString(
        "artworkId",
        "coverId",
        "imageId",
        "artworkUrl",
        "coverUrl",
        "imageUrl",
    ) != null
}

private fun JSONObject.artworkObjectUrl(): String? {
    artworkValue("1200")?.let { return it }
    optJSONArray("sizes")?.firstArtworkUrl()?.let { return it }
    optJSONArray("variants")?.firstArtworkUrl()?.let { return it }
    optJSONArray("images")?.firstArtworkUrl()?.let { return it }
    optJSONObject("sizes")?.artworkObjectUrl()?.let { return it }
    optJSONObject("variants")?.artworkObjectUrl()?.let { return it }
    return optionalString("url", "href", "src", "artworkUrl", "imageUrl")
}

private fun JSONObject.artworkValue(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return when (val value = opt(name)) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> value.artworkObjectUrl()
        else -> null
    }
}

private fun JSONArray.firstArtworkUrl(): String? {
    var fallback: String? = null
    for (index in 0 until length()) {
        when (val value = opt(index)) {
            is String -> if (value.isNotBlank() && fallback == null) fallback = value
            is JSONObject -> {
                val url = value.artworkObjectUrl()
                if (url != null && fallback == null) {
                    fallback = url
                }
                if (url != null && value.matchesArtworkSize()) {
                    return url
                }
            }
        }
    }
    return fallback
}

private fun JSONObject.matchesArtworkSize(): Boolean {
    return optionalInt("size", "width", "height") == ARTWORK_SIZE_PX ||
        optionalString("size", "width", "height") == ARTWORK_SIZE_PX.toString()
}

private fun JSONObject.stableId(fallbackPrefix: String): String {
    return optionalString("id", "_id", "${fallbackPrefix}Id") ?: fallbackPrefix
}

private fun JSONObject.artistIdList(): List<String> {
    val directIds = listOfNotNull(optionalString("artistId", "primaryArtistId", "albumArtistId"))
    val arrayIds = listOfNotNull(
        optJSONArray("artistIds"),
        optJSONArray("artists"),
        optJSONArray("artistEntities"),
        optJSONArray("trackArtists"),
        optJSONArray("trackArtistEntities"),
        optJSONArray("albumArtists"),
        optJSONArray("albumArtistEntities"),
    ).flatMap { array ->
        buildList {
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is JSONObject -> (
                        value.optionalString("artistId", "primaryArtistId", "albumArtistId", "trackArtistId")
                            ?: value.optJSONObject("artist")?.optionalString("id", "_id", "artistId")
                            ?: value.optJSONObject("artistEntity")?.optionalString("id", "_id", "artistId")
                            ?: value.optJSONObject("albumArtist")?.optionalString("id", "_id", "artistId")
                            ?: value.optJSONObject("trackArtist")?.optionalString("id", "_id", "artistId")
                            ?: value.optionalString("id", "_id")
                        )?.let(::add)
                    else -> value.toString().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
    val objectIds = listOfNotNull(
        optJSONObject("artist")?.optionalString("id", "_id", "artistId"),
        optJSONObject("artistEntity")?.optionalString("id", "_id", "artistId"),
        optJSONObject("albumArtist")?.optionalString("id", "_id", "artistId"),
        optJSONObject("albumArtistEntity")?.optionalString("id", "_id", "artistId"),
        optJSONObject("primaryArtist")?.optionalString("id", "_id", "artistId"),
        optJSONObject("primaryArtistEntity")?.optionalString("id", "_id", "artistId"),
    )
    return (directIds + arrayIds + objectIds).distinct()
}

private fun JSONObject.artistNameList(): List<String> {
    val directNames = listOfNotNull(optionalString("artist", "artistName", "albumArtist"))
    val arrayNames = listOfNotNull(
        optJSONArray("artists"),
        optJSONArray("artistEntities"),
        optJSONArray("trackArtists"),
        optJSONArray("trackArtistEntities"),
        optJSONArray("albumArtists"),
        optJSONArray("albumArtistEntities"),
    ).flatMap { array ->
        buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index)
                when (value) {
                    is JSONObject -> (
                        value.optJSONObject("artist")?.optionalString("name", "title", "artist", "artistName")
                            ?: value.optJSONObject("artistEntity")?.optionalString("name", "title", "artist", "artistName")
                            ?: value.optJSONObject("albumArtist")?.optionalString("name", "title", "artist", "artistName")
                            ?: value.optJSONObject("trackArtist")?.optionalString("name", "title", "artist", "artistName")
                            ?: value.optionalString("name", "title", "artist", "artistName")
                        )?.let(::add)
                    else -> value.toString().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
    val objectNames = listOfNotNull(
        optJSONObject("artist")?.optionalString("name", "title", "artist", "artistName"),
        optJSONObject("artistEntity")?.optionalString("name", "title", "artist", "artistName"),
        optJSONObject("albumArtist")?.optionalString("name", "title", "artist", "artistName"),
        optJSONObject("albumArtistEntity")?.optionalString("name", "title", "artist", "artistName"),
        optJSONObject("primaryArtist")?.optionalString("name", "title", "artist", "artistName"),
        optJSONObject("primaryArtistEntity")?.optionalString("name", "title", "artist", "artistName"),
    )
    return (directNames + arrayNames + objectNames)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
}

private fun JSONObject.albumArtistId(): String? {
    val albumEntity = optJSONObject("albumEntity")
    return albumEntity?.optionalString("artistId", "albumArtistId")
        ?: albumEntity?.optJSONObject("artist")?.optionalString("id", "_id", "artistId")
        ?: albumEntity?.optJSONObject("artistEntity")?.optionalString("id", "_id", "artistId")
        ?: albumEntity?.optJSONObject("albumArtist")?.optionalString("id", "_id", "artistId")
        ?: albumEntity?.optJSONObject("albumArtistEntity")?.optionalString("id", "_id", "artistId")
        ?: albumEntity?.optJSONObject("primaryArtist")?.optionalString("id", "_id", "artistId")
        ?: albumEntity?.optJSONObject("primaryArtistEntity")?.optionalString("id", "_id", "artistId")
        ?: albumEntity?.artistIdList()?.firstOrNull()
        ?: optionalString("albumArtistId")
}

private fun JSONObject.toAccount(): Account {
    val email = optionalString("email") ?: ""
    val displayName = optionalString("displayName", "name", "username")
        ?: email.substringBefore('@', missingDelimiterValue = "User")

    return Account(
        id = optionalString("id", "_id", "userId") ?: email.ifBlank { "user" },
        displayName = displayName,
        email = email,
        avatarUrl = optionalString("avatarUrl", "avatar", "picture", "photoUrl", "imageUrl"),
        lastFmConnection = accountLastFmConnection(),
    )
}

private fun JSONObject.accountLastFmConnection(): LastFmConnection? {
    val lastFm = optJSONObject("lastfm") ?: optJSONObject("lastFm") ?: return null
    val username = lastFm.optionalString("username", "name", "lastFmUsername")
    val connected = lastFm.optBoolean("connected", false)
    if (!connected && username.isNullOrBlank()) {
        return null
    }
    return lastFm.toLastFmConnection()
}

private fun JSONObject.toPlaylist(): Playlist {
    val id = stableId("playlist")
    val trackIds = parseTrackIds()
    val playlistTrackIds = parsePlaylistTrackIds()
    val count = optJSONObject("_count")
    val trackCount = count?.optionalInt("tracks")
        ?: trackIds.size
    return Playlist(
        id = id,
        title = optionalString("title", "name") ?: "Untitled playlist",
        description = optionalString("description") ?: "",
        trackIds = trackIds,
        isOfflineEnabled = optBoolean("isOfflineEnabled", false),
        isPublic = optBoolean("isPublic", false),
        playlistTrackIds = playlistTrackIds,
        playlistTrackIdsByTrackId = parsePlaylistTrackIdsByTrackId(),
        isFavorites = optBoolean("isFavorites", false) ||
            optionalString("type", "kind", "systemKey")?.equals("favorites", ignoreCase = true) == true,
        trackCount = trackCount.coerceAtLeast(trackIds.size),
    )
}

private fun JSONObject.playlistUpdateOrNull(): Playlist? {
    optJSONObject("playlist")?.let { return it.toPlaylist() }
    return if (isPlaylistObject()) toPlaylist() else null
}

private fun JSONObject.parseTrackIds(): List<String> {
    val array = optJSONArray("trackIds") ?: optJSONArray("tracks") ?: return emptyList()
    val ids = mutableListOf<String>()

    for (index in 0 until array.length()) {
        when (val item = array.get(index)) {
            is JSONObject -> {
                val nestedTrack = item.optJSONObject("track")
                val trackId = nestedTrack?.optionalString("id", "_id", "trackId")
                    ?: item.optionalString("trackId")
                    ?: item.optionalString("id", "_id")
                trackId?.let(ids::add)
            }
            else -> item.toString().takeIf { it.isNotBlank() }?.let(ids::add)
        }
    }

    return ids
}

private fun JSONObject.parsePlaylistTrackIds(): List<String> {
    optJSONArray("playlistTrackIds")?.let { array ->
        return array.stringItems()
    }

    val array = optJSONArray("tracks") ?: return emptyList()
    val ids = mutableListOf<String>()

    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val nestedTrack = item.optJSONObject("track")
        val playlistTrackId = item.optionalString("id", "_id", "playlistTrackId")
            ?: nestedTrack?.optionalString("playlistTrackId")
        playlistTrackId?.let(ids::add)
    }

    return ids
}

private fun JSONArray.stringItems(): List<String> {
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        optString(index).takeIf { it.isNotBlank() }?.let(values::add)
    }
    return values
}

private fun JSONObject.parsePlaylistTrackIdsByTrackId(): Map<String, String> {
    val array = optJSONArray("tracks") ?: return emptyMap()
    val idsByTrackId = linkedMapOf<String, String>()

    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val nestedTrack = item.optJSONObject("track")
        val trackId = nestedTrack?.optionalString("id", "_id", "trackId")
            ?: item.optionalString("trackId")
            ?: continue
        val playlistTrackId = item.optionalString("id", "_id", "playlistTrackId")
            ?: continue

        idsByTrackId.putIfAbsent(trackId, playlistTrackId)
    }

    return idsByTrackId
}

private fun JSONObject.embeddedTracks(): List<Track> {
    val array = optJSONArray("tracks") ?: return emptyList()
    val tracks = mutableListOf<Track>()

    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val trackJson = item.optJSONObject("track")
            ?: item.takeIf {
                it.has("title") ||
                    it.has("name") ||
                    it.has("artist") ||
                    it.has("artistName") ||
                    it.has("album") ||
                    it.has("albumEntity") ||
                    it.has("artworkId")
            }
            ?: continue
        val id = trackJson.stableId("track")
        tracks += trackJson.toTrack(accentColor = stableAccentColorFor(id))
    }

    return tracks
}

private fun JSONObject.toTrack(accentColor: Long): Track {
    val id = stableId("track")
    val albumEntity = optJSONObject("albumEntity")
    val artistIds = artistIdList()
    val artistNames = artistNameList()
    val albumArtistNames = albumEntity?.artistNameList().orEmpty()
    val albumArtistIds = albumEntity?.artistIdList().orEmpty()
    return Track(
        id = id,
        title = optionalString("title", "name") ?: "Untitled track",
        artist = optionalString("artist", "artistName")
            ?: artistNames.joinToString("; ").takeIf { it.isNotBlank() }
            ?: albumEntity?.optionalString("artist", "artistName")
            ?: "Unknown artist",
        album = albumEntity?.optionalString("title", "name")
            ?: optionalString("album", "albumTitle")
            ?: "Unknown album",
        durationSeconds = durationSeconds(),
        serverPath = optionalString("serverPath", "filePath", "streamPath", "path") ?: "/tracks/$id",
        accentColor = accentColor,
        downloadState = DownloadState.NotDownloaded,
        playCount = optInt("playCount", 0),
        artistId = artistIds.firstOrNull(),
        artistIds = artistIds,
        albumId = optionalString("albumId") ?: albumEntity?.optionalString("id", "_id", "albumId"),
        albumArtist = albumEntity?.optionalString("artist", "artistName")
            ?: albumArtistNames.joinToString("; ").takeIf { it.isNotBlank() }
            ?: optionalString("albumArtist"),
        albumArtistId = albumArtistId() ?: albumArtistIds.firstOrNull(),
        trackNumber = optionalInt("trackNumber", "position", "discTrackNumber"),
        discNumber = optionalInt("discNumber"),
        releaseYear = optionalInt("releaseYear", "year")
            ?: albumEntity?.optionalInt("releaseYear", "year"),
        genre = optionalString("genre"),
        isLiked = optionalBoolean("isLiked", "liked", "isFavorite", "favorite"),
    )
}

private fun JSONObject.toTrackLyrics(): TrackLyrics? {
    val plainLyrics = optionalString("plainLyrics", "plain", "lyrics")
    val syncedLyrics = optionalString("syncedLyrics", "synced", "lrc")
    val instrumental = optBoolean("instrumental", false)
    if (!instrumental && plainLyrics == null && syncedLyrics == null) {
        return null
    }
    return TrackLyrics(
        plainLyrics = plainLyrics,
        syncedLyrics = syncedLyrics,
        instrumental = instrumental,
    )
}

private fun String.toTrackLyricsPayload(): TrackLyrics? {
    val root = JSONObject(trim()).payloadObject()
    val lyrics = root.optJSONObject("lyrics") ?: root
    return lyrics.toTrackLyrics()
}

private fun JSONObject.toLibraryArtist(): LibraryArtist? {
    val id = optionalString("id", "_id", "artistId") ?: return null
    val name = optionalString("name", "artist", "artistName", "title") ?: id
    val count = optJSONObject("_count")
    return LibraryArtist(
        name = name,
        id = id,
        albumCount = optionalInt("albumCount", "albumsCount")
            ?: count?.optionalInt("albums")
            ?: 0,
        trackCount = optionalInt("trackCount", "tracksCount")
            ?: count?.optionalInt("tracks")
            ?: 0,
        representativeAlbumId = optionalString(
            "latestReleaseAlbumId",
            "lastReleaseAlbumId",
            "latestOwnReleaseAlbumId",
            "lastOwnReleaseAlbumId",
            "ownLatestReleaseAlbumId",
            "latestAlbumId",
            "lastAlbumId",
            "representativeAlbumId",
            "albumId",
        )
            ?: optJSONObject("latestRelease")?.optionalString("id", "_id", "albumId")
            ?: optJSONObject("lastRelease")?.optionalString("id", "_id", "albumId")
            ?: optJSONObject("latestOwnRelease")?.optionalString("id", "_id", "albumId")
            ?: optJSONObject("latestAlbum")?.optionalString("id", "_id", "albumId")
            ?: optJSONObject("representativeAlbum")?.optionalString("id", "_id", "albumId"),
        similarity = optJSONObject("similarity")?.toArtistSimilarity(),
    )
}

private fun JSONObject.toArtistSimilarity(): ArtistSimilarity? {
    val source = optionalString("source") ?: return null
    return ArtistSimilarity(
        source = source,
        score = optionalInt("score"),
        sharedGenres = optJSONArray("sharedGenres")?.stringItems().orEmpty(),
    )
}

private fun JSONObject.toLibraryAlbum(): LibraryAlbum? {
    val id = stableId("album")
    val title = optionalString("title", "name", "albumTitle") ?: return null
    val artistNames = artistNameList()
    val artistIds = artistIdList()
    val artist = optionalString("artist", "artistName", "albumArtist")
        ?: artistNames.joinToString("; ").takeIf { it.isNotBlank() }
        ?: optJSONObject("artistEntity")?.optionalString("name", "title")
        ?: "Unknown artist"
    val artistId = optionalString("artistId", "albumArtistId")
        ?: optJSONObject("artist")?.optionalString("id", "_id", "artistId")
        ?: optJSONObject("artistEntity")?.optionalString("id", "_id", "artistId")
        ?: optJSONObject("albumArtist")?.optionalString("id", "_id", "artistId")
        ?: optJSONObject("albumArtistEntity")?.optionalString("id", "_id", "artistId")
        ?: optJSONObject("primaryArtist")?.optionalString("id", "_id", "artistId")
        ?: optJSONObject("primaryArtistEntity")?.optionalString("id", "_id", "artistId")
        ?: artistIds.firstOrNull()
    val count = optJSONObject("_count")
    return LibraryAlbum(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        artistIds = artistIds,
        releaseYear = optionalInt("releaseYear", "year"),
        genre = optionalString("genre"),
        trackCount = optionalInt("trackCount", "tracksCount")
            ?: count?.optionalInt("tracks")
            ?: optJSONArray("tracks")?.length()
            ?: 0,
        accentColor = stableAccentColorFor(id),
        artworkTrackId = optionalString("artworkTrackId", "coverTrackId", "firstTrackId", "trackId")
            ?: optJSONObject("track")?.optionalString("id", "_id", "trackId")
            ?: optJSONArray("tracks")?.optJSONObject(0)?.let { item ->
                item.optJSONObject("track")?.optionalString("id", "_id", "trackId")
                    ?: item.optionalString("trackId", "id", "_id")
            },
        savedByCurrentUser = optBoolean("savedByCurrentUser", false),
        isOfflineEnabled = optBoolean("isOfflineEnabled", false),
        hasArtwork = hasArtworkMetadata(),
    )
}

private fun JSONObject.toLastFmConnection(): LastFmConnection {
    val username = optionalString("username", "name", "lastFmUsername")
    return LastFmConnection(
        username = username,
        state = when {
            optBoolean("connected", false) && username != null -> ScrobbleState.Ready
            username != null -> ScrobbleState.Ready
            else -> ScrobbleState.NeedsAuth
        },
        pendingScrobbles = optInt("pendingScrobbles", 0),
    )
}

private fun JSONObject.durationSeconds(): Int {
    return when {
        has("durationSeconds") -> optInt("durationSeconds")
        has("duration") -> optInt("duration")
        has("durationMs") -> optInt("durationMs") / 1000
        else -> 0
    }
}

private fun String.toJsonArray(key: String): JSONArray {
    val trimmed = trim()
    if (trimmed.startsWith("[")) {
        return JSONArray(trimmed)
    }

    val root = JSONObject(trimmed)
    val payload = root.opt("data")
    return when (payload) {
        is JSONArray -> payload
        is JSONObject -> payload.optJSONArray(key) ?: JSONArray()
        else -> root.optJSONArray(key) ?: JSONArray()
    }
}

private fun <T> JSONArray.mapJsonObjects(transform: (JSONObject) -> T): List<T> {
    val values = mutableListOf<T>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        values += transform(item)
    }
    return values
}

private fun String.toPlaylistPayload(): PlaylistPayload {
    val playlistJsonObjects = playlistJsonObjects()
    val playlists = playlistJsonObjects.map { it.toPlaylist() }
    val tracks = playlistJsonObjects
        .flatMap { it.embeddedTracks() }
        .distinctBy { it.id }

    return PlaylistPayload(
        playlists = playlists,
        tracks = tracks,
    )
}

private fun String.toFavoritesPlaylistPayload(
    fallbackPlaylistId: String?,
    fallbackIsOfflineEnabled: Boolean,
): PlaylistPayload {
    val root = JSONObject(trim()).payloadObject()
    val playlistObject = root.optJSONObject("playlist")
        ?: root.takeIf { it.isPlaylistObject() }
    val tracks = root.embeddedTracks().distinctBy { it.id }
    val trackIds = root.parseTrackIds().ifEmpty { tracks.map { it.id } }
    val playlistTrackIds = root.parsePlaylistTrackIds()
    val trackCount = root.optJSONObject("_count")?.optionalInt("tracks")
        ?: root.optionalInt("total", "trackCount", "tracksCount")
        ?: playlistObject?.optJSONObject("_count")?.optionalInt("tracks")
        ?: playlistObject?.optionalInt("total", "trackCount", "tracksCount")
        ?: trackIds.size
    val playlistTrackIdsByTrackId = root.parsePlaylistTrackIdsByTrackId()
    val basePlaylist = playlistObject?.toPlaylist()
    val playlist = Playlist(
        id = fallbackPlaylistId
            ?: basePlaylist?.id
            ?: root.optionalString("id", "_id", "playlistId")
            ?: "favorites",
        title = basePlaylist?.title?.takeIf { it.isNotBlank() } ?: "Favorites",
        description = basePlaylist?.description.orEmpty(),
        trackIds = trackIds,
        isOfflineEnabled = fallbackIsOfflineEnabled || basePlaylist?.isOfflineEnabled == true,
        isPublic = basePlaylist?.isPublic == true,
        playlistTrackIds = playlistTrackIds,
        playlistTrackIdsByTrackId = playlistTrackIdsByTrackId,
        isFavorites = true,
        trackCount = trackCount.coerceAtLeast(trackIds.size),
    )

    return PlaylistPayload(
        playlists = listOf(playlist),
        tracks = tracks,
    )
}

private fun String.playlistJsonObjects(): List<JSONObject> {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }
    if (trimmed.startsWith("[")) {
        return JSONArray(trimmed).jsonObjects().filter { it.isPlaylistObject() }
    }

    val root = JSONObject(trimmed)
    return when (val payload = root.opt("data")) {
        is JSONArray -> payload.jsonObjects().filter { it.isPlaylistObject() }
        is JSONObject -> payload.optJSONArray("playlists")?.jsonObjects()?.filter { it.isPlaylistObject() }
            ?: payload.optJSONObject("playlist")?.takeIf { it.isPlaylistObject() }?.let(::listOf)
            ?: payload.takeIf { it.isPlaylistObject() }?.let(::listOf)
            ?: emptyList()
        else -> root.optJSONArray("playlists")?.jsonObjects()?.filter { it.isPlaylistObject() }
            ?: root.optJSONObject("playlist")?.takeIf { it.isPlaylistObject() }?.let(::listOf)
            ?: root.takeIf { it.isPlaylistObject() }?.let(::listOf)
            ?: emptyList()
    }
}

private fun JSONObject.isPlaylistObject(): Boolean {
    val hasIdentity = optionalString("id", "_id", "playlistId") != null ||
        optionalString("title", "name") != null
    val hasPlaylistShape = optionalString("title", "name") != null ||
        has("description") ||
        has("isFavorites") ||
        has("isPublic") ||
        has("trackIds") ||
        optJSONObject("_count")?.has("tracks") == true ||
        optionalString("type", "kind", "systemKey")?.equals("favorites", ignoreCase = true) == true
    return hasIdentity && hasPlaylistShape
}

private fun List<PlaylistPayload>.mergePlaylistPayloads(): PlaylistPayload {
    return PlaylistPayload(
        playlists = flatMap { it.playlists }.distinctBy { it.id },
        tracks = flatMap { it.tracks }.distinctBy { it.id },
    )
}

private fun List<PlaylistPayload>.mergeSinglePlaylistPayload(): PlaylistPayload {
    val firstPlaylist = asSequence()
        .flatMap { it.playlists.asSequence() }
        .firstOrNull()
        ?: return PlaylistPayload(playlists = emptyList(), tracks = emptyList())
    val trackIds = flatMap { payload -> payload.playlists.firstOrNull()?.trackIds.orEmpty() }
    val playlistTrackIds = flatMap { payload -> payload.playlists.firstOrNull()?.playlistTrackIds.orEmpty() }
    val playlistTrackIdsByTrackId = fold(linkedMapOf<String, String>()) { idsByTrackId, payload ->
        payload.playlists.firstOrNull()?.playlistTrackIdsByTrackId.orEmpty().forEach { (trackId, playlistTrackId) ->
            idsByTrackId.putIfAbsent(trackId, playlistTrackId)
        }
        idsByTrackId
    }
    val playlist = firstPlaylist.copy(
        trackIds = trackIds,
        playlistTrackIds = playlistTrackIds,
        playlistTrackIdsByTrackId = playlistTrackIdsByTrackId,
        trackCount = firstPlaylist.trackCount.coerceAtLeast(trackIds.size),
    )
    return PlaylistPayload(
        playlists = listOf(playlist),
        tracks = flatMap { it.tracks }.distinctBy { it.id },
    )
}

private fun String.toTracks(): List<Track> {
    return toJsonArray("tracks").mapJsonObjects { json ->
        val trackJson = json.optJSONObject("track") ?: json
        val id = trackJson.stableId("track")
        trackJson.toTrack(accentColor = stableAccentColorFor(id))
    }
}

private fun String.toLibraryArtists(): List<LibraryArtist> {
    val values = jsonValues(arrayKey = "artists", objectKey = "artist")
    return values.mapNotNull { value ->
        when (value) {
            is JSONObject -> value.toLibraryArtist()
            else -> null
        }
    }.distinctBy { it.id }
}

private fun String.toLibraryArtistsPage(): LibraryArtistsPage {
    val artists = toLibraryArtists()
    val root = trim()
        .takeIf { it.startsWith("{") }
        ?.let { runCatching { JSONObject(it).payloadObject() }.getOrNull() }
    val totalCount = root?.paginationTotalCount()
    return LibraryArtistsPage(
        artists = artists,
        totalCount = totalCount,
    )
}

private fun String.toSimilarArtists(): List<LibraryArtist> {
    val values = jsonValues(arrayKey = "artists", objectKey = "artist")
    return values.mapNotNull { value ->
        when (value) {
            is JSONObject -> value.toSimilarArtist()
            else -> value.toString().takeIf { it.isNotBlank() }?.let { LibraryArtist(name = it, id = it) }
        }
    }.distinctBy { it.id }
}

private fun JSONObject.toSimilarArtist(): LibraryArtist? {
    val nestedArtist = optJSONObject("artist")
    val parsed = nestedArtist?.toLibraryArtist() ?: toLibraryArtist()
    val similarity = optJSONObject("similarity")?.toArtistSimilarity()
    if (parsed != null) {
        return parsed.copy(similarity = similarity ?: parsed.similarity)
    }
    val id = optionalString("id", "_id", "artistId") ?: return null
    return LibraryArtist(
        name = optionalString("name", "artist", "artistName", "title") ?: id,
        id = id,
        similarity = similarity,
    )
}

private fun String.toLibraryAlbums(): List<LibraryAlbum> {
    val values = jsonValues(arrayKey = "albums", objectKey = "album")
    return values.mapNotNull { it.toLibraryAlbumValue() }.distinctBy { it.id }
}

private fun String.toLibraryArtistAlbums(): LibraryArtistAlbums {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return LibraryArtistAlbums(albums = emptyList())
    }
    if (trimmed.startsWith("[")) {
        return LibraryArtistAlbums(albums = toLibraryAlbums())
    }

    val root = JSONObject(trimmed).payloadObject()
    val albums = root.optJSONArray("albums")
        ?.jsonValues()
        ?.mapNotNull { it.toLibraryAlbumValue() }
        ?.distinctBy { it.id }
        ?: emptyList()
    val appearsOn = root.optJSONArray("appearsOn")
        ?.jsonValues()
        ?.mapNotNull { it.toLibraryAlbumValue() }
        ?.distinctBy { it.id }
        ?: emptyList()
    val tracks = root.optJSONArray("tracks")
        ?.jsonValues()
        ?.mapNotNull { it.toTrackValue() }
        ?.distinctBy { it.id }
        ?: emptyList()

    if (albums.isEmpty() && appearsOn.isEmpty() && tracks.isEmpty()) {
        root.toLibraryAlbumValue()?.let { album ->
            return LibraryArtistAlbums(albums = listOf(album))
        }
    }

    return LibraryArtistAlbums(
        albums = albums,
        appearsOn = appearsOn,
        tracks = tracks,
    )
}

private fun Any.toLibraryAlbumValue(): LibraryAlbum? {
    val value = this as? JSONObject ?: return null
    val albumJson = value.optJSONObject("album") ?: value
    val parsedAlbum = albumJson.toLibraryAlbum() ?: return null

    return if (value.has("savedByCurrentUser")) {
        parsedAlbum.copy(savedByCurrentUser = value.optBoolean("savedByCurrentUser", parsedAlbum.savedByCurrentUser))
    } else {
        parsedAlbum
    }
}

private fun Any.toTrackValue(): Track? {
    val value = this as? JSONObject ?: return null
    val trackJson = value.optJSONObject("track") ?: value
    val id = trackJson.stableId("track")
    return trackJson.toTrack(accentColor = stableAccentColorFor(id))
}

private fun String.toLibraryAlbumPayload(): LibraryAlbum? {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return null
    }
    val root = if (trimmed.startsWith("{")) JSONObject(trimmed).payloadObject() else return null
    val album = root.optJSONObject("album")
        ?: root.optJSONObject("libraryAlbum")
        ?: root.optJSONObject("data")
        ?: root
    return album.toLibraryAlbum()?.let { parsedAlbum ->
        if (root.has("savedByCurrentUser")) {
            parsedAlbum.copy(savedByCurrentUser = root.optBoolean("savedByCurrentUser", parsedAlbum.savedByCurrentUser))
        } else {
            parsedAlbum
        }
    }
}

private fun String.toLibrarySearchResults(): LibrarySearchResults {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return LibrarySearchResults(emptyList(), emptyList(), emptyList())
    }
    val root = if (trimmed.startsWith("{")) JSONObject(trimmed).payloadObject() else JSONObject()
    val artists = root.optJSONArray("artists")
        ?.jsonValues()
        ?.mapNotNull { value ->
            when (value) {
                is JSONObject -> value.toLibraryArtist()
                else -> null
            }
        }
        .orEmpty()
    val albums = root.optJSONArray("albums")
        ?.jsonValues()
        ?.mapNotNull { value -> value.toLibraryAlbumValue() }
        .orEmpty()
    val tracks = root.optJSONArray("tracks")
        ?.jsonObjects()
        ?.map { json ->
            json.toTrackValue()
        }
        ?.filterNotNull()
        .orEmpty()
    val playlists = root.optJSONArray("playlists")
        ?.jsonObjects()
        ?.map { json -> json.toPlaylist() }
        .orEmpty()

    return LibrarySearchResults(
        artists = artists.distinctBy { it.name.lowercase() },
        albums = albums.distinctBy { it.id },
        tracks = tracks.distinctBy { it.id },
        playlists = playlists.distinctBy { it.id },
    )
}

private fun String.toJsonObjects(
    arrayKey: String,
    objectKey: String,
): List<JSONObject> {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }
    if (trimmed.startsWith("[")) {
        return JSONArray(trimmed).jsonObjects()
    }

    val root = JSONObject(trimmed)
    return when (val payload = root.opt("data")) {
        is JSONArray -> payload.jsonObjects()
        is JSONObject -> payload.optJSONArray(arrayKey)?.jsonObjects()
            ?: payload.optJSONObject(objectKey)?.let { listOf(it) }
            ?: listOf(payload)
        else -> root.optJSONArray(arrayKey)?.jsonObjects()
            ?: root.optJSONObject(objectKey)?.let { listOf(it) }
            ?: listOf(root)
    }
}

private fun String.jsonValues(
    arrayKey: String,
    objectKey: String,
): List<Any> {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }
    if (trimmed.startsWith("[")) {
        return JSONArray(trimmed).jsonValues()
    }

    val root = JSONObject(trimmed)
    return when (val payload = root.opt("data")) {
        is JSONArray -> payload.jsonValues()
        is JSONObject -> payload.optJSONArray(arrayKey)?.jsonValues()
            ?: payload.optJSONObject(objectKey)?.let { listOf(it) }
            ?: listOf(payload)
        else -> root.optJSONArray(arrayKey)?.jsonValues()
            ?: root.optJSONObject(objectKey)?.let { listOf(it) }
            ?: listOf(root)
    }
}

private fun JSONArray.jsonObjects(): List<JSONObject> {
    val values = mutableListOf<JSONObject>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let(values::add)
    }
    return values
}

private fun JSONArray.jsonValues(): List<Any> {
    val values = mutableListOf<Any>()
    for (index in 0 until length()) {
        values += get(index)
    }
    return values
}

private fun String.withPagination(limit: Int, offset: Int): String {
    val separator = if (contains("?")) "&" else "?"
    return "$this${separator}limit=${limit.coerceAtLeast(1)}&offset=${offset.coerceAtLeast(0)}"
}

private fun PendingPlayEvent.toJson(): JSONObject {
    return JSONObject()
        .put("clientEventId", clientEventId)
        .put("trackId", trackId)
        .put("playedAt", playedAt)
        .put("durationPlayedMs", durationPlayedMs)
        .put("completed", completed)
        .put("source", source)
}

private fun String.syncedClientEventIds(defaultIds: Set<String>): Set<String> {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return defaultIds
    }
    return runCatching {
        val root = JSONObject(trimmed).payloadObject()
        val results = root.optJSONArray("results") ?: return@runCatching defaultIds
        buildSet {
            for (index in 0 until results.length()) {
                val result = results.optJSONObject(index) ?: continue
                if (result.optBoolean("ok", false)) {
                    result.optionalString("clientEventId")?.let(::add)
                }
            }
        }
    }.getOrDefault(emptySet())
}

private fun String.pathSegment(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
}

private fun String.queryValue(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}

private fun String.isMeaningfulString(): Boolean {
    val normalized = trim()
    return normalized.isNotBlank() && !normalized.equals("null", ignoreCase = true)
}

private fun String.queryParameter(name: String): String? {
    val query = substringAfter('?', missingDelimiterValue = "")
        .substringBefore('#')
    if (query.isBlank()) {
        return null
    }

    return query
        .split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
            val value = part.substringAfter('=', missingDelimiterValue = "")
            if (key == name && value.isNotBlank()) {
                URLDecoder.decode(value, Charsets.UTF_8.name())
            } else {
                null
            }
        }
        .firstOrNull()
}

private fun stableAccentColorFor(id: String): Long {
    val colors = longArrayOf(
        0xFF111111,
        0xFF2A2A2A,
        0xFF444444,
        0xFF5E5E5E,
        0xFF787878,
        0xFF929292,
    )
    val index = abs(id.hashCode()) % colors.size
    return colors[index]
}

private fun String.toAbsoluteUrl(baseUrl: String): String {
    val value = trim()
    if (
        value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("file://", ignoreCase = true)
    ) {
        return value
    }

    val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    if (value.startsWith("/")) {
        val base = URL(normalizedBase)
        if (base.path.trimEnd('/').endsWith("/api") && value.isApiRelativePath()) {
            return URL(base, "/api$value").toString()
        }
    }
    return URL(URL(normalizedBase), value).toString()
}

private fun String.isApiRelativePath(): Boolean {
    return listOf(
        "/auth/",
        "/tracks/",
        "/library/",
        "/playlists/",
        "/lastfm/",
    ).any { startsWith(it) }
}
