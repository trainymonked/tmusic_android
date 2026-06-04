package dev.teacode.tmusic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics

@Composable
fun FullPlayerScreen(
    playerState: PlayerState,
    artworkBitmap: ImageBitmap?,
    artworkTransitionDirection: Int,
    playbackBufferedFraction: Float,
    canSkip: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: PlaybackRepeatMode,
    showLyrics: Boolean,
    lyrics: TrackLyrics?,
    lyricsUnavailable: Boolean,
    lyricsLoading: Boolean,
    sourceLabel: String?,
    sourceDetail: String?,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (PlaybackRepeatMode) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onGoToArtist: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)?,
    onRefreshLyrics: (() -> Unit)?,
    onOpenQueue: () -> Unit,
    onCollapseDragStart: () -> Unit,
    onCollapseDrag: (Float) -> Unit,
    onCollapseDragEnd: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    val track = playerState.currentTrack ?: return
    val durationSeconds = track.durationSeconds.coerceAtLeast(0)
    val progressSeconds = playerState.progressSeconds.coerceIn(0, durationSeconds)
    var displayedArtworkBitmap by remember { mutableStateOf(artworkBitmap) }
    LaunchedEffect(artworkBitmap) {
        if (artworkBitmap != null) {
            displayedArtworkBitmap = artworkBitmap
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { onCollapseDragStart() },
                    onDragEnd = onCollapseDragEnd,
                    onDragCancel = onCollapseDragEnd,
                    onVerticalDrag = { change, delta ->
                        if (delta > 0f) {
                            change.consume()
                            onCollapseDrag(delta)
                        }
                    },
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        displayedArtworkBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.75f
                        scaleX = 1.12f
                        scaleY = 1.12f
                    }
                    .blur(32.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f)),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (sourceLabel != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = sourceLabel.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    sourceDetail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            FullPlayerArtworkSection(
                track = track,
                artworkBitmap = displayedArtworkBitmap,
                artworkTransitionDirection = artworkTransitionDirection,
                showLyrics = showLyrics,
                lyrics = lyrics,
                lyricsUnavailable = lyricsUnavailable,
                lyricsLoading = lyricsLoading,
                progressSeconds = progressSeconds,
                onRefreshLyrics = onRefreshLyrics,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            FullPlayerTrackInfo(
                track = track,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onAddToPlaylist = onAddToPlaylist,
                onGoToArtist = onGoToArtist,
                onGoToAlbum = onGoToAlbum,
            )
            FullPlayerProgress(
                progressSeconds = progressSeconds,
                durationSeconds = durationSeconds,
                playbackBufferedFraction = playbackBufferedFraction,
                onSeek = onSeek,
            )
            FullPlayerControls(
                playerState = playerState,
                canSkip = canSkip,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onShuffleChange = onShuffleChange,
                onRepeatModeChange = onRepeatModeChange,
                onTogglePlayback = onTogglePlayback,
                onOpenQueue = onOpenQueue,
            )
        }
    }
}

internal fun Track.playbackArtistNames(): String {
    val names = artist.split(';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
    return names.joinToString(" \u2022 ").ifBlank { artist }
}
