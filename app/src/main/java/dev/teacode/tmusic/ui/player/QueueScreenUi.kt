package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.teacode.tmusic.domain.Track
import kotlin.math.max
import kotlin.math.min

@Composable
fun QueueScreen(
    tracks: List<Track>,
    manualQueueFlags: List<Boolean>,
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
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        PlayerBottomSheet(
            title = "Queue",
            onClose = onClose,
        ) {
            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding)
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Queue is empty",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                val windowStart = max(0, currentIndex - 10)
                val windowEndExclusive = min(tracks.size, currentIndex + 21)
                val visibleTracks = tracks.subList(windowStart, windowEndExclusive)
                val visibleManualFlags = manualQueueFlags
                    .let { flags -> tracks.indices.map { index -> flags.getOrNull(index) == true } }
                    .subList(windowStart, windowEndExclusive)
                QueueTrackList(
                    tracks = visibleTracks,
                    manualQueueFlags = visibleManualFlags,
                    queueStartIndex = windowStart,
                    currentTrackId = currentTrackId,
                    currentIndex = currentIndex,
                    artworkBitmaps = artworkBitmaps,
                    canPlayFromNetwork = canPlayFromNetwork,
                    offlinePlayableTrackIds = offlinePlayableTrackIds,
                    onRequestArtwork = onRequestArtwork,
                    onSelectTrack = {
                        onSelectTrack(it)
                        onClose()
                    },
                    onRemoveTrack = onRemoveTrack,
                    onReorderTracks = { reorderedWindowIndices ->
                        val nextIndices = tracks.indices.toMutableList()
                        reorderedWindowIndices.forEachIndexed { index, originalIndex ->
                            val targetIndex = windowStart + index
                            if (targetIndex in nextIndices.indices) {
                                nextIndices[targetIndex] = originalIndex
                            }
                        }
                        onReorderTracks(nextIndices)
                    },
                    onGoToArtist = {
                        onGoToArtist(it)
                        onClose()
                    },
                    onGoToAlbum = {
                        onGoToAlbum(it)
                        onClose()
                    },
                )
            }
        }
    }
}
