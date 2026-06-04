package dev.teacode.tmusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class MiniPlayerVisualState(
    val track: Track,
)

private enum class MiniPlayerDragAxis {
    Undetermined,
    Horizontal,
    Vertical,
}

@Composable
fun MiniPlayer(
    playerState: PlayerState,
    artworkBitmap: ImageBitmap?,
    queueGeneration: Long,
    canSkip: Boolean,
    previousTrack: Track?,
    nextTrack: Track?,
    onOpenPlayer: () -> Unit,
    onExpandDragStart: () -> Unit,
    onExpandDrag: (Float) -> Unit,
    onExpandDragEnd: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTogglePlayback: () -> Unit,
) {
    val track = playerState.currentTrack ?: return
    var displayedArtworkBitmap by remember { mutableStateOf(artworkBitmap) }
    var displayedVisualState by remember {
        mutableStateOf(MiniPlayerVisualState(track = track))
    }
    var incomingVisualState by remember { mutableStateOf<MiniPlayerVisualState?>(null) }
    var displayedPreviousTrack by remember { mutableStateOf(previousTrack) }
    var displayedNextTrack by remember { mutableStateOf(nextTrack) }
    var displayedQueueGeneration by remember { mutableStateOf(queueGeneration) }
    var activeTransitionDirection by remember { mutableStateOf(1) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var dragAxis by remember { mutableStateOf(MiniPlayerDragAxis.Undetermined) }
    var contentWidthPx by remember { mutableStateOf(1f) }
    var transitionAnimating by remember { mutableStateOf(false) }
    val transitionOffsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val swipeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val gestureLockThresholdPx = with(LocalDensity.current) { 8.dp.toPx() }
    val visualState = MiniPlayerVisualState(track = track)

    LaunchedEffect(artworkBitmap) {
        if (artworkBitmap != null) {
            displayedArtworkBitmap = artworkBitmap
        }
    }

    LaunchedEffect(track.id, queueGeneration) {
        if (displayedVisualState.track.id == track.id) {
            displayedQueueGeneration = queueGeneration
            return@LaunchedEffect
        }
        val queueDirection = if (displayedQueueGeneration != queueGeneration) {
            0
        } else {
            when (track.id) {
                displayedNextTrack?.id -> 1
                displayedPreviousTrack?.id -> -1
                else -> 0
            }
        }
        if (queueDirection == 0) {
            transitionOffsetX.snapTo(0f)
            dragX = 0f
            dragAxis = MiniPlayerDragAxis.Undetermined
            incomingVisualState = null
            displayedVisualState = visualState
            displayedPreviousTrack = previousTrack
            displayedNextTrack = nextTrack
            displayedQueueGeneration = queueGeneration
            transitionAnimating = false
            return@LaunchedEffect
        }
        activeTransitionDirection = queueDirection
        transitionAnimating = true
        transitionOffsetX.snapTo(dragX.coerceIn(-contentWidthPx, contentWidthPx))
        dragX = 0f
        dragAxis = MiniPlayerDragAxis.Undetermined
        incomingVisualState = visualState
        transitionOffsetX.animateTo(
            targetValue = -queueDirection * contentWidthPx,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        )
        displayedVisualState = visualState
        incomingVisualState = null
        transitionOffsetX.snapTo(0f)
        displayedPreviousTrack = previousTrack
        displayedNextTrack = nextTrack
        displayedQueueGeneration = queueGeneration
        transitionAnimating = false
    }

    LaunchedEffect(track.id, displayedArtworkBitmap, previousTrack, nextTrack) {
        when {
            incomingVisualState?.track?.id == track.id -> incomingVisualState = visualState
            !transitionAnimating && displayedVisualState.track.id == track.id -> {
                displayedVisualState = visualState
                displayedPreviousTrack = previousTrack
                displayedNextTrack = nextTrack
            }
        }
    }

    fun animateBackToRest() {
        val startOffset = dragX
        dragX = 0f
        scope.launch {
            transitionAnimating = true
            transitionOffsetX.snapTo(startOffset)
            transitionOffsetX.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            )
            transitionAnimating = false
            dragAxis = MiniPlayerDragAxis.Undetermined
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(track.id, canSkip, swipeThresholdPx, gestureLockThresholdPx) {
                detectDragGestures(
                    onDragStart = {
                        scope.launch {
                            transitionOffsetX.stop()
                            transitionAnimating = false
                        }
                        dragX = 0f
                        dragY = 0f
                        dragAxis = MiniPlayerDragAxis.Undetermined
                    },
                    onDragEnd = {
                        when {
                            dragAxis == MiniPlayerDragAxis.Vertical -> {
                                onExpandDragEnd()
                                dragAxis = MiniPlayerDragAxis.Undetermined
                            }
                            canSkip && dragAxis == MiniPlayerDragAxis.Horizontal && dragX <= -swipeThresholdPx -> onSkipNext()
                            canSkip && dragAxis == MiniPlayerDragAxis.Horizontal && dragX >= swipeThresholdPx -> onSkipPrevious()
                            else -> animateBackToRest()
                        }
                        dragY = 0f
                    },
                    onDragCancel = {
                        animateBackToRest()
                        if (dragAxis == MiniPlayerDragAxis.Vertical) {
                            onExpandDragEnd()
                        }
                        dragAxis = MiniPlayerDragAxis.Undetermined
                        dragY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        when (dragAxis) {
                            MiniPlayerDragAxis.Undetermined -> {
                                dragX += dragAmount.x
                                dragY += dragAmount.y
                                if (maxOf(abs(dragX), abs(dragY)) >= gestureLockThresholdPx) {
                                    dragAxis = if (abs(dragY) > abs(dragX)) {
                                        MiniPlayerDragAxis.Vertical
                                    } else {
                                        MiniPlayerDragAxis.Horizontal
                                    }
                                    if (dragAxis == MiniPlayerDragAxis.Vertical) {
                                        dragX = 0f
                                        onExpandDragStart()
                                        onExpandDrag(-dragY)
                                    } else {
                                        dragY = 0f
                                    }
                                }
                            }
                            MiniPlayerDragAxis.Horizontal -> {
                                dragX += dragAmount.x
                                dragY = 0f
                            }
                            MiniPlayerDragAxis.Vertical -> {
                                dragX = 0f
                                dragY += dragAmount.y
                                onExpandDrag(-dragAmount.y)
                            }
                        }
                    },
                )
            }
            .clickable(onClick = onOpenPlayer),
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
        ) {
            displayedArtworkBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = 0.42f
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                        .blur(24.dp),
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f)),
            )
            Column(modifier = Modifier.matchParentSize()) {
                PlaybackScrubber(
                    progressSeconds = playerState.progressSeconds,
                    durationSeconds = track.durationSeconds,
                    onSeek = {},
                    interactive = false,
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .padding(4.dp),
                    ) {
                        ArtworkBox(
                            bitmap = displayedArtworkBitmap,
                            accentColor = track.accentColor,
                            keepPreviousWhileLoading = true,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(5.dp)),
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .onSizeChanged { size ->
                                contentWidthPx = size.width.toFloat().coerceAtLeast(1f)
                            },
                    ) {
                        val currentOffset = currentMiniPlayerOffset(
                            transitionAnimating = transitionAnimating,
                            animatedOffsetX = transitionOffsetX.value,
                            dragX = dragX,
                            dragAxis = dragAxis,
                        )
                        MiniPlayerTrackText(
                            state = displayedVisualState,
                            offsetX = currentOffset,
                        )
                        val dragPreview = when {
                            transitionAnimating -> incomingVisualState
                            dragAxis == MiniPlayerDragAxis.Horizontal && dragX < 0f -> nextTrack?.let(::MiniPlayerVisualState)
                            dragAxis == MiniPlayerDragAxis.Horizontal && dragX > 0f -> previousTrack?.let(::MiniPlayerVisualState)
                            else -> null
                        }
                        dragPreview?.let { previewState ->
                            val previewDirection = when {
                                transitionAnimating -> activeTransitionDirection
                                dragX < 0f -> 1
                                else -> -1
                            }
                            MiniPlayerTrackText(
                                state = previewState,
                                offsetX = currentOffset + previewDirection * contentWidthPx,
                            )
                        }
                    }
                    IconButton(
                        onClick = onTogglePlayback,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerTrackText(
    state: MiniPlayerVisualState,
    offsetX: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = offsetX
            },
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = state.track.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = state.track.playbackArtistNames(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun currentMiniPlayerOffset(
    transitionAnimating: Boolean,
    animatedOffsetX: Float,
    dragX: Float,
    dragAxis: MiniPlayerDragAxis,
): Float {
    return if (transitionAnimating) {
        animatedOffsetX
    } else {
        dragX.takeIf { dragAxis == MiniPlayerDragAxis.Horizontal } ?: 0f
    }
}
