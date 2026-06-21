package dev.teacode.tmusic.data

import android.content.Context
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

data class CachedLibrary(
    val playlists: List<Playlist>,
    val tracks: List<Track>,
    val savedAlbums: List<LibraryAlbum> = emptyList(),
) {
    val isEmpty: Boolean = playlists.isEmpty() && tracks.isEmpty() && savedAlbums.isEmpty()
}

class LibraryCacheStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        LIBRARY_CACHE_NAME,
        Context.MODE_PRIVATE,
    )
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val saveSequence = AtomicLong(0L)

    fun library(): CachedLibrary {
        return CachedLibrary(
            playlists = readPlaylists(),
            tracks = readTracks(),
            savedAlbums = readSavedAlbums(),
        )
    }

    fun saveLibrary(
        playlists: List<Playlist>,
        tracks: List<Track>,
        savedAlbums: List<LibraryAlbum>? = null,
    ) {
        val sequence = saveSequence.incrementAndGet()
        val playlistsSnapshot = playlists.toList()
        val tracksSnapshot = tracks.toList()
        val savedAlbumsSnapshot = savedAlbums?.toList()
        saveExecutor.execute {
            val albumsToSave = savedAlbumsSnapshot ?: readSavedAlbums()
            val playlistsJson = playlistsSnapshot.toJsonArray { it.toJson() }.toString()
            val tracksJson = tracksSnapshot.toJsonArray { it.toJson() }.toString()
            val savedAlbumsJson = albumsToSave.toJsonArray { it.toJson() }.toString()
            if (saveSequence.get() != sequence) {
                return@execute
            }
            preferences.edit()
                .putString(KEY_PLAYLISTS, playlistsJson)
                .putString(KEY_TRACKS, tracksJson)
                .putString(KEY_SAVED_ALBUMS, savedAlbumsJson)
                .apply()
        }
    }

    fun clear() {
        saveSequence.incrementAndGet()
        preferences.edit().clear().apply()
    }

    fun sizeBytes(): Long {
        return preferences.getString(KEY_PLAYLISTS, null).orEmpty().toByteArray().size.toLong() +
            preferences.getString(KEY_TRACKS, null).orEmpty().toByteArray().size.toLong()
    }

    private fun readPlaylists(): List<Playlist> {
        return preferences.getString(KEY_PLAYLISTS, null)
            ?.let { json -> runCatching { JSONArray(json).mapPlaylists() }.getOrNull() }
            .orEmpty()
    }

    private fun readTracks(): List<Track> {
        return preferences.getString(KEY_TRACKS, null)
            ?.let { json -> runCatching { JSONArray(json).mapTracks() }.getOrNull() }
            .orEmpty()
    }

    private fun readSavedAlbums(): List<LibraryAlbum> {
        return preferences.getString(KEY_SAVED_ALBUMS, null)
            ?.let { json -> runCatching { JSONArray(json).mapAlbums() }.getOrNull() }
            .orEmpty()
    }

    private companion object {
        const val LIBRARY_CACHE_NAME = "tmusic_library_cache"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_TRACKS = "tracks"
        const val KEY_SAVED_ALBUMS = "saved_albums"
    }
}

private fun LibraryAlbum.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("artistId", artistId)
        .put("artistIds", JSONArray(artistIds))
        .put("artists", artists.toArtistJsonArray())
        .put("releaseYear", releaseYear)
        .put("genre", genre)
        .put("trackCount", trackCount)
        .put("accentColor", accentColor)
        .put("artworkTrackId", artworkTrackId)
        .put("savedByCurrentUser", savedByCurrentUser)
        .put("isOfflineEnabled", isOfflineEnabled)
        .put("hasArtwork", hasArtwork)
        .put("totalDurationSeconds", totalDurationSeconds)
        .put("userAlbumCreatedAt", userAlbumCreatedAt)
}

private fun JSONObject.toAlbum(): LibraryAlbum {
    return LibraryAlbum(
        id = optString("id"),
        title = optString("title"),
        artist = optString("artist"),
        artistId = optString("artistId").takeIf { it.isNotBlank() },
        artistIds = optJSONArray("artistIds").stringValues(),
        artists = optJSONArray("artists").artistValues(),
        releaseYear = optInt("releaseYear", -1).takeIf { it > 0 },
        genre = optString("genre").takeIf { it.isNotBlank() },
        trackCount = optInt("trackCount", 0).coerceAtLeast(0),
        accentColor = optLong("accentColor", 0xFF444444),
        artworkTrackId = optString("artworkTrackId").takeIf { it.isNotBlank() },
        savedByCurrentUser = optBoolean("savedByCurrentUser", false),
        isOfflineEnabled = optBoolean("isOfflineEnabled", false),
        hasArtwork = optBoolean("hasArtwork", false),
        totalDurationSeconds = if (has("totalDurationSeconds") && !isNull("totalDurationSeconds")) {
            optInt("totalDurationSeconds").coerceAtLeast(0)
        } else {
            null
        },
        userAlbumCreatedAt = optString("userAlbumCreatedAt").takeIf { it.isNotBlank() },
    )
}

