package dev.teacode.tmusic.playback

import android.content.Context
import android.os.Build

const val PLAYBACK_ATTRIBUTION_TAG = "playback"

fun Context.playbackAttributionContext(): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return this
    }
    return if (attributionTag == PLAYBACK_ATTRIBUTION_TAG) {
        this
    } else {
        createAttributionContext(PLAYBACK_ATTRIBUTION_TAG)
    }
}
