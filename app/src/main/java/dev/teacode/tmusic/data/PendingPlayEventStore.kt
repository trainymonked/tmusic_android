package dev.teacode.tmusic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingPlayEvent(
    val clientEventId: String,
    val trackId: String,
    val playedAt: String,
    val durationPlayedMs: Long,
    val completed: Boolean,
    val source: String,
)

class PendingPlayEventStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun events(): List<PendingPlayEvent> {
        val raw = preferences.getString(KEY_EVENTS, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val event = item.toPendingPlayEvent() ?: continue
                    add(event)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun count(): Int = events().size

    fun append(event: PendingPlayEvent) {
        val nextEvents = (events().filterNot { it.clientEventId == event.clientEventId } + event)
        save(nextEvents)
    }

    fun remove(clientEventIds: Set<String>) {
        if (clientEventIds.isEmpty()) {
            return
        }
        save(events().filterNot { it.clientEventId in clientEventIds })
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun save(events: List<PendingPlayEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("clientEventId", event.clientEventId)
                    .put("trackId", event.trackId)
                    .put("playedAt", event.playedAt)
                    .put("durationPlayedMs", event.durationPlayedMs)
                    .put("completed", event.completed)
                    .put("source", event.source),
            )
        }
        preferences.edit()
            .putString(KEY_EVENTS, array.toString())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "tmusic_pending_play_events"
        const val KEY_EVENTS = "events"
    }
}

private fun JSONObject.toPendingPlayEvent(): PendingPlayEvent? {
    val clientEventId = optString("clientEventId").takeIf { it.isNotBlank() } ?: return null
    val trackId = optString("trackId").takeIf { it.isNotBlank() } ?: return null
    val playedAt = optString("playedAt").takeIf { it.isNotBlank() } ?: return null

    return PendingPlayEvent(
        clientEventId = clientEventId,
        trackId = trackId,
        playedAt = playedAt,
        durationPlayedMs = optLong("durationPlayedMs", 0L).coerceAtLeast(0L),
        completed = optBoolean("completed", true),
        source = optString("source").takeIf { it.isNotBlank() } ?: "STREAM",
    )
}
