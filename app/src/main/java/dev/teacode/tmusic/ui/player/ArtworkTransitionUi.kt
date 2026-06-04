package dev.teacode.tmusic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun <T> SlidingArtworkTransition(
    targetState: T,
    direction: Int,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val normalizedDirection = if (direction < 0) -1 else 1
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            (
                slideInHorizontally(
                    animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                    initialOffsetX = { width -> normalizedDirection * (width * 0.78f).toInt() },
                ) + fadeIn(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing))
                ) togetherWith (
                slideOutHorizontally(
                    animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                    targetOffsetX = { width -> -normalizedDirection * (width * 0.78f).toInt() },
                ) + fadeOut(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
                )
        },
        label = label,
    ) { state ->
        content(state)
    }
}
