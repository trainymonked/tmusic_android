package dev.teacode.tmusic.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal fun AppUpdateEffects(
    controller: AppUpdateController,
    context: Context,
    accountId: String?,
    offlineOnly: Boolean,
    useLocalBackend: Boolean,
    canCheck: Boolean,
    debugStatus: String,
) {
    AppUpdateDownloadEffect(
        context = context,
        downloadId = controller.downloadId,
        onDownloaded = controller::onDownloaded,
        onFailed = controller::onDownloadFailed,
        onUnknown = controller::onDownloadUnknown,
    )

    LaunchedEffect(accountId, offlineOnly, useLocalBackend, canCheck) {
        controller.checkForUpdate(
            manual = false,
            canCheck = canCheck,
            debugStatus = debugStatus,
        )
    }
}

@Composable
internal fun AppUpdateDialogHost(controller: AppUpdateController) {
    controller.dialogUpdate?.let { update ->
        UpdateAvailableDialog(
            update = update,
            status = controller.downloadStatus,
            actionLabel = controller.actionLabel,
            actionEnabled = controller.actionEnabled,
            onDismiss = controller::dismissDialog,
            onUpdate = {
                controller.dismissDialog()
                controller.openUpdate(update)
            },
        )
    }
}
