package dev.teacode.tmusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class FullPlayerDragAxis {
    Undetermined,
    Horizontal,
    Vertical,
    Ignored,
}

@Composable
fun FullPlayerScreen(
    playerState: PlayerState,
    artworkBitmap: ImageBitmap?,
    previousTrack: Track?,
    previousArtworkBitmap: ImageBitmap?,
    nextTrack: Track?,
    nextArtworkBitmap: ImageBitmap?,
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
    onOpenSource: (() -> Unit)?,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSwipePrevious: () -> Unit,
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
    val currentDurationSeconds = track.durationSeconds.coerceAtLeast(0)
    val currentProgressSeconds = playerState.progressSeconds.coerceIn(0, currentDurationSeconds)
    var displayedArtworkTrackId by remember { mutableStateOf(track.id) }
    var displayedArtworkBitmap by remember { mutableStateOf(artworkBitmap) }
    var dragAxis by remember { mutableStateOf(FullPlayerDragAxis.Undetermined) }
    var horizontalSwipeStartAllowed by remember { mutableStateOf(false) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var contentWidthPx by remember { mutableFloatStateOf(1f) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var artworkBounds by remember { mutableStateOf<Rect?>(null) }
    var resetAnimating by remember { mutableStateOf(false) }
    var lockedSwipePreviewDirection by remember { mutableStateOf(0) }
    var lockedSwipePreviewTrack by remember { mutableStateOf<Track?>(null) }
    var lockedSwipePreviewArtworkBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val resetAnimation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentTrackIdState = rememberUpdatedState(track.id)
    val rootBoundsState = rememberUpdatedState(rootBounds)
    val artworkBoundsState = rememberUpdatedState(artworkBounds)
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val gestureLockThresholdPx = with(LocalDensity.current) { 10.dp.toPx() }
    val manualHorizontalOffset = if (resetAnimating) {
        resetAnimation.value
    } else if (dragAxis == FullPlayerDragAxis.Horizontal) {
        dragX
    } else {
        0f
    }
    val horizontalOffset = manualHorizontalOffset
    val lockedPreviewActive = resetAnimating && lockedSwipePreviewDirection != 0 && lockedSwipePreviewTrack != null
    val previewDirection = when {
        lockedPreviewActive -> lockedSwipePreviewDirection
        horizontalOffset < 0f -> 1
        horizontalOffset > 0f -> -1
        else -> 0
    }
    val previewTrack = when {
        lockedPreviewActive -> lockedSwipePreviewTrack
        previewDirection == 1 -> nextTrack
        previewDirection == -1 -> previousTrack
        else -> null
    }
    val previewArtworkBitmap = when {
        lockedPreviewActive -> lockedSwipePreviewArtworkBitmap
        previewDirection == 1 -> nextArtworkBitmap
        previewDirection == -1 -> previousArtworkBitmap
        else -> null
    }
    val gestureSwipeLocked = resetAnimating
    LaunchedEffect(track.id, artworkBitmap) {
        if (displayedArtworkTrackId != track.id) {
            displayedArtworkTrackId = track.id
            displayedArtworkBitmap = artworkBitmap
        } else if (artworkBitmap != null) {
            displayedArtworkBitmap = artworkBitmap
        }
    }
    val controlsContrastScrimAlpha = remember(displayedArtworkBitmap) {
        displayedArtworkBitmap?.controlsContrastScrimAlpha() ?: 0f
    }
    val durationSeconds = currentDurationSeconds
    val progressSeconds = currentProgressSeconds
    fun animateSwipeBack(startOffset: Float) {
        scope.launch {
            resetAnimating = true
            lockedSwipePreviewDirection = 0
            lockedSwipePreviewTrack = null
            lockedSwipePreviewArtworkBitmap = null
            resetAnimation.snapTo(startOffset)
            dragAxis = FullPlayerDragAxis.Undetermined
            dragX = 0f
            dragY = 0f
            try {
                resetAnimation.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                )
            } finally {
                resetAnimation.snapTo(0f)
                resetAnimating = false
                lockedSwipePreviewDirection = 0
                lockedSwipePreviewTrack = null
                lockedSwipePreviewArtworkBitmap = null
                dragAxis = FullPlayerDragAxis.Undetermined
                dragX = 0f
                dragY = 0f
            }
        }
    }

    fun animateSwipeComplete(
        startOffset: Float,
        direction: Int,
        targetTrack: Track,
        targetArtworkBitmap: ImageBitmap?,
        onComplete: () -> Unit,
    ) {
        scope.launch {
            logPlaybackDebug(
                "full swipe complete direction=$direction startOffset=$startOffset " +
                    "current=${track.debugTrack()} target=${targetTrack.debugTrack()} " +
                    "previous=${previousTrack?.debugTrack()} next=${nextTrack?.debugTrack()}",
            )
            resetAnimating = true
            lockedSwipePreviewDirection = direction
            lockedSwipePreviewTrack = targetTrack
            lockedSwipePreviewArtworkBitmap = targetArtworkBitmap
            resetAnimation.snapTo(startOffset)
            dragAxis = FullPlayerDragAxis.Undetermined
            dragX = 0f
            dragY = 0f
            try {
                resetAnimation.animateTo(
                    targetValue = -direction * contentWidthPx,
                    animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                )
                onComplete()
                var waitFrames = 0
                while (currentTrackIdState.value != targetTrack.id && waitFrames < 12) {
                    delay(16)
                    waitFrames += 1
                }
            } finally {
                resetAnimation.snapTo(0f)
                resetAnimating = false
                lockedSwipePreviewDirection = 0
                lockedSwipePreviewTrack = null
                lockedSwipePreviewArtworkBitmap = null
                dragAxis = FullPlayerDragAxis.Undetermined
                dragX = 0f
                dragY = 0f
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { coordinates ->
                rootBounds = coordinates.boundsInRoot()
            }
            .pointerInput(
                canSkip,
                gestureSwipeLocked,
                track.id,
                previousTrack?.id,
                nextTrack?.id,
                swipeThresholdPx,
                gestureLockThresholdPx,
            ) {
                detectDragGestures(
                    onDragStart = {
                        if (gestureSwipeLocked) {
                            dragAxis = FullPlayerDragAxis.Ignored
                            horizontalSwipeStartAllowed = false
                            dragX = 0f
                            dragY = 0f
                        } else {
                            scope.launch { resetAnimation.stop() }
                            resetAnimating = false
                            dragAxis = FullPlayerDragAxis.Undetermined
                            val rootTopLeft = rootBoundsState.value?.topLeft ?: Offset.Zero
                            horizontalSwipeStartAllowed = artworkBoundsState.value?.contains(rootTopLeft + it) == true
                            dragX = 0f
                            dragY = 0f
                        }
                    },
                    onDragEnd = {
                        val releasedX = dragX
                        val releasedAxis = dragAxis
                        var handledHorizontalSwipe = false
                        when {
                            releasedAxis == FullPlayerDragAxis.Vertical -> onCollapseDragEnd()
                            canSkip && !gestureSwipeLocked && releasedAxis == FullPlayerDragAxis.Horizontal &&
                                releasedX <= -swipeThresholdPx && nextTrack != null -> {
                                handledHorizontalSwipe = true
                                animateSwipeComplete(
                                    startOffset = releasedX,
                                    direction = 1,
                                    targetTrack = nextTrack,
                                    targetArtworkBitmap = nextArtworkBitmap,
                                    onComplete = onSkipNext,
                                )
                            }
                            canSkip && !gestureSwipeLocked && releasedAxis == FullPlayerDragAxis.Horizontal &&
                                releasedX >= swipeThresholdPx && previousTrack != null -> {
                                handledHorizontalSwipe = true
                                animateSwipeComplete(
                                    startOffset = releasedX,
                                    direction = -1,
                                    targetTrack = previousTrack,
                                    targetArtworkBitmap = previousArtworkBitmap,
                                    onComplete = onSwipePrevious,
                                )
                            }
                            releasedAxis == FullPlayerDragAxis.Horizontal -> animateSwipeBack(releasedX)
                        }
                        if (!handledHorizontalSwipe && releasedAxis != FullPlayerDragAxis.Horizontal) {
                            dragAxis = FullPlayerDragAxis.Undetermined
                            dragX = 0f
                            dragY = 0f
                        }
                        horizontalSwipeStartAllowed = false
                    },
                    onDragCancel = {
                        if (dragAxis == FullPlayerDragAxis.Vertical) {
                            onCollapseDragEnd()
                            dragAxis = FullPlayerDragAxis.Undetermined
                            dragX = 0f
                            dragY = 0f
                        } else if (dragAxis == FullPlayerDragAxis.Horizontal) {
                            animateSwipeBack(dragX)
                        } else {
                            dragAxis = FullPlayerDragAxis.Undetermined
                            dragX = 0f
                            dragY = 0f
                        }
                        horizontalSwipeStartAllowed = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        when (dragAxis) {
                            FullPlayerDragAxis.Undetermined -> {
                                dragX += dragAmount.x
                                dragY += dragAmount.y
                                if (maxOf(abs(dragX), abs(dragY)) >= gestureLockThresholdPx) {
                                    dragAxis = when {
                                        abs(dragX) > abs(dragY) -> {
                                            if (gestureSwipeLocked || !canSkip || !horizontalSwipeStartAllowed) {
                                                dragX = 0f
                                                FullPlayerDragAxis.Undetermined
                                            } else {
                                                FullPlayerDragAxis.Horizontal
                                            }
                                        }
                                        dragY > 0f -> {
                                            onCollapseDragStart()
                                            onCollapseDrag(dragY)
                                            FullPlayerDragAxis.Vertical
                                        }
                                        else -> FullPlayerDragAxis.Ignored
                                    }
                                    if (dragAxis == FullPlayerDragAxis.Horizontal) {
                                        dragY = 0f
                                    }
                                }
                            }
                            FullPlayerDragAxis.Horizontal -> {
                                dragX = (dragX + dragAmount.x)
                                    .coerceIn(-contentWidthPx, contentWidthPx)
                                dragY = 0f
                            }
                            FullPlayerDragAxis.Vertical -> {
                                dragX = 0f
                                dragY += dragAmount.y
                                onCollapseDrag(dragAmount.y)
                            }
                            FullPlayerDragAxis.Ignored -> Unit
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
        if (controlsContrastScrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.40f to Color.Transparent,
                            1f to Color.Black.copy(alpha = controlsContrastScrimAlpha),
                        ),
                    ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { size -> contentWidthPx = size.width.toFloat().coerceAtLeast(1f) },
        ) {
            FullPlayerCurrentContent(
                track = track,
                playerState = playerState,
                artworkBitmap = displayedArtworkBitmap,
                playbackBufferedFraction = playbackBufferedFraction,
                canSkip = canSkip,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                showLyrics = showLyrics,
                lyrics = lyrics,
                lyricsUnavailable = lyricsUnavailable,
                lyricsLoading = lyricsLoading,
                sourceLabel = sourceLabel,
                sourceDetail = sourceDetail,
                onOpenSource = onOpenSource,
                progressSeconds = progressSeconds,
                durationSeconds = durationSeconds,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onAddToPlaylist = onAddToPlaylist,
                onGoToArtist = onGoToArtist,
                onGoToAlbum = onGoToAlbum,
                onRefreshLyrics = onRefreshLyrics,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onShuffleChange = onShuffleChange,
                onRepeatModeChange = onRepeatModeChange,
                onTogglePlayback = onTogglePlayback,
                onOpenQueue = onOpenQueue,
                onSeek = onSeek,
                horizontalOffsetX = horizontalOffset,
                previewTrack = previewTrack,
                previewArtworkBitmap = previewArtworkBitmap,
                onArtworkBoundsChanged = { artworkBounds = it },
                previewOffsetX = if (previewDirection == 0) {
                    0f
                } else {
                    horizontalOffset + previewDirection * contentWidthPx
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun ImageBitmap.controlsContrastScrimAlpha(): Float {
    val bitmap = runCatching { asAndroidBitmap() }.getOrNull() ?: return 0f
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        return 0f
    }
    val sampleCount = 5
    val centerX = bitmap.width / 2
    val centerY = (bitmap.height * 0.78f).toInt().coerceIn(0, bitmap.height - 1)
    val radiusX = (bitmap.width * 0.05f).toInt().coerceAtLeast(1)
    val radiusY = (bitmap.height * 0.05f).toInt().coerceAtLeast(1)
    var luminanceSum = 0f
    repeat(sampleCount) { yIndex ->
        val y = (centerY + (yIndex - sampleCount / 2) * radiusY / (sampleCount / 2))
            .coerceIn(0, bitmap.height - 1)
        repeat(sampleCount) { xIndex ->
            val x = (centerX + (xIndex - sampleCount / 2) * radiusX / (sampleCount / 2))
                .coerceIn(0, bitmap.width - 1)
            val pixel = bitmap.getPixel(x, y)
            val red = (pixel shr 16 and 0xFF) / 255f
            val green = (pixel shr 8 and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            luminanceSum += 0.2126f * red + 0.7152f * green + 0.0722f * blue
        }
    }
    return when (luminanceSum / (sampleCount * sampleCount)) {
        in 0.70f..1f -> 0.24f
        in 0.54f..0.70f -> 0.18f
        in 0.42f..0.54f -> 0.13f
        else -> 0.08f
    }
}

@Composable
private fun FullPlayerCurrentContent(
    track: Track,
    playerState: PlayerState,
    artworkBitmap: ImageBitmap?,
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
    onOpenSource: (() -> Unit)?,
    progressSeconds: Int,
    durationSeconds: Int,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onGoToArtist: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)?,
    onRefreshLyrics: (() -> Unit)?,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (PlaybackRepeatMode) -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenQueue: () -> Unit,
    onSeek: (Int) -> Unit,
    horizontalOffsetX: Float,
    previewTrack: Track?,
    previewArtworkBitmap: ImageBitmap?,
    onArtworkBoundsChanged: (Rect) -> Unit,
    previewOffsetX: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FullPlayerSourceHeader(
            sourceLabel = sourceLabel,
            sourceDetail = sourceDetail,
            onOpenSource = onOpenSource,
        )
        FullPlayerArtworkSection(
            track = track,
            artworkBitmap = artworkBitmap,
            showLyrics = showLyrics,
            lyrics = lyrics,
            lyricsUnavailable = lyricsUnavailable,
            lyricsLoading = lyricsLoading,
            isPlaying = playerState.isPlaying,
            progressSeconds = progressSeconds,
            onRefreshLyrics = onRefreshLyrics,
            onSeek = onSeek,
            horizontalOffsetX = horizontalOffsetX,
            previewTrack = previewTrack,
            previewArtworkBitmap = previewArtworkBitmap,
            onArtworkBoundsChanged = onArtworkBoundsChanged,
            previewOffsetX = previewOffsetX,
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

@Composable
private fun FullPlayerSourceHeader(
    sourceLabel: String?,
    sourceDetail: String?,
    onOpenSource: (() -> Unit)?,
) {
    if (sourceLabel == null) {
        Spacer(modifier = Modifier.fillMaxWidth())
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onOpenSource != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onOpenSource,
                    )
                } else {
                    Modifier
                },
            ),
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

internal fun Track.playbackArtistNames(): String {
    val names = (artists.map { it.name } + artist.split(';'))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
    return names.joinToString(" \u2022 ").ifBlank { artist }
}
