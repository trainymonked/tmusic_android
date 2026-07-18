package dev.teacode.tmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackScrubber(
    progressSeconds: Int,
    durationSeconds: Int,
    bufferedFraction: Float = 0f,
    onSeek: (Int) -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val safeDurationSeconds = durationSeconds.coerceAtLeast(1)
    val safeProgressSeconds = progressSeconds.coerceIn(0, safeDurationSeconds)
    val progressFraction = safeProgressSeconds.toFloat() / safeDurationSeconds.toFloat()
    val safeBufferedFraction = bufferedFraction.coerceIn(progressFraction, 1f)
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0) }

    fun seekFromX(x: Float) {
        if (!interactive || widthPx <= 0) {
            return
        }
        val fraction = (x / widthPx.toFloat()).coerceIn(0f, 1f)
        onSeek((fraction * safeDurationSeconds).toInt())
    }

    val inputModifier = if (interactive) {
        Modifier.pointerInput(safeDurationSeconds, widthPx) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var targetX = down.position.x
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    targetX = change.position.x
                    change.consume()
                    if (!change.pressed) {
                        break
                    }
                }
                seekFromX(targetX)
            }
        }
    } else {
        Modifier
    }

    val trackHeight = if (compact) 6.dp else 7.dp
    val containerHeight = if (compact) 6.dp else 26.dp
    val thumbSize = 12.dp
    val fullTrackShape = RoundedCornerShape(50)
    val leadingTrackShape = RoundedCornerShape(
        topStart = trackHeight / 2,
        bottomStart = trackHeight / 2,
    )
    val inactiveTrackColor = if (compact) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    }
    val bufferedTrackColor = if (compact) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    }
    val thumbOffsetPx = with(density) {
        ((widthPx - thumbSize.toPx()).coerceAtLeast(0f) * progressFraction).toInt()
    }

    Box(
        modifier = modifier
            .height(containerHeight)
            .onSizeChanged { widthPx = it.width }
            .then(inputModifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(fullTrackShape)
                .background(inactiveTrackColor),
        )
        if (safeBufferedFraction > progressFraction) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safeBufferedFraction)
                    .height(trackHeight)
                    .clip(
                        if (compact || safeBufferedFraction >= 1f) {
                            fullTrackShape
                        } else {
                            leadingTrackShape
                        },
                    )
                    .background(bufferedTrackColor),
            )
        }
        if (progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFraction.coerceIn(0.01f, 1f))
                    .height(trackHeight)
                    .clip(
                        if (compact || progressFraction >= 1f) {
                            fullTrackShape
                        } else {
                            leadingTrackShape
                        },
                    )
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        if (!compact) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffsetPx, 0) }
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
