package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.PendingPlayEvent
import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Track
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal fun newActivePlayEvent(track: Track): ActivePlayEvent {
    return ActivePlayEvent(
        clientEventId = UUID.randomUUID().toString(),
        trackId = track.id,
        playedAt = Instant.now().toString(),
        durationPlayedMs = 0L,
    )
}

internal fun scrobbleThresholdMs(track: Track): Long {
    val durationMs = track.durationSeconds.toLong().coerceAtLeast(0L) * 1000L
    if (durationMs == 0L) {
        return 30_000L
    }
    return minOf(durationMs / 2L, 240_000L)
        .coerceAtLeast(30_000L)
        .coerceAtMost(durationMs)
}

internal fun shouldCompletePlayEvent(
    activeEvent: ActivePlayEvent,
    track: Track,
): Boolean {
    return activeEvent.trackId == track.id &&
        activeEvent.durationPlayedMs >= scrobbleThresholdMs(track)
}

internal fun ActivePlayEvent.toPendingPlayEvent(): PendingPlayEvent {
    return PendingPlayEvent(
        clientEventId = clientEventId,
        trackId = trackId,
        playedAt = playedAt,
        durationPlayedMs = durationPlayedMs.coerceAtLeast(0L),
        completed = true,
        source = "STREAM",
    )
}

internal suspend fun syncPendingPlayEventBatches(
    store: PendingPlayEventStore,
    musicRepository: RemoteMusicRepository,
    onBatchSynced: (Set<String>) -> Unit,
): Throwable? {
    store.events()
        .chunked(PENDING_PLAY_EVENT_SYNC_BATCH_SIZE)
        .forEach { batch ->
            val syncedIds = try {
                musicRepository.syncPlayEvents(batch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return error
            }
            if (syncedIds.isNotEmpty()) {
                store.remove(syncedIds)
                onBatchSynced(syncedIds)
            }
        }
    return null
}
