package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics

@Composable
internal fun PlayerOverlayHost(
    fullPlayerOpen: Boolean,
    fullPlayerRevealProgress: Float,
    shellHeightPx: Float,
    queueOpen: Boolean,
    playerState: PlayerState,
    artworkBitmap: ImageBitmap?,
    artworkBitmaps: Map<String, ImageBitmap>,
    artworkTransitionDirection: Int,
    playbackBufferedFraction: Float,
    canSkipTracks: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: PlaybackRepeatMode,
    showLyrics: Boolean,
    currentLyrics: TrackLyrics?,
    currentLyricsUnavailable: Boolean,
    currentLyricsLoading: Boolean,
    playerSourceLabel: String?,
    playerSourceDetail: String?,
    currentTrackFavorite: Boolean,
    canPlayRemoteTracks: Boolean,
    offlineAvailableTrackIds: Set<String>,
    queueTracks: List<Track>,
    manualQueueFlags: List<Boolean>,
    queueCurrentIndex: Int,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (PlaybackRepeatMode) -> Unit,
    onToggleCurrentFavorite: (() -> Unit)?,
    onAddCurrentTrackToPlaylist: (() -> Unit)?,
    onGoToTrackArtist: (Track) -> Unit,
    onGoToTrackAlbum: (Track) -> Unit,
    onRefreshCurrentLyrics: (() -> Unit)?,
    onOpenQueue: () -> Unit,
    onCloseQueue: () -> Unit,
    onCloseFullPlayer: () -> Unit,
    onCollapseDragStart: () -> Unit,
    onCollapseDrag: (Float) -> Unit,
    onCollapseDragEnd: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Int) -> Unit,
    onSelectQueueTrack: (Int) -> Unit,
    onRemoveQueueTrack: (Int) -> Unit,
    onReorderQueueTracks: (List<Int>) -> Unit,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
) {
    val collapsedPlayerOffsetPx = shellHeightPx - with(LocalDensity.current) { 140.dp.toPx() }
    if (fullPlayerOpen || fullPlayerRevealProgress > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = (1f - fullPlayerRevealProgress) * collapsedPlayerOffsetPx.coerceAtLeast(1f)
                    clip = true
                    shape = RectangleShape
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            FullPlayerScreen(
                playerState = playerState,
                artworkBitmap = artworkBitmap,
                artworkTransitionDirection = artworkTransitionDirection,
                playbackBufferedFraction = playbackBufferedFraction,
                canSkip = canSkipTracks,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                showLyrics = showLyrics,
                lyrics = currentLyrics,
                lyricsUnavailable = currentLyricsUnavailable,
                lyricsLoading = currentLyricsLoading,
                sourceLabel = playerSourceLabel,
                sourceDetail = playerSourceDetail,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onShuffleChange = onShuffleChange,
                onRepeatModeChange = onRepeatModeChange,
                isFavorite = currentTrackFavorite,
                onToggleFavorite = onToggleCurrentFavorite,
                onAddToPlaylist = onAddCurrentTrackToPlaylist,
                onGoToArtist = playerState.currentTrack?.let { track ->
                    {
                        onCloseFullPlayer()
                        onGoToTrackArtist(track)
                    }
                },
                onGoToAlbum = playerState.currentTrack?.albumId?.let {
                    playerState.currentTrack?.let { track ->
                        {
                            onCloseFullPlayer()
                            onGoToTrackAlbum(track)
                        }
                    }
                },
                onRefreshLyrics = onRefreshCurrentLyrics,
                onOpenQueue = onOpenQueue,
                onCollapseDragStart = onCollapseDragStart,
                onCollapseDrag = onCollapseDrag,
                onCollapseDragEnd = onCollapseDragEnd,
                onTogglePlayback = onTogglePlayback,
                onSeek = onSeek,
            )
        }
    }
    if (queueOpen) {
        QueueScreen(
            tracks = queueTracks.takeIf { it.isNotEmpty() }
                ?: playerState.currentTrack?.let(::listOf).orEmpty(),
            manualQueueFlags = manualQueueFlags,
            currentTrackId = playerState.currentTrack?.id,
            currentIndex = queueCurrentIndex,
            artworkBitmaps = artworkBitmaps,
            canPlayFromNetwork = canPlayRemoteTracks,
            offlinePlayableTrackIds = offlineAvailableTrackIds,
            onRequestArtwork = onRequestArtwork,
            onSelectTrack = onSelectQueueTrack,
            onRemoveTrack = onRemoveQueueTrack,
            onReorderTracks = onReorderQueueTracks,
            onGoToArtist = onGoToTrackArtist,
            onGoToAlbum = onGoToTrackAlbum,
            onClose = onCloseQueue,
        )
    }
}
