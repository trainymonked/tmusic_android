package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.Account

internal fun canUseServerRequests(
    account: Account?,
    offlineOnly: Boolean,
    syncMode: SyncMode,
    hasNetworkConnection: Boolean,
): Boolean {
    return account != null &&
        !offlineOnly &&
        syncMode != SyncMode.Offline &&
        syncMode != SyncMode.OfflineOnly &&
        hasNetworkConnection
}

internal fun canAttemptMetadataRequest(
    account: Account?,
    offlineOnly: Boolean,
    syncMode: SyncMode,
    hasNetworkConnection: Boolean,
): Boolean {
    return account != null &&
        !offlineOnly &&
        syncMode != SyncMode.OfflineOnly &&
        hasNetworkConnection
}

internal fun canUseMediaServerRequests(
    account: Account?,
    offlineOnly: Boolean,
    syncMode: SyncMode,
    hasNetworkConnection: Boolean,
): Boolean {
    return account != null &&
        !offlineOnly &&
        syncMode != SyncMode.OfflineOnly &&
        hasNetworkConnection &&
        account.canPlayMedia
}

internal fun canCheckAppUpdates(
    account: Account?,
    offlineOnly: Boolean,
    hasNetworkConnection: Boolean,
): Boolean {
    return account != null && !offlineOnly && hasNetworkConnection
}

internal fun appUpdateDebugStatus(
    account: Account?,
    offlineOnly: Boolean,
    syncMode: SyncMode,
    hasNetworkConnection: Boolean,
): String {
    return "account=${account != null} offlineOnly=$offlineOnly syncMode=$syncMode network=$hasNetworkConnection"
}
