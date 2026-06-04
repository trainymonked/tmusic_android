package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
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
            IconButton(
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
            IconButton(
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
            )
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(
        onClick = onOpenQueue,
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Queue")
    }
}

private fun PlaybackRepeatMode.nextPlaybackRepeatMode(): PlaybackRepeatMode {
    return when (this) {
        PlaybackRepeatMode.None -> PlaybackRepeatMode.Queue
        PlaybackRepeatMode.Queue -> PlaybackRepeatMode.Track
        PlaybackRepeatMode.Track -> PlaybackRepeatMode.None
    }
}
