package dev.teacode.tmusic.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun LyricsFullScreen(
    syncedLines: List<SyncedLyricLine>,
    activeLyricIndex: Int,
    plainLyrics: String,
    listState: LazyListState,
    lyricsLoading: Boolean,
    onRefreshLyrics: (() -> Unit)?,
    onSeek: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val refreshRotation = if (lyricsLoading) {
        val refreshTransition = rememberInfiniteTransition(label = "Lyrics refresh")
        val rotation by refreshTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "Lyrics refresh rotation",
        )
        rotation
    } else {
        0f
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        PlayerBottomSheet(
            title = "Lyrics",
            onClose = onClose,
            actions = {
                onRefreshLyrics?.let { refresh ->
                    IconButton(onClick = refresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh lyrics",
                            modifier = Modifier.graphicsLayer {
                                rotationZ = if (lyricsLoading) refreshRotation else 0f
                            },
                        )
                    }
                }
            },
        ) {
            when {
                syncedLines.isNotEmpty() -> SyncedLyricsList(
                    lines = syncedLines,
                    activeIndex = activeLyricIndex,
                    listState = listState,
                    onSeek = onSeek,
                )
                plainLyrics.isBlank() -> NoLyricsState()
                else -> PlainLyricsList(plainLyrics)
            }
        }
    }
}

@Composable
private fun ColumnScope.SyncedLyricsList(
    lines: List<SyncedLyricLine>,
    activeIndex: Int,
    listState: LazyListState,
    onSeek: (Int) -> Unit,
) {
    val centerSyncedLyrics = LocalCenterSyncedLyrics.current
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "${line.timeMs}:$index" }) { index, line ->
            val active = index == activeIndex
            Text(
                text = line.text,
                style = if (active) {
                    MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                    )
                },
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = if (centerSyncedLyrics) TextAlign.Center else TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHorizontalPadding)
                    .clickable { onSeek((line.timeMs / 1000L).toInt().coerceAtLeast(0)) },
            )
        }
    }
}

@Composable
private fun ColumnScope.NoLyricsState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding)
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No lyrics",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColumnScope.PlainLyricsList(plainLyrics: String) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(plainLyrics.lines()) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
            )
        }
    }
}
