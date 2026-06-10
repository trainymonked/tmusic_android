package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onRefreshLyrics: (() -> Unit)?,
    onSeek: (Int) -> Unit,
    onClose: () -> Unit,
) {
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
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "${line.timeMs}:$index" }) { index, line ->
            val active = index == activeIndex
            Text(
                text = line.text.ifBlank { "\u2022\u2022\u2022" },
                style = if (active) {
                    MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 30.sp,
                        lineHeight = 36.sp,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 21.sp,
                        lineHeight = 28.sp,
                    )
                },
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(plainLyrics.lines()) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
