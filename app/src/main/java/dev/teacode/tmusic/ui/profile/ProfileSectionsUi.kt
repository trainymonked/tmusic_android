package dev.teacode.tmusic.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import dev.teacode.tmusic.data.AppUpdateInfo
import dev.teacode.tmusic.ui.theme.AppThemeMode

@Composable
internal fun ProfileUpdateSection(
    update: AppUpdateInfo,
    updateStatus: String?,
    actionLabel: String,
    actionEnabled: Boolean,
    onOpenUpdate: () -> Unit,
) {
    ProfileSettingsSection(title = "App update") {
        ProfileActionRow(
            title = "Update available",
            subtitle = updateStatus ?: update.subtitle(),
            actionLabel = actionLabel,
            onAction = onOpenUpdate,
            enabled = actionEnabled,
        )
    }
}

@Composable
internal fun ProfileAppearanceSection(
    themeMode: AppThemeMode,
    animatedPlayerBackground: Boolean,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onAnimatedPlayerBackgroundChange: (Boolean) -> Unit,
) {
    ProfileSettingsSection(title = "Appearance") {
        ProfileThemeModeRow(
            title = "Theme",
            subtitle = "",
            selectedMode = themeMode,
            onModeSelected = onThemeModeChange,
        )
        ProfileSettingDivider()
        ProfileSwitchRow(
            title = "Animated player background",
            subtitle = "Colors from album artwork",
            checked = animatedPlayerBackground,
            onCheckedChange = onAnimatedPlayerBackgroundChange,
        )
    }
}

@Composable
internal fun ProfilePlaybackSection(
    showOnlyActiveSyncedLyrics: Boolean,
    centerSyncedLyrics: Boolean,
    crossfadeSeconds: Int,
    equalizerAvailable: Boolean,
    onShowOnlyActiveSyncedLyricsChange: (Boolean) -> Unit,
    onCenterSyncedLyricsChange: (Boolean) -> Unit,
    onCrossfadeSecondsChange: (Int) -> Unit,
    onOpenEqualizer: () -> Unit,
) {
    ProfileSettingsSection(title = "Playback") {
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
            subtitle = "",
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
    useLocalBackend: Boolean,
    showLocalBackendOption: Boolean,
    syncMode: SyncMode,
    offlineOnly: Boolean,
    onUseLocalBackendChange: (Boolean) -> Unit,
    onOfflineOnlyChange: (Boolean) -> Unit,
) {
    val statusLabel = syncMode.connectionStatusLabel()
    val isOnline = statusLabel == "Online"
    ProfileSettingsSection(title = "Connection") {
        ProfileSwitchRow(
            title = "Offline only",
            subtitle = "",
            checked = offlineOnly,
            onCheckedChange = onOfflineOnlyChange,
        )
        ProfileSettingDivider()
        if (showLocalBackendOption) {
            ProfileSwitchRow(
                title = "Local server",
                subtitle = "",
                checked = useLocalBackend,
                onCheckedChange = onUseLocalBackendChange,
            )
            ProfileSettingDivider()
        }
        ProfileStatusRow(
            title = "Status",
            value = statusLabel,
            icon = if (isOnline) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
            iconColor = if (isOnline) {
                androidx.compose.ui.graphics.Color(0xFF34A853)
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
internal fun ProfileAppInfoSection(
    versionName: String,
    updateCheckInProgress: Boolean,
    onCheckUpdates: () -> Unit,
) {
    ProfileSettingsSection(title = "App") {
        ProfileInfoRow(
            title = "Version",
            value = versionName,
            valueTextAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        ProfileSettingDivider()
        ProfileActionRow(
            title = "Check updates",
            subtitle = "",
            actionLabel = if (updateCheckInProgress) "Checking" else "Check",
            onAction = onCheckUpdates,
            enabled = !updateCheckInProgress,
        )
    }
}

private fun AppUpdateInfo.subtitle(): String {
    val firstChangeLine = changelog
        .lineSequence()
        .map { it.trim().trimStart('-', '*').trim() }
        .firstOrNull { it.isNotBlank() }
    return firstChangeLine?.take(120) ?: "New version is ready to install"
}
