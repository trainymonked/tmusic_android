package dev.teacode.tmusic.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal fun artworkBitmapKey(
    artworkKey: String,
    imageSize: ArtworkImageSize,
): String {
    return "${imageSize.name}:$artworkKey"
}

internal fun artworkCacheKey(
    artworkKey: String,
    imageSize: ArtworkImageSize,
): String {
    return artworkKey
}

internal fun legacyArtworkCacheKey(
    artworkKey: String,
    imageSize: ArtworkImageSize,
): String {
    return artworkBitmapKey(artworkKey, imageSize)
}

internal fun artworkSourceKey(bitmapKey: String): String {
    ArtworkImageSize.entries.forEach { imageSize ->
        val prefix = "${imageSize.name}:"
        if (bitmapKey.startsWith(prefix)) {
            return bitmapKey.removePrefix(prefix)
        }
    }
    return bitmapKey
}

fun Map<String, ImageBitmap>.artworkBitmap(
    artworkKey: String?,
    imageSize: ArtworkImageSize,
): ImageBitmap? {
    return artworkKey?.let { get(artworkBitmapKey(it, imageSize)) }
}

internal suspend fun decodeArtworkBitmap(path: String, maxSizePx: Int): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        decodeSampledBitmap(path, maxSizePx)?.asImageBitmap()
    }
}

private fun decodeSampledBitmap(path: String, maxSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, bounds)
    val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
    if (largestSide <= 0) {
        return BitmapFactory.decodeFile(path)
    }

    var sampleSize = 1
    while (largestSide / (sampleSize * 2) >= maxSizePx) {
        sampleSize *= 2
    }

    val decoded = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.RGB_565
        },
    ) ?: return null
    val decodedLargestSide = maxOf(decoded.width, decoded.height)
    if (decodedLargestSide <= maxSizePx) {
        return decoded
    }
    val scale = maxSizePx.toFloat() / decodedLargestSide.toFloat()
    val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
    val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(decoded, width, height, true).also { scaled ->
        if (scaled != decoded) {
            decoded.recycle()
        }
    }
}
