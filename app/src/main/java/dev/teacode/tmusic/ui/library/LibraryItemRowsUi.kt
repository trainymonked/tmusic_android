package dev.teacode.tmusic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist

@Composable
internal fun LibraryPlaylistRow(
    playlist: Playlist,
    trackCount: Int,
    downloadState: DownloadState,
    artworkBitmap: ImageBitmap?,
    coverKey: String,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(coverKey) {
        onRequestArtwork(coverKey, ArtworkImageSize.AlbumGrid)
    }
    LibraryItemRow(
        title = playlist.title,
        subtitle = trackCountLabel(trackCount),
        artworkBitmap = artworkBitmap,
        accentColor = stableUiColor(playlist.id),
        fallbackIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
        downloadState = downloadState,
        onClick = onClick,
    )
}

@Composable
internal fun LibraryAlbumRow(
    album: LibraryAlbum,
    downloadState: DownloadState,
    artworkBitmap: ImageBitmap?,
    coverKey: String?,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(coverKey) {
        coverKey?.let { onRequestArtwork(it, ArtworkImageSize.AlbumGrid) }
    }
    LibraryItemRow(
        title = album.title,
        subtitle = album.displayArtistNames(),
        artworkBitmap = artworkBitmap,
        accentColor = album.accentColor,
        fallbackIcon = Icons.Filled.Album,
        downloadState = downloadState,
        onClick = onClick,
    )
}

@Composable
private fun LibraryItemRow(
    title: String,
    subtitle: String,
    artworkBitmap: ImageBitmap?,
    accentColor: Long,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    downloadState: DownloadState,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(accentColor)),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkBitmap != null) {
                Image(
                    bitmap = artworkBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloadState != DownloadState.NotDownloaded) {
                    DownloadBadge(downloadState)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
