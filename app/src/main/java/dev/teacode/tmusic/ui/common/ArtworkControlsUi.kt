package dev.teacode.tmusic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.teacode.tmusic.domain.DownloadState

@Composable
fun ArtworkBox(
    bitmap: ImageBitmap?,
    accentColor: Long,
    modifier: Modifier = Modifier,
    keepPreviousWhileLoading: Boolean = false,
    placeholderIcon: ImageVector? = null,
    placeholderIconSize: Dp = 32.dp,
    placeholderTint: Color = Color.White.copy(alpha = 0.88f),
) {
    var displayedBitmap by remember(accentColor) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bitmap, keepPreviousWhileLoading) {
        if (bitmap != null) {
            displayedBitmap = bitmap
        } else if (!keepPreviousWhileLoading) {
            displayedBitmap = null
        }
    }
    val bitmapToRender = bitmap ?: displayedBitmap.takeIf { keepPreviousWhileLoading }
    Box(
        modifier = modifier.background(Color(accentColor)),
        contentAlignment = Alignment.Center,
    ) {
        bitmapToRender?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: placeholderIcon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = placeholderTint,
                modifier = Modifier.size(placeholderIconSize),
            )
        }
    }
}

@Composable
fun DownloadBadge(downloadState: DownloadState) {
    val badge = when (downloadState) {
        DownloadState.Downloaded -> Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, "Downloaded")
        DownloadState.Queued -> Triple(Icons.Filled.Sync, MaterialTheme.colorScheme.tertiary, "Downloading")
        DownloadState.NotDownloaded -> return
    }
    Icon(
        imageVector = badge.first,
        contentDescription = badge.third,
        modifier = Modifier.size(14.dp),
        tint = badge.second,
    )
}

val ActiveControlRed = Color(0xFFE53935)

@Composable
fun CircleIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier.size(26.dp),
    buttonSize: Dp = 52.dp,
    containerSize: Dp = 40.dp,
    activeContentColor: Color = ActiveControlRed,
    suppressInteractionIndication: Boolean = false,
) {
    @Composable
    fun Content() {
        Surface(
            modifier = Modifier.size(containerSize),
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = if (active) activeContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = contentDescription,
                    modifier = iconModifier,
                )
            }
        }
    }

    if (!suppressInteractionIndication) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(buttonSize),
        ) {
            Content()
        }
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = modifier
                .size(buttonSize)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Content()
        }
    }
}

@Composable
fun CollectionDownloadControls(
    downloadState: DownloadState,
    progressPercent: Int?,
    isPaused: Boolean = false,
    isActive: Boolean = false,
    enabled: Boolean,
    downloadContentDescription: String,
    deleteContentDescription: String,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRemoveDownloadDialog by remember { mutableStateOf(false) }
    val isQueued = downloadState == DownloadState.Queued
    val isPausedDownload = isQueued && isPaused
    val isError = isQueued && !isActive && !isPausedDownload
    val dialogTitle = if (downloadState == DownloadState.Downloaded) {
        "Remove download?"
    } else {
        "Stop download?"
    }
    val dialogText = if (downloadState == DownloadState.Downloaded) {
        "This removes the collection from downloads. Tracks still used by another downloaded playlist or album stay downloaded."
    } else {
        "This stops the current download and removes the collection from downloads. Tracks still used by another downloaded playlist or album stay downloaded."
    }

    CollectionDownloadButton(
        downloadState = downloadState,
        progressPercent = progressPercent,
        isPaused = isPausedDownload,
        isActive = isActive,
        isError = isError,
        enabled = enabled,
        contentDescription = when {
            downloadState == DownloadState.Downloaded -> deleteContentDescription
            isPausedDownload -> "Resume download"
            isActive -> "Pause download"
            else -> downloadContentDescription
        },
        onClick = {
            when (downloadState) {
                DownloadState.Downloaded -> showRemoveDownloadDialog = true
                DownloadState.Queued,
                DownloadState.NotDownloaded,
                -> onDownload()
            }
        },
        onLongClick = if (isQueued || downloadState == DownloadState.Downloaded) {
            { showRemoveDownloadDialog = true }
        } else {
            null
        },
        modifier = modifier,
    ) {
        if (showRemoveDownloadDialog) {
            AlertDialog(
                onDismissRequest = { showRemoveDownloadDialog = false },
                title = { Text(dialogTitle) },
                text = { Text(dialogText) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRemoveDownloadDialog = false
                            onDeleteDownload()
                        },
                    ) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRemoveDownloadDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionDownloadButton(
    downloadState: DownloadState,
    progressPercent: Int?,
    isPaused: Boolean,
    isActive: Boolean,
    isError: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "collection-download-button")
    val activeArrowProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "collection-download-arrow-progress",
    )
    val progress = when (downloadState) {
        DownloadState.Downloaded -> 1f
        DownloadState.Queued -> (progressPercent ?: 0).coerceIn(0, 100) / 100f
        DownloadState.NotDownloaded -> 0f
    }
    val showRing = downloadState != DownloadState.NotDownloaded
    val ringColor = when {
        isError -> MaterialTheme.colorScheme.error
        downloadState == DownloadState.Downloaded -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    val ringTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)
    val iconColor = if (enabled) {
        when {
            isError -> MaterialTheme.colorScheme.error
            downloadState == DownloadState.Downloaded -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (showRing) {
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 2.25.dp.toPx()
                val inset = strokeWidth / 2f
                val arcSize = Size(
                    width = size.width - strokeWidth,
                    height = size.height - strokeWidth,
                )
                drawCircle(
                    color = ringTrackColor,
                    radius = (size.minDimension - strokeWidth) / 2f,
                    style = Stroke(width = strokeWidth),
                )
                if (progress > 0f || downloadState == DownloadState.Downloaded) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }
        }
        DownloadGlyph(
            contentDescription = contentDescription,
            color = iconColor,
            arrowProgress = if (isActive && !isPaused) activeArrowProgress else 0f,
            animateArrow = isActive && !isPaused,
            modifier = Modifier.size(25.dp),
        )
        when {
            downloadState == DownloadState.Downloaded -> DownloadStateBadge(
                imageVector = Icons.Filled.CheckCircle,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 5.dp, bottom = 5.dp),
            )
            isPaused -> DownloadStateBadge(
                imageVector = Icons.Filled.PlayArrow,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 5.dp, bottom = 5.dp),
            )
            isError -> DownloadStateBadge(
                imageVector = Icons.Filled.Close,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 5.dp, bottom = 5.dp),
            )
        }
        content()
    }
}

