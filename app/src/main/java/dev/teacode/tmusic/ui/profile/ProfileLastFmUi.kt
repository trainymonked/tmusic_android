package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.domain.ScrobbleState

@Composable
internal fun ProfileLastFmSection(
    connection: LastFmConnection,
    pendingPlayEventCount: Int,
    syncProgress: Pair<Int, Int>?,
    scrobblingPaused: Boolean,
    waitingForSession: Boolean,
    canUseNetwork: Boolean,
    onConnect: () -> Unit,
    onCompleteSession: () -> Unit,
    onDisconnect: () -> Unit,
    onScrobblingPausedChange: (Boolean) -> Unit,
    onSyncUpdates: () -> Unit,
) {
    val username = connection.username.meaningfulLastFmUsername()
    val connected = connection.state == ScrobbleState.Ready && username != null
    ProfileSettingsSection(title = "Last.fm") {
        if (connected) {
            ProfileInfoRow(
                title = "Account",
                value = username,
                valueTextAlign = TextAlign.End,
            )
            ProfileSettingDivider()
            ProfileActionRow(
                title = "Disconnect Last.fm",
                subtitle = "",
                actionLabel = "Disconnect",
                onAction = onDisconnect,
                enabled = canUseNetwork,
            )
        } else {
            ProfileActionRow(
                title = "Account",
                subtitle = if (canUseNetwork) {
                    "Link Last.fm to sync listening history"
                } else {
                    "Connect to the internet to link Last.fm"
                },
                actionLabel = if (waitingForSession) "Complete" else "Connect",
                onAction = if (waitingForSession) onCompleteSession else onConnect,
                enabled = canUseNetwork,
            )
        }
    }
}

private fun String?.meaningfulLastFmUsername(): String? {
    val normalized = this?.trim().orEmpty()
    return normalized.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
}
