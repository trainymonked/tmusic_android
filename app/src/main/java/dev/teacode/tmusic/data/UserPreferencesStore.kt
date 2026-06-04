package dev.teacode.tmusic.data

import android.content.Context
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.RecentLibraryItemType
import dev.teacode.tmusic.domain.ScrobbleState
import org.json.JSONArray
import org.json.JSONObject

class UserPreferencesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        USER_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun offlineOnly(): Boolean {
        return preferences.getBoolean(KEY_OFFLINE_ONLY, false)
    }

    fun setOfflineOnly(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_OFFLINE_ONLY, enabled)
            .apply()
    }

    fun useLocalBackend(): Boolean {
        return preferences.getBoolean(KEY_USE_LOCAL_BACKEND, false)
    }

    fun setUseLocalBackend(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_USE_LOCAL_BACKEND, enabled)
            .apply()
    }

    fun scrobblingPaused(): Boolean {
        return preferences.getBoolean(KEY_SCROBBLING_PAUSED, false)
    }

    fun setScrobblingPaused(paused: Boolean) {
        preferences.edit()
            .putBoolean(KEY_SCROBBLING_PAUSED, paused)
            .apply()
    }

    fun shuffleEnabled(): Boolean {
        return preferences.getBoolean(KEY_SHUFFLE_ENABLED, false)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_SHUFFLE_ENABLED, enabled)
            .apply()
    }

    fun playbackRepeatMode(): String {
        return preferences.getString(KEY_PLAYBACK_REPEAT_MODE, "None") ?: "None"
    }

    fun setPlaybackRepeatMode(mode: String) {
        preferences.edit()
            .putString(KEY_PLAYBACK_REPEAT_MODE, mode)
            .apply()
    }

    fun showLyrics(): Boolean {
        return preferences.getBoolean(KEY_SHOW_LYRICS, true)
    }

    fun setShowLyrics(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_SHOW_LYRICS, enabled)
            .apply()
    }

    fun downloadUsingCellular(): Boolean {
        return preferences.getBoolean(KEY_DOWNLOAD_USING_CELLULAR, false)
    }

    fun setDownloadUsingCellular(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_DOWNLOAD_USING_CELLULAR, enabled)
            .apply()
    }

    fun crossfadeSeconds(): Int {
        return preferences.getInt(KEY_CROSSFADE_SECONDS, 0).coerceIn(0, MAX_CROSSFADE_SECONDS)
    }

    fun setCrossfadeSeconds(seconds: Int) {
        preferences.edit()
            .putInt(KEY_CROSSFADE_SECONDS, seconds.coerceIn(0, MAX_CROSSFADE_SECONDS))
            .apply()
    }

    fun offlineAlbumIds(): Set<String> {
        return preferences.getStringSet(KEY_OFFLINE_ALBUM_IDS, emptySet()).orEmpty()
    }

    fun addOfflineAlbumId(albumId: String) {
        val normalizedAlbumId = albumId.trim()
        if (normalizedAlbumId.isBlank()) {
            return
        }
        preferences.edit()
            .putStringSet(KEY_OFFLINE_ALBUM_IDS, offlineAlbumIds() + normalizedAlbumId)
            .apply()
    }

    fun removeOfflineAlbumId(albumId: String) {
        preferences.edit()
            .putStringSet(KEY_OFFLINE_ALBUM_IDS, offlineAlbumIds() - albumId)
            .apply()
    }

    fun clearOfflineAlbumIds() {
        preferences.edit()
            .remove(KEY_OFFLINE_ALBUM_IDS)
            .apply()
    }

    fun lastFmConnection(): LastFmConnection? {
        val username = preferences.getString(KEY_LASTFM_USERNAME, null)?.meaningfulStringOrNull()
        val state = preferences.getString(KEY_LASTFM_STATE, null)
            ?.let { value -> runCatching { ScrobbleState.valueOf(value) }.getOrNull() }
            ?: return null
        if (state == ScrobbleState.Ready && username == null) {
            return null
        }
        return LastFmConnection(
            username = username,
            state = state,
            pendingScrobbles = preferences.getInt(KEY_LASTFM_PENDING, 0).coerceAtLeast(0),
        )
    }

    fun setLastFmConnection(connection: LastFmConnection) {
        preferences.edit()
            .putString(KEY_LASTFM_USERNAME, connection.username)
            .putString(KEY_LASTFM_STATE, connection.state.name)
            .putInt(KEY_LASTFM_PENDING, connection.pendingScrobbles.coerceAtLeast(0))
            .apply()
    }

    fun clearLastFmConnection() {
        preferences.edit()
            .remove(KEY_LASTFM_USERNAME)
            .remove(KEY_LASTFM_STATE)
            .remove(KEY_LASTFM_PENDING)
            .apply()
    }

    fun recentSearches(): List<String> {
        return preferences.getString(KEY_RECENT_SEARCHES, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    fun addRecentSearch(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) {
            return
        }
        val searches = (listOf(normalizedQuery) + recentSearches().filterNot {
            it.equals(normalizedQuery, ignoreCase = true)
        }).take(MAX_RECENT_SEARCHES)
        preferences.edit()
            .putString(KEY_RECENT_SEARCHES, searches.joinToString("\n"))
            .apply()
    }

    fun recentLibraryItems(): List<RecentLibraryItem> {
        val raw = preferences.getString(KEY_RECENT_LIBRARY_ITEMS, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = runCatching {
                        RecentLibraryItemType.valueOf(item.optString("type"))
                    }.getOrNull() ?: continue
                    val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                    add(
                        RecentLibraryItem(
                            type = type,
                            title = title,
                            subtitle = item.optString("subtitle").takeIf { it.isNotBlank() },
                            id = item.optString("id").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addRecentLibraryItem(item: RecentLibraryItem) {
        if (item.title.isBlank()) {
            return
        }

        val items = (listOf(item) + recentLibraryItems().filterNot { existing ->
            existing.type == item.type &&
                existing.id == item.id &&
                existing.title.equals(item.title, ignoreCase = true)
        }).take(MAX_RECENT_SEARCHES)
        val array = JSONArray()
        items.forEach { recentItem ->
            array.put(
                JSONObject()
                    .put("type", recentItem.type.name)
                    .put("title", recentItem.title)
                    .put("subtitle", recentItem.subtitle)
                    .put("id", recentItem.id),
            )
        }
        preferences.edit()
            .putString(KEY_RECENT_LIBRARY_ITEMS, array.toString())
            .apply()
    }

    fun clearRecentLibraryItems() {
        preferences.edit()
            .remove(KEY_RECENT_LIBRARY_ITEMS)
            .apply()
    }

    private companion object {
        const val USER_PREFERENCES_NAME = "tmusic_user_preferences"
        const val KEY_OFFLINE_ONLY = "offline_only"
        const val KEY_USE_LOCAL_BACKEND = "use_local_backend"
        const val KEY_SCROBBLING_PAUSED = "scrobbling_paused"
        const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        const val KEY_PLAYBACK_REPEAT_MODE = "playback_repeat_mode"
        const val KEY_SHOW_LYRICS = "show_lyrics"
        const val KEY_DOWNLOAD_USING_CELLULAR = "download_using_cellular"
        const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"
        const val KEY_OFFLINE_ALBUM_IDS = "offline_album_ids"
        const val KEY_LASTFM_USERNAME = "lastfm_username"
        const val KEY_LASTFM_STATE = "lastfm_state"
        const val KEY_LASTFM_PENDING = "lastfm_pending"
        const val KEY_RECENT_SEARCHES = "recent_searches"
        const val KEY_RECENT_LIBRARY_ITEMS = "recent_library_items"
        const val MAX_RECENT_SEARCHES = 8
        const val MAX_CROSSFADE_SECONDS = 12
    }
}

private fun String.meaningfulStringOrNull(): String? {
    val normalized = trim()
    return normalized.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
}
