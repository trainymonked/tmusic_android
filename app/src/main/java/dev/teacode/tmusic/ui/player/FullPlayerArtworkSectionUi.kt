package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics

@Composable
internal fun FullPlayerArtworkSection(
    track: Track,
    artworkBitmap: ImageBitmap?,
    showLyrics: Boolean,
    lyrics: TrackLyrics?,
    lyricsUnavailable: Boolean,
    lyricsLoading: Boolean,
    isPlaying: Boolean,
    progressSeconds: Int,
    onRefreshLyrics: (() -> Unit)?,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontalOffsetX: Float = 0f,
    previewTrack: Track? = null,
    previewArtworkBitmap: ImageBitmap? = null,
    onArtworkBoundsChanged: (Rect) -> Unit = {},
    previewOffsetX: Float = 0f,
) {
    Box(
        modifier = modifier.clipToBounds(),
    ) {
        FullPlayerArtworkSectionContent(
            track = track,
            artworkBitmap = artworkBitmap,
            showLyrics = showLyrics,
            lyrics = lyrics,
            lyricsUnavailable = lyricsUnavailable,
            lyricsLoading = lyricsLoading,
            isPlaying = isPlaying,
            progressSeconds = progressSeconds,
            onRefreshLyrics = onRefreshLyrics,
            onSeek = onSeek,
            onArtworkBoundsChanged = onArtworkBoundsChanged,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = horizontalOffsetX },
        )
        if (previewTrack != null) {
            FullPlayerArtworkPreviewContent(
                track = previewTrack,
                artworkBitmap = previewArtworkBitmap,
                showLyrics = showLyrics,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = previewOffsetX },
            )
        }
    }
}

@Composable
private fun FullPlayerArtworkSectionContent(
    track: Track,
    artworkBitmap: ImageBitmap?,
    showLyrics: Boolean,
    lyrics: TrackLyrics?,
    lyricsUnavailable: Boolean,
    lyricsLoading: Boolean,
    isPlaying: Boolean,
    progressSeconds: Int,
    onRefreshLyrics: (() -> Unit)?,
    onSeek: (Int) -> Unit,
    onArtworkBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ArtworkBox(
            bitmap = artworkBitmap,
            accentColor = track.accentColor,
            keepPreviousWhileLoading = false,
            placeholderIcon = Icons.Filled.LibraryMusic,
            placeholderIconSize = 78.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .onGloballyPositioned { coordinates ->
                    onArtworkBoundsChanged(coordinates.boundsInRoot())
                }
                .clip(RoundedCornerShape(8.dp)),
        )
        if (showLyrics) {
            LyricsBlock(
                lyrics = lyrics,
                lyricsUnavailable = lyricsUnavailable,
                lyricsLoading = lyricsLoading,
                isPlaying = isPlaying,
                progressSeconds = progressSeconds,
                onRefreshLyrics = onRefreshLyrics,
                onSeek = onSeek,
            )
        }
    }
}

@Composable
private fun FullPlayerArtworkPreviewContent(
    track: Track,
    artworkBitmap: ImageBitmap?,
    showLyrics: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ArtworkBox(
            bitmap = artworkBitmap,
            accentColor = track.accentColor,
            keepPreviousWhileLoading = true,
            placeholderIcon = Icons.Filled.LibraryMusic,
            placeholderIconSize = 78.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
        )
        if (showLyrics) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp),
            )
        }
    }
}
