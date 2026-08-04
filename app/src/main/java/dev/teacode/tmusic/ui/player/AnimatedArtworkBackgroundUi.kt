package dev.teacode.tmusic.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val BACKGROUND_COLORS_DURATION_MS = 36_000
private const val BACKGROUND_BREATH_DURATION_MS = 18_000
private const val BACKGROUND_COLOR_COUNT = 6
private val TWO_PI = (PI * 2.0).toFloat()
private val BACKGROUND_X_FREQUENCIES = intArrayOf(1, 1, 2, 1, 2, 1)
private val BACKGROUND_Y_FREQUENCIES = intArrayOf(1, 2, 1, 2, 1, 2)

private class ArtworkColorBucket {
    var count: Int = 0
    var redSum: Int = 0
    var greenSum: Int = 0
    var blueSum: Int = 0

    fun add(red: Int, green: Int, blue: Int) {
        count += 1
        redSum += red
        greenSum += green
        blueSum += blue
    }

    fun color(): Color {
        val divisor = count.coerceAtLeast(1) * 255f
        return Color(
            red = redSum / divisor,
            green = greenSum / divisor,
            blue = blueSum / divisor,
            alpha = 1f,
        )
    }
}

@Composable
internal fun AnimatedArtworkBackground(
    artworkBitmap: ImageBitmap?,
    accentColor: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val themeBackground = MaterialTheme.colorScheme.background
    val palette = remember(artworkBitmap, accentColor) {
        extractArtworkPalette(artworkBitmap, Color(accentColor))
    }
    val noiseBrush = remember { createBackgroundNoiseBrush() }
    val colorMotion = remember { Animatable(0f) }
    val breath = remember { Animatable(0f) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            return@LaunchedEffect
        }
        while (true) {
            if (colorMotion.value >= 0.999f) {
                colorMotion.snapTo(0f)
            }
            val remainingDuration = (
                BACKGROUND_COLORS_DURATION_MS * (1f - colorMotion.value)
                ).roundToInt().coerceAtLeast(1)
            colorMotion.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = remainingDuration,
                    easing = LinearEasing,
                ),
            )
            colorMotion.snapTo(0f)
        }
    }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            return@LaunchedEffect
        }
        while (true) {
            if (breath.value >= 0.999f) {
                breath.snapTo(0f)
            }
            val remainingDuration = (
                BACKGROUND_BREATH_DURATION_MS * (1f - breath.value)
                ).roundToInt().coerceAtLeast(1)
            breath.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = remainingDuration,
                    easing = LinearEasing,
                ),
            )
            breath.snapTo(0f)
        }
    }

    val isDarkTheme = themeBackground.luminance() < 0.5f
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val colorsPhase = colorMotion.value * TWO_PI
            val breathPhase = breath.value * TWO_PI
            val breathAmount = (sin(breathPhase) + 1f) / 2f
            val saturationFactor = 0.90f + breathAmount * 0.18f
            val brightnessFactor = 0.94f + breathAmount * 0.10f
            drawRect(themeBackground)
            val largestDimension = max(size.width, size.height)
            palette.forEachIndexed { index, color ->
                val xFrequency = BACKGROUND_X_FREQUENCIES[index]
                val yFrequency = BACKGROUND_Y_FREQUENCIES[index]
                val direction = if (index % 2 == 0) 1f else -1f
                val trajectoryOffset = index * (TWO_PI / BACKGROUND_COLOR_COUNT)
                val center = Offset(
                    x = size.width * (
                        0.5f + 0.36f * sin(
                            colorsPhase * xFrequency * direction + trajectoryOffset,
                        )
                        ),
                    y = size.height * (
                        0.5f + 0.38f * cos(
                            colorsPhase * yFrequency + trajectoryOffset * 1.7f,
                        )
                        ),
                )
                val individualBreath = (sin(breathPhase + index * 0.72f) + 1f) / 2f
                val radius = largestDimension * (0.35f + individualBreath * 0.09f)
                val animatedColor = color.adjustSaturationAndBrightness(
                    saturationFactor = saturationFactor,
                    brightnessFactor = brightnessFactor,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedColor.copy(alpha = 0.88f),
                            animatedColor.copy(alpha = 0.38f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            drawRect(
                color = themeBackground.copy(alpha = if (isDarkTheme) 0.28f else 0.50f),
            )
            drawRect(
                color = Color.Black.copy(alpha = if (isDarkTheme) 0.18f else 0.05f),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = noiseBrush)
        }
    }
}

private fun createBackgroundNoiseBrush(): ShaderBrush {
    val tileSize = 128
    val random = Random(0x544D5553)
    val pixels = IntArray(tileSize * tileSize) {
        val channel = if (random.nextBoolean()) 0xFF else 0x00
        val alpha = random.nextInt(from = 3, until = 11)
        (alpha shl 24) or (channel shl 16) or (channel shl 8) or channel
    }
    val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize)
    }
    return ShaderBrush(
        ImageShader(
            image = bitmap.asImageBitmap(),
            tileModeX = TileMode.Repeated,
            tileModeY = TileMode.Repeated,
        ),
    )
}

