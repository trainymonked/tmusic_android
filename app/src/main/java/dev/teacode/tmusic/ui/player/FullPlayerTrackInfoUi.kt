package dev.teacode.tmusic.ui

import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            )
            TrackActionsMenu(
                onAddToPlaylist = onAddToPlaylist,
                onRemoveFromPlaylist = null,
                onGoToArtist = onGoToArtist,
                onGoToAlbum = onGoToAlbum,
                buttonSize = 36.dp,
                iconModifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun FullPlayerTrackText(
    track: Track,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
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
        )
    }
}
