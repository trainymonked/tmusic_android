package dev.teacode.tmusic.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.teacode.tmusic.domain.Track

private data class QueueListItem(
    val originalIndex: Int,
    val track: Track,
    val isManualQueueItem: Boolean,
)

@Composable
internal fun ColumnScope.QueueTrackList(
    tracks: List<Track>,
    manualQueueFlags: List<Boolean>,
    queueStartIndex: Int,
    currentTrackId: String?,
    currentIndex: Int,
    artworkBitmaps: Map<String, ImageBitmap>,
    canPlayFromNetwork: Boolean,
    offlinePlayableTrackIds: Set<String>,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onReorderTracks: (List<Int>) -> Unit,
    onGoToArtist: (Track) -> Unit,
    onGoToAlbum: (Track) -> Unit,
) {
    val listState = rememberLazyListState()
    val queueIdentity = remember(tracks, manualQueueFlags, queueStartIndex) {
        tracks.mapIndexed { index, track ->
            "${queueStartIndex + index}:${track.id}:${manualQueueFlags.getOrNull(index) == true}"
        }
    }
    val initialItems = remember(queueIdentity) {
        tracks.mapIndexed { index, track ->
            QueueListItem(
                originalIndex = queueStartIndex + index,
                track = track,
                isManualQueueItem = manualQueueFlags.getOrNull(index) == true,
            )
        }
    }
    var displayedItems by remember { mutableStateOf(initialItems) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val reorderStepPx = with(LocalDensity.current) { 56.dp.toPx() }

    LaunchedEffect(queueIdentity, draggedIndex) {
        if (draggedIndex == null) {
            displayedItems = initialItems
        }
    }
    LaunchedEffect(currentIndex, currentTrackId, tracks.size, draggedIndex) {
        if (draggedIndex != null) {
            return@LaunchedEffect
        }
        val activeIndex = displayedItems.indexOfFirst { item ->
            item.originalIndex == currentIndex ||
                (currentIndex !in (queueStartIndex until queueStartIndex + tracks.size) && item.track.id == currentTrackId)
        }
        if (activeIndex >= 0) {
            listState.scrollToItem(activeIndex)
        }
    }

    fun finishReorder() {
        val reorderedIndices = displayedItems.map { it.originalIndex }
        if (reorderedIndices != initialItems.map { it.originalIndex }) {
            onReorderTracks(reorderedIndices)
        }
        draggedIndex = null
        draggedKey = null
        dragOffset = 0f
    }

    LazyColumn(
        state = listState,
        userScrollEnabled = draggedIndex == null,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        itemsIndexed(
            items = displayedItems,
            key = { _, item -> "queue:${item.originalIndex}:${item.track.id}" },
        ) { index, item ->
            val track = item.track
            val canPlayTrack = canPlayFromNetwork || track.id in offlinePlayableTrackIds
            val itemKey = "queue:${item.originalIndex}:${track.id}"
            Column(
                modifier = Modifier
                    .zIndex(if (draggedKey == itemKey) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (draggedKey == itemKey) dragOffset else 0f
                    }
                    .pointerInput(itemKey) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedKey = itemKey
                                draggedIndex = index
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggedKey = null
                                draggedIndex = null
                                dragOffset = 0f
                            },
                            onDragEnd = ::finishReorder,
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                var nextIndex = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                while (dragOffset >= reorderStepPx && nextIndex < displayedItems.lastIndex) {
                                    displayedItems = displayedItems.moveQueueItem(nextIndex, nextIndex + 1)
                                    nextIndex += 1
                                    draggedIndex = nextIndex
                                    dragOffset -= reorderStepPx
                                }
                                while (dragOffset <= -reorderStepPx && nextIndex > 0) {
                                    displayedItems = displayedItems.moveQueueItem(nextIndex, nextIndex - 1)
                                    nextIndex -= 1
                                    draggedIndex = nextIndex
                                    dragOffset += reorderStepPx
                                }
                            },
                        )
                    },
            ) {
                if (item.isManualQueueItem && displayedItems.getOrNull(index - 1)?.isManualQueueItem != true) {
                    Text(
                        text = "Added to queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 52.dp, top = 6.dp, bottom = 2.dp),
                    )
                }
                TrackRow(
                    track = track,
                    artworkBitmap = artworkBitmaps.artworkBitmap(track.listArtworkKey(), ArtworkImageSize.TrackList),
                    onRequestArtwork = onRequestArtwork,
                    onClick = { onSelectTrack(item.originalIndex) },
                    onRemoveFromQueue = { onRemoveTrack(item.originalIndex) },
                    onGoToArtist = { onGoToArtist(track) },
                    onGoToAlbum = track.albumId?.let { { onGoToAlbum(track) } },
                    isActive = item.originalIndex == currentIndex ||
                        (currentIndex !in (queueStartIndex until queueStartIndex + tracks.size) && track.id == currentTrackId),
                    showDownloadBadge = false,
                    enabled = canPlayTrack,
                    modifier = Modifier.then(
                        if (item.isManualQueueItem) {
                            Modifier.padding(start = 8.dp)
                        } else {
                            Modifier
                        },
                    ),
                )
            }
        }
    }
}

private fun <T> List<T>.moveQueueItem(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) {
        return this
    }
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}