private fun extractArtworkPalette(
    artworkBitmap: ImageBitmap?,
    fallbackColor: Color,
): List<Color> {
    val fallback = fallbackArtworkPalette(fallbackColor)
    val bitmap = runCatching { artworkBitmap?.asAndroidBitmap() }.getOrNull() ?: return fallback
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        return fallback
    }
    return runCatching {
        val buckets = mutableMapOf<Int, ArtworkColorBucket>()
        val horizontalSamples = minOf(18, bitmap.width)
        val verticalSamples = minOf(18, bitmap.height)
        for (yIndex in 0 until verticalSamples) {
            val y = (((yIndex + 0.5f) * bitmap.height) / verticalSamples)
                .toInt()
                .coerceIn(0, bitmap.height - 1)
            for (xIndex in 0 until horizontalSamples) {
                val x = (((xIndex + 0.5f) * bitmap.width) / horizontalSamples)
                    .toInt()
                    .coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                if ((pixel ushr 24) < 128) {
                    continue
                }
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF
                val luminance = colorLuminance(red, green, blue)
                if (luminance <= 0.025f || luminance >= 0.975f) {
                    continue
                }
                val key = ((red shr 5) shl 6) or ((green shr 5) shl 3) or (blue shr 5)
                buckets.getOrPut(key, ::ArtworkColorBucket).add(red, green, blue)
            }
        }

        val rankedColors = buckets.values
            .sortedByDescending { bucket ->
                val color = bucket.color()
                val saturation = colorSaturation(color)
                val middleLuminanceWeight = 1f - abs(color.luminance() - 0.5f) * 0.35f
                bucket.count * (0.55f + saturation * 1.45f) * middleLuminanceWeight
            }
            .map(ArtworkColorBucket::color)
        val selected = mutableListOf<Color>()
        rankedColors.forEach { candidate ->
            if (selected.none { color -> colorDistanceSquared(color, candidate) < 0.055f }) {
                selected += candidate
            }
            if (selected.size == BACKGROUND_COLOR_COUNT) {
                return@runCatching selected
            }
        }
        fallback.forEach { candidate ->
            if (selected.none { color -> colorDistanceSquared(color, candidate) < 0.018f }) {
                selected += candidate
            }
            if (selected.size == BACKGROUND_COLOR_COUNT) {
                return@runCatching selected
            }
        }
        (selected + fallback).take(BACKGROUND_COLOR_COUNT)
    }.getOrDefault(fallback)
}

private fun fallbackArtworkPalette(color: Color): List<Color> {
    val base = color.copy(alpha = 1f)
    return listOf(
        base,
        base.mix(Color.White, 0.24f),
        Color(base.green, base.blue, base.red).mix(base, 0.38f),
        Color(base.blue, base.red, base.green).mix(Color.Black, 0.18f),
        Color(base.red, base.blue, base.green).mix(Color.White, 0.12f),
        base.mix(Color.Black, 0.26f),
    )
}

private fun Color.adjustSaturationAndBrightness(
    saturationFactor: Float,
    brightnessFactor: Float,
): Color {
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    fun adjusted(channel: Float): Float {
        val saturated = luminance + (channel - luminance) * saturationFactor
        return if (brightnessFactor >= 1f) {
            saturated + (1f - saturated) * (brightnessFactor - 1f)
        } else {
            saturated * brightnessFactor
        }.coerceIn(0f, 1f)
    }
    return Color(
        red = adjusted(red),
        green = adjusted(green),
        blue = adjusted(blue),
        alpha = 1f,
    )
}

private fun Color.mix(other: Color, amount: Float): Color {
    val boundedAmount = amount.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * boundedAmount,
        green = green + (other.green - green) * boundedAmount,
        blue = blue + (other.blue - blue) * boundedAmount,
        alpha = 1f,
    )
}

private fun colorSaturation(color: Color): Float {
    val maximum = maxOf(color.red, color.green, color.blue)
    val minimum = minOf(color.red, color.green, color.blue)
    return if (maximum <= 0f) 0f else (maximum - minimum) / maximum
}

private fun colorDistanceSquared(first: Color, second: Color): Float {
    val redDifference = first.red - second.red
    val greenDifference = first.green - second.green
    val blueDifference = first.blue - second.blue
    return redDifference * redDifference +
        greenDifference * greenDifference +
        blueDifference * blueDifference
}

private fun colorLuminance(red: Int, green: Int, blue: Int): Float {
    return 0.2126f * red / 255f + 0.7152f * green / 255f + 0.0722f * blue / 255f
}
