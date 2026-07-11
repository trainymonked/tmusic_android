package dev.teacode.tmusic.ui

import android.content.Context
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.teacode.tmusic.playback.playbackAttributionContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val GAPLESS_PRELOAD_DURATION_US = 20L * C.MICROS_PER_SECOND
private const val PLAYBACK_MIN_BUFFER_MS = 45_000
private const val PLAYBACK_MAX_BUFFER_MS = 30 * 60 * 1000
private const val PLAYBACK_START_BUFFER_MS = 2_500
private const val PLAYBACK_RESUME_BUFFER_MS = 5_000

internal data class PreparedCrossfade(
    val player: ExoPlayer,
    val queueGeneration: Long,
    val queueIndex: Int,
    val trackId: String,
    val url: String,
    val mediaId: String,
) {
    val signature: String = "$queueGeneration:$queueIndex:$trackId:$url:$mediaId"
}

internal fun createPlaybackPlayer(
    context: Context,
    mediaCache: SimpleCache,
    handleAudioFocus: Boolean,
): ExoPlayer {
    val playbackContext = context.playbackAttributionContext()
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            PLAYBACK_MIN_BUFFER_MS,
            PLAYBACK_MAX_BUFFER_MS,
            PLAYBACK_START_BUFFER_MS,
            PLAYBACK_RESUME_BUFFER_MS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    val playbackDataSourceFactory = CacheDataSource.Factory()
        .setCache(mediaCache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(playbackContext))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val audioTrackProvider = object : DefaultAudioTrackProvider() {
        override fun customizeAudioTrackBuilder(audioTrackBuilder: AudioTrack.Builder): AudioTrack.Builder {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioTrackBuilder.setContext(playbackContext)
            }
            return audioTrackBuilder
        }
    }
    val renderersFactory = object : DefaultRenderersFactory(playbackContext) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            return DefaultAudioSink.Builder(playbackContext)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioTrackProvider(audioTrackProvider)
                .build()
        }
    }
    return ExoPlayer.Builder(playbackContext, renderersFactory)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSourceFactory))
        .build()
        .apply {
            configurePlaybackAudioFocus(handleAudioFocus)
            playbackParameters = PlaybackParameters(1f, 1f)
            setSkipSilenceEnabled(false)
            setPauseAtEndOfMediaItems(false)
            setPreloadConfiguration(ExoPlayer.PreloadConfiguration(GAPLESS_PRELOAD_DURATION_US))
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
}

internal fun ExoPlayer.configurePlaybackAudioFocus(handleAudioFocus: Boolean) {
    setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
        handleAudioFocus,
    )
}

internal fun ExoPlayer.prepareCrossfadeItem(
    url: String,
    mediaId: String,
    cacheKey: String?,
) {
    stop()
    clearMediaItems()
    volume = 0f
    configurePlaybackAudioFocus(handleAudioFocus = false)
    repeatMode = ExoPlayer.REPEAT_MODE_OFF
    shuffleModeEnabled = false
    setMediaItem(
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(mediaId)
            .apply {
                cacheKey?.let { resolvedCacheKey -> setCustomCacheKey(resolvedCacheKey) }
            }
            .build(),
        true,
    )
    seekTo(0, 0L)
    prepare()
}

internal suspend fun performCrossfade(
    fromPlayer: ExoPlayer,
    toPlayer: ExoPlayer,
    fadeDurationMs: Long,
    onOverlapStarted: () -> Unit,
): Boolean {
    val currentMediaIndex = fromPlayer.currentMediaItemIndex
    if (currentMediaIndex >= 0 && currentMediaIndex + 1 < fromPlayer.mediaItemCount) {
        fromPlayer.removeMediaItems(currentMediaIndex + 1, fromPlayer.mediaItemCount)
    }

    toPlayer.pause()
    toPlayer.seekTo(0, 0L)
    toPlayer.volume = 0f
    toPlayer.play()
    val readyDeadline = SystemClock.elapsedRealtime() + 1_000L
    while (
        currentCoroutineContext().isActive &&
        !toPlayer.isPlaying &&
        SystemClock.elapsedRealtime() < readyDeadline
    ) {
        delay(5L)
    }
    if (!currentCoroutineContext().isActive || !toPlayer.isPlaying) {
        return false
    }

    onOverlapStarted()
    val startedAt = SystemClock.elapsedRealtime()
    while (currentCoroutineContext().isActive) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val progress = (elapsedMs.toFloat() / fadeDurationMs.coerceAtLeast(1L))
            .coerceIn(0f, 1f)
        fromPlayer.volume = 1f - progress
        toPlayer.volume = progress
        if (progress >= 1f) {
            break
        }
        delay(16L)
    }
    if (!currentCoroutineContext().isActive) {
        return false
    }

    fromPlayer.stop()
    fromPlayer.clearMediaItems()
    fromPlayer.volume = 0f
    fromPlayer.configurePlaybackAudioFocus(handleAudioFocus = false)
    toPlayer.volume = 1f
    toPlayer.configurePlaybackAudioFocus(handleAudioFocus = true)
    return true
}
