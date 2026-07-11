package dev.teacode.tmusic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class AppThemeMode(
    val storageValue: String,
    val label: String,
) {
    Dark("dark", "Dark"),
    Light("light", "Light"),
    System("system", "System");

    companion object {
        fun fromStorageValue(value: String?): AppThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: Dark
        }
    }
}

data class AppThemeController(
    val themeMode: AppThemeMode,
    val onThemeModeChange: (AppThemeMode) -> Unit,
)

val LocalAppThemeController = staticCompositionLocalOf {
    AppThemeController(
        themeMode = AppThemeMode.System,
        onThemeModeChange = {},
    )
}

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

private val LightColorScheme = lightColorScheme(
    primary = PureBlack,
    onPrimary = PureWhite,
    primaryContainer = LightSurfaceHigh,
    onPrimaryContainer = LightText,
    secondary = MutedTextLight,
    onSecondary = PureWhite,
    secondaryContainer = LightSurfaceHigh,
    onSecondaryContainer = LightText,
    tertiary = Color(0xFF3F3F3F),
    onTertiary = PureWhite,
    tertiaryContainer = Color(0xFFD9D9D4),
    onTertiaryContainer = LightText,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceContainer = Color(0xFFEAEAE6),
    surfaceContainerHigh = Color(0xFFE3E3DF),
    surfaceContainerHighest = Color(0xFFDADAD5),
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = MutedTextLight,
    outline = Color(0xFF686868),
    outlineVariant = Color(0xFFC3C3BD),
    error = ErrorRedLight,
)

@Composable
fun TMusicTheme(
    themeMode: AppThemeMode = AppThemeMode.System,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        AppThemeMode.Dark -> true
        AppThemeMode.Light -> false
        AppThemeMode.System -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
