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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.delay

internal data class SyncedLyricLine(
    val timeMs: Long,
    val text: String,
)

@Composable
fun LyricsBlock(
    lyrics: TrackLyrics?,
    lyricsUnavailable: Boolean,
    lyricsLoading: Boolean,
    progressSeconds: Int,
    onRefreshLyrics: (() -> Unit)?,
) {
    var showFullLyrics by remember(lyrics) { mutableStateOf(false) }
    val syncedLines = remember(lyrics?.syncedLyrics) {
        lyrics?.syncedLyrics?.parseSyncedLyrics().orEmpty()
    }
    val lines = remember(syncedLines, progressSeconds) {
        syncedLines.twoVisibleLyricLines(progressSeconds + 1)
    }
    val activeLyricIndex = remember(syncedLines, progressSeconds) {
        syncedLines.activeLyricIndex(progressSeconds + 1)
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
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
            lines.isNotEmpty() -> {
                AnimatedContent(
                    targetState = activeLyricIndex to lines,
                    transitionSpec = {
                        slideInVertically(
                            animationSpec = tween(durationMillis = 220),
                            initialOffsetY = { height -> height },
                        ) togetherWith slideOutVertically(
                            animationSpec = tween(durationMillis = 220),
                            targetOffsetY = { height -> -height },
                        )
                    },
                    label = "Synced lyrics preview",
                ) { (_, visibleLines) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFullLyrics = true },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        visibleLines.take(2).forEachIndexed { index, line ->
                            val active = index == 0
                            Text(
                                text = line,
                                style = if (active) {
                                    when {
                                        line.length <= 34 -> MaterialTheme.typography.headlineSmall.copy(
                                            fontSize = 25.sp,
                                            lineHeight = 29.sp,
                                        )
                                        line.length <= 72 -> MaterialTheme.typography.titleLarge.copy(
                                            fontSize = 21.sp,
                                            lineHeight = 25.sp,
                                        )
                                        else -> MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 18.sp,
                                            lineHeight = 22.sp,
                                        )
                                    }
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = if (active) 2 else 1,
                                softWrap = active,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
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
            delay(80)
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
            onRefreshLyrics = onRefreshLyrics,
            onClose = { showFullLyrics = false },
        )
    }
}

private fun List<SyncedLyricLine>.twoVisibleLyricLines(progressSeconds: Int): List<String> {
    if (isEmpty()) {
        return emptyList()
    }
    val progressMs = progressSeconds.toLong().coerceAtLeast(0L) * 1000L
    val currentIndex = indexOfLast { it.timeMs <= progressMs }
    if (currentIndex >= lastIndex) {
        return listOf("\u2022\u2022\u2022")
    }
    val currentText = if (currentIndex < 0) "\u2022\u2022\u2022" else getOrNull(currentIndex)?.text.orEmpty()
    val nextText = getOrNull(currentIndex + 1)?.text.orEmpty()
    return listOf(
        currentText.ifBlank { "\u2022\u2022\u2022" },
        nextText.ifBlank { "\u2022\u2022\u2022" },
    )
}

private fun List<SyncedLyricLine>.activeLyricIndex(progressSeconds: Int): Int {
    if (isEmpty()) {
        return -1
    }
    val progressMs = progressSeconds.toLong().coerceAtLeast(0L) * 1000L
    return indexOfLast { it.timeMs <= progressMs }
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
        .filter { it.text.isNotBlank() }
        .toList()
}
