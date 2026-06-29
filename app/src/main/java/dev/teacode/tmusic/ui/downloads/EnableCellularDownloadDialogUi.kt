package dev.teacode.tmusic.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun EnableCellularDownloadDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onEnable: () -> Unit,
) {
    if (!visible) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable cellular downloads?") },
        text = { Text("Downloads are blocked on cellular. Enable Download using cellular to continue.") },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
