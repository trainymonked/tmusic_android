package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Playlist

@Composable
internal fun PlaylistHeader(
    playlist: Playlist,
    subtitle: String,
    artworkBitmap: ImageBitmap?,
    coverKey: String,
    downloadState: DownloadState,
    isFavorites: Boolean,
    canDownload: Boolean,
    hasPlayableTracks: Boolean,
    isActivePlaylist: Boolean,
    isPlaybackPlaying: Boolean,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onDeleteClick: () -> Unit,
    onEditClick: () -> Unit,
    onPlayPlaylist: () -> Unit,
    onShufflePlayPlaylist: () -> Unit,
    onTogglePlayback: () -> Unit,
    onDownloadPlaylist: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        ArtworkBox(
            bitmap = artworkBitmap,
            accentColor = stableUiColor(playlist.id),
            keepPreviousWhileLoading = true,
            modifier = Modifier
                .size(176.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
    LaunchedEffect(coverKey) {
        onRequestArtwork(coverKey, ArtworkImageSize.AlbumGrid)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        HeaderBlock(
            title = playlist.title,
            subtitle = subtitle,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (!isFavorites) {
            IconButton(
                onClick = onDeleteClick,
                enabled = canDownload,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete playlist",
                )
            }
            IconButton(
                onClick = onEditClick,
                enabled = canDownload,
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit playlist",
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isFavorites) {
            Button(
                onClick = onShufflePlayPlaylist,
                enabled = hasPlayableTracks,
                modifier = Modifier
                    .height(44.dp)
                    .widthIn(min = 156.dp, max = 200.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Shuffle")
            }
        } else {
            IconButton(
                onClick = if (isActivePlaylist) onTogglePlayback else onPlayPlaylist,
                enabled = hasPlayableTracks,
            ) {
                Icon(
                    imageVector = if (isActivePlaylist && isPlaybackPlaying) {
                        Icons.Filled.Pause
                    } else {
                        Icons.Filled.PlayArrow
                    },
                    contentDescription = if (isActivePlaylist && isPlaybackPlaying) {
                        "Pause playlist"
                    } else {
                        "Play playlist"
                    },
                )
            }
        }
        DownloadCircleButton(
            downloadState = downloadState,
            contentDescription = "Download playlist",
            onClick = onDownloadPlaylist,
            enabled = canDownload,
        )
    }
}
