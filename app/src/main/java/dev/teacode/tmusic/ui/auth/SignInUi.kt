package dev.teacode.tmusic.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.teacode.tmusic.R
import dev.teacode.tmusic.ui.theme.AppThemeMode
import dev.teacode.tmusic.ui.theme.LocalAppThemeController
import org.json.JSONObject

@Composable
fun SignInScreen(
    isLoading: Boolean,
    errorMessage: String?,
    useLocalBackend: Boolean,
    onUseLocalBackendChange: (Boolean) -> Unit,
    onGoogleSignIn: () -> Unit,
    canContinueOffline: Boolean,
    onContinueOffline: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val appThemeController = LocalAppThemeController.current
    val systemInDarkTheme = isSystemInDarkTheme()
    val splashBackground = colorResource(id = R.color.splash_background)
    val typography = MaterialTheme.typography
    val signInColorScheme = darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF242424),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFC7C7C7),
        onSecondary = Color.Black,
        background = splashBackground,
        onBackground = Color.White,
        surface = Color(0xFF0B0B0B),
        onSurface = Color.White,
        surfaceContainer = Color(0xFF171717),
        surfaceVariant = Color(0xFF242424),
        onSurfaceVariant = Color(0xFFC7C7C7),
        outline = Color(0xFF8F8F8F),
        error = Color(0xFFFFB4AB),
    )
    val appIconBitmap = remember(context) {
        context.drawableResourceBitmap(R.mipmap.ic_launcher)
    }
    val friendlyError = errorMessage?.toSignInErrorMessage(canContinueOffline)

    DisposableEffect(view, appThemeController.themeMode, systemInDarkTheme) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.run {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        onDispose {
            val useDarkSystemBars = when (appThemeController.themeMode) {
                AppThemeMode.Dark -> true
                AppThemeMode.Light -> false
                AppThemeMode.System -> systemInDarkTheme
            }
            controller?.run {
                isAppearanceLightStatusBars = !useDarkSystemBars
                isAppearanceLightNavigationBars = !useDarkSystemBars
            }
        }
    }

    MaterialTheme(
        colorScheme = signInColorScheme,
        typography = typography,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    appIconBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.size(112.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Spacer(modifier = Modifier.height(22.dp))
                    Text(
                        text = "T-Music",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Stream, save, and listen offline.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Local server",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Switch(
                                checked = useLocalBackend,
                                onCheckedChange = onUseLocalBackendChange,
                                enabled = !isLoading,
                            )
                        }
                    }
                    Button(
                        onClick = onGoogleSignIn,
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isLoading) "Signing in" else "Continue with Google")
                    }
                    if (canContinueOffline) {
                        OutlinedButton(
                            onClick = onContinueOffline,
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Continue offline")
                        }
                    }
                    friendlyError?.let {
                        ErrorState(
                            message = it,
                            actionLabel = null,
                            onAction = {},
                        )
                    }
                }
            }
        }
    }
}

private fun String.toSignInErrorMessage(canContinueOffline: Boolean): String {
    extractServerMessage()?.let { return it }
    val normalized = trim()
    if (normalized.equals("Invite is required", ignoreCase = true)) {
        return "Invite is required"
    }
    if (
        normalized.contains("Server unavailable", ignoreCase = true) ||
        normalized.contains("Unable to resolve host", ignoreCase = true) ||
        normalized.contains("timeout", ignoreCase = true)
    ) {
        return if (canContinueOffline) {
            "Could not reach your library online. Offline listening is still available."
        } else {
            "Could not sign in right now. Check your connection and try again."
        }
    }
    return normalized.ifBlank { "Could not sign in right now. Check your connection and try again." }
}

private fun String.extractServerMessage(): String? {
    val jsonStart = indexOf('{').takeIf { it >= 0 } ?: return null
    return runCatching {
        JSONObject(substring(jsonStart)).optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
}
