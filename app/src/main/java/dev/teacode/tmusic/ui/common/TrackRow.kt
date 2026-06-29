package dev.teacode.tmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
    titleBadge: String? = null,
    leadingLabel: String? = null,
    isActive: Boolean = false,
    showActivePlaybackButton: Boolean = false,
    isPlaybackPlaying: Boolean = false,
    onTogglePlayback: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(artworkKey) {
        if (leadingLabel == null) {
            onRequestArtwork?.invoke(artworkKey, ArtworkImageSize.TrackList)
        }
    }
    val activeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val rowClick = if (isActive && onTogglePlayback != null) onTogglePlayback else onClick

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.42f)
            .background(
                color = if (isActive) activeBackgroundColor else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) {
                rowClick()
            }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hasLeadingLabel = leadingLabel != null
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier,
        ) {
            if (hasLeadingLabel) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = leadingLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            } else {
                ArtworkBox(
                    bitmap = artworkBitmap,
                    accentColor = track.accentColor,
                    placeholderIcon = Icons.Filled.LibraryMusic,
                    placeholderIconSize = 20.dp,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
        }
        Spacer(modifier = Modifier.width(if (hasLeadingLabel) 8.dp else 12.dp))
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
                titleBadge?.let { badge ->
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
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
            buttonSize = 40.dp,
        )
        if (showDownloadBadge && downloadBadgePlacement == DownloadBadgePlacement.End) {
            DownloadBadge(track.downloadState)
        }
    }
}
