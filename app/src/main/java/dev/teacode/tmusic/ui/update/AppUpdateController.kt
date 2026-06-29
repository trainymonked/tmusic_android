package dev.teacode.tmusic.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.teacode.tmusic.BuildConfig
import dev.teacode.tmusic.data.AppUpdateChecker
import dev.teacode.tmusic.data.AppUpdateInfo
import dev.teacode.tmusic.data.PendingDownloadedAppUpdate
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.data.enforcedForCurrentApp
import dev.teacode.tmusic.data.isAppVersionNewer
import dev.teacode.tmusic.data.isAvailableForCurrentApp
import kotlinx.coroutines.CancellationException

internal class AppUpdateController(
    private val context: Context,
    private val userPreferencesStore: UserPreferencesStore,
    private val appUpdateChecker: AppUpdateChecker,
    private val currentVersion: String,
    initialUpdate: AppUpdateInfo?,
    private val onNotice: (String?) -> Unit,
    private val onError: (String?) -> Unit,
) {
    var availableUpdate by mutableStateOf(initialUpdate)
        private set
    var dialogUpdate by mutableStateOf<AppUpdateInfo?>(null)
        private set
    var checkInProgress by mutableStateOf(false)
        private set
    var downloadId by mutableStateOf<Long?>(null)
        private set
    var downloadedVersion by mutableStateOf<String?>(null)
        private set
    var installUri by mutableStateOf<Uri?>(null)
        private set
    var downloadStatus by mutableStateOf<String?>(null)
        private set

    val readyToInstall: Boolean
        get() = availableUpdate?.version == downloadedVersion && installUri != null

    val actionLabel: String
        get() = when {
            readyToInstall -> "Install"
            downloadId != null -> "Downloading"
            else -> "Update"
        }

    val actionEnabled: Boolean
        get() = downloadId == null

    init {
        if (availableUpdate?.isAvailableForCurrentApp(currentVersion) != true) {
            clearAvailableUpdate()
        }
        removeInstalledDownloadedUpdateIfNeeded()
    }

    fun dismissDialog() {
        dialogUpdate = null
    }

    fun applyServerUpdate(update: AppUpdateInfo, prompt: Boolean) {
        val enforcedUpdate = update.enforcedForCurrentApp()
        if (!enforcedUpdate.isAvailableForCurrentApp(currentVersion)) {
            clearAvailableUpdate()
            return
        }
        Log.d(
            APP_UPDATE_LOG_TAG,
            "server update version=${enforcedUpdate.version} latestCode=${enforcedUpdate.latestVersionCode} " +
                "minCode=${enforcedUpdate.minSupportedVersionCode} force=${enforcedUpdate.forceUpdate}",
        )
        availableUpdate = enforcedUpdate
        userPreferencesStore.setCachedAppUpdate(enforcedUpdate)
        if (prompt || enforcedUpdate.forceUpdate) {
            if (userPreferencesStore.lastPromptedUpdateVersion() != enforcedUpdate.version || enforcedUpdate.forceUpdate) {
                userPreferencesStore.setLastPromptedUpdateVersion(enforcedUpdate.version)
            }
            dialogUpdate = enforcedUpdate
        }
    }

    fun showDetails(update: AppUpdateInfo?) {
        dialogUpdate = update ?: availableUpdate
    }

    fun openUpdate(update: AppUpdateInfo?) {
        val selectedUpdate = update ?: return
        if (readyToInstall && downloadedVersion == selectedUpdate.version) {
            installDownloadedUpdate(installUri)
            return
        }
        if (downloadId != null) {
            downloadStatus = "Downloading update..."
            return
        }
        val url = selectedUpdate.downloadUrl.takeIf { it.isNotBlank() } ?: return
        val uri = Uri.parse(url)
        if (url.endsWith(".apk", ignoreCase = true)) {
            enqueueApkDownload(selectedUpdate, uri)
        } else {
            openExternalUrl(uri)
        }
    }

    suspend fun checkForUpdate(
        manual: Boolean,
        canCheck: Boolean,
        debugStatus: String,
    ) {
        if (!canCheck || checkInProgress) {
            Log.d(
                APP_UPDATE_LOG_TAG,
                "skip check manual=$manual canCheck=$canCheck inProgress=$checkInProgress $debugStatus",
            )
            if (manual && !canCheck) {
                onError("Connect to the internet before checking updates.")
            } else if (manual && checkInProgress) {
                onNotice("Update check is already running.")
            }
            return
        }
        val now = System.currentTimeMillis()
        userPreferencesStore.setLastUpdateCheckEpochMs(now)
        checkInProgress = true
        Log.d(APP_UPDATE_LOG_TAG, "run check manual=$manual currentVersion=$currentVersion")
        var checkError: Throwable? = null
        val update = try {
            appUpdateChecker.latestUpdate(currentVersion)
        } catch (error: CancellationException) {
            checkInProgress = false
            throw error
        } catch (error: Throwable) {
            Log.w(APP_UPDATE_LOG_TAG, "check failed manual=$manual", error)
            checkError = error
            null
        } finally {
            checkInProgress = false
        }
        if (update != null) {
            Log.d(APP_UPDATE_LOG_TAG, "new update available version=${update.version} url=${update.downloadUrl}")
            applyServerUpdate(
                update = update,
                prompt = manual || userPreferencesStore.lastPromptedUpdateVersion() != update.version,
            )
            if (manual) {
                onNotice("Update ${update.version} is available.")
            }
        } else if (checkError != null) {
            if (manual) {
                onError("Could not check updates.")
            }
        } else if (availableUpdate?.isAvailableForCurrentApp(currentVersion) == true) {
            if (manual) {
                showDetails(availableUpdate)
            }
        } else {
            Log.d(APP_UPDATE_LOG_TAG, "no newer update available")
            clearAvailableUpdate()
            if (manual) {
                onNotice("No updates found.")
            }
        }
    }

    fun onDownloaded(uri: Uri?) {
        val completedDownloadId = downloadId
        val downloadedUpdate = availableUpdate
        if (
            completedDownloadId == null &&
            downloadedVersion == downloadedUpdate?.version &&
            installUri == uri
        ) {
            return
        }
        downloadId = null
        downloadedVersion = downloadedUpdate?.version
        installUri = uri
        downloadStatus = if (uri != null) {
            "Ready to install."
        } else {
            "Downloaded, but installer is unavailable."
        }
        if (uri != null && completedDownloadId != null && downloadedUpdate != null) {
            userPreferencesStore.setPendingDownloadedAppUpdate(
                version = downloadedUpdate.version,
                downloadId = completedDownloadId,
                targetVersionCode = downloadedUpdate.latestVersionCode ?: downloadedUpdate.minSupportedVersionCode,
            )
        }
        onNotice(downloadStatus)
        if (uri != null) {
            downloadedUpdate?.let { dialogUpdate = it }
        }
    }

    fun onDownloadFailed(reason: Int) {
        downloadId = null
        downloadStatus = "Download failed."
        onError("Update download failed. Reason: $reason")
    }

    fun onDownloadUnknown() {
        downloadId = null
        downloadStatus = "Download status is unknown."
    }

    private fun enqueueApkDownload(update: AppUpdateInfo, uri: Uri) {
        val fileName = "TMusic-${update.version}.apk"
        runCatching {
            val request = DownloadManager.Request(uri)
                .setTitle("TMusic ${update.version}")
                .setDescription("Downloading update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val downloadManager = context.getSystemService(DownloadManager::class.java)
            downloadId = downloadManager.enqueue(request)
            downloadedVersion = null
            installUri = null
            downloadStatus = "Downloading update..."
            onNotice("Update download started.")
        }.onFailure {
            downloadId = null
            downloadStatus = "Download could not be started."
            onError("Could not start the update download.")
        }
    }

    private fun installDownloadedUpdate(uri: Uri?) {
        if (uri == null) {
            onError("Update is not ready to install yet.")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            onError("Could not open the update installer.")
        }
    }

    private fun openExternalUrl(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            onError("Could not open the update link.")
        }
    }

    private fun clearAvailableUpdate() {
        availableUpdate = null
        dialogUpdate = null
        downloadedVersion = null
        installUri = null
        userPreferencesStore.clearCachedAppUpdate()
    }

    private fun removeInstalledDownloadedUpdateIfNeeded() {
        val pendingUpdate = userPreferencesStore.pendingDownloadedAppUpdate() ?: return
        if (!pendingUpdate.isInstalled()) {
            return
        }
        runCatching {
            context.getSystemService(DownloadManager::class.java).remove(pendingUpdate.downloadId)
        }.onSuccess {
            Log.d(
                APP_UPDATE_LOG_TAG,
                "removed installed update apk version=${pendingUpdate.version} downloadId=${pendingUpdate.downloadId}",
            )
        }.onFailure { error ->
            Log.w(
                APP_UPDATE_LOG_TAG,
                "failed to remove installed update apk downloadId=${pendingUpdate.downloadId}",
                error,
            )
        }
        userPreferencesStore.clearPendingDownloadedAppUpdate()
    }

    private fun PendingDownloadedAppUpdate.isInstalled(): Boolean {
        targetVersionCode?.let { requiredVersionCode ->
            if (BuildConfig.VERSION_CODE >= requiredVersionCode) {
                return true
            }
        }
        return !isAppVersionNewer(version, currentVersion)
    }

    private companion object {
        const val APP_UPDATE_LOG_TAG = "TMusicUpdate"
    }
}
