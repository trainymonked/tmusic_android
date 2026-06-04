package dev.teacode.tmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Track

@Composable
fun TrackRow(
    track: Track,
    artworkBitmap: ImageBitmap? = null,
    artworkKey: String = track.listArtworkKey(),
    onRequestArtwork: ((String, ArtworkImageSize) -> Unit)? = null,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    downloadBadgePlacement: DownloadBadgePlacement = DownloadBadgePlacement.End,
    showDownloadBadge: Boolean = true,
    isActive: Boolean = false,
    showActivePlaybackButton: Boolean = false,
    isPlaybackPlaying: Boolean = false,
    onTogglePlayback: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(artworkKey) {
        onRequestArtwork?.invoke(artworkKey, ArtworkImageSize.TrackList)
    }
    val activeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.42f)
            .drawBehind {
                if (isActive) {
                    val bleed = 20.dp.toPx()
                    drawRoundRect(
                        color = activeBackgroundColor,
                        topLeft = Offset(-bleed, 0f),
                        size = Size(size.width + bleed * 2f, size.height),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    )
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) {
                if (showActivePlaybackButton && onTogglePlayback != null) {
                    onTogglePlayback()
                } else {
                    onClick()
                }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ArtworkBox(
                bitmap = artworkBitmap,
                accentColor = track.accentColor,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            if (showActivePlaybackButton && onTogglePlayback != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.18f)),
                )
                Icon(
                    imageVector = if (isPlaybackPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaybackPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDownloadBadge && downloadBadgePlacement == DownloadBadgePlacement.BeforeTitle) {
                    DownloadBadge(track.downloadState)
                    if (track.downloadState != DownloadState.NotDownloaded) {
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                }
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = track.displayArtistNames(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        onToggleFavorite?.let {
            CircleIconButton(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                active = isFavorite,
                onClick = it,
                modifier = Modifier.size(46.dp),
                iconModifier = Modifier.size(24.dp),
            )
        }
        TrackActionsMenu(
            onAddToPlaylist = onAddToPlaylist,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            onAddToQueue = onAddToQueue,
            onRemoveFromQueue = onRemoveFromQueue,
            onGoToArtist = onGoToArtist,
            onGoToAlbum = onGoToAlbum,
        )
        if (showDownloadBadge && downloadBadgePlacement == DownloadBadgePlacement.End) {
            DownloadBadge(track.downloadState)
        }
    }
}
