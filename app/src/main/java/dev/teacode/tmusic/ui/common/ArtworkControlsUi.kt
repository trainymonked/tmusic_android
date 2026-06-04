package dev.teacode.tmusic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
) {
    var displayedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bitmap, keepPreviousWhileLoading) {
        if (bitmap != null) {
            displayedBitmap = bitmap
        } else if (!keepPreviousWhileLoading) {
            displayedBitmap = null
        }
    }
    Box(
        modifier = modifier.background(Color(accentColor)),
        contentAlignment = Alignment.Center,
    ) {
        displayedBitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
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
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(buttonSize),
    ) {
        Surface(
            modifier = Modifier.size(containerSize),
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = if (active) ActiveControlRed else MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@Composable
fun DownloadCircleButton(
    downloadState: DownloadState,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val rotation = if (downloadState == DownloadState.Queued) {
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
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
