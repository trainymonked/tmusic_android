package dev.teacode.tmusic.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AppUpdateHost(
    private val scope: CoroutineScope,
    private val appUpdateController: AppUpdateController,
    private val networkPolicyController: NetworkPolicyController,
) {
    suspend fun checkForAppUpdate(manual: Boolean) {
        appUpdateController.checkForUpdate(
            manual = manual,
            canCheck = networkPolicyController.canCheckAppUpdates(),
            debugStatus = networkPolicyController.appUpdateDebugStatus(),
        )
    }

    fun checkUpdatesManually() {
        scope.launch {
            checkForAppUpdate(manual = true)
        }
    }
}
