package dev.teacode.tmusic.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.media3.common.C

internal fun isSystemEqualizerAvailable(context: Context): Boolean {
    return resolvedEqualizerIntents(context, audioSessionId = 0).isNotEmpty()
}

internal fun openSystemEqualizer(
    context: Context,
    audioSessionId: Int,
): Boolean {
    val activity = context as? Activity ?: return false
    return resolvedEqualizerIntents(context, audioSessionId).any { intent ->
        runCatching {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, SYSTEM_EQUALIZER_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }
}

private fun resolvedEqualizerIntents(
    context: Context,
    audioSessionId: Int,
): List<Intent> {
    val resolvedAudioSessionId = audioSessionId
        .takeIf { it != C.AUDIO_SESSION_ID_UNSET }
        ?: 0

    val baseIntent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, resolvedAudioSessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    val packageManager = context.packageManager
    val resolvedActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            baseIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return resolvedActivities
        .mapNotNull { resolved ->
            val activityInfo = resolved.activityInfo ?: return@mapNotNull null
            val normalizedActivityName = activityInfo.name.lowercase()
            if (
                activityInfo.packageName == context.packageName ||
                !activityInfo.enabled ||
                !activityInfo.exported ||
                normalizedActivityName.contains("redirector") ||
                normalizedActivityName.contains("compatibility")
            ) {
                return@mapNotNull null
            }
            Intent(baseIntent).apply {
                component = ComponentName(activityInfo.packageName, activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        .distinctBy { intent -> intent.component }
        .sortedByDescending { intent ->
            when {
                intent.component?.className?.contains("ActivityMusic", ignoreCase = true) == true -> 3
                intent.component?.packageName == "com.android.musicfx" -> 2
                intent.component?.packageName == "com.google.android.musicfx" -> 1
                else -> 0
            }
        }
}

private const val SYSTEM_EQUALIZER_REQUEST_CODE = 7321
