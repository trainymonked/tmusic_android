package dev.teacode.tmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.teacode.tmusic.data.AppUpdateInfo

@Composable
internal fun UpdateAvailableDialog(
    update: AppUpdateInfo,
    status: String?,
    actionLabel: String,
    actionEnabled: Boolean,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = !update.forceUpdate,
        ),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .heightIn(min = 520.dp, max = 720.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Update available",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (update.forceUpdate) {
                            Surface(
                                shape = RoundedCornerShape(99.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) {
                                Text(
                                    text = "Required",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = if (update.forceUpdate) {
                            update.title.ifBlank { "Update required" }
                        } else {
                            update.title.ifBlank { update.version }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!status.isNullOrBlank()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UpdateReleaseNotes(
                    update = update,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 20.dp, top = 14.dp, end = 14.dp, bottom = 10.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text("Later")
                    }
                    Button(
                        onClick = onUpdate,
                        enabled = actionEnabled,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateReleaseNotes(
    update: AppUpdateInfo,
    modifier: Modifier = Modifier,
) {
    val releaseNotes = remember(update.changelog) {
        update.changelog.markdownReleaseNoteLines()
    }
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (releaseNotes.isEmpty()) {
                Text(
                    text = "No release notes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                releaseNotes.forEach { line ->
                    Text(
                        text = line.text,
                        style = if (line.isHeading) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (line.isHeading) FontWeight.SemiBold else null,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val scrollRange = scrollState.maxValue
        val viewportSize = scrollState.viewportSize
        if (scrollRange > 0 && viewportSize > 0) {
            val thumbFraction = (viewportSize.toFloat() / (viewportSize + scrollRange).toFloat())
                .coerceIn(0.35f, 1f)
            ScrollIndicator(
                scrollFraction = (scrollState.value.toFloat() / scrollRange).coerceIn(0f, 1f),
                thumbFraction = thumbFraction,
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ScrollIndicator(
    scrollFraction: Float,
    thumbFraction: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .width(3.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        val thumbHeight = (maxHeight * thumbFraction).coerceAtLeast(54.dp).coerceAtMost(maxHeight)
        val maxOffset = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(thumbHeight)
                .offset(y = maxOffset * scrollFraction)
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

private data class ReleaseNoteLine(
    val text: String,
    val isHeading: Boolean,
)

private fun String.markdownReleaseNoteLines(): List<ReleaseNoteLine> {
    return lineSequence()
        .mapNotNull { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("```")) {
                return@mapNotNull null
            }
            val headingLevel = line.takeWhile { it == '#' }.length
            val withoutHeading = if (headingLevel > 0) {
                line.drop(headingLevel).trim()
            } else {
                line
            }
            val isBullet = withoutHeading.startsWith("- ") ||
                withoutHeading.startsWith("* ") ||
                withoutHeading.startsWith("+ ")
            val isNumbered = withoutHeading.matches(Regex("""^\d+\.\s+.*"""))
            val content = when {
                isBullet -> withoutHeading.drop(2)
                isNumbered -> withoutHeading.replaceFirst(Regex("""^\d+\.\s+"""), "")
                else -> withoutHeading
            }.cleanInlineMarkdown()
            if (content.isBlank()) {
                null
            } else {
                ReleaseNoteLine(
                    text = if (isBullet || isNumbered) "- $content" else content,
                    isHeading = headingLevel > 0,
                )
            }
        }
        .toList()
}

private fun String.cleanInlineMarkdown(): String {
    return replace(Regex("""!\[([^]]*)]\(([^)]+)\)"""), "$1")
        .replace(Regex("""\[([^]]+)]\(([^)]+)\)"""), "$1 ($2)")
        .replace(Regex("""`([^`]*)`"""), "$1")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("**", "")
        .replace("__", "")
        .replace("~~", "")
        .replace("*", "")
        .trim()
}
