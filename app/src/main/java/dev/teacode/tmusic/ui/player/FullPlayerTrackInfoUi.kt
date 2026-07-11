package dev.teacode.tmusic.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.teacode.tmusic.domain.Track

@Composable
internal fun FullPlayerTrackInfo(
    track: Track,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onGoToArtist: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FullPlayerTrackText(
            track = track,
            onGoToArtist = onGoToArtist,
            onGoToAlbum = onGoToAlbum,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                active = isFavorite,
                onClick = { onToggleFavorite?.invoke() },
                enabled = onToggleFavorite != null,
                iconModifier = Modifier.size(22.dp),
                buttonSize = 42.dp,
                containerSize = 34.dp,
                suppressInteractionIndication = true,
            )
            TrackActionsMenu(
                onAddToPlaylist = onAddToPlaylist,
                onRemoveFromPlaylist = null,
                onGoToArtist = null,
                onGoToAlbum = null,
                buttonSize = 36.dp,
                iconModifier = Modifier.size(22.dp),
                suppressButtonIndication = true,
            )
        }
    }
}

@Composable
private fun FullPlayerTrackText(
    track: Track,
    onGoToArtist: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val albumInteractionSource = remember { MutableInteractionSource() }
    val artistInteractionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 20.sp,
                lineHeight = 24.sp,
            ),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onGoToAlbum != null) {
                        Modifier.clickable(
                            interactionSource = albumInteractionSource,
                            indication = null,
                            onClick = onGoToAlbum,
                        )
                    } else {
                        Modifier
                    },
                )
                .basicMarquee(),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = track.playbackArtistNames(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onGoToArtist != null) {
                        Modifier.clickable(
                            interactionSource = artistInteractionSource,
                            indication = null,
                            onClick = onGoToArtist,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
