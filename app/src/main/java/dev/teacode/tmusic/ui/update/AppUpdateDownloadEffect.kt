package dev.teacode.tmusic.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable
internal fun AppUpdateDownloadEffect(
    context: Context,
    downloadId: Long?,
    onDownloaded: (Uri?) -> Unit,
    onFailed: (Int) -> Unit,
    onUnknown: () -> Unit,
) {
    LaunchedEffect(context, downloadId) {
        val activeDownloadId = downloadId ?: return@LaunchedEffect
        var unknownCount = 0
        while (true) {
            delay(1_000)
            when (val status = context.queryAppUpdateDownloadStatus(activeDownloadId)) {
                is AppUpdateDownloadStatus.Downloaded -> {
                    onDownloaded(status.uri)
                    break
                }
                is AppUpdateDownloadStatus.Failed -> {
                    onFailed(status.reason)
                    break
                }
                AppUpdateDownloadStatus.Running -> {
                    unknownCount = 0
                }
                AppUpdateDownloadStatus.Unknown -> {
                    unknownCount += 1
                    if (unknownCount >= 3) {
                        onUnknown()
                        break
                    }
                }
            }
        }
    }

    DisposableEffect(context, downloadId) {
        val activeDownloadId = downloadId
        if (activeDownloadId == null) {
            onDispose {}
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                        return
                    }
                    val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (completedId != activeDownloadId) {
                        return
                    }
                    when (val status = receiverContext.queryAppUpdateDownloadStatus(completedId)) {
                        is AppUpdateDownloadStatus.Downloaded -> {
                            onDownloaded(status.uri)
                        }
                        is AppUpdateDownloadStatus.Failed -> {
                            onFailed(status.reason)
                        }
                        AppUpdateDownloadStatus.Unknown -> {
                            onUnknown()
                        }
                        AppUpdateDownloadStatus.Running -> Unit
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED,
            )
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}

private sealed class AppUpdateDownloadStatus {
    data class Downloaded(val uri: Uri?) : AppUpdateDownloadStatus()
    data class Failed(val reason: Int) : AppUpdateDownloadStatus()
    data object Running : AppUpdateDownloadStatus()
    data object Unknown : AppUpdateDownloadStatus()
}

private fun Context.queryAppUpdateDownloadStatus(downloadId: Long): AppUpdateDownloadStatus {
    val downloadManager = getSystemService(DownloadManager::class.java)
    val cursor = runCatching {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
    }.getOrNull()
    cursor.use { result ->
        if (result == null || !result.moveToFirst()) {
            return AppUpdateDownloadStatus.Unknown
        }
        val statusColumn = result.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val reasonColumn = result.getColumnIndex(DownloadManager.COLUMN_REASON)
        val status = if (statusColumn >= 0) result.getInt(statusColumn) else DownloadManager.STATUS_FAILED
        return when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                AppUpdateDownloadStatus.Downloaded(downloadManager.getUriForDownloadedFile(downloadId))
            }
            DownloadManager.STATUS_FAILED -> {
                AppUpdateDownloadStatus.Failed(if (reasonColumn >= 0) result.getInt(reasonColumn) else 0)
            }
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_RUNNING -> {
                AppUpdateDownloadStatus.Running
            }
            else -> AppUpdateDownloadStatus.Unknown
        }
    }
}
