package dev.teacode.tmusic.data

import android.content.Context
import dev.teacode.tmusic.domain.TrackLyrics
import org.json.JSONObject

class OfflineLyricsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        OFFLINE_LYRICS_NAME,
        Context.MODE_PRIVATE,
    )

    fun lyrics(trackId: String): TrackLyrics? {
        val json = preferences.getString(trackId, null) ?: return null
        return runCatching { JSONObject(json).toTrackLyrics() }.getOrNull()
    }

    fun save(trackId: String, lyrics: TrackLyrics) {
        preferences.edit()
            .putString(trackId, lyrics.toJson().toString())
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun clearExcept(retainedTrackIds: Set<String>) {
        val editor = preferences.edit()
        preferences.all.keys.forEach { trackId ->
            if (trackId !in retainedTrackIds) {
                editor.remove(trackId)
            }
        }
        editor.apply()
    }

    fun sizeBytes(): Long {
        return preferences.all.values
            .filterIsInstance<String>()
            .sumOf { it.toByteArray().size.toLong() }
    }

    private companion object {
        const val OFFLINE_LYRICS_NAME = "tmusic_offline_lyrics"
    }
}

private fun TrackLyrics.toJson(): JSONObject {
    return JSONObject()
        .put("plainLyrics", plainLyrics)
        .put("syncedLyrics", syncedLyrics)
        .put("instrumental", instrumental)
}

private fun JSONObject.toTrackLyrics(): TrackLyrics {
    return TrackLyrics(
        plainLyrics = optString("plainLyrics").takeIf { it.isNotBlank() },
        syncedLyrics = optString("syncedLyrics").takeIf { it.isNotBlank() },
        instrumental = optBoolean("instrumental", false),
    )
}
