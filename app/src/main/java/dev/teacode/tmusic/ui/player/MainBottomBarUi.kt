package dev.teacode.tmusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track

@Composable
internal fun MainBottomBar(
    visible: Boolean,
    fullPlayerRevealProgress: Float,
    selectedTab: AppTab,
    playerState: PlayerState,
    artworkBitmap: ImageBitmap?,
    animatedPlayerBackground: Boolean,
    queueGeneration: Long,
    canSkipTracks: Boolean,
    previousTrack: Track?,
    nextTrack: Track?,
    onOpenFullPlayer: () -> Unit,
    onExpandDragStart: () -> Unit,
    onExpandDrag: (Float) -> Unit,
    onExpandDragEnd: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSelectTab: (AppTab) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 190),
            initialOffsetY = { it },
        ),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = 190),
            targetOffsetY = { it },
        ),
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                alpha = (1f - fullPlayerRevealProgress).coerceIn(0f, 1f)
            },
        ) {
            MiniPlayer(
                playerState = playerState,
                artworkBitmap = artworkBitmap,
                animatedPlayerBackground = animatedPlayerBackground,
                queueGeneration = queueGeneration,
                canSkip = canSkipTracks,
                previousTrack = previousTrack,
                nextTrack = nextTrack,
                onOpenPlayer = onOpenFullPlayer,
                onExpandDragStart = onExpandDragStart,
                onExpandDrag = onExpandDrag,
                onExpandDragEnd = onExpandDragEnd,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onTogglePlayback = onTogglePlayback,
            )
            NavigationBar(
                modifier = Modifier.height(72.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
            ) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        alwaysShowLabel = false,
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                            )
                        },
                    )
                }
            }
        }
    }
}
