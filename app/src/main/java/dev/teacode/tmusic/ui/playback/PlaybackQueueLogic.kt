package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.Track
import kotlin.random.Random

internal enum class PlaybackSourceType {
    Search,
    Recent,
    Playlist,
    Album,
}

internal data class PlaybackQueue(
    val playlistId: String? = null,
    val sourceType: PlaybackSourceType = PlaybackSourceType.Search,
    val sourceId: String? = null,
    val sourceTitle: String? = null,
    val tracks: List<Track> = emptyList(),
    val sourceTracks: List<Track> = tracks,
    val manualQueueFlags: List<Boolean> = emptyList(),
    val isShuffled: Boolean = false,
    val currentIndex: Int = -1,
) {
    val canSkip: Boolean = tracks.size > 1
}

internal data class GaplessPlaybackRequest(
    val queueKey: String,
    val trackIds: List<String>,
    val urls: List<String>,
    val startIndex: Int,
    val resumePositionMs: Long,
    val requestId: Long = System.nanoTime(),
    val queueIndices: List<Int> = trackIds.indices.toList(),
    val mediaIds: List<String> = trackIds.mapIndexed { index, trackId -> "$requestId:$index:$trackId" },
) {
    val signature: String = listOf(
        queueKey,
        startIndex.toString(),
        resumePositionMs.toString(),
        requestId.toString(),
    )
        .plus(trackIds)
        .plus(queueIndices.map(Int::toString))
        .plus(urls)
        .joinToString("|")
}

internal fun PlaybackQueue.naturalTracks(): List<Track> {
    return sourceTracks.takeIf { it.isNotEmpty() }
        ?: tracks.filterIndexed { index, _ -> !isManualQueueItem(index) }
            .takeIf { it.isNotEmpty() }
        ?: tracks
}

internal fun PlaybackQueue.isManualQueueItem(index: Int): Boolean {
    return manualQueueFlags.getOrNull(index) == true
}

internal fun PlaybackQueue.normalizedManualQueueFlags(): List<Boolean> {
    return tracks.indices.map { index -> isManualQueueItem(index) }
}

private fun List<Track>.removeFirstOccurrenceById(trackId: String): List<Track> {
    val index = indexOfFirst { it.id == trackId }
    if (index < 0) {
        return this
    }
    return filterIndexed { itemIndex, _ -> itemIndex != index }
}

private fun PlaybackQueue.manualTracksAfter(index: Int): List<Track> {
    val flags = normalizedManualQueueFlags()
    return tracks.drop(index + 1)
        .zip(flags.drop(index + 1))
        .filter { (_, manual) -> manual }
        .map { (track, _) -> track }
}

private fun PlaybackQueue.currentQueueIndex(currentTrack: Track?, fallbackTracks: List<Track> = tracks): Int {
    return currentIndex
        .takeIf { it in tracks.indices && tracks[it].id == currentTrack?.id }
        ?: currentTrack?.let { track ->
            tracks.indexOfFirst { it.id == track.id }
        }?.takeIf { it >= 0 }
        ?: currentIndex.coerceIn(0, fallbackTracks.lastIndex.coerceAtLeast(0))
}

internal fun shuffleQueue(
    queue: PlaybackQueue,
    currentTrack: Track?,
): PlaybackQueue {
    val naturalTracks = queue.naturalTracks()
    val currentQueueIndex = queue.currentQueueIndex(currentTrack, naturalTracks)
    val activeTrack = queue.tracks.getOrNull(currentQueueIndex)
        ?: currentTrack
        ?: naturalTracks.firstOrNull()
    if (naturalTracks.isEmpty() && activeTrack == null) {
        return queue.copy(
            tracks = emptyList(),
            sourceTracks = emptyList(),
            manualQueueFlags = emptyList(),
            isShuffled = false,
            currentIndex = -1,
        )
    }
    val currentIsManual = queue.isManualQueueItem(currentQueueIndex)
    val active = activeTrack ?: naturalTracks.first()
    val manualAfterCurrent = queue.manualTracksAfter(currentQueueIndex)
    val remainingNaturalTracks = if (currentIsManual) {
        naturalTracks
    } else {
        naturalTracks.removeFirstOccurrenceById(active.id)
    }
    if (remainingNaturalTracks.isEmpty() && manualAfterCurrent.isEmpty()) {
        return queue.copy(
            tracks = listOf(active),
            sourceTracks = naturalTracks,
            manualQueueFlags = listOf(currentIsManual),
            isShuffled = true,
            currentIndex = 0,
        )
    }
    val shuffledTracks = listOf(active) + manualAfterCurrent + remainingNaturalTracks.shuffled(Random)
    return queue.copy(
        tracks = shuffledTracks,
        sourceTracks = naturalTracks,
        manualQueueFlags = listOf(currentIsManual) +
            List(manualAfterCurrent.size) { true } +
            List(remainingNaturalTracks.size) { false },
        isShuffled = true,
        currentIndex = 0,
    )
}

internal fun restoreNaturalQueue(
    queue: PlaybackQueue,
    currentTrack: Track?,
): PlaybackQueue {
    val naturalTracks = queue.naturalTracks()
    val currentQueueIndex = queue.currentQueueIndex(currentTrack, naturalTracks)
    val activeTrack = queue.tracks.getOrNull(currentQueueIndex)
        ?: currentTrack
        ?: naturalTracks.firstOrNull()
    if (naturalTracks.isEmpty() && activeTrack == null) {
        return queue.copy(
            tracks = emptyList(),
            sourceTracks = emptyList(),
            manualQueueFlags = emptyList(),
            isShuffled = false,
            currentIndex = -1,
        )
    }
    val active = activeTrack ?: naturalTracks.first()
    val currentIsManual = queue.isManualQueueItem(currentQueueIndex)
    val manualAfterCurrent = queue.manualTracksAfter(currentQueueIndex)
    val naturalIndex = naturalTracks.indexOfFirst { it.id == active.id }
    val restoredTracks = if (!currentIsManual && naturalIndex >= 0) {
        naturalTracks.take(naturalIndex + 1) + manualAfterCurrent + naturalTracks.drop(naturalIndex + 1)
    } else {
        listOf(active) + manualAfterCurrent + naturalTracks
    }
    val restoredIndex = if (!currentIsManual && naturalIndex >= 0) naturalIndex else 0
    return queue.copy(
        tracks = restoredTracks,
        sourceTracks = naturalTracks,
        manualQueueFlags = List(restoredTracks.size) { index ->
            index in (restoredIndex + 1)..(restoredIndex + manualAfterCurrent.size) ||
                (index == restoredIndex && currentIsManual)
        },
        isShuffled = false,
        currentIndex = restoredIndex,
    )
}

internal fun prepareQueueForPlayback(
    queue: PlaybackQueue,
    track: Track,
    shuffleEnabled: Boolean,
): PlaybackQueue {
    return when {
        shuffleEnabled && queue.isShuffled -> queue
        shuffleEnabled -> shuffleQueue(queue, track)
        queue.isShuffled -> restoreNaturalQueue(queue, track)
        else -> queue.copy(
            sourceTracks = queue.naturalTracks(),
            manualQueueFlags = queue.normalizedManualQueueFlags(),
            isShuffled = false,
        )
    }
}
