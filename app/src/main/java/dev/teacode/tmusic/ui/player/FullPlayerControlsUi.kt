package dev.teacode.tmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.PlayerState

@Composable
internal fun FullPlayerControls(
    playerState: PlayerState,
    canSkip: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: PlaybackRepeatMode,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (PlaybackRepeatMode) -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val audioOutput = rememberAudioOutputDevice()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            CircleIconButton(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = if (shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                active = shuffleEnabled,
                onClick = { onShuffleChange(!shuffleEnabled) },
                modifier = Modifier.size(58.dp),
                iconModifier = Modifier.size(28.dp),
                suppressInteractionIndication = true,
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            FullPlayerIconButton(
                onClick = onSkipPrevious,
                enabled = canSkip,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous track",
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            FullPlayerIconButton(
                onClick = onTogglePlayback,
                modifier = Modifier.size(82.dp),
            ) {
                Surface(
                    modifier = Modifier.size(70.dp),
                    shape = CircleShape,
                    color = Color.White,
                    contentColor = Color.Black,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(46.dp),
                            tint = Color.Black,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            FullPlayerIconButton(
                onClick = onSkipNext,
                enabled = canSkip,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next track",
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            CircleIconButton(
                imageVector = if (repeatMode == PlaybackRepeatMode.Track) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = when (repeatMode) {
                    PlaybackRepeatMode.None -> "Enable repeat"
                    PlaybackRepeatMode.Queue -> "Repeat playlist"
                    PlaybackRepeatMode.Track -> "Repeat track"
                },
                active = repeatMode != PlaybackRepeatMode.None,
                onClick = { onRepeatModeChange(repeatMode.nextPlaybackRepeatMode()) },
                modifier = Modifier.size(58.dp),
                iconModifier = Modifier.size(28.dp),
                suppressInteractionIndication = true,
            )
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (audioOutput.usesHeadphones) {
                    Icons.Filled.Headphones
                } else {
                    Icons.Filled.PhoneAndroid
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = audioOutput.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        FullPlayerIconButton(
            onClick = onOpenQueue,
            modifier = Modifier.size(48.dp),
        ) {
            QueueLinesIcon()
        }
    }
}

@Composable
private fun QueueLinesIcon(modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.size(24.dp)) {
        val startX = size.width * 0.12f
        val endX = size.width * 0.88f
        val topStroke = size.minDimension * 0.15f
        val regularStroke = size.minDimension * 0.09f
        drawLine(
            color = lineColor,
            start = Offset(startX, size.height * 0.23f),
            end = Offset(endX, size.height * 0.23f),
            strokeWidth = topStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = lineColor,
            start = Offset(startX, size.height * 0.5f),
            end = Offset(endX, size.height * 0.5f),
            strokeWidth = regularStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = lineColor,
            start = Offset(startX, size.height * 0.77f),
            end = Offset(endX, size.height * 0.77f),
            strokeWidth = regularStroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun FullPlayerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun PlaybackRepeatMode.nextPlaybackRepeatMode(): PlaybackRepeatMode {
    return when (this) {
        PlaybackRepeatMode.None -> PlaybackRepeatMode.Queue
        PlaybackRepeatMode.Queue -> PlaybackRepeatMode.Track
        PlaybackRepeatMode.Track -> PlaybackRepeatMode.None
    }
}
