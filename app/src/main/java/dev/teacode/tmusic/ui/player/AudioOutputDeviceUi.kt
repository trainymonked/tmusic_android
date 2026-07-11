package dev.teacode.tmusic.ui

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal data class AudioOutputDevice(
    val name: String,
    val usesHeadphones: Boolean,
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
    var outputDevice by remember(audioManager, mediaRouter) {
        mutableStateOf(resolveAudioOutputDevice(context, audioManager, mediaRouter))
    }

    DisposableEffect(context, audioManager, mediaRouter) {
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val mediaAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val routedDevices = runCatching<List<AudioDeviceInfo>> {
            audioManager.getAudioDevicesForAttributes(mediaAttributes)
        }.getOrDefault(emptyList())
        routedDevices.firstOrNull(AudioDeviceInfo::isPersonalAudioOutput)?.let { device ->
            return device.toAudioOutputDevice()
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
                activeMediaDevice.toAudioOutputDevice()
            } else {
                phoneAudioOutputDevice()
            }
        }
    }

    val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    val selectedRoute = mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    if (selectedRoute.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
        val bluetoothDevice = outputDevices.firstOrNull(AudioDeviceInfo::isBluetoothAudioOutput)
        return bluetoothDevice?.toAudioOutputDevice()
            ?: AudioOutputDevice(selectedRoute.getName(context).toString().ifBlank { "Headphones" }, true)
    }

    @Suppress("DEPRECATION")
    if (audioManager.isBluetoothA2dpOn) {
        outputDevices.firstOrNull(AudioDeviceInfo::isBluetoothAudioOutput)?.let { device ->
            return device.toAudioOutputDevice()
        }
    }
    @Suppress("DEPRECATION")
    if (audioManager.isWiredHeadsetOn) {
        outputDevices.firstOrNull(AudioDeviceInfo::isWiredAudioOutput)?.let { device ->
            return device.toAudioOutputDevice()
        }
        return AudioOutputDevice("Headphones", true)
    }
    outputDevices.firstOrNull(AudioDeviceInfo::isUsbHeadphoneOutput)?.let { device ->
        return device.toAudioOutputDevice()
    }
    return phoneAudioOutputDevice()
}

private fun AudioDeviceInfo.toAudioOutputDevice(): AudioOutputDevice {
    val resolvedName = if (isWiredAudioOutput()) {
        "Headphones"
    } else {
        productName
            .toString()
            .trim()
            .takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
            ?: "Headphones"
    }
    return AudioOutputDevice(resolvedName, usesHeadphones = true)
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