@Composable
private fun DownloadGlyph(
    contentDescription: String,
    color: Color,
    arrowProgress: Float,
    animateArrow: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.35.dp.toPx()
            val centerX = size.width / 2f
            val trayY = size.height * 0.82f
            val trayHalfWidth = size.width * 0.32f
            val safeProgress = arrowProgress.coerceIn(0f, 1f)
            val travelY = if (animateArrow) {
                (-size.height * 0.22f) + (size.height * 0.52f * safeProgress)
            } else {
                0f
            }
            val arrowAlpha = if (!animateArrow) {
                1f
            } else {
                when {
                    safeProgress < 0.12f -> (safeProgress / 0.12f).coerceIn(0f, 1f)
                    safeProgress > 0.68f -> ((1f - safeProgress) / 0.32f).coerceIn(0f, 1f)
                    else -> 1f
                }
            }
            val arrowColor = color.copy(alpha = color.alpha * arrowAlpha)
            val arrowTopY = size.height * 0.18f + travelY
            val arrowBottomY = (size.height * 0.58f + travelY).coerceAtMost(trayY - strokeWidth * 0.55f)
            val headHalfWidth = size.width * 0.20f
            val headTopY = (size.height * 0.44f + travelY).coerceAtMost(trayY - strokeWidth * 1.2f)
            drawLine(
                color = color,
                start = Offset(centerX - trayHalfWidth, trayY),
                end = Offset(centerX + trayHalfWidth, trayY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = arrowColor,
                start = Offset(centerX, arrowTopY),
                end = Offset(centerX, arrowBottomY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = arrowColor,
                start = Offset(centerX, arrowBottomY),
                end = Offset(centerX - headHalfWidth, headTopY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = arrowColor,
                start = Offset(centerX, arrowBottomY),
                end = Offset(centerX + headHalfWidth, headTopY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = contentDescription,
            modifier = Modifier.size(1.dp),
            tint = Color.Transparent,
        )
    }
}

@Composable
private fun DownloadStateBadge(
    imageVector: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(16.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
fun DownloadCircleButton(
    downloadState: DownloadState,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    animateQueued: Boolean = true,
) {
    val rotation = if (downloadState == DownloadState.Queued && animateQueued) {
        val transition = rememberInfiniteTransition(label = "download-spinner")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 950, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "download-spinner-rotation",
        ).value
    } else {
        0f
    }
    CircleIconButton(
        imageVector = when (downloadState) {
            DownloadState.Downloaded -> Icons.Filled.CheckCircle
            DownloadState.Queued -> Icons.Filled.Sync
            DownloadState.NotDownloaded -> Icons.Filled.Download
        },
        contentDescription = contentDescription,
        active = downloadState == DownloadState.Downloaded,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        iconModifier = Modifier
            .size(26.dp)
            .graphicsLayer { rotationZ = rotation },
    )
}

fun Context.drawableResourceBitmap(resourceId: Int): ImageBitmap? {
    val drawable = ContextCompat.getDrawable(this, resourceId) ?: return null
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
