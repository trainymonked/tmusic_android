package dev.teacode.tmusic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.AccountRole
import dev.teacode.tmusic.domain.LastFmConnection
import dev.teacode.tmusic.data.WebLoginCode
import dev.teacode.tmusic.ui.theme.LocalAppThemeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProfileScreen(
    account: Account,
    avatarBitmap: ImageBitmap?,
    useLocalBackend: Boolean,
    showLocalBackendOption: Boolean,
    canUseNetwork: Boolean,
    syncMode: SyncMode,
    lastFmConnection: LastFmConnection,
    pendingPlayEventCount: Int,
    pendingPlayEventSyncProgress: Pair<Int, Int>?,
    waitingForLastFmSession: Boolean,
    scrobblingPaused: Boolean,
    showOnlyActiveSyncedLyrics: Boolean,
    centerSyncedLyrics: Boolean,
    animatedPlayerBackground: Boolean,
    crossfadeSeconds: Int,
    equalizerAvailable: Boolean,
    offlineOnly: Boolean,
    downloadUsingCellular: Boolean,
    downloadedTrackCount: Int,
    downloadedSizeBytes: Long,
    cacheSizeBytes: Long,
    appUpdateController: AppUpdateController,
    appVersionName: String,
    onUseLocalBackendChange: (Boolean) -> Unit,
    onOfflineOnlyChange: (Boolean) -> Unit,
    onScrobblingPausedChange: (Boolean) -> Unit,
    onShowOnlyActiveSyncedLyricsChange: (Boolean) -> Unit,
    onCenterSyncedLyricsChange: (Boolean) -> Unit,
    onAnimatedPlayerBackgroundChange: (Boolean) -> Unit,
    onCrossfadeSecondsChange: (Int) -> Unit,
    onDownloadUsingCellularChange: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onConnectLastFm: () -> Unit,
    onCompleteLastFmSession: () -> Unit,
    onDisconnectLastFm: () -> Unit,
    onSyncLastFmUpdates: () -> Unit,
    onCreateWebLoginCode: suspend () -> WebLoginCode,
    onClearDownloads: () -> Unit,
    onClearCache: () -> Unit,
    onCheckUpdates: () -> Unit,
    onSignOut: () -> Unit,
) {
    val themeController = LocalAppThemeController.current
    val scope = rememberCoroutineScope()
    var pendingConfirmation by remember { mutableStateOf<ProfileConfirmation?>(null) }
    var webLoginCode by remember(account.id) { mutableStateOf<WebLoginCode?>(null) }
    var webLoginCodeCreating by remember(account.id) { mutableStateOf(false) }
    var webLoginCodeError by remember(account.id) { mutableStateOf<String?>(null) }

    fun requestWebLoginCode() {
        if (account.role != AccountRole.ADMIN || webLoginCodeCreating || !canUseNetwork) {
            return
        }
        webLoginCodeCreating = true
        webLoginCodeError = null
        webLoginCode = null
        scope.launch {
            try {
                webLoginCode = onCreateWebLoginCode()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                webLoginCodeError = error.userMessage()
            } finally {
                webLoginCodeCreating = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ProfileHeader(
                account = account,
                avatarBitmap = avatarBitmap,
                onSignOut = { pendingConfirmation = ProfileConfirmation.SignOut },
            )
        }
        appUpdateController.availableUpdate?.let { update ->
            item {
                ProfileUpdateSection(
                    update = update,
                    updateStatus = appUpdateController.downloadStatus,
                    actionLabel = "Details",
                    actionEnabled = true,
                    onOpenUpdate = { appUpdateController.showDetails(update) },
                )
            }
        }
        item {
            ProfileLastFmSection(
                connection = lastFmConnection,
                pendingPlayEventCount = pendingPlayEventCount,
                syncProgress = pendingPlayEventSyncProgress,
                scrobblingPaused = scrobblingPaused,
                waitingForSession = waitingForLastFmSession,
                canUseNetwork = canUseNetwork,
                onConnect = onConnectLastFm,
                onCompleteSession = onCompleteLastFmSession,
                onDisconnect = { pendingConfirmation = ProfileConfirmation.DisconnectLastFm },
                onScrobblingPausedChange = onScrobblingPausedChange,
                onSyncUpdates = onSyncLastFmUpdates,
            )
        }
        item {
            ProfileAppearanceSection(
                themeMode = themeController.themeMode,
                animatedPlayerBackground = animatedPlayerBackground,
                onThemeModeChange = themeController.onThemeModeChange,
                onAnimatedPlayerBackgroundChange = onAnimatedPlayerBackgroundChange,
            )
        }
        item {
            ProfilePlaybackSection(
                showOnlyActiveSyncedLyrics = showOnlyActiveSyncedLyrics,
                centerSyncedLyrics = centerSyncedLyrics,
                crossfadeSeconds = crossfadeSeconds,
                equalizerAvailable = equalizerAvailable,
                onShowOnlyActiveSyncedLyricsChange = onShowOnlyActiveSyncedLyricsChange,
                onCenterSyncedLyricsChange = onCenterSyncedLyricsChange,
                onCrossfadeSecondsChange = onCrossfadeSecondsChange,
                onOpenEqualizer = onOpenEqualizer,
            )
        }
        item {
            ProfileDownloadsSection(
                downloadUsingCellular = downloadUsingCellular,
                downloadedTrackCount = downloadedTrackCount,
                downloadedSizeBytes = downloadedSizeBytes,
                cacheSizeBytes = cacheSizeBytes,
                onDownloadUsingCellularChange = onDownloadUsingCellularChange,
                onClearDownloads = { pendingConfirmation = ProfileConfirmation.ClearDownloads },
                onClearCache = onClearCache,
            )
        }
        if (account.role == AccountRole.ADMIN) {
            item {
                ProfileWebPlayerSection(
                    canUseNetwork = canUseNetwork,
                    creatingCode = webLoginCodeCreating,
                    errorMessage = webLoginCodeError,
                    onCreateCode = ::requestWebLoginCode,
                )
            }
        }
        item {
            ProfileConnectionSection(
                useLocalBackend = useLocalBackend,
                showLocalBackendOption = showLocalBackendOption,
                syncMode = syncMode,
                offlineOnly = offlineOnly,
                onUseLocalBackendChange = onUseLocalBackendChange,
                onOfflineOnlyChange = onOfflineOnlyChange,
            )
        }
        item {
            ProfileAppInfoSection(
                versionName = appVersionName,
                updateCheckInProgress = appUpdateController.checkInProgress,
                onCheckUpdates = onCheckUpdates,
            )
        }
    }
    pendingConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(confirmation.title) },
            text = { Text(confirmation.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirmation = null
                        when (confirmation) {
                            ProfileConfirmation.SignOut -> onSignOut()
                            ProfileConfirmation.DisconnectLastFm -> onDisconnectLastFm()
                            ProfileConfirmation.ClearDownloads -> onClearDownloads()
                        }
                    },
                ) {
                    Text(confirmation.confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text("Cancel")
                }
            },
        )
    }
    webLoginCode?.let { loginCode ->
        WebLoginCodeDialog(
            loginCode = loginCode,
            onDismiss = { webLoginCode = null },
        )
    }
}

private enum class ProfileConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
) {
    SignOut(
        title = "Sign out?",
        message = "You will need to sign in again to use your account.",
        confirmLabel = "Sign out",
    ),
    DisconnectLastFm(
        title = "Disconnect Last.fm?",
        message = "Listening history will stop syncing to Last.fm.",
        confirmLabel = "Disconnect",
    ),
    ClearDownloads(
        title = "Clear downloads?",
        message = "Downloaded music will be removed from this device.",
        confirmLabel = "Clear",
    ),
}

@Composable
private fun ProfileHeader(
    account: Account,
    avatarBitmap: ImageBitmap?,
    onSignOut: () -> Unit,
) {
    val username = account.profileUsername()
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = username.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = username,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSignOut) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                contentDescription = "Sign out",
            )
        }
    }
}

private fun Account.profileUsername(): String {
    return email.substringBefore('@')
        .takeIf { it.isNotBlank() }
        ?: displayName
}
