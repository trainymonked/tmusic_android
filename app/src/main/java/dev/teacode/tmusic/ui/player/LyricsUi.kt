package dev.teacode.tmusic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.teacode.tmusic.domain.TrackLyrics

private const val SYNCED_LYRICS_TRANSITION_DURATION_MS = 220

internal data class SyncedLyricLine(
    val timeMs: Long,
    val text: String,
)

internal val LocalPlaybackPositionMs = staticCompositionLocalOf<() -> Long> { { -1L } }
internal val LocalShowOnlyActiveSyncedLyrics = staticCompositionLocalOf { false }
internal val LocalCenterSyncedLyrics = staticCompositionLocalOf { true }

@Composable
fun LyricsBlock(
    trackId: String,
    lyrics: TrackLyrics?,
    lyricsUnavailable: Boolean,
    lyricsLoading: Boolean,
    isPlaying: Boolean,
    progressSeconds: Int,
    onRefreshLyrics: (() -> Unit)?,
    onSeek: (Int) -> Unit,
) {
    var showFullLyrics by remember(lyrics) { mutableStateOf(false) }
    val syncedLines = remember(lyrics?.syncedLyrics) {
        lyrics?.syncedLyrics?.parseSyncedLyrics().orEmpty()
    }
    val showOnlyActiveSyncedLyrics = LocalShowOnlyActiveSyncedLyrics.current
    val centerSyncedLyrics = LocalCenterSyncedLyrics.current
    val currentPlaybackPositionMs = LocalPlaybackPositionMs.current
    var activeLyricIndex by remember(syncedLines) {
        mutableIntStateOf(
            syncedLines.activeLyricIndexAt(
                progressSeconds.toLong().coerceAtLeast(0L) * 1000L +
                    SYNCED_LYRICS_TRANSITION_DURATION_MS,
            ),
        )
    }
    val lines = remember(syncedLines, activeLyricIndex) {
        syncedLines.twoVisibleLyricLines(activeLyricIndex)
    }
    val previewLines = remember(lines, showOnlyActiveSyncedLyrics) {
        if (showOnlyActiveSyncedLyrics) lines.take(1) else lines
    }
    val fullLyricsListState = rememberLazyListState()
    val plainLyrics = lyrics?.plainLyrics
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.joinToString("\n")
        .orEmpty()

    val hasNoLyrics = lyricsUnavailable ||
        (lyrics == null && !lyricsLoading) ||
        lyrics?.instrumental == true ||
        (lyrics != null && syncedLines.isEmpty() && plainLyrics.isBlank())
    val hasPlainOnly = syncedLines.isEmpty() && plainLyrics.isNotBlank()

    LaunchedEffect(trackId) {
        fullLyricsListState.scrollToItem(0)
    }

    LaunchedEffect(syncedLines, isPlaying, progressSeconds, currentPlaybackPositionMs) {
        if (syncedLines.isEmpty()) {
            return@LaunchedEffect
        }
        do {
            val exactPositionMs = currentPlaybackPositionMs()
                .takeIf { positionMs -> positionMs >= 0L }
                ?: progressSeconds.toLong().coerceAtLeast(0L) * 1000L
            activeLyricIndex = syncedLines.activeLyricIndexAt(
                exactPositionMs + SYNCED_LYRICS_TRANSITION_DURATION_MS,
            )
            if (!isPlaying) {
                break
            }
            withFrameNanos { }
        } while (true)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            lyricsLoading && lyrics == null && !lyricsUnavailable -> {
                Text(
                    text = "Loading lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            previewLines.isNotEmpty() -> {
                AnimatedContent(
                    targetState = activeLyricIndex to previewLines,
                    transitionSpec = {
                        slideInVertically(
                            animationSpec = tween(durationMillis = SYNCED_LYRICS_TRANSITION_DURATION_MS),
                            initialOffsetY = { height -> height },
                        ) togetherWith slideOutVertically(
                            animationSpec = tween(durationMillis = SYNCED_LYRICS_TRANSITION_DURATION_MS),
                            targetOffsetY = { height -> -height },
                        )
                    },
                    label = "Synced lyrics preview",
                ) { (_, visibleLines) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (showOnlyActiveSyncedLyrics) 88.dp else 92.dp)
                            .padding(top = if (showOnlyActiveSyncedLyrics) 8.dp else 0.dp)
                            .clickable { showFullLyrics = true },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = if (centerSyncedLyrics) {
                            Alignment.CenterHorizontally
                        } else {
                            Alignment.Start
                        },
                    ) {
                        visibleLines.take(2).forEachIndexed { index, line ->
                            val active = index == 0
                            var activeLineSizeStep by remember(
                                activeLyricIndex,
                                line,
                                showOnlyActiveSyncedLyrics,
                            ) {
                                mutableIntStateOf(0)
                            }
                            Text(
                                text = line,
                                style = if (active) {
                                    when (activeLineSizeStep) {
                                        0 -> MaterialTheme.typography.titleLarge.copy(
                                            fontSize = 20.sp,
                                            lineHeight = 24.sp,
                                        )
                                        1 -> MaterialTheme.typography.titleMedium
                                        else -> MaterialTheme.typography.titleSmall
                                    }
                                } else {
                                    MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 15.sp,
                                        lineHeight = 19.sp,
                                    )
                                },
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = when {
                                    !active -> 1
                                    activeLineSizeStep > 0 || showOnlyActiveSyncedLyrics -> 3
                                    else -> 2
                                },
                                softWrap = active,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = if (centerSyncedLyrics) TextAlign.Center else TextAlign.Start,
                                onTextLayout = { result ->
                                    if (active && result.hasVisualOverflow && activeLineSizeStep < 2) {
                                        activeLineSizeStep += 1
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            hasPlainOnly -> {
                Text(
                    text = "No synced lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFullLyrics = true }
                        .padding(vertical = 6.dp),
                )
            }
            hasNoLyrics -> {
                Text(
                    text = "No lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFullLyrics = true }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }

    LaunchedEffect(showFullLyrics, activeLyricIndex, syncedLines.size) {
        if (showFullLyrics && syncedLines.isNotEmpty() && activeLyricIndex >= 0) {
            val viewportHeight = fullLyricsListState.layoutInfo.viewportSize.height
            val centerOffset = if (viewportHeight > 0) {
                -(viewportHeight * 0.42f).toInt()
            } else {
                0
            }
            fullLyricsListState.animateScrollToItem(activeLyricIndex, scrollOffset = centerOffset)
        }
    }

    if (showFullLyrics) {
        LyricsFullScreen(
            syncedLines = syncedLines,
            activeLyricIndex = activeLyricIndex,
            plainLyrics = plainLyrics,
            listState = fullLyricsListState,
            lyricsLoading = lyricsLoading,
            onRefreshLyrics = onRefreshLyrics,
            onSeek = onSeek,
            onClose = { showFullLyrics = false },
        )
    }
}

private fun List<SyncedLyricLine>.twoVisibleLyricLines(currentIndex: Int): List<String> {
    if (isEmpty()) {
        return emptyList()
    }
    if (currentIndex >= lastIndex) {
        return listOf(getOrNull(currentIndex)?.text.orEmpty().ifBlank { "\u2022\u2022\u2022" })
    }
    val currentText = if (currentIndex < 0) "\u2022\u2022\u2022" else getOrNull(currentIndex)?.text.orEmpty()
    val nextText = getOrNull(currentIndex + 1)?.text.orEmpty()
    return listOf(
        currentText.ifBlank { "\u2022\u2022\u2022" },
        nextText.ifBlank { "\u2022\u2022\u2022" },
    )
}

private fun List<SyncedLyricLine>.activeLyricIndexAt(progressMs: Long): Int {
    if (isEmpty()) {
        return -1
    }
    return indexOfLast { it.timeMs <= progressMs.coerceAtLeast(0L) }
}

private fun String.parseSyncedLyrics(): List<SyncedLyricLine> {
    val lineRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)""")
    return lineSequence()
        .mapNotNull { line ->
            val match = lineRegex.matchEntire(line.trim()) ?: return@mapNotNull null
            val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val fraction = match.groupValues[3]
            val fractionMs = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100L
                2 -> fraction.toLong() * 10L
                else -> fraction.take(3).toLong()
            }
            SyncedLyricLine(
                timeMs = minutes * 60_000L + seconds * 1000L + fractionMs,
                text = match.groupValues[4].trim(),
            )
        }
        .toList()
}
