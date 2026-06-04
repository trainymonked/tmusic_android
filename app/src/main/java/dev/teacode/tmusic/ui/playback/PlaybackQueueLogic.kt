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
    return sourceTracks.takeIf { it.isNotEmpty() } ?: tracks
}

internal fun shuffleQueue(
    queue: PlaybackQueue,
    currentTrack: Track?,
): PlaybackQueue {
    val naturalTracks = queue.naturalTracks()
    if (naturalTracks.isEmpty()) {
        return queue.copy(
            tracks = emptyList(),
            sourceTracks = emptyList(),
            isShuffled = false,
            currentIndex = -1,
        )
    }
    if (naturalTracks.size <= 1) {
        val currentIndex = queue.currentIndex
            .takeIf { it in naturalTracks.indices && naturalTracks[it].id == currentTrack?.id }
            ?: naturalTracks.indexOfFirst { it.id == currentTrack?.id }.coerceAtLeast(0)
        return queue.copy(
            tracks = naturalTracks,
            sourceTracks = naturalTracks,
            isShuffled = true,
            currentIndex = currentIndex,
        )
    }
    val activeIndex = queue.currentIndex
        .takeIf { it in naturalTracks.indices && naturalTracks[it].id == currentTrack?.id }
        ?: currentTrack?.let { track ->
            naturalTracks.indexOfFirst { it.id == track.id }
        }?.takeIf { it >= 0 }
        ?: queue.currentIndex.coerceIn(0, naturalTracks.lastIndex)
    val activeTrack = naturalTracks[activeIndex]
    val shuffledTracks = listOf(activeTrack) +
        naturalTracks.filterIndexed { index, _ -> index != activeIndex }.shuffled(Random)
    return queue.copy(
        tracks = shuffledTracks,
        sourceTracks = naturalTracks,
        isShuffled = true,
        currentIndex = 0,
    )
}

internal fun restoreNaturalQueue(
    queue: PlaybackQueue,
    currentTrack: Track?,
): PlaybackQueue {
    val naturalTracks = queue.naturalTracks()
    if (naturalTracks.isEmpty()) {
        return queue.copy(
            tracks = emptyList(),
            sourceTracks = emptyList(),
            isShuffled = false,
            currentIndex = -1,
        )
    }
    val restoredIndex = queue.currentIndex
        .takeIf { it in naturalTracks.indices && naturalTracks[it].id == currentTrack?.id }
        ?: currentTrack?.let { track ->
            naturalTracks.indexOfFirst { it.id == track.id }
        }?.takeIf { it >= 0 }
        ?: queue.currentIndex.coerceIn(0, naturalTracks.lastIndex)
    return queue.copy(
        tracks = naturalTracks,
        sourceTracks = naturalTracks,
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
        else -> queue.copy(sourceTracks = queue.naturalTracks(), isShuffled = false)
    }
}
