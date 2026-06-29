package dev.teacode.tmusic.data

import android.util.Log
import dev.teacode.tmusic.BuildConfig
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.ArtistSimilarity
import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LastFmAuthRequest
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibraryArtistAlbums
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.RecentAlbumChange
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
    val code: String? = null,
    val updateInfo: AppUpdateInfo? = null,
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
private const val HTTP_UPGRADE_REQUIRED = 426

class TMusicApiClient(
    initialBaseUrl: String,
    private val sessionStore: SessionStore,
) {
    @Volatile
    private var baseUrl: String = initialBaseUrl.trimEnd('/')

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

    suspend fun appUpdateConfig(): AppUpdateInfo? {
        val body = request(
            method = "GET",
            path = "/app/config",
            authenticated = true,
        ).trim()
        if (body.isBlank()) {
            return null
        }

        return JSONObject(body).payloadObject().appUpdateInfoOrNull()
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val tokens = sessionStore.tokens() ?: return@withContext
        val body = JSONObject()
            .put("refreshToken", tokens.refreshToken)
            .toString()
        val response = execute(
            method = "POST",
            path = "/auth/logout",
            body = body,
            accessToken = tokens.accessToken,
        )
        if (response.statusCode !in 200..299 && response.statusCode != HttpURLConnection.HTTP_UNAUTHORIZED) {
            throw TMusicApiException(
                statusCode = response.statusCode,
                message = response.errorMessage(),
                code = response.errorCode(),
                updateInfo = response.appUpdateInfoOrNull(),
            )
        }
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
                path = "/playlists/${playlistId.pathSegment()}?trackLimit=$PLAYLIST_FULL_TRACK_PAGE_LIMIT" +
                    "&trackOffset=$trackOffset",
                authenticated = true,
            )
            val page = body.toPlaylistPayload()
            pages += page
            val loadedTrackCount = pages.flatMap { payload -> payload.playlists.firstOrNull()?.trackIds.orEmpty() }.size
            val totalTrackCount = pages.asSequence()
                .mapNotNull { payload -> payload.playlists.firstOrNull()?.trackCount?.takeIf { it > 0 } }
                .firstOrNull()
            val playlistTrackCount = page.playlists.firstOrNull()?.trackIds.orEmpty().size
            val pageSize = maxOf(page.tracks.size, playlistTrackCount)
            val canTrustTotalCount = totalTrackCount != null &&
                (totalTrackCount > PLAYLIST_FULL_TRACK_PAGE_LIMIT || pageSize < PLAYLIST_FULL_TRACK_PAGE_LIMIT)
            if (totalTrackCount != null && canTrustTotalCount && loadedTrackCount >= totalTrackCount) {
                break
            }
            if (pageSize == 0 || (pageSize < PLAYLIST_FULL_TRACK_PAGE_LIMIT && totalTrackCount == null)) {
                break
            }
            trackOffset += PLAYLIST_FULL_TRACK_PAGE_LIMIT
        }

        return pages.mergeSinglePlaylistPayload()
    }

    suspend fun playlistPayloadTrackPage(
        playlistId: String,
        trackLimit: Int = PLAYLIST_DETAIL_TRACK_PAGE_LIMIT,
        trackOffset: Int = 0,
    ): PlaylistPayload {
        val safeTrackLimit = trackLimit.coerceIn(1, PLAYLIST_FULL_TRACK_PAGE_LIMIT)
        val body = request(
            method = "GET",
            path = "/playlists/${playlistId.pathSegment()}?trackLimit=$safeTrackLimit" +
                "&trackOffset=${trackOffset.coerceAtLeast(0)}",
            authenticated = true,
        )
        return body.toPlaylistPayload()
    }

    suspend fun favoritesPlaylistPayload(): PlaylistPayload {
        val pages = mutableListOf<PlaylistPayload>()
        var offset = 0
        while (true) {
            val page = favoritesPlaylistPayloadPage(
                trackLimit = FAVORITES_FULL_TRACK_PAGE_LIMIT,
                trackOffset = offset,
            )
            pages += page
            val loadedTrackCount = pages.flatMap { payload -> payload.playlists.firstOrNull()?.trackIds.orEmpty() }.size
            val totalTrackCount = pages.asSequence()
                .mapNotNull { payload -> payload.playlists.firstOrNull()?.trackCount?.takeIf { it > 0 } }
                .firstOrNull()
            val pageSize = maxOf(page.tracks.size, page.playlists.firstOrNull()?.trackIds.orEmpty().size)
            val canTrustTotalCount = totalTrackCount != null &&
                (totalTrackCount > FAVORITES_FULL_TRACK_PAGE_LIMIT || pageSize < FAVORITES_FULL_TRACK_PAGE_LIMIT)
            if (totalTrackCount != null && canTrustTotalCount && loadedTrackCount >= totalTrackCount) {
                break
            }
            if (pageSize == 0 || (pageSize < FAVORITES_FULL_TRACK_PAGE_LIMIT && totalTrackCount == null)) {
                break
            }
            offset += FAVORITES_FULL_TRACK_PAGE_LIMIT
        }

        return pages.mergeSinglePlaylistPayload()
    }

    suspend fun favoritesPlaylistPayloadPage(
        trackLimit: Int = PLAYLIST_DETAIL_TRACK_PAGE_LIMIT,
        trackOffset: Int = 0,
    ): PlaylistPayload {
        val safeTrackLimit = trackLimit.coerceIn(1, FAVORITES_FULL_TRACK_PAGE_LIMIT)
        val body = request(
            method = "GET",
            path = "/playlists/favorites?limit=$safeTrackLimit&offset=${trackOffset.coerceAtLeast(0)}",
            authenticated = true,
        )
        return body.toFavoritesPlaylistPayload()
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
        sortOption: ArtistSortOption,
    ): List<LibraryArtist> {
        return libraryArtistsPageWithTotal(
            limit = limit,
            offset = offset,
            sortOption = sortOption,
        ).artists
    }

    suspend fun libraryArtistsPageWithTotal(
        limit: Int = LIBRARY_PAGE_LIMIT,
        offset: Int = 0,
        sortOption: ArtistSortOption,
    ): LibraryArtistsPage {
        val body = request(
            method = "GET",
            path = (
                "/library/artists?sortBy=${sortOption.apiSortBy}" +
                    "&sortDirection=${sortOption.apiSortDirection}"
                ).withPagination(
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

    suspend fun recentAlbums(
        limit: Int = 10,
        offset: Int = 0,
    ): List<LibraryAlbum> {
        val safeLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val body = request(
            method = "GET",
            path = "/library/albums/recent?limit=$safeLimit&offset=${offset.coerceAtLeast(0)}",
            authenticated = true,
        )
        return body.toLibraryAlbums().distinctBy { it.id }
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
            parse = String::toSavedLibraryAlbums,
        ).distinctBy { it.id }
    }

    suspend fun savedAlbumsPage(
        limit: Int = LIBRARY_PAGE_LIMIT,
        offset: Int = 0,
    ): List<LibraryAlbum> {
        return pagedListPage(
            path = "/library/me/albums",
            limit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT),
            offset = offset,
            parse = String::toSavedLibraryAlbums,
        ).distinctBy { it.id }
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

    suspend fun librarySearch(query: String, limit: Int, offset: Int = 0): LibrarySearchResults {
        val safeLimit = limit.coerceIn(1, TRACK_SEARCH_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        return runCatching {
            val body = request(
                method = "GET",
                path = "/library/search?q=${query.queryValue()}" +
                    "&artistLimit=5&albumLimit=5&playlistLimit=5" +
                    "&trackLimit=$safeLimit&trackOffset=$safeOffset",
                authenticated = true,
            )
            body.toLibrarySearchResults()
        }.getOrDefault(LibrarySearchResults(emptyList(), emptyList(), emptyList()))
    }

    suspend fun tracks(): List<Track> {
        return pagedList(
            path = "/tracks",
            limit = TRACK_CATALOG_PAGE_LIMIT,
            parse = String::toTracks,
        ).distinctBy { it.id }
    }

    suspend fun recentTracks(limit: Int = 50, offset: Int = 0): List<Track> {
        val safeLimit = limit.coerceIn(1, TRACK_CATALOG_PAGE_LIMIT)
        val body = request(
            method = "GET",
            path = "/tracks/recent?limit=$safeLimit&offset=${offset.coerceAtLeast(0)}",
            authenticated = true,
        )
        return body.toTracks().distinctBy { it.id }
    }

    suspend fun track(trackId: String): Track {
        val body = request(
            method = "GET",
            path = "/tracks/${trackId.pathSegment()}",
            authenticated = true,
        )
        val root = JSONObject(body).payloadObject()
        val trackJson = root.optJSONObject("track") ?: root
        return trackJson.toTrack(accentColor = stableAccentColorFor(trackJson.stableId("track")))
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

    suspend fun streamUrl(trackId: String): String {
        val body = request(
            method = "GET",
            path = "/tracks/${trackId.pathSegment()}/stream-url",
            authenticated = true,
        ).trim()

        val root = JSONObject(body).payloadObject()
        return root.expectedStringOrLog("url", "Stream URL response")
            ?.toAbsoluteUrl(baseUrl)
            ?: throw TMusicApiException(null, "The stream URL response does not contain url.")
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
                readTimeoutMs = LYRICS_REFRESH_READ_TIMEOUT_MS,
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

    suspend fun updatePlaylist(playlistId: String, name: String): Playlist? {
        val body = JSONObject()
            .put("name", name)
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
        return root.playlistMutationUpdateOrNull(playlistId)
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
        return root.playlistMutationUpdateOrNull(playlistId)
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
        if (root.optBoolean("ok", false) && root.optJSONObject("playlist") == null) {
            return null
        }
        return root.playlistUpdateOrNull()
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

    suspend fun movePlaylistTrack(playlistId: String, playlistTrackId: String, position: Int): Playlist? {
        val body = JSONObject()
            .put("position", position.coerceAtLeast(1))
            .toString()
        val responseBody = request(
            method = "PATCH",
            path = "/playlists/${playlistId.pathSegment()}/tracks/${playlistTrackId.pathSegment()}",
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
            url = root.expectedStringOrLog("url", "Track download URL response")?.toAbsoluteUrl(baseUrl)
                ?: throw TMusicApiException(null, "The download URL response does not contain url."),
            etag = root.optionalString("etag"),
            checksumSha256 = root.optionalString("checksumSha256"),
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
        return root.expectedStringOrLog("artworkUrl", "Track artwork response")
            ?.toAbsoluteUrl(baseUrl)
            ?: throw TMusicApiException(null, "The artwork response does not contain artworkUrl.")
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
        return root.expectedStringOrLog("artworkUrl", "Album artwork response")
            ?.toAbsoluteUrl(baseUrl)
            ?: throw TMusicApiException(null, "The album artwork response does not contain artworkUrl.")
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
        return root.expectedStringOrLog("artworkUrl", "Artist artwork response")
            ?.toAbsoluteUrl(baseUrl)
            ?: throw TMusicApiException(null, "The artist artwork response does not contain artworkUrl.")
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
        return root.expectedStringOrLog("artworkUrl", "Playlist artwork response")
            ?.toAbsoluteUrl(baseUrl)
            ?: throw TMusicApiException(null, "The playlist artwork response does not contain artworkUrl.")
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
        val url = root.optionalString("authUrl")?.toAbsoluteUrl(baseUrl)
            ?: throw TMusicApiException(null, "The Last.fm auth response does not contain authUrl or url.")
        return LastFmAuthRequest(
            token = root.optionalString("token")
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
            readTimeoutMs = LASTFM_SYNC_READ_TIMEOUT_MS,
        )
        return responseBody.syncedClientEventIds(defaultIds = events.map { it.clientEventId }.toSet())
    }

    suspend fun syncLibraryMutations(mutations: List<PendingLibraryMutation>): Set<String> {
        if (mutations.isEmpty()) {
            return emptySet()
        }
        val body = JSONObject()
            .put(
                "operations",
                JSONArray().apply {
                    mutations.forEach { mutation -> put(mutation.toJson()) }
                },
            )
            .toString()

        val responseBody = request(
            method = "POST",
            path = "/library/sync",
            body = body,
            authenticated = true,
        )
        return responseBody.syncedClientMutationIds(defaultIds = mutations.map { it.clientMutationId }.toSet())
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
        readTimeoutMs: Int = READ_TIMEOUT_MS,
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
            readTimeoutMs = readTimeoutMs,
        )

        if (authenticated && response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            Log.d(API_LOG_TAG, "$requestLabel -> HTTP 401, refreshing token")
            refreshTokens()
            response = execute(
                method = method,
                path = path,
                body = body,
                accessToken = sessionStore.tokens()?.accessToken,
                readTimeoutMs = readTimeoutMs,
            )
        }

        if (response.statusCode !in 200..299) {
            Log.e(
                API_LOG_TAG,
                "$requestLabel -> HTTP ${response.statusCode}: ${response.body.sanitizedForLog().take(400)}",
            )
            throw TMusicApiException(
                statusCode = response.statusCode,
                message = response.errorMessage(),
                code = response.errorCode(),
                updateInfo = response.appUpdateInfoOrNull(),
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
        (root.optJSONObject("user") ?: root.optJSONObject("account"))?.let { user ->
            sessionStore.saveAccount(user.toAccount())
        }
    }

    private fun execute(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): ApiResponse {
        val url = baseUrl + path
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-TMusic-Platform", "android")
            setRequestProperty("X-TMusic-Version-Code", BuildConfig.VERSION_CODE.toString())
            setRequestProperty("X-TMusic-Version-Name", BuildConfig.VERSION_NAME)
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
        const val LASTFM_SYNC_READ_TIMEOUT_MS = 60_000
        const val LYRICS_REFRESH_READ_TIMEOUT_MS = 120_000
        const val LIBRARY_PAGE_LIMIT = 50
        const val PLAYLIST_PAGE_LIMIT = 50
        const val PLAYLIST_TRACK_PAGE_LIMIT = 50
        const val PLAYLIST_DETAIL_TRACK_PAGE_LIMIT = 100
        const val PLAYLIST_FULL_TRACK_PAGE_LIMIT = 500
        const val FAVORITES_FULL_TRACK_PAGE_LIMIT = 500
        const val TRACK_LIST_PAGE_LIMIT = 500
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
        if (statusCode == HTTP_UPGRADE_REQUIRED || errorCode().equals("APP_UPDATE_REQUIRED", ignoreCase = true)) {
            return "App update required to continue."
        }
        if (body.isBlank()) {
            return "Request failed with HTTP $statusCode."
        }

        return runCatching {
            val root = JSONObject(body)
            root.optionalString("message") ?: "Request failed with HTTP $statusCode."
        }.getOrElse {
            body
        }
    }

    fun errorCode(): String? {
        if (body.isBlank()) {
            return null
        }

        return runCatching {
            JSONObject(body).optionalString("code")
        }.getOrNull()
    }

    fun appUpdateInfoOrNull(): AppUpdateInfo? {
        if (body.isBlank()) {
            return null
        }

        return runCatching {
            JSONObject(body).payloadObject().appUpdateInfoOrNull(statusCode = statusCode)
        }.getOrNull()
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

private fun JSONObject.expectedStringOrLog(name: String, context: String): String? {
    val value = optionalString(name)
    if (value == null) {
        Log.e(
            API_LOG_TAG,
            "$context is missing expected JSON field '$name': " +
                toString().sanitizedForLog().take(800),
        )
    }
    return value
}

private fun JSONObject.expectedBooleanOrLog(name: String, context: String): Boolean? {
    if (!has(name) || isNull(name)) {
        Log.e(
            API_LOG_TAG,
            "$context is missing expected JSON field '$name': " +
                toString().sanitizedForLog().take(800),
        )
        return null
    }
    val value = opt(name)
    if (value !is Boolean) {
        Log.e(
            API_LOG_TAG,
            "$context has non-boolean JSON field '$name': " +
                toString().sanitizedForLog().take(800),
        )
        return null
    }
    return value
}

private fun JSONObject.optionalString(vararg names: String): String? {
    val name = names.firstOrNull() ?: return null
    if (has(name)) {
        val rawValue = opt(name)
        if (rawValue is JSONObject || rawValue is JSONArray || rawValue == JSONObject.NULL) {
            return null
        }
        val value = rawValue?.toString().orEmpty()
        if (value.isMeaningfulString()) {
            return value
        }
    }
    return null
}

private fun JSONObject.optionalInt(vararg names: String): Int? {
    val name = names.firstOrNull() ?: return null
    if (has(name) && !isNull(name)) {
        return optInt(name)
    }
    return null
}

private fun JSONObject.optionalBoolean(vararg names: String): Boolean? {
    val name = names.firstOrNull() ?: return null
    if (has(name) && !isNull(name)) {
        return optBoolean(name)
    }
    return null
}

private fun JSONObject.appUpdateInfoOrNull(statusCode: Int? = null): AppUpdateInfo? {
    val code = optionalString("code")
    val minSupportedVersionCode = optionalInt("minSupportedVersionCode")
    val forceUpdate = statusCode == HTTP_UPGRADE_REQUIRED ||
        code.equals("APP_UPDATE_REQUIRED", ignoreCase = true) ||
        minSupportedVersionCode?.let { BuildConfig.VERSION_CODE < it } == true
    if (!forceUpdate) {
        return null
    }
    return AppUpdateInfo(
        version = minSupportedVersionCode?.let { "min-$it" } ?: BuildConfig.VERSION_NAME,
        title = "Update required",
        changelog = "",
        pageUrl = "",
        downloadUrl = "",
        releaseNotesUrl = "",
        minSupportedVersionCode = minSupportedVersionCode,
        latestVersionCode = null,
        forceUpdate = true,
        blockingScopes = optJSONArray("blockingScopes")
            ?.stringValues()
            .orEmpty(),
    )
}

private fun JSONObject.paginationTotalCount(): Int? {
    optionalInt("total")?.let { return it }
    for (name in listOf("pagination", "meta", "page")) {
        optJSONObject(name)
            ?.optionalInt("total")
            ?.let { return it }
    }
    return null
}

private fun JSONObject.hasArtworkMetadata(): Boolean {
    return (has("artwork") && !isNull("artwork")) ||
        optionalString("artworkId") != null
}

private fun JSONObject.stableId(fallbackPrefix: String): String {
    return optionalString("id") ?: fallbackPrefix
}

private fun JSONObject.artistIdList(): List<String> {
    val directIds = listOfNotNull(optionalString("artistId"))
    val directArrayIds = listOfNotNull(
        optJSONArray("artistIds"),
        optJSONArray("primaryArtistIds"),
        optJSONArray("albumArtistIds"),
        optJSONArray("trackArtistIds"),
    ).flatMap { array -> array.artistIdValues(allowPrimitiveIds = true) }
    val dtoArrayIds = listOfNotNull(
        optJSONArray("artists"),
        optJSONArray("artistEntities"),
        optJSONArray("trackArtists"),
        optJSONArray("trackArtistEntities"),
        optJSONArray("albumArtists"),
        optJSONArray("albumArtistEntities"),
        optJSONArray("primaryArtists"),
        optJSONArray("primaryArtistEntities"),
    ).flatMap { array -> array.artistIdValues(allowPrimitiveIds = false) }
    val objectIds = listOfNotNull(
        optJSONObject("artist")?.optionalString("id"),
        optJSONObject("artistEntity")?.optionalString("id"),
        optJSONObject("albumArtist")?.optionalString("id"),
        optJSONObject("albumArtistEntity")?.optionalString("id"),
        optJSONObject("primaryArtist")?.optionalString("id"),
        optJSONObject("primaryArtistEntity")?.optionalString("id"),
        optJSONObject("trackArtist")?.optionalString("id"),
        optJSONObject("trackArtistEntity")?.optionalString("id"),
    )
    return (directIds + directArrayIds + dtoArrayIds + objectIds).distinct()
}

private fun JSONArray.artistIdValues(allowPrimitiveIds: Boolean): List<String> {
    return buildList {
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is JSONObject -> (
                    value.optionalString("artistId")
                        ?: value.optJSONObject("artist")?.optionalString("id")
                        ?: value.optJSONObject("artistEntity")?.optionalString("id")
                        ?: value.optJSONObject("albumArtist")?.optionalString("id")
                        ?: value.optJSONObject("trackArtist")?.optionalString("id")
                        ?: value.optionalString("id")
                    )?.let(::add)
                else -> if (allowPrimitiveIds) {
                    value.toString().takeIf { it.isMeaningfulString() }?.let(::add)
                }
            }
        }
    }
}

private fun JSONObject.artistNameList(): List<String> {
    val directNames = listOfNotNull(optionalString("artist"))
    val arrayNames = listOfNotNull(
        optJSONArray("artists"),
        optJSONArray("artistEntities"),
        optJSONArray("trackArtists"),
        optJSONArray("trackArtistEntities"),
        optJSONArray("albumArtists"),
        optJSONArray("albumArtistEntities"),
        optJSONArray("primaryArtists"),
        optJSONArray("primaryArtistEntities"),
    ).flatMap { array ->
        buildList {
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is JSONObject -> (
                        value.optJSONObject("artist")?.optionalString("name")
                            ?: value.optJSONObject("artistEntity")?.optionalString("name")
                            ?: value.optJSONObject("albumArtist")?.optionalString("name")
                            ?: value.optJSONObject("trackArtist")?.optionalString("name")
                            ?: value.optionalString("name")
                        )?.let(::add)
                    else -> value.toString().takeIf { it.isMeaningfulString() }?.let(::add)
                }
            }
        }
    }
    val objectNames = listOfNotNull(
        optJSONObject("artist")?.optionalString("name"),
        optJSONObject("artistEntity")?.optionalString("name"),
        optJSONObject("albumArtist")?.optionalString("name"),
        optJSONObject("albumArtistEntity")?.optionalString("name"),
        optJSONObject("primaryArtist")?.optionalString("name"),
        optJSONObject("primaryArtistEntity")?.optionalString("name"),
        optJSONObject("trackArtist")?.optionalString("name"),
        optJSONObject("trackArtistEntity")?.optionalString("name"),
    )
    return (directNames + arrayNames + objectNames)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
}

private fun JSONObject.artistReferenceList(vararg arrayNames: String): List<LibraryArtist> {
    return arrayNames
        .mapNotNull(::optJSONArray)
        .flatMap { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.opt(index)
                    val entity = when (value) {
                        is JSONObject -> value.optJSONObject("artist")
                            ?: value.optJSONObject("artistEntity")
                            ?: value.optJSONObject("albumArtist")
                            ?: value.optJSONObject("trackArtist")
                            ?: value
                        else -> null
                    }
                    val id = entity?.optionalString("id")
                    if (id.isNullOrBlank()) {
                        continue
                    }
                    add(
                        LibraryArtist(
                            id = id,
                            name = entity.optionalString("name") ?: id,
                        ),
                    )
                }
            }
        }
        .distinctBy { it.id }
}

private fun artistReferencesFromIdsAndNames(
    ids: List<String>,
    names: List<String>,
): List<LibraryArtist> {
    val cleanNames = names
        .map { it.trim() }
        .filter { it.isNotBlank() }
    return ids
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .mapIndexed { index, id ->
            LibraryArtist(
                id = id,
                name = cleanNames.getOrNull(index) ?: cleanNames.firstOrNull() ?: id,
            )
        }
}

private fun JSONObject.albumArtistId(): String? {
    val albumEntity = optJSONObject("albumEntity")
        ?: optJSONObject("album")
        ?: optJSONObject("albumDto")
        ?: optJSONObject("albumObject")
    return albumEntity?.optionalString("artistId")
        ?: albumEntity?.optJSONObject("artist")?.optionalString("id")
        ?: albumEntity?.optJSONObject("artistEntity")?.optionalString("id")
        ?: albumEntity?.optJSONObject("albumArtist")?.optionalString("id")
        ?: albumEntity?.optJSONObject("albumArtistEntity")?.optionalString("id")
        ?: albumEntity?.optJSONObject("primaryArtist")?.optionalString("id")
        ?: albumEntity?.optJSONObject("primaryArtistEntity")?.optionalString("id")
        ?: albumEntity?.artistIdList()?.firstOrNull()
        ?: optionalString("albumArtistId")
}

private fun JSONObject.toAccount(): Account {
    val email = optionalString("email") ?: ""
    val displayName = optionalString("displayName")
        ?: email.substringBefore('@', missingDelimiterValue = "User")

    return Account(
        id = optionalString("id") ?: email.ifBlank { "user" },
        displayName = displayName,
        email = email,
        avatarUrl = optionalString("avatarUrl"),
        lastFmConnection = accountLastFmConnection(),
        canPlayMedia = optionalBoolean("canPlayMedia") ?: true,
    )
}

private fun JSONObject.accountLastFmConnection(): LastFmConnection? {
    val lastFm = optJSONObject("lastfm") ?: optJSONObject("lastFm") ?: return null
    val username = lastFm.optionalString("username")
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
    val trackCount = optionalInt("trackCount")
        ?: count?.optionalInt("tracks")
        ?: trackIds.size
    return Playlist(
        id = id,
        title = expectedStringOrLog("name", "Playlist $id").orEmpty(),
        trackIds = trackIds,
        isOfflineEnabled = optBoolean("isOfflineEnabled", false),
        isPublic = optBoolean("isPublic", false),
        playlistTrackIds = playlistTrackIds,
        playlistTrackIdsByTrackId = parsePlaylistTrackIdsByTrackId(),
        isFavorites = optionalString("kind")?.equals("FAVORITES", ignoreCase = true) == true,
        trackCount = trackCount.coerceAtLeast(trackIds.size),
        totalDurationSeconds = totalDurationSeconds(),
        updatedAt = expectedStringOrLog("updatedAt", "Playlist $id"),
    )
}

private fun JSONObject.playlistUpdateOrNull(): Playlist? {
    optJSONObject("playlist")?.let { return it.toPlaylist() }
    return if (isPlaylistObject()) toPlaylist() else null
}

private fun JSONObject.playlistMutationUpdateOrNull(playlistId: String): Playlist? {
    val playlist = playlistUpdateOrNull()
    val playlistTrack = playlistTrackUpdateOrNull(playlistId)
    if (playlist == null) {
        return playlistTrack
    }
    if (playlistTrack == null) {
        return playlist
    }
    return playlist.copy(
        trackIds = playlistTrack.trackIds,
        playlistTrackIds = playlistTrack.playlistTrackIds,
        playlistTrackIdsByTrackId = playlistTrack.playlistTrackIdsByTrackId,
    )
}

private fun JSONObject.playlistTrackUpdateOrNull(playlistId: String): Playlist? {
    val row = optJSONObject("playlistTrack")
        ?: takeIf { has("playlistTrackId") && has("track") }
        ?: return null
    val playlistTrackId = row.expectedStringOrLog("playlistTrackId", "Playlist track row")
        ?: return null
    val trackId = row.optJSONObject("track")?.expectedStringOrLog("id", "Playlist track row track")
        ?: row.expectedStringOrLog("trackId", "Playlist track row")
        ?: return null
    return Playlist(
        id = playlistId,
        title = "",
        trackIds = listOf(trackId),
        isOfflineEnabled = false,
        playlistTrackIds = listOf(playlistTrackId),
        playlistTrackIdsByTrackId = mapOf(trackId to playlistTrackId),
    )
}

private fun JSONObject.parseTrackIds(): List<String> {
    val array = optJSONArray("trackIds") ?: optJSONArray("tracks") ?: return emptyList()
    val ids = mutableListOf<String>()

    for (index in 0 until array.length()) {
        when (val item = array.get(index)) {
            is JSONObject -> {
                val nestedTrack = item.optJSONObject("track")
                val trackId = nestedTrack?.optionalString("id")
                    ?: item.optionalString("trackId")
                if (trackId == null) {
                    Log.e(API_LOG_TAG, "Playlist track row at index $index is missing expected JSON field 'track.id': $item")
                } else {
                    ids += trackId
                }
            }
            else -> item.toString().takeIf { it.isNotBlank() }?.let(ids::add)
        }
    }

    return ids
}

private fun JSONObject.parsePlaylistTrackIds(): List<String> {
    val array = optJSONArray("tracks") ?: return emptyList()
    val ids = mutableListOf<String>()

    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val playlistTrackId = item.optionalString("playlistTrackId")
        if (playlistTrackId == null) {
            Log.e(API_LOG_TAG, "Playlist track row at index $index is missing expected JSON field 'playlistTrackId': $item")
        } else {
            ids += playlistTrackId
        }
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
        val trackId = nestedTrack?.optionalString("id")
            ?: item.optionalString("trackId")
            ?: continue
        val playlistTrackId = item.optionalString("playlistTrackId")
        if (playlistTrackId == null) {
            Log.e(API_LOG_TAG, "Playlist track row for track $trackId is missing expected JSON field 'playlistTrackId': $item")
            continue
        }

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
        ?: optJSONObject("album")
        ?: optJSONObject("albumDto")
        ?: optJSONObject("albumObject")
    val rawTrackArtistReferences = artistReferenceList("artists", "trackArtists", "artistEntities", "trackArtistEntities")
    val rawAlbumArtistReferences = (
        artistReferenceList("albumArtists", "albumArtistEntities", "primaryArtists", "primaryArtistEntities") +
            albumEntity?.artistReferenceList(
                "artists",
                "albumArtists",
                "artistEntities",
                "albumArtistEntities",
                "primaryArtists",
                "primaryArtistEntities",
            ).orEmpty()
        ).distinctBy { it.id }
    val artistIds = (rawTrackArtistReferences.map { it.id } + artistIdList()).distinct()
    val artistNames = (rawTrackArtistReferences.map { it.name } + artistNameList())
        .distinctBy { it.lowercase() }
    val albumArtistNames = (
        rawAlbumArtistReferences.map { it.name } +
            listOfNotNull(optionalString("albumArtist")) +
            albumEntity?.artistNameList().orEmpty()
        )
        .distinctBy { it.lowercase() }
    val albumArtistIds = (
        rawAlbumArtistReferences.map { it.id } +
            listOfNotNull(optionalString("albumArtistId")) +
            albumEntity?.artistIdList().orEmpty()
        ).distinct()
    val trackArtistReferences = rawTrackArtistReferences.ifEmpty {
        artistReferencesFromIdsAndNames(artistIds, artistNames)
    }
    val albumArtistReferences = rawAlbumArtistReferences.ifEmpty {
        artistReferencesFromIdsAndNames(albumArtistIds, albumArtistNames)
    }
    return Track(
        id = id,
        title = optionalString("title") ?: "Untitled track",
        artist = optionalString("artist")
            ?: artistNames.joinToString("; ").takeIf { it.isNotBlank() }
            ?: albumEntity?.optionalString("artist")
            ?: "Unknown artist",
        album = optionalString("albumTitle")
            ?: albumEntity?.optionalString("title")
            ?: "Unknown album",
        durationSeconds = durationSeconds(),
        serverPath = optionalString("serverPath") ?: "/tracks/$id",
        accentColor = accentColor,
        downloadState = DownloadState.NotDownloaded,
        playCount = optInt("playCount", 0),
        artistId = artistIds.firstOrNull(),
        artistIds = artistIds,
        artists = trackArtistReferences,
        albumId = optionalString("albumId") ?: albumEntity?.optionalString("id"),
        albumArtist = albumEntity?.optionalString("artist")
            ?: albumArtistNames.joinToString("; ").takeIf { it.isNotBlank() }
            ?: optionalString("albumArtist"),
        albumArtistId = albumArtistId() ?: albumArtistIds.firstOrNull(),
        albumArtists = albumArtistReferences,
        trackNumber = optionalInt("trackNumber"),
        discNumber = optionalInt("discNumber"),
        releaseYear = optionalInt("releaseYear")
            ?: albumEntity?.optionalInt("releaseYear"),
        genre = optionalString("genre"),
        isLiked = optionalBoolean("isLiked"),
        foundInLyrics = optionalBoolean("foundInLyrics") == true,
    )
}

private fun JSONObject.toTrackLyrics(): TrackLyrics? {
    val plainLyrics = optionalString("plainLyrics")
    val syncedLyrics = optionalString("syncedLyrics")
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
    val id = optionalString("id") ?: return null
    val name = expectedStringOrLog("name", "Artist $id") ?: return null
    val count = optJSONObject("_count")
    return LibraryArtist(
        name = name,
        id = id,
        albumCount = optionalInt("albumCount")
            ?: count?.optionalInt("albums")
            ?: 0,
        trackCount = optionalInt("trackCount")
            ?: count?.optionalInt("tracks")
            ?: 0,
        representativeAlbumId = optionalString("representativeAlbumId"),
        latestReleaseYear = optionalInt("latestReleaseYear"),
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

private fun JSONObject.toRecentAlbumChange(): RecentAlbumChange? {
    val type = optionalString("type") ?: return null
    return RecentAlbumChange(
        type = type,
        latestTrackCreatedAt = optionalString("latestTrackCreatedAt"),
        latestTrackUpdatedAt = optionalString("latestTrackUpdatedAt"),
    )
}

private fun JSONObject.toLibraryAlbum(): LibraryAlbum? {
    val id = stableId("album")
    val title = optionalString("title") ?: return null
    val rawArtistReferences = artistReferenceList(
        "artists",
        "albumArtists",
        "artistEntities",
        "albumArtistEntities",
        "primaryArtists",
        "primaryArtistEntities",
    )
    val artistIds = (rawArtistReferences.map { it.id } + artistIdList()).distinct()
    val artistNames = (rawArtistReferences.map { it.name } + artistNameList())
        .distinctBy { it.lowercase() }
    val artistReferences = rawArtistReferences.ifEmpty {
        artistReferencesFromIdsAndNames(artistIds, artistNames)
    }
    val artist = optionalString("artist")
        ?: artistReferences.joinToString("; ") { it.name }.takeIf { it.isNotBlank() }
        ?: artistNames.joinToString("; ").takeIf { it.isNotBlank() }
        ?: optJSONObject("artistEntity")?.optionalString("name")
        ?: "Unknown artist"
    val artistId = optionalString("artistId")
        ?: optJSONObject("artist")?.optionalString("id")
        ?: optJSONObject("artistEntity")?.optionalString("id")
        ?: optJSONObject("albumArtist")?.optionalString("id")
        ?: optJSONObject("albumArtistEntity")?.optionalString("id")
        ?: optJSONObject("primaryArtist")?.optionalString("id")
        ?: optJSONObject("primaryArtistEntity")?.optionalString("id")
        ?: artistReferences.firstOrNull()?.id
        ?: artistIds.firstOrNull()
    val count = optJSONObject("_count")
    val recentChange = optJSONObject("recentChange")?.toRecentAlbumChange()
    val recentChangeType = optionalString("recentChangeType") ?: recentChange?.type
    return LibraryAlbum(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        artistIds = artistIds,
        artists = artistReferences,
        releaseYear = optionalInt("releaseYear"),
        genre = optionalString("genre"),
        trackCount = optionalInt("trackCount")
            ?: count?.optionalInt("tracks")
            ?: optJSONArray("tracks")?.length()
            ?: 0,
        accentColor = stableAccentColorFor(id),
        artworkTrackId = optionalString("artworkTrackId")
            ?: optJSONObject("track")?.optionalString("id")
            ?: optJSONArray("tracks")?.optJSONObject(0)?.let { item ->
                item.optJSONObject("track")?.optionalString("id")
                    ?: item.optionalString("trackId")
            },
        savedByCurrentUser = optBoolean("savedByCurrentUser", false),
        isOfflineEnabled = optBoolean("isOfflineEnabled", false),
        hasArtwork = hasArtworkMetadata(),
        totalDurationSeconds = totalDurationSeconds(),
        recentChangeType = recentChangeType,
        isNewAlbum = optBoolean("isNewAlbum", false) ||
            recentChangeType.equals("new", ignoreCase = true),
        recentChange = recentChange,
    )
}

private fun JSONObject.toLastFmConnection(): LastFmConnection {
    val username = optionalString("username")
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

private fun JSONObject.totalDurationSeconds(): Int? {
    optionalInt("totalDurationSeconds")?.let { return it.coerceAtLeast(0) }
    optionalInt("totalDurationMs")?.let { return (it / 1000).coerceAtLeast(0) }
    optJSONObject("_sum")?.let { sum ->
        sum.optionalInt("durationSeconds")?.let { return it.coerceAtLeast(0) }
        sum.optionalInt("durationMs")?.let { return (it / 1000).coerceAtLeast(0) }
    }
    optJSONObject("duration")?.let { duration ->
        duration.optionalInt("seconds")?.let { return it.coerceAtLeast(0) }
        duration.optionalInt("ms")?.let { return (it / 1000).coerceAtLeast(0) }
    }
    return null
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

private fun String.toFavoritesPlaylistPayload(): PlaylistPayload {
    val response = JSONObject(trim())
    val root = response.payloadObject()
    val playlistObject = root.optJSONObject("playlist")
        ?: root.takeIf { it.isPlaylistObject() }
    val playlistSource = playlistObject ?: root
    val tracks = playlistSource.embeddedTracks().distinctBy { it.id }
    val trackIds = playlistSource.parseTrackIds().ifEmpty { tracks.map { it.id } }
    val playlistTrackIds = playlistSource.parsePlaylistTrackIds()
    val trackCount = playlistSource.optJSONObject("_count")?.optionalInt("tracks")
        ?: playlistSource.optionalInt("total")
        ?: playlistSource.paginationTotalCount()
        ?: response.paginationTotalCount()
        ?: trackIds.size
    val playlistTrackIdsByTrackId = playlistSource.parsePlaylistTrackIdsByTrackId()
    val playlistId = playlistObject?.expectedStringOrLog("id", "Favorites playlist")
        ?: root.expectedStringOrLog("id", "Favorites playlist")
        ?: return PlaylistPayload(playlists = emptyList(), tracks = tracks)
    val playlistTitle = playlistObject?.expectedStringOrLog("name", "Favorites playlist")
        ?: root.expectedStringOrLog("name", "Favorites playlist")
        ?: return PlaylistPayload(playlists = emptyList(), tracks = tracks)
    val playlist = Playlist(
        id = playlistId,
        title = playlistTitle,
        trackIds = trackIds,
        isOfflineEnabled = playlistSource.optionalBoolean("isOfflineEnabled")
            ?: false,
        isPublic = playlistSource.optionalBoolean("isPublic")
            ?: false,
        playlistTrackIds = playlistTrackIds,
        playlistTrackIdsByTrackId = playlistTrackIdsByTrackId,
        isFavorites = true,
        trackCount = trackCount.coerceAtLeast(trackIds.size),
        totalDurationSeconds = playlistSource.totalDurationSeconds()
            ?: root.totalDurationSeconds(),
        updatedAt = playlistSource.expectedStringOrLog("updatedAt", "Favorites playlist"),
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
    val hasIdentity = optionalString("id") != null ||
        optionalString("name") != null
    val hasPlaylistShape = optionalString("name") != null ||
        has("isPublic") ||
        has("trackIds") ||
        optJSONObject("_count")?.has("tracks") == true ||
        optionalString("kind")?.equals("FAVORITES", ignoreCase = true) == true
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
        totalDurationSeconds = asSequence()
            .flatMap { it.playlists.asSequence() }
            .mapNotNull { it.totalDurationSeconds }
            .firstOrNull(),
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
            else -> null
        }
    }.distinctBy { it.id }
}

private fun JSONObject.toSimilarArtist(): LibraryArtist? {
    val nestedArtist = optJSONObject("artist")
    val parsed = nestedArtist?.toLibraryArtist()
        ?: takeIf { has("name") }?.toLibraryArtist()
    val similarity = optJSONObject("similarity")?.toArtistSimilarity()
    if (parsed != null) {
        return parsed.copy(similarity = similarity ?: parsed.similarity)
    }
    val id = expectedStringOrLog("id", "Similar artist") ?: return null
    return LibraryArtist(
        id = id,
        name = "",
        similarity = similarity,
    )
}

private fun String.toLibraryAlbums(): List<LibraryAlbum> {
    val values = jsonValues(arrayKey = "albums", objectKey = "album")
    return values.mapNotNull { it.toLibraryAlbumValue() }.distinctBy { it.id }
}

private fun String.toSavedLibraryAlbums(): List<LibraryAlbum> {
    val values = jsonValues(arrayKey = "albums", objectKey = "album")
    return values.mapNotNull { it.toSavedLibraryAlbumValue() }.distinctBy { it.id }
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

private fun Any.toSavedLibraryAlbumValue(): LibraryAlbum? {
    val value = this as? JSONObject ?: return null
    val albumJson = value.optJSONObject("album")
    if (albumJson == null) {
        Log.e(
            API_LOG_TAG,
            "UserAlbum payload is missing expected JSON field 'album': " +
                value.toString().sanitizedForLog().take(800),
        )
        return null
    }

    val parsedAlbum = albumJson.toLibraryAlbum() ?: return null
    return parsedAlbum.copy(
        savedByCurrentUser = true,
        userAlbumCreatedAt = value.expectedStringOrLog("createdAt", "UserAlbum ${parsedAlbum.id}"),
    )
}

private fun Any.toTrackValue(
    requiredFoundInLyrics: Boolean = false,
    context: String = "Track",
): Track? {
    val value = this as? JSONObject ?: return null
    val trackJson = value.optJSONObject("track") ?: value
    val id = trackJson.stableId("track")
    val track = trackJson.toTrack(accentColor = stableAccentColorFor(id))
    if (!requiredFoundInLyrics) {
        return track
    }
    val foundInLyrics = when {
        trackJson.has("foundInLyrics") -> trackJson.expectedBooleanOrLog("foundInLyrics", "$context $id")
        value.has("foundInLyrics") -> value.expectedBooleanOrLog("foundInLyrics", "$context $id")
        else -> {
            trackJson.expectedBooleanOrLog("foundInLyrics", "$context $id")
            null
        }
    }
    return foundInLyrics?.let { track.copy(foundInLyrics = it) } ?: track
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
        val userAlbumCreatedAt = root.optJSONObject("album")?.let {
            root.expectedStringOrLog("createdAt", "UserAlbum ${parsedAlbum.id}")
        }
        if (root.has("savedByCurrentUser")) {
            parsedAlbum.copy(
                savedByCurrentUser = root.optBoolean("savedByCurrentUser", parsedAlbum.savedByCurrentUser),
                userAlbumCreatedAt = userAlbumCreatedAt,
            )
        } else {
            parsedAlbum.copy(userAlbumCreatedAt = userAlbumCreatedAt)
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
            json.toTrackValue(
                requiredFoundInLyrics = true,
                context = "LibrarySearch track",
            )
        }
        ?.filterNotNull()
        .orEmpty()
    val playlists = root.optJSONArray("playlists")
        ?.jsonObjects()
        ?.map { json -> json.toPlaylist() }
        .orEmpty()

    return LibrarySearchResults(
        artists = artists.distinctBy { it.id },
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

private fun JSONArray.stringValues(): List<String> {
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        optString(index).takeIf { it.isNotBlank() }?.let(values::add)
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

private fun String.syncedClientMutationIds(defaultIds: Set<String>): Set<String> {
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
                    result.optionalString("clientMutationId")?.let(::add)
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
