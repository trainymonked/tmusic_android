package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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

@Composable
fun QueueScreen(
    tracks: List<Track>,
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
                QueueTrackList(
                    tracks = tracks,
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
                    onReorderTracks = onReorderTracks,
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
