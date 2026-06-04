package dev.teacode.tmusic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    primaryContainer = DarkSurfaceHigh,
    onPrimaryContainer = PureWhite,
    secondary = MutedTextDark,
    onSecondary = PureBlack,
    secondaryContainer = DarkSurface,
    onSecondaryContainer = PureWhite,
    tertiary = Color(0xFFE6E6E6),
    onTertiary = PureBlack,
    tertiaryContainer = Color(0xFF303030),
    onTertiaryContainer = PureWhite,
    background = PureBlack,
    onBackground = PureWhite,
    surface = NearBlack,
    onSurface = PureWhite,
    surfaceContainer = DarkSurface,
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = MutedTextDark,
    outline = Color(0xFF8F8F8F),
    error = ErrorRedDark,
)

@Composable
fun TMusicTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
