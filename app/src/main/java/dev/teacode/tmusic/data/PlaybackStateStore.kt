package dev.teacode.tmusic.data

import android.content.Context
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Track
import org.json.JSONArray
import org.json.JSONObject

data class SavedPlaybackState(
    val playlistId: String?,
    val sourceType: String? = null,
    val sourceId: String? = null,
    val sourceTitle: String? = null,
    val queueTrackIds: List<String> = emptyList(),
    val sourceTrackIds: List<String> = emptyList(),
    val manualQueueFlags: List<Boolean> = emptyList(),
    val queueTracks: List<Track> = emptyList(),
    val sourceTracks: List<Track> = emptyList(),
    val isShuffled: Boolean = false,
    val currentIndex: Int = -1,
    val trackId: String?,
    val track: Track? = null,
    val positionMs: Long,
    val wasPlaying: Boolean,
    val scrobbleClientEventId: String? = null,
    val scrobblePlayedAt: String? = null,
    val scrobbleDurationPlayedMs: Long = 0L,
)

class PlaybackStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PLAYBACK_STATE_NAME,
        Context.MODE_PRIVATE,
    )

    fun state(): SavedPlaybackState? {
        val trackId = preferences.getString(KEY_TRACK_ID, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return SavedPlaybackState(
            playlistId = preferences.getString(KEY_PLAYLIST_ID, null)?.takeIf { it.isNotBlank() },
            sourceType = preferences.getString(KEY_SOURCE_TYPE, null)?.takeIf { it.isNotBlank() },
            sourceId = preferences.getString(KEY_SOURCE_ID, null)?.takeIf { it.isNotBlank() },
            sourceTitle = preferences.getString(KEY_SOURCE_TITLE, null)?.takeIf { it.isNotBlank() },
            queueTrackIds = preferences.getString(KEY_QUEUE_TRACK_IDS, null).toIdList(),
            sourceTrackIds = preferences.getString(KEY_SOURCE_TRACK_IDS, null).toIdList(),
            manualQueueFlags = preferences.getString(KEY_MANUAL_QUEUE_FLAGS, null).toBooleanList(),
            queueTracks = preferences.getString(KEY_QUEUE_TRACKS_JSON, null).toTrackList(),
            sourceTracks = preferences.getString(KEY_SOURCE_TRACKS_JSON, null).toTrackList(),
            isShuffled = preferences.getBoolean(KEY_IS_SHUFFLED, false),
            currentIndex = preferences.getInt(KEY_CURRENT_INDEX, -1),
            trackId = trackId,
            track = readTrack(trackId),
            positionMs = preferences.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
            wasPlaying = preferences.getBoolean(KEY_WAS_PLAYING, false),
            scrobbleClientEventId = preferences.getString(KEY_SCROBBLE_CLIENT_EVENT_ID, null)
                ?.takeIf { it.isNotBlank() },
            scrobblePlayedAt = preferences.getString(KEY_SCROBBLE_PLAYED_AT, null)
                ?.takeIf { it.isNotBlank() },
            scrobbleDurationPlayedMs = preferences
                .getLong(KEY_SCROBBLE_DURATION_PLAYED_MS, 0L)
                .coerceAtLeast(0L),
        )
    }

    fun save(state: SavedPlaybackState) {
        preferences.edit()
            .putString(KEY_PLAYLIST_ID, state.playlistId)
            .putString(KEY_SOURCE_TYPE, state.sourceType)
            .putString(KEY_SOURCE_ID, state.sourceId)
            .putString(KEY_SOURCE_TITLE, state.sourceTitle)
            .putString(KEY_QUEUE_TRACK_IDS, state.queueTrackIds.joinToString("\n"))
            .putString(KEY_SOURCE_TRACK_IDS, state.sourceTrackIds.joinToString("\n"))
            .putString(KEY_MANUAL_QUEUE_FLAGS, state.manualQueueFlags.joinToString("\n") { if (it) "1" else "0" })
            .putString(KEY_QUEUE_TRACKS_JSON, state.queueTracks.toTrackJson())
            .putString(KEY_SOURCE_TRACKS_JSON, state.sourceTracks.toTrackJson())
            .putBoolean(KEY_IS_SHUFFLED, state.isShuffled)
            .putInt(KEY_CURRENT_INDEX, state.currentIndex)
            .putString(KEY_TRACK_ID, state.trackId)
            .putString(KEY_TRACK_TITLE, state.track?.title)
            .putString(KEY_TRACK_ARTIST, state.track?.artist)
            .putString(KEY_TRACK_ALBUM, state.track?.album)
            .putInt(KEY_TRACK_DURATION_SECONDS, state.track?.durationSeconds ?: 0)
            .putString(KEY_TRACK_SERVER_PATH, state.track?.serverPath)
            .putLong(KEY_TRACK_ACCENT_COLOR, state.track?.accentColor ?: 0xFF444444)
            .putInt(KEY_TRACK_PLAY_COUNT, state.track?.playCount ?: 0)
            .putString(KEY_TRACK_ARTIST_ID, state.track?.artistId)
            .putString(KEY_TRACK_ARTIST_IDS, state.track?.artistIds?.joinToString("\n"))
            .putString(KEY_TRACK_ALBUM_ID, state.track?.albumId)
            .putString(KEY_TRACK_ALBUM_ARTIST, state.track?.albumArtist)
            .putString(KEY_TRACK_ALBUM_ARTIST_ID, state.track?.albumArtistId)
            .putInt(KEY_TRACK_NUMBER, state.track?.trackNumber ?: -1)
            .putInt(KEY_TRACK_DISC_NUMBER, state.track?.discNumber ?: -1)
            .putInt(KEY_TRACK_RELEASE_YEAR, state.track?.releaseYear ?: -1)
            .putString(KEY_TRACK_GENRE, state.track?.genre)
            .putBoolean(KEY_TRACK_IS_LIKED, state.track?.isLiked == true)
            .putBoolean(KEY_TRACK_HAS_IS_LIKED, state.track?.isLiked != null)
            .putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_WAS_PLAYING, state.wasPlaying)
            .putString(KEY_SCROBBLE_CLIENT_EVENT_ID, state.scrobbleClientEventId)
            .putString(KEY_SCROBBLE_PLAYED_AT, state.scrobblePlayedAt)
            .putLong(KEY_SCROBBLE_DURATION_PLAYED_MS, state.scrobbleDurationPlayedMs.coerceAtLeast(0L))
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun readTrack(trackId: String): Track? {
        val title = preferences.getString(KEY_TRACK_TITLE, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return Track(
            id = trackId,
            title = title,
            artist = preferences.getString(KEY_TRACK_ARTIST, null)?.takeIf { it.isNotBlank() }
                ?: "Unknown artist",
            album = preferences.getString(KEY_TRACK_ALBUM, null)?.takeIf { it.isNotBlank() }
                ?: "Unknown album",
            durationSeconds = preferences.getInt(KEY_TRACK_DURATION_SECONDS, 0).coerceAtLeast(0),
            serverPath = preferences.getString(KEY_TRACK_SERVER_PATH, null).orEmpty(),
            accentColor = preferences.getLong(KEY_TRACK_ACCENT_COLOR, 0xFF444444),
            downloadState = DownloadState.NotDownloaded,
            playCount = preferences.getInt(KEY_TRACK_PLAY_COUNT, 0).coerceAtLeast(0),
            artistId = preferences.getString(KEY_TRACK_ARTIST_ID, null)?.takeIf { it.isNotBlank() },
            artistIds = preferences.getString(KEY_TRACK_ARTIST_IDS, null).toIdList(),
            albumId = preferences.getString(KEY_TRACK_ALBUM_ID, null)?.takeIf { it.isNotBlank() },
            albumArtist = preferences.getString(KEY_TRACK_ALBUM_ARTIST, null)?.takeIf { it.isNotBlank() },
            albumArtistId = preferences.getString(KEY_TRACK_ALBUM_ARTIST_ID, null)?.takeIf { it.isNotBlank() },
            trackNumber = preferences.getInt(KEY_TRACK_NUMBER, -1).takeIf { it >= 0 },
            discNumber = preferences.getInt(KEY_TRACK_DISC_NUMBER, -1).takeIf { it >= 0 },
            releaseYear = preferences.getInt(KEY_TRACK_RELEASE_YEAR, -1).takeIf { it > 0 },
            genre = preferences.getString(KEY_TRACK_GENRE, null)?.takeIf { it.isNotBlank() },
            isLiked = if (preferences.getBoolean(KEY_TRACK_HAS_IS_LIKED, false)) {
                preferences.getBoolean(KEY_TRACK_IS_LIKED, false)
            } else {
                null
            },
        )
    }

    private fun String?.toIdList(): List<String> {
        return orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun String?.toBooleanList(): List<Boolean> {
        return orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it == "1" || it.equals("true", ignoreCase = true) }
            .toList()
    }

    private fun List<Track>.toTrackJson(): String {
        val array = JSONArray()
        forEach { track ->
            array.put(track.toJson())
        }
        return array.toString()
    }

    private fun String?.toTrackList(): List<Track> {
        if (isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(this)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    item.toTrack()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
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
            .put("albumId", albumId)
            .put("albumArtist", albumArtist)
            .put("albumArtistId", albumArtistId)
            .put("trackNumber", trackNumber)
            .put("discNumber", discNumber)
            .put("releaseYear", releaseYear)
            .put("genre", genre)
            .put("isLiked", isLiked)
    }

    private fun JSONObject.toTrack(): Track? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() } ?: return null
        return Track(
            id = id,
            title = title,
            artist = optString("artist").takeIf { it.isNotBlank() } ?: "Unknown artist",
            album = optString("album").takeIf { it.isNotBlank() } ?: "Unknown album",
            durationSeconds = optInt("durationSeconds", 0).coerceAtLeast(0),
            serverPath = optString("serverPath"),
            accentColor = optLong("accentColor", 0xFF444444),
            downloadState = runCatching {
                DownloadState.valueOf(optString("downloadState", DownloadState.NotDownloaded.name))
            }.getOrDefault(DownloadState.NotDownloaded),
            playCount = optInt("playCount", 0).coerceAtLeast(0),
            artistId = optString("artistId").takeIf { it.isNotBlank() },
            artistIds = optJSONArray("artistIds").stringValues(),
            albumId = optString("albumId").takeIf { it.isNotBlank() },
            albumArtist = optString("albumArtist").takeIf { it.isNotBlank() },
            albumArtistId = optString("albumArtistId").takeIf { it.isNotBlank() },
            trackNumber = optInt("trackNumber", -1).takeIf { it >= 0 },
            discNumber = optInt("discNumber", -1).takeIf { it >= 0 },
            releaseYear = optInt("releaseYear", -1).takeIf { it > 0 },
            genre = optString("genre").takeIf { it.isNotBlank() },
            isLiked = if (has("isLiked") && !isNull("isLiked")) optBoolean("isLiked") else null,
        )
    }

    private companion object {
        const val PLAYBACK_STATE_NAME = "tmusic_playback_state"
        const val KEY_PLAYLIST_ID = "playlist_id"
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_SOURCE_TITLE = "source_title"
        const val KEY_QUEUE_TRACK_IDS = "queue_track_ids"
        const val KEY_SOURCE_TRACK_IDS = "source_track_ids"
        const val KEY_MANUAL_QUEUE_FLAGS = "manual_queue_flags"
        const val KEY_QUEUE_TRACKS_JSON = "queue_tracks_json"
        const val KEY_SOURCE_TRACKS_JSON = "source_tracks_json"
        const val KEY_IS_SHUFFLED = "is_shuffled"
        const val KEY_CURRENT_INDEX = "current_index"
        const val KEY_TRACK_ID = "track_id"
        const val KEY_TRACK_TITLE = "track_title"
        const val KEY_TRACK_ARTIST = "track_artist"
        const val KEY_TRACK_ALBUM = "track_album"
        const val KEY_TRACK_DURATION_SECONDS = "track_duration_seconds"
        const val KEY_TRACK_SERVER_PATH = "track_server_path"
        const val KEY_TRACK_ACCENT_COLOR = "track_accent_color"
        const val KEY_TRACK_PLAY_COUNT = "track_play_count"
        const val KEY_TRACK_ARTIST_ID = "track_artist_id"
        const val KEY_TRACK_ARTIST_IDS = "track_artist_ids"
        const val KEY_TRACK_ALBUM_ID = "track_album_id"
        const val KEY_TRACK_ALBUM_ARTIST = "track_album_artist"
        const val KEY_TRACK_ALBUM_ARTIST_ID = "track_album_artist_id"
        const val KEY_TRACK_NUMBER = "track_number"
        const val KEY_TRACK_DISC_NUMBER = "track_disc_number"
        const val KEY_TRACK_RELEASE_YEAR = "track_release_year"
        const val KEY_TRACK_GENRE = "track_genre"
        const val KEY_TRACK_IS_LIKED = "track_is_liked"
        const val KEY_TRACK_HAS_IS_LIKED = "track_has_is_liked"
        const val KEY_POSITION_MS = "position_ms"
        const val KEY_WAS_PLAYING = "was_playing"
        const val KEY_SCROBBLE_CLIENT_EVENT_ID = "scrobble_client_event_id"
        const val KEY_SCROBBLE_PLAYED_AT = "scrobble_played_at"
        const val KEY_SCROBBLE_DURATION_PLAYED_MS = "scrobble_duration_played_ms"
    }
}

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) {
        return emptyList()
    }
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        optString(index).takeIf { it.isNotBlank() }?.let(values::add)
    }
    return values
}
