package dev.teacode.tmusic.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch

internal fun Context.hasUsableNetworkConnection(useLocalBackend: Boolean): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.isUsableNetwork(useLocalBackend)
}

internal fun Context.isUsingCellularNetwork(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}

@Composable
internal fun ObserveNetworkConnectivity(
    enabled: Boolean,
    useLocalBackend: Boolean,
    onNetworkPolicyChanged: () -> Unit,
    onDisconnected: () -> Unit,
    onReconnected: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val policyChangedState = rememberUpdatedState(onNetworkPolicyChanged)
    val disconnectedState = rememberUpdatedState(onDisconnected)
    val reconnectedState = rememberUpdatedState(onReconnected)

    DisposableEffect(context, enabled, useLocalBackend) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (!enabled || connectivityManager == null) {
            return@DisposableEffect onDispose {}
        }

        var wasUsable = context.hasUsableNetworkConnection(useLocalBackend)
        var wasCellular = context.isUsingCellularNetwork()
        fun dispatchNetworkState(
            isUsable: Boolean,
            isCellular: Boolean,
        ) {
            scope.launch {
                val availabilityChanged = isUsable != wasUsable
                val policyChanged = availabilityChanged || isCellular != wasCellular
                if (!policyChanged) {
                    return@launch
                }
                val previouslyUsable = wasUsable
                wasUsable = isUsable
                wasCellular = isCellular
                policyChangedState.value()
                if (!availabilityChanged) {
                    return@launch
                }
                if (isUsable && !previouslyUsable) {
                    reconnectedState.value()
                } else if (!isUsable && previouslyUsable) {
                    disconnectedState.value()
                }
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dispatchNetworkState(
                    isUsable = context.hasUsableNetworkConnection(useLocalBackend),
                    isCellular = context.isUsingCellularNetwork(),
                )
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                dispatchNetworkState(
                    isUsable = networkCapabilities.isUsableNetwork(useLocalBackend),
                    isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                )
            }

            override fun onLost(network: Network) {
                dispatchNetworkState(
                    isUsable = context.hasUsableNetworkConnection(useLocalBackend),
                    isCellular = context.isUsingCellularNetwork(),
                )
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
}

private fun NetworkCapabilities.isUsableNetwork(useLocalBackend: Boolean): Boolean {
    if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return false
    }
    return useLocalBackend || hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
