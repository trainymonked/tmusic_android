package dev.teacode.tmusic.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

internal data class AudioOutputDevice(
    val name: String,
    val usesHeadphones: Boolean,
    val isBluetooth: Boolean = false,
)

@Composable
internal fun rememberAudioOutputDevice(): AudioOutputDevice {
    val context = LocalContext.current.applicationContext
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val mediaRouter = remember(context) {
        context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    }
    var bluetoothNameAccessGranted by remember(context) {
        mutableStateOf(context.hasBluetoothNameAccess())
    }
    var outputDevice by remember(audioManager, mediaRouter, bluetoothNameAccessGranted) {
        mutableStateOf(resolveAudioOutputDevice(context, audioManager, mediaRouter))
    }
    val bluetoothNamePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        bluetoothNameAccessGranted = granted
        outputDevice = resolveAudioOutputDevice(context, audioManager, mediaRouter)
    }

    LaunchedEffect(outputDevice.isBluetooth, bluetoothNameAccessGranted) {
        if (
            outputDevice.isBluetooth &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !bluetoothNameAccessGranted
        ) {
            bluetoothNamePermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    DisposableEffect(context, audioManager, mediaRouter, bluetoothNameAccessGranted) {
        val updateOutput = {
            outputDevice = resolveAudioOutputDevice(context, audioManager, mediaRouter)
        }
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                updateOutput()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                updateOutput()
            }
        }
        val routeCallback = object : MediaRouter.SimpleCallback() {
            override fun onRouteSelected(
                router: MediaRouter,
                type: Int,
                info: MediaRouter.RouteInfo,
            ) {
                updateOutput()
            }

            override fun onRouteUnselected(
                router: MediaRouter,
                type: Int,
                info: MediaRouter.RouteInfo,
            ) {
                updateOutput()
            }

            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {
                updateOutput()
            }
        }
        val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                updateOutput()
            }
        }

        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        audioManager.registerAudioPlaybackCallback(playbackCallback, Handler(Looper.getMainLooper()))
        mediaRouter.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, routeCallback)
        updateOutput()
        onDispose {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
            runCatching { mediaRouter.removeCallback(routeCallback) }
        }
    }

    return outputDevice
}

private fun resolveAudioOutputDevice(
    context: Context,
    audioManager: AudioManager,
    mediaRouter: MediaRouter,
): AudioOutputDevice {
    val bluetoothRouteName = mediaRouter.selectedBluetoothRouteName(context)
    val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    // MediaRouter knows about a selected Bluetooth route even before the app has
    // an active playback configuration. Prefer it over the platform's temporary
    // speaker fallback so the device name appears as soon as permission is granted.
    if (bluetoothRouteName != null) {
        val bluetoothDevice = outputDevices.firstOrNull(AudioDeviceInfo::isBluetoothAudioOutput)
        return bluetoothDevice?.toAudioOutputDevice(context, bluetoothRouteName)
            ?: AudioOutputDevice(bluetoothRouteName, usesHeadphones = true, isBluetooth = true)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val mediaAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val routedDevices = runCatching<List<AudioDeviceInfo>> {
            audioManager.getAudioDevicesForAttributes(mediaAttributes)
        }.getOrDefault(emptyList())
        routedDevices.firstOrNull(AudioDeviceInfo::isPersonalAudioOutput)?.let { device ->
            return device.toAudioOutputDevice(context, bluetoothRouteName)
        }
        if (routedDevices.isNotEmpty()) {
            return phoneAudioOutputDevice()
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val activeMediaDevice = runCatching {
            audioManager.activePlaybackConfigurations
                .lastOrNull { configuration ->
                    configuration.audioAttributes.usage == android.media.AudioAttributes.USAGE_MEDIA
                }
                ?.audioDeviceInfo
        }.getOrNull()
        if (activeMediaDevice != null) {
            return if (activeMediaDevice.isPersonalAudioOutput()) {
                activeMediaDevice.toAudioOutputDevice(context, bluetoothRouteName)
            } else {
                phoneAudioOutputDevice()
            }
        }
    }

    @Suppress("DEPRECATION")
    if (audioManager.isBluetoothA2dpOn) {
        outputDevices.firstOrNull(AudioDeviceInfo::isBluetoothAudioOutput)?.let { device ->
            return device.toAudioOutputDevice(context, bluetoothRouteName)
        }
    }
    @Suppress("DEPRECATION")
    if (audioManager.isWiredHeadsetOn) {
        outputDevices.firstOrNull(AudioDeviceInfo::isWiredAudioOutput)?.let { device ->
            return device.toAudioOutputDevice(context)
        }
        return AudioOutputDevice("Headphones", usesHeadphones = true)
    }
    outputDevices.firstOrNull(AudioDeviceInfo::isUsbHeadphoneOutput)?.let { device ->
        return device.toAudioOutputDevice(context)
    }
    return phoneAudioOutputDevice()
}

private fun AudioDeviceInfo.toAudioOutputDevice(
    context: Context,
    bluetoothRouteName: String? = null,
): AudioOutputDevice {
    val productDeviceName = productName
        .toString()
        .trim()
        .takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
    val resolvedName = if (isWiredAudioOutput()) {
        "Headphones"
    } else if (isBluetoothAudioOutput()) {
        context.bluetoothDeviceName(address)
            ?: productDeviceName?.takeUnless(::isGenericBluetoothDeviceName)
            ?: bluetoothRouteName
            ?: "Bluetooth device"
    } else {
        productDeviceName ?: "Headphones"
    }
    return AudioOutputDevice(
        name = resolvedName,
        usesHeadphones = true,
        isBluetooth = isBluetoothAudioOutput(),
    )
}

private fun Context.hasBluetoothNameAccess(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
}

private fun Context.bluetoothDeviceName(address: String): String? {
    if (!hasBluetoothNameAccess()) {
        return null
    }
    val normalizedAddress = address.trim().takeIf { it.matches(BLUETOOTH_ADDRESS_PATTERN) }
        ?: return null
    val device = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(normalizedAddress)
    }.getOrNull() ?: return null
    val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { device.alias }.getOrNull()
    } else {
        null
    }
    return alias
        ?.trim()
        ?.takeUnless(::isGenericBluetoothDeviceName)
        ?: runCatching { device.name }
            .getOrNull()
            ?.trim()
            ?.takeUnless(::isGenericBluetoothDeviceName)
}

private fun isGenericBluetoothDeviceName(name: String): Boolean {
    return name.equals("bluetooth", ignoreCase = true) ||
        name.equals("headphones", ignoreCase = true) ||
        name.equals("unknown", ignoreCase = true)
}

private fun MediaRouter.selectedBluetoothRouteName(context: Context): String? {
    val selectedRoute = getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    if (selectedRoute.deviceType != MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
        return null
    }
    return selectedRoute.getName(context)
        .toString()
        .trim()
        .takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
}

private fun phoneAudioOutputDevice(): AudioOutputDevice {
    return AudioOutputDevice(
        name = "This phone",
        usesHeadphones = false,
    )
}

private fun AudioDeviceInfo.isPersonalAudioOutput(): Boolean {
    return isBluetoothAudioOutput() || isWiredAudioOutput() || isUsbHeadphoneOutput()
}

private fun AudioDeviceInfo.isBluetoothAudioOutput(): Boolean {
    return when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID -> true
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        else -> false
    }
}

private fun AudioDeviceInfo.isWiredAudioOutput(): Boolean {
    return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET
}

private fun AudioDeviceInfo.isUsbHeadphoneOutput(): Boolean {
    return type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY
}

private val BLUETOOTH_ADDRESS_PATTERN = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
