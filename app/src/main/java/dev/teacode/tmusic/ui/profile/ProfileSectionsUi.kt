package dev.teacode.tmusic.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme

@Composable
internal fun ProfilePlaybackSection(
    showLyrics: Boolean,
    crossfadeSeconds: Int,
    equalizerAvailable: Boolean,
    onShowLyricsChange: (Boolean) -> Unit,
    onCrossfadeSecondsChange: (Int) -> Unit,
    onOpenEqualizer: () -> Unit,
) {
    ProfileSettingsSection(title = "Playback") {
        ProfileSwitchRow(
            title = "Show lyrics",
            subtitle = "Display lyrics in the full player",
            checked = showLyrics,
            onCheckedChange = onShowLyricsChange,
        )
        ProfileSettingDivider()
        ProfileSliderRow(
            title = "Crossfade",
            value = crossfadeSeconds,
            valueRange = 0..12,
            valueLabel = if (crossfadeSeconds == 0) "Off" else "$crossfadeSeconds s",
            onValueChange = onCrossfadeSecondsChange,
        )
        if (equalizerAvailable) {
            ProfileSettingDivider()
            ProfileActionRow(
                title = "Equalizer",
                subtitle = "Provided by your device",
                actionLabel = "Open",
                onAction = onOpenEqualizer,
            )
        }
    }
}

@Composable
internal fun ProfileDownloadsSection(
    downloadUsingCellular: Boolean,
    downloadedTrackCount: Int,
    downloadedSizeBytes: Long,
    cacheSizeBytes: Long,
    onDownloadUsingCellularChange: (Boolean) -> Unit,
    onClearDownloads: () -> Unit,
    onClearCache: () -> Unit,
) {
    ProfileSettingsSection(title = "Downloads") {
        ProfileSwitchRow(
            title = "Download using cellular",
            subtitle = "Applies only to the downloads",
            checked = downloadUsingCellular,
            onCheckedChange = onDownloadUsingCellularChange,
        )
        ProfileSettingDivider()
        ProfileActionRow(
            title = "Downloads",
            subtitle = "$downloadedTrackCount tracks - ${formatStorageBytes(downloadedSizeBytes)}",
            actionLabel = "Clear",
            onAction = onClearDownloads,
            enabled = downloadedTrackCount > 0 || downloadedSizeBytes > 0L,
        )
        ProfileSettingDivider()
        ProfileActionRow(
            title = "Cache",
            subtitle = formatStorageBytes(cacheSizeBytes),
            actionLabel = "Clear",
            onAction = onClearCache,
            enabled = cacheSizeBytes > 0L,
        )
    }
}

@Composable
internal fun ProfileConnectionSection(
    apiBaseUrl: String,
    useLocalBackend: Boolean,
    canUseNetwork: Boolean,
    syncMode: SyncMode,
    offlineOnly: Boolean,
    onUseLocalBackendChange: (Boolean) -> Unit,
    onOfflineOnlyChange: (Boolean) -> Unit,
) {
    ProfileSettingsSection(title = "Connection") {
        ProfileSwitchRow(
            title = "Offline only",
            subtitle = "Don't contact the server while enabled",
            checked = offlineOnly,
            onCheckedChange = onOfflineOnlyChange,
        )
        ProfileSettingDivider()
        ProfileSwitchRow(
            title = "Local server",
            subtitle = "Use the local development backend",
            checked = useLocalBackend,
            onCheckedChange = onUseLocalBackendChange,
        )
        ProfileSettingDivider()
        ProfileInfoRow(
            title = "API server",
            value = apiBaseUrl,
        )
        ProfileSettingDivider()
        ProfileStatusRow(
            title = "Status",
            value = syncMode.profileLabel(canUseNetwork),
            icon = when {
                syncMode == SyncMode.Syncing -> Icons.Filled.Sync
                syncMode == SyncMode.Online && canUseNetwork -> Icons.Filled.CloudDone
                else -> Icons.Filled.CloudOff
            },
            iconColor = when {
                syncMode == SyncMode.Syncing -> MaterialTheme.colorScheme.tertiary
                syncMode == SyncMode.Online && canUseNetwork -> androidx.compose.ui.graphics.Color(0xFF34A853)
                else -> MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun SyncMode.profileLabel(canUseNetwork: Boolean): String {
    return when (this) {
        SyncMode.Offline -> "Offline"
        SyncMode.Syncing -> "Syncing"
        SyncMode.Online -> if (canUseNetwork) "Online" else "Offline"
        SyncMode.OfflineOnly -> "Offline only"
    }
}
