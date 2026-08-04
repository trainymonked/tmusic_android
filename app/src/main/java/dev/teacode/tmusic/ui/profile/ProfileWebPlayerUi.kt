package dev.teacode.tmusic.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.teacode.tmusic.data.WebLoginCode
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun ProfileWebPlayerSection(
    canUseNetwork: Boolean,
    creatingCode: Boolean,
    errorMessage: String?,
    onCreateCode: () -> Unit,
) {
    val subtitle = when {
        creatingCode -> "Creating a one-time code"
        !errorMessage.isNullOrBlank() -> errorMessage
        !canUseNetwork -> "Connect to the server to create a code"
        else -> "Valid for 5 minutes and can be used once"
    }
    ProfileSettingsSection(title = "Web player") {
        ProfileActionRow(
            title = "Sign-in code",
            subtitle = subtitle,
            actionLabel = if (creatingCode) "Creating" else "Create",
            onAction = onCreateCode,
            enabled = canUseNetwork && !creatingCode,
        )
    }
}

@Composable
internal fun WebLoginCodeDialog(
    loginCode: WebLoginCode,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val expiresAtMillis = remember(loginCode.expiresAt) {
        runCatching { Instant.parse(loginCode.expiresAt).toEpochMilli() }.getOrNull()
    }
    var nowMillis by remember(loginCode.code) { mutableLongStateOf(System.currentTimeMillis()) }
    var copied by remember(loginCode.code) { mutableStateOf(false) }

    LaunchedEffect(expiresAtMillis) {
        if (expiresAtMillis == null) {
            return@LaunchedEffect
        }
        while (nowMillis < expiresAtMillis) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val remainingSeconds = expiresAtMillis?.let { expiry ->
        ((expiry - nowMillis + 999L) / 1_000L).coerceAtLeast(0L)
    }
    val expired = remainingSeconds != null && remainingSeconds <= 0L
    val expiryLabel = when {
        remainingSeconds == null -> "Expires soon"
        expired -> "Expired"
        else -> {
            val minutes = remainingSeconds / 60L
            val seconds = remainingSeconds % 60L
            "Expires in $minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Web player sign-in") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = loginCode.code,
                        style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 2.sp),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = expiryLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (expired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "Enter this one-time code in the web player. Creating another code invalidates this one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("T-Music web sign-in code", loginCode.code))
                    copied = true
                },
                enabled = !expired,
            ) {
                Text(if (copied) "Copied" else "Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
