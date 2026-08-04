package dev.teacode.tmusic.ui

import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import dev.teacode.tmusic.MainActivity
import dev.teacode.tmusic.R
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.playback.PlaybackForegroundService
import dev.teacode.tmusic.playback.playbackAttributionContext

@Composable
internal fun PlaybackSystemIntegration(
    playerState: PlayerState,
    artworkBitmap: ImageBitmap?,
    isFavorite: Boolean,
    canSkip: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val baseContext = LocalContext.current
    val context = remember(baseContext) { baseContext.playbackAttributionContext() }
    val playAction = rememberUpdatedState(onPlay)
    val pauseAction = rememberUpdatedState(onPause)
    val previousAction = rememberUpdatedState(onPrevious)
    val nextAction = rememberUpdatedState(onNext)
    val seekAction = rememberUpdatedState(onSeek)
    val toggleFavoriteAction = rememberUpdatedState(onToggleFavorite)
    val mediaSession = remember {
        MediaSession(context, "TMusicPlaybackSession").apply {
            setSessionActivity(MainActivity.openFullPlayerPendingIntent(context))
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = playAction.value.invoke()
                    override fun onPause() = pauseAction.value.invoke()
                    override fun onSkipToPrevious() = previousAction.value.invoke()
                    override fun onSkipToNext() = nextAction.value.invoke()
                    override fun onSeekTo(pos: Long) = seekAction.value.invoke(pos)

                    override fun onCustomAction(action: String, extras: Bundle?) {
                        if (action == MEDIA_ACTION_TOGGLE_FAVORITE) {
                            toggleFavoriteAction.value.invoke()
                        }
                    }
                },
            )
            isActive = true
        }
    }

    DisposableEffect(mediaSession) {
        onDispose {
            context.stopService(Intent(context, PlaybackForegroundService::class.java))
            mediaSession.release()
        }
    }

    LaunchedEffect(
        mediaSession,
        playerState.currentTrack?.id,
        artworkBitmap,
    ) {
        context.updateMediaSessionMetadata(
            session = mediaSession,
            state = playerState,
            artworkBitmap = artworkBitmap,
        )
    }

    LaunchedEffect(
        mediaSession,
        playerState.currentTrack?.id,
        playerState.isPlaying,
        playerState.progressSeconds,
        playerState.streamUrl,
        isFavorite,
    ) {
        mediaSession.updatePlaybackState(
            state = playerState,
            isFavorite = isFavorite,
        )
    }

    LaunchedEffect(
        mediaSession,
        playerState.currentTrack?.id,
        playerState.isPlaying,
        playerState.streamUrl,
        canSkip,
        isFavorite,
    ) {
        context.updatePlaybackService(
            session = mediaSession,
            state = playerState,
            isFavorite = isFavorite,
            canSkip = canSkip,
        )
    }
}

private fun android.content.Context.updatePlaybackService(
    session: MediaSession,
    state: PlayerState,
    isFavorite: Boolean,
    canSkip: Boolean,
) {
    val currentTrack = state.currentTrack
    if (currentTrack == null) {
        stopService(Intent(this, PlaybackForegroundService::class.java))
        return
    }

    val intent = PlaybackForegroundService.updateIntent(
        context = this,
        title = currentTrack.title,
        artist = currentTrack.displayArtistNames(),
        isPlaying = state.isPlaying,
        isFavorite = isFavorite,
        canSkip = canSkip,
        token = session.sessionToken,
    )
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    } catch (error: IllegalStateException) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        } else {
            throw error
        }
    }
}

private fun android.content.Context.updateMediaSessionMetadata(
    session: MediaSession,
    state: PlayerState,
    artworkBitmap: ImageBitmap?,
) {
    val currentTrack = state.currentTrack
    if (currentTrack == null) {
        session.setMetadata(MediaMetadata.Builder().build())
        return
    }

    val metadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, currentTrack.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, currentTrack.displayArtistNames())
        .putString(MediaMetadata.METADATA_KEY_ALBUM, currentTrack.album)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, currentTrack.durationSeconds.toLong() * 1000L)
        .apply {
            val bitmap = artworkBitmap?.asAndroidBitmap()
                ?: drawableResourceBitmap(R.drawable.ic_launcher_monochrome)?.asAndroidBitmap()
            bitmap?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
            }
        }
        .build()
    session.setMetadata(metadata)
}

private fun MediaSession.updatePlaybackState(
    state: PlayerState,
    isFavorite: Boolean,
) {
    val currentTrack = state.currentTrack
    if (currentTrack == null) {
        setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_NONE, 0L, 0f)
                .build(),
        )
        return
    }

    val actions = PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SEEK_TO or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_SKIP_TO_NEXT
    setPlaybackState(
        PlaybackState.Builder()
            .setActions(actions)
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    MEDIA_ACTION_TOGGLE_FAVORITE,
                    if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                    if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                ).build(),
            )
            .setState(
                if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.progressSeconds.toLong().coerceAtLeast(0L) * 1000L,
                if (state.isPlaying) 1f else 0f,
            )
            .build(),
    )
}
