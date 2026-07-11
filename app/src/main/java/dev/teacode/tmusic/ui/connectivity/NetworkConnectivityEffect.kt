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
    onConnectionStateChanged: (Boolean) -> Unit,
    onNetworkAvailableOrChanged: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val policyChangedState = rememberUpdatedState(onNetworkPolicyChanged)
    val connectionStateChangedState = rememberUpdatedState(onConnectionStateChanged)
    val networkAvailableOrChangedState = rememberUpdatedState(onNetworkAvailableOrChanged)

    DisposableEffect(context, enabled, useLocalBackend) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (!enabled || connectivityManager == null) {
            return@DisposableEffect onDispose {}
        }

        var observedNetwork = connectivityManager.activeNetwork
        val initialCapabilities = observedNetwork
            ?.let { network -> connectivityManager.getNetworkCapabilities(network) }
        var wasUsable = initialCapabilities?.isUsableNetwork(useLocalBackend) == true
        var wasCellular = initialCapabilities
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        fun dispatchCurrentNetworkState() {
            val currentNetwork = connectivityManager.activeNetwork
            val capabilities = currentNetwork
                ?.let { network -> connectivityManager.getNetworkCapabilities(network) }
            val isUsable = capabilities?.isUsableNetwork(useLocalBackend) == true
            val isCellular = capabilities
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val networkChanged = currentNetwork != observedNetwork
            val availabilityChanged = isUsable != wasUsable
            val transportChanged = isCellular != wasCellular
            val policyChanged = availabilityChanged || transportChanged
            if (!networkChanged && !policyChanged) {
                return
            }
            observedNetwork = currentNetwork
            wasUsable = isUsable
            wasCellular = isCellular
            scope.launch {
                if (policyChanged) {
                    policyChangedState.value()
                }
                if (availabilityChanged) {
                    connectionStateChangedState.value(isUsable)
                }
                if (isUsable && (networkChanged || availabilityChanged || transportChanged)) {
                    networkAvailableOrChangedState.value()
                }
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dispatchCurrentNetworkState()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                dispatchCurrentNetworkState()
            }

            override fun onLost(network: Network) {
                dispatchCurrentNetworkState()
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
}

private fun NetworkCapabilities.isUsableNetwork(useLocalBackend: Boolean): Boolean {
    return hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
