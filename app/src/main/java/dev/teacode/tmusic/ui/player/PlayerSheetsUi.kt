package dev.teacode.tmusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val PLAYER_SHEET_COLLAPSED_FRACTION = 0.72f
private const val PLAYER_SHEET_CLOSE_FRACTION = 0.48f

@Composable
fun PlayerBottomSheet(
    title: String,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settledHeightFraction = remember { Animatable(PLAYER_SHEET_COLLAPSED_FRACTION) }
    var draggedHeightFraction by remember { mutableStateOf<Float?>(null) }
    var containerHeightPx by remember { mutableFloatStateOf(1f) }
    val sheetHeightFraction = draggedHeightFraction ?: settledHeightFraction.value
    val sheetInteraction = remember { MutableInteractionSource() }
    val scrimInteraction = remember { MutableInteractionSource() }

    fun applySheetDrag(delta: Float): Boolean {
        val heightPx = containerHeightPx.coerceAtLeast(1f)
        val currentFraction = draggedHeightFraction ?: settledHeightFraction.value
        val nextFraction = (currentFraction - delta / heightPx).coerceIn(0.25f, 1f)
        if (nextFraction == currentFraction) {
            return false
        }
        draggedHeightFraction = nextFraction
        return true
    }

    fun settleSheet(releasedFraction: Float) {
        val targetFraction = when {
            releasedFraction < PLAYER_SHEET_CLOSE_FRACTION -> {
                onClose()
                PLAYER_SHEET_COLLAPSED_FRACTION
            }
            releasedFraction >= (1f + PLAYER_SHEET_COLLAPSED_FRACTION) / 2f -> 1f
            else -> PLAYER_SHEET_COLLAPSED_FRACTION
        }
        scope.launch {
            settledHeightFraction.snapTo(releasedFraction)
            draggedHeightFraction = null
            settledHeightFraction.animateTo(
                targetValue = targetFraction,
                animationSpec = tween(durationMillis = 220),
            )
        }
    }

    fun Modifier.sheetDragInput(): Modifier = pointerInput(containerHeightPx) {
        detectVerticalDragGestures(
            onDragStart = {
                scope.launch { settledHeightFraction.stop() }
                draggedHeightFraction = settledHeightFraction.value
            },
            onVerticalDrag = { change, delta ->
                change.consume()
                applySheetDrag(delta)
            },
            onDragEnd = {
                val releasedFraction = draggedHeightFraction ?: settledHeightFraction.value
                settleSheet(releasedFraction)
            },
            onDragCancel = {
                val releasedFraction = draggedHeightFraction ?: settledHeightFraction.value
                scope.launch {
                    settledHeightFraction.snapTo(releasedFraction)
                    draggedHeightFraction = null
                    settledHeightFraction.animateTo(
                        targetValue = PLAYER_SHEET_COLLAPSED_FRACTION,
                        animationSpec = tween(durationMillis = 180),
                    )
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size -> containerHeightPx = size.height.toFloat().coerceAtLeast(1f) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.46f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onClose,
                ),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(sheetHeightFraction)
                .clickable(
                    interactionSource = sheetInteraction,
                    indication = null,
                    onClick = {},
                ),
            color = MaterialTheme.colorScheme.background,
            shape = if (sheetHeightFraction >= 0.98f) {
                RoundedCornerShape(0.dp)
            } else {
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
            ) {
                SheetDragHandle(
                    modifier = Modifier.sheetDragInput(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = ScreenHorizontalPadding,
                            end = ScreenHorizontalPadding,
                            bottom = 10.dp,
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        actions()
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close $title",
                            )
                        }
                    }
                }
                content()
            }
        }
    }
}

@Composable
private fun SheetDragHandle(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(modifier)
                .size(width = 72.dp, height = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)),
            )
        }
    }
}
