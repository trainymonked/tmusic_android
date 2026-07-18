package dev.teacode.tmusic.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DISCONNECT_CONFIRMATION_DELAY_MS = 1_000L

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
        var pendingDisconnectConfirmation: Job? = null

        fun dispatchCurrentNetworkState(confirmDisconnect: Boolean = false) {
            val currentNetwork = connectivityManager.activeNetwork
            val capabilities = currentNetwork
                ?.let { network -> connectivityManager.getNetworkCapabilities(network) }
            val isUsable = capabilities?.isUsableNetwork(useLocalBackend) == true
            val isCellular = capabilities
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            // During a cellular handoff Android can momentarily report no default network.
            // Confirm the loss before changing the UI state, otherwise a short handoff looks
            // like an offline/online transition and starts duplicate syncs.
            if (!isUsable && wasUsable && !confirmDisconnect) {
                pendingDisconnectConfirmation?.cancel()
                pendingDisconnectConfirmation = scope.launch {
                    delay(DISCONNECT_CONFIRMATION_DELAY_MS)
                    dispatchCurrentNetworkState(confirmDisconnect = true)
                }
                return
            }
            if (isUsable) {
                pendingDisconnectConfirmation?.cancel()
                pendingDisconnectConfirmation = null
            }
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
            pendingDisconnectConfirmation?.cancel()
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
}

private fun NetworkCapabilities.isUsableNetwork(useLocalBackend: Boolean): Boolean {
    if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return false
    }
    // A cellular transport can advertise INTERNET before Android has finished
    // proving that it can actually reach the Internet. A local backend is the
    // one intentional exception: it may be reachable without public Internet.
    return useLocalBackend || hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
