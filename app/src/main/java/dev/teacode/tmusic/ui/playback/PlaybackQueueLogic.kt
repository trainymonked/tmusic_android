package dev.teacode.tmusic.ui

import androidx.media3.common.Player
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
    preserveExistingShuffle: Boolean = false,
): PlaybackQueue {
    return when {
        preserveExistingShuffle && queue.isShuffled -> queue
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

internal data class ManualQueueInsertResult(
    val queue: PlaybackQueue,
    val insertionIndex: Int,
    val anchorTrackId: String?,
)

internal fun PlaybackQueue.withManualTrackInsertedAfterCurrent(
    track: Track,
    currentTrack: Track?,
    insertionAnchorTrackId: String?,
    insertionCursor: Int?,
): ManualQueueInsertResult {
    val baseQueue = takeIf { it.tracks.isNotEmpty() }
        ?: currentTrack?.let {
            PlaybackQueue(
                sourceType = PlaybackSourceType.Search,
                sourceId = "Queue",
                sourceTitle = "Queue",
                tracks = listOf(it),
                sourceTracks = listOf(it),
                currentIndex = 0,
            )
        }
        ?: PlaybackQueue(
            sourceType = PlaybackSourceType.Search,
            sourceId = "Queue",
            sourceTitle = "Queue",
            tracks = emptyList(),
            sourceTracks = emptyList(),
            currentIndex = -1,
        )
    val currentIndex = baseQueue.currentIndex
        .takeIf { it in baseQueue.tracks.indices }
        ?: currentTrack?.id
            ?.let { trackId -> baseQueue.tracks.indexOfFirst { it.id == trackId } }
            ?.takeIf { it >= 0 }
        ?: baseQueue.currentIndex.coerceIn(0, baseQueue.tracks.lastIndex.coerceAtLeast(0))
    val insertionBase = if (insertionAnchorTrackId == currentTrack?.id) {
        insertionCursor?.coerceIn(currentIndex, baseQueue.tracks.lastIndex.coerceAtLeast(currentIndex))
            ?: currentIndex
    } else {
        currentIndex
    }
    val insertionIndex = (insertionBase + 1).coerceIn(0, baseQueue.tracks.size)
    val nextTracks = baseQueue.tracks.toMutableList().apply {
        add(insertionIndex, track)
    }
    val nextManualFlags = baseQueue.normalizedManualQueueFlags().toMutableList().apply {
        add(insertionIndex, true)
    }
    return ManualQueueInsertResult(
        queue = baseQueue.copy(
            tracks = nextTracks,
            sourceTracks = baseQueue.naturalTracks(),
            manualQueueFlags = nextManualFlags,
            currentIndex = when {
                baseQueue.currentIndex < 0 -> 0
                insertionIndex <= baseQueue.currentIndex -> baseQueue.currentIndex + 1
                else -> baseQueue.currentIndex
            },
            isShuffled = baseQueue.isShuffled,
        ),
        insertionIndex = insertionIndex,
        anchorTrackId = currentTrack?.id,
    )
}

internal data class QueueRemovalResult(
    val queue: PlaybackQueue,
    val nextQueueInsertionCursor: Int?,
    val nextTrackToPlay: Track?,
    val nextTrackIndex: Int,
)

internal fun PlaybackQueue.withTrackRemovedAt(index: Int, queueInsertionCursor: Int?): QueueRemovalResult? {
    if (index !in tracks.indices) {
        return null
    }
    val removedTrack = tracks[index]
    val removedManual = isManualQueueItem(index)
    val nextTracks = tracks.filterIndexed { itemIndex, _ -> itemIndex != index }
    val nextManualFlags = normalizedManualQueueFlags()
        .filterIndexed { itemIndex, _ -> itemIndex != index }
    val nextSourceTracks = if (removedManual) {
        naturalTracks()
    } else {
        naturalTracks().removeFirstOccurrenceById(removedTrack.id)
    }
    if (nextTracks.isEmpty()) {
        return QueueRemovalResult(
            queue = PlaybackQueue(),
            nextQueueInsertionCursor = null,
            nextTrackToPlay = null,
            nextTrackIndex = -1,
        )
    }
    val currentIndex = currentIndex.coerceIn(0, tracks.lastIndex)
    val nextCurrentIndex = when {
        index < currentIndex -> currentIndex - 1
        index == currentIndex -> currentIndex.coerceAtMost(nextTracks.lastIndex)
        else -> currentIndex
    }
    val nextCursor = queueInsertionCursor?.let { cursor ->
        when {
            index < cursor -> cursor - 1
            index == cursor -> null
            else -> cursor
        }
    }
    return QueueRemovalResult(
        queue = copy(
            tracks = nextTracks,
            sourceTracks = nextSourceTracks,
            manualQueueFlags = nextManualFlags,
            currentIndex = nextCurrentIndex,
            isShuffled = isShuffled,
        ),
        nextQueueInsertionCursor = nextCursor,
        nextTrackToPlay = if (index == currentIndex) nextTracks[nextCurrentIndex] else null,
        nextTrackIndex = nextCurrentIndex,
    )
}

internal fun PlaybackQueue.withReorderedTracks(
    reorderedIndices: List<Int>,
    currentTrackId: String?,
): PlaybackQueue? {
    if (
        reorderedIndices.isEmpty() ||
        reorderedIndices.size != tracks.size ||
        reorderedIndices.toSet().size != tracks.size ||
        reorderedIndices.any { it !in tracks.indices }
    ) {
        return null
    }
    val reorderedTracks = reorderedIndices.map(tracks::get)
    val manualFlags = normalizedManualQueueFlags()
    val reorderedManualFlags = reorderedIndices.map(manualFlags::get)
    val activeOriginalIndex = currentIndex
        .takeIf { it in tracks.indices && tracks[it].id == currentTrackId }
        ?: currentTrackId
            ?.let { trackId -> tracks.indexOfFirst { it.id == trackId } }
            ?.takeIf { it >= 0 }
        ?: currentIndex
    val nextIndex = reorderedIndices.indexOf(activeOriginalIndex)
        .takeIf { it >= 0 }
        ?: currentIndex.coerceIn(0, reorderedTracks.lastIndex)
    return copy(
        tracks = reorderedTracks,
        sourceTracks = reorderedTracks.filterIndexed { index, _ -> !reorderedManualFlags[index] },
        manualQueueFlags = reorderedManualFlags,
        currentIndex = nextIndex,
        isShuffled = isShuffled,
    )
}

internal fun selectedTrackIndexInResolvedTracks(
    selectedTrack: Track,
    selectedIndex: Int,
    sourceTracks: List<Track>,
    resolvedTracks: List<Track>,
): Int {
    if (resolvedTracks.isEmpty()) {
        return -1
    }
    val selectedOccurrence = sourceTracks
        .take(selectedIndex + 1)
        .count { it.id == selectedTrack.id }
        .coerceAtLeast(1)
    var occurrence = 0
    resolvedTracks.forEachIndexed { index, track ->
        if (track.id == selectedTrack.id) {
            occurrence += 1
            if (occurrence == selectedOccurrence) {
                return index
            }
        }
    }
    return resolvedTracks.indexOfFirst { it.id == selectedTrack.id }
        .takeIf { it >= 0 }
        ?: selectedIndex.coerceIn(resolvedTracks.indices)
}

internal fun PlaybackQueue.withResolvedTracksForCurrentTrack(
    currentTrack: Track,
    resolvedTracks: List<Track>,
    resolvedSourceTracks: List<Track> = resolvedTracks,
): PlaybackQueue? {
    if (resolvedTracks.isEmpty()) {
        return null
    }
    if (isShuffled) {
        val activeTrack = resolvedTracks.firstOrNull { it.id == currentTrack.id }
            ?: currentTrack
        val remainingResolvedTracks = resolvedTracks.toMutableList().apply {
            val activeIndex = indexOfFirst { it.id == activeTrack.id }
            if (activeIndex >= 0) {
                removeAt(activeIndex)
            }
        }
        val nextTracks = listOf(activeTrack) + remainingResolvedTracks.shuffled(Random)
        return copy(
            tracks = nextTracks,
            sourceTracks = resolvedSourceTracks,
            currentIndex = 0,
            manualQueueFlags = List(nextTracks.size) { false },
            isShuffled = true,
        )
    }
    val resolvedIndex = selectedTrackIndexInResolvedTracks(
        selectedTrack = currentTrack,
        selectedIndex = currentIndex.coerceAtLeast(0),
        sourceTracks = tracks,
        resolvedTracks = resolvedTracks,
    )
    return copy(
        tracks = resolvedTracks,
        sourceTracks = resolvedSourceTracks,
        currentIndex = resolvedIndex,
        manualQueueFlags = List(resolvedTracks.size) { false },
    )
}

internal fun desiredExoRepeatMode(
    mode: PlaybackRepeatMode,
    hasGaplessQueue: Boolean,
): Int {
    return when {
        mode == PlaybackRepeatMode.Track -> Player.REPEAT_MODE_ONE
        mode == PlaybackRepeatMode.Queue && hasGaplessQueue -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }
}

internal fun gaplessQueueKey(queue: PlaybackQueue): String {
    return queue.playlistId
        ?: "${queue.sourceType.name}:${queue.sourceId.orEmpty()}:${queue.sourceTitle.orEmpty()}:${queue.tracks.joinToString(",") { it.id }}"
}
