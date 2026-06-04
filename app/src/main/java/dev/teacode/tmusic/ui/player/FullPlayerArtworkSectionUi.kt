package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics

@Composable
internal fun FullPlayerArtworkSection(
    track: Track,
    artworkBitmap: ImageBitmap?,
    artworkTransitionDirection: Int,
    showLyrics: Boolean,
    lyrics: TrackLyrics?,
    lyricsUnavailable: Boolean,
    lyricsLoading: Boolean,
    progressSeconds: Int,
    onRefreshLyrics: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SlidingArtworkTransition(
            targetState = artworkBitmap,
            direction = artworkTransitionDirection,
            label = "Full player artwork",
        ) { bitmap ->
            ArtworkBox(
                bitmap = bitmap,
                accentColor = track.accentColor,
                keepPreviousWhileLoading = false,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .fillMaxWidth(if (showLyrics) 0.87f else 0.98f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        if (showLyrics) {
            LyricsBlock(
                lyrics = lyrics,
                lyricsUnavailable = lyricsUnavailable,
                lyricsLoading = lyricsLoading,
                progressSeconds = progressSeconds,
                onRefreshLyrics = onRefreshLyrics,
            )
        }
    }
}
