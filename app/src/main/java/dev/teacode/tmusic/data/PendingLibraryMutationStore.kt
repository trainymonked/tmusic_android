package dev.teacode.tmusic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingLibraryMutation(
    val clientMutationId: String,
    val type: String,
    val createdAtEpochMs: Long,
    val payload: JSONObject,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("clientMutationId", clientMutationId)
            .put("type", type)
            .put("createdAtEpochMs", createdAtEpochMs)
            .put("payload", payload)
    }
}

class PendingLibraryMutationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PENDING_LIBRARY_MUTATIONS_NAME,
        Context.MODE_PRIVATE,
    )

    fun all(): List<PendingLibraryMutation> {
        return preferences.getString(KEY_MUTATIONS, null)
            ?.let { json -> runCatching { JSONArray(json).mapMutations() }.getOrNull() }
            .orEmpty()
    }

    fun count(): Int {
        return all().size
    }

    /**
     * Local favorite changes take precedence over any cached or in-flight
     * library payload until the corresponding mutation is synchronized.
     */
    fun pendingFavoriteStates(): Map<String, Boolean> {
        return all().pendingFavoriteStates()
    }

    fun append(type: String, payload: JSONObject): PendingLibraryMutation {
        val mutation = PendingLibraryMutation(
            clientMutationId = UUID.randomUUID().toString(),
            type = type,
            createdAtEpochMs = System.currentTimeMillis(),
            payload = payload,
        )
        save(all() + mutation)
        return mutation
    }

    fun remove(clientMutationIds: Set<String>) {
        if (clientMutationIds.isEmpty()) {
            return
        }
        save(all().filterNot { it.clientMutationId in clientMutationIds })
    }

    fun removeSyncedPreservingDependencies(clientMutationIds: Set<String>) {
        if (clientMutationIds.isEmpty()) {
            return
        }
        val mutations = all()
        val syncedMutations = mutations.filter { it.clientMutationId in clientMutationIds }
        val unsyncedMutations = mutations.filterNot { it.clientMutationId in clientMutationIds }
        val requiredClientPlaylistIds = unsyncedMutations
            .mapNotNull { it.payload.optString("playlistId").takeIf(String::isLocalPlaylistId) }
            .toSet()
        val requiredClientPlaylistTrackIds = unsyncedMutations
            .mapNotNull { it.payload.optString("playlistTrackId").takeIf(String::isLocalPlaylistTrackId) }
            .toSet()
        val syncedDependencies = syncedMutations.filter { mutation ->
            when (mutation.type) {
                "playlist.create" -> mutation.payload
                    .optString("clientPlaylistId")
                    .takeIf(String::isLocalPlaylistId) in requiredClientPlaylistIds
                "playlist.track.add" -> mutation.payload
                    .optString("clientPlaylistTrackId")
                    .takeIf(String::isLocalPlaylistTrackId) in requiredClientPlaylistTrackIds
                else -> false
            }
        }
        save(unsyncedMutations + syncedDependencies)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun save(mutations: List<PendingLibraryMutation>) {
        preferences.edit()
            .putString(KEY_MUTATIONS, JSONArray().apply {
                mutations.forEach { mutation -> put(mutation.toJson()) }
            }.toString())
            .apply()
    }

    private companion object {
        const val PENDING_LIBRARY_MUTATIONS_NAME = "tmusic_pending_library_mutations"
        const val KEY_MUTATIONS = "mutations"
    }
}

internal fun List<PendingLibraryMutation>.pendingFavoriteStates(): Map<String, Boolean> {
    val states = linkedMapOf<String, Boolean>()
    forEach { mutation ->
        if (mutation.type != "favorite.set") {
            return@forEach
        }
        val trackId = mutation.payload.opt("trackId") as? String
        val liked = mutation.payload.opt("liked") as? Boolean
        if (!trackId.isNullOrBlank() && liked != null) {
            states[trackId] = liked
        }
    }
    return states
}

private fun String?.isLocalPlaylistId(): Boolean {
    return this?.startsWith("local-playlist:") == true
}

private fun String?.isLocalPlaylistTrackId(): Boolean {
    return this?.startsWith("local-playlist-track:") == true
}

private fun JSONArray.mapMutations(): List<PendingLibraryMutation> {
    val values = mutableListOf<PendingLibraryMutation>()
    for (index in 0 until length()) {
        val json = optJSONObject(index) ?: continue
        val clientMutationId = json.optString("clientMutationId")
        val type = json.optString("type")
        if (clientMutationId.isBlank() || type.isBlank()) {
            continue
        }
        values += PendingLibraryMutation(
            clientMutationId = clientMutationId,
            type = type,
            createdAtEpochMs = json.optLong("createdAtEpochMs", 0L),
            payload = json.optJSONObject("payload") ?: JSONObject(),
        )
    }
    return values
}