private fun Playlist.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("trackIds", JSONArray(trackIds))
        .put("playlistTrackIds", JSONArray(playlistTrackIds))
        .put("playlistTrackIdsByTrackId", JSONObject(playlistTrackIdsByTrackId))
        .put("isOfflineEnabled", isOfflineEnabled)
        .put("isPublic", isPublic)
        .put("isFavorites", isFavorites)
        .put("trackCount", trackCount)
        .put("totalDurationSeconds", totalDurationSeconds)
        .put("updatedAt", updatedAt)
}

private fun JSONObject.toPlaylist(): Playlist {
    val trackIds = optJSONArray("trackIds").stringValues()
    return Playlist(
        id = optString("id"),
        title = optString("title"),
        trackIds = trackIds,
        isOfflineEnabled = optBoolean("isOfflineEnabled", false),
        isPublic = optBoolean("isPublic", false),
        playlistTrackIds = optJSONArray("playlistTrackIds").stringValues(),
        playlistTrackIdsByTrackId = optJSONObject("playlistTrackIdsByTrackId").stringMap(),
        isFavorites = optBoolean("isFavorites", false),
        trackCount = optInt("trackCount", trackIds.size).coerceAtLeast(trackIds.size),
        totalDurationSeconds = if (has("totalDurationSeconds") && !isNull("totalDurationSeconds")) {
            optInt("totalDurationSeconds").coerceAtLeast(0)
        } else {
            null
        },
        updatedAt = optString("updatedAt").takeIf { it.isNotBlank() },
    )
}

private fun Track.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationSeconds", durationSeconds)
        .put("serverPath", serverPath)
        .put("accentColor", accentColor)
        .put("downloadState", downloadState.name)
        .put("playCount", playCount)
        .put("artistId", artistId)
        .put("artistIds", JSONArray(artistIds))
        .put("artists", artists.toArtistJsonArray())
        .put("albumId", albumId)
        .put("albumArtist", albumArtist)
        .put("albumArtistId", albumArtistId)
        .put("albumArtists", albumArtists.toArtistJsonArray())
        .put("trackNumber", trackNumber)
        .put("discNumber", discNumber)
        .put("releaseYear", releaseYear)
        .put("genre", genre)
        .put("isLiked", isLiked)
}

private fun JSONObject.toTrack(): Track {
    return Track(
        id = optString("id"),
        title = optString("title"),
        artist = optString("artist"),
        album = optString("album"),
        durationSeconds = optInt("durationSeconds", 0),
        serverPath = optString("serverPath"),
        accentColor = optLong("accentColor", 0xFF444444),
        downloadState = optString("downloadState").toDownloadState(),
        playCount = optInt("playCount", 0),
        artistId = optString("artistId").takeIf { it.isNotBlank() },
        artistIds = optJSONArray("artistIds").stringValues(),
        artists = optJSONArray("artists").artistValues(),
        albumId = optString("albumId").takeIf { it.isNotBlank() },
        albumArtist = optString("albumArtist").takeIf { it.isNotBlank() },
        albumArtistId = optString("albumArtistId").takeIf { it.isNotBlank() },
        albumArtists = optJSONArray("albumArtists").artistValues(),
        trackNumber = if (has("trackNumber") && !isNull("trackNumber")) optInt("trackNumber") else null,
        discNumber = if (has("discNumber") && !isNull("discNumber")) optInt("discNumber") else null,
        releaseYear = if (has("releaseYear") && !isNull("releaseYear")) optInt("releaseYear") else null,
        genre = optString("genre").takeIf { it.isNotBlank() },
        isLiked = if (has("isLiked") && !isNull("isLiked")) optBoolean("isLiked") else null,
    )
}

private fun String.toDownloadState(): DownloadState {
    return runCatching { DownloadState.valueOf(this) }.getOrDefault(DownloadState.NotDownloaded)
}

private fun <T> List<T>.toJsonArray(transform: (T) -> JSONObject): JSONArray {
    val array = JSONArray()
    forEach { array.put(transform(it)) }
    return array
}

private fun JSONArray.mapPlaylists(): List<Playlist> {
    val values = mutableListOf<Playlist>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { values += it.toPlaylist() }
    }
    return values
}

private fun JSONArray.mapTracks(): List<Track> {
    val values = mutableListOf<Track>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { values += it.toTrack() }
    }
    return values
}

private fun JSONArray.mapAlbums(): List<LibraryAlbum> {
    val values = mutableListOf<LibraryAlbum>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { values += it.toAlbum() }
    }
    return values
}

private fun List<LibraryArtist>.toArtistJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { artist ->
        array.put(
            JSONObject()
                .put("id", artist.id)
                .put("name", artist.name),
        )
    }
    return array
}

private fun JSONArray?.artistValues(): List<LibraryArtist> {
    if (this == null) {
        return emptyList()
    }

    val values = mutableListOf<LibraryArtist>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
        values += LibraryArtist(
            id = id,
            name = item.optString("name").takeIf { it.isNotBlank() } ?: id,
        )
    }
    return values.distinctBy { it.id }
}

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) {
        return emptyList()
    }

    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isNotBlank()) {
            values += value
        }
    }
    return values
}

private fun JSONObject?.stringMap(): Map<String, String> {
    if (this == null) {
        return emptyMap()
    }

    val values = linkedMapOf<String, String>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = optString(key)
        if (key.isNotBlank() && value.isNotBlank()) {
            values[key] = value
        }
    }
    return values
}
