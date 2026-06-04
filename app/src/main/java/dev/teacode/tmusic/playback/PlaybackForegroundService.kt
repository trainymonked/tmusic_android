package dev.teacode.tmusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import dev.teacode.tmusic.MainActivity
import dev.teacode.tmusic.R

class PlaybackForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PREVIOUS -> {
                if (intent.getBooleanExtra(EXTRA_CAN_SKIP, false)) {
                    intent.mediaSessionToken()?.let { token ->
                        MediaController(playbackAttributionContext(), token).transportControls.skipToPrevious()
                    }
                }
                return START_STICKY
            }
            ACTION_TOGGLE_PLAYBACK -> {
                intent.mediaSessionToken()?.let { token ->
                    val controls = MediaController(playbackAttributionContext(), token).transportControls
                    if (intent.getBooleanExtra(EXTRA_IS_PLAYING, false)) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }
                return START_STICKY
            }
            ACTION_NEXT -> {
                if (intent.getBooleanExtra(EXTRA_CAN_SKIP, false)) {
                    intent.mediaSessionToken()?.let { token ->
                        MediaController(playbackAttributionContext(), token).transportControls.skipToNext()
                    }
                }
                return START_STICKY
            }
            ACTION_TOGGLE_FAVORITE -> {
                intent.mediaSessionToken()?.let { token ->
                    MediaController(playbackAttributionContext(), token).transportControls.sendCustomAction(
                        MEDIA_ACTION_TOGGLE_FAVORITE,
                        null,
                    )
                }
                return START_STICKY
            }
        }

        createNotificationChannel()
        val notification = buildNotification(intent)
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun buildNotification(intent: Intent?): Notification {
        val title = intent?.getStringExtra(EXTRA_TITLE).takeUnless { it.isNullOrBlank() } ?: "T-Music"
        val artist = intent?.getStringExtra(EXTRA_ARTIST).takeUnless { it.isNullOrBlank() } ?: "Music playback"
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, false) ?: false
        val isFavorite = intent?.getBooleanExtra(EXTRA_IS_FAVORITE, false) ?: false
        val canSkip = intent?.getBooleanExtra(EXTRA_CAN_SKIP, false) ?: false
        val sessionToken = intent?.mediaSessionToken()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (sessionToken != null) {
            val previousPendingIntent = playbackActionIntent(
                action = ACTION_PREVIOUS,
                requestCode = REQUEST_PREVIOUS,
                sessionToken = sessionToken,
                isPlaying = isPlaying,
                canSkip = canSkip,
            )
            val togglePlaybackPendingIntent = playbackActionIntent(
                action = ACTION_TOGGLE_PLAYBACK,
                requestCode = REQUEST_TOGGLE_PLAYBACK,
                sessionToken = sessionToken,
                isPlaying = isPlaying,
                canSkip = canSkip,
            )
            val nextPendingIntent = playbackActionIntent(
                action = ACTION_NEXT,
                requestCode = REQUEST_NEXT,
                sessionToken = sessionToken,
                isPlaying = isPlaying,
                canSkip = canSkip,
            )
            val favoritePendingIntent = playbackActionIntent(
                action = ACTION_TOGGLE_FAVORITE,
                requestCode = REQUEST_TOGGLE_FAVORITE,
                sessionToken = sessionToken,
                isPlaying = isPlaying,
                canSkip = canSkip,
            )
            builder.addAction(
                R.drawable.ic_skip_previous,
                "Previous",
                previousPendingIntent,
            )
            builder.addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                if (isPlaying) "Pause" else "Play",
                togglePlaybackPendingIntent,
            )
            builder.addAction(
                R.drawable.ic_skip_next,
                "Next",
                nextPendingIntent,
            )
            builder.addAction(
                if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                favoritePendingIntent,
            )
        }

        if (sessionToken != null) {
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        }

        return builder.build()
    }

    private fun playbackActionIntent(
        action: String,
        requestCode: Int,
        sessionToken: MediaSession.Token,
        isPlaying: Boolean,
        canSkip: Boolean,
    ): PendingIntent {
        val actionIntent = Intent(this, PlaybackForegroundService::class.java)
            .setAction(action)
            .putExtra(EXTRA_MEDIA_SESSION_TOKEN, sessionToken)
            .putExtra(EXTRA_IS_PLAYING, isPlaying)
            .putExtra(EXTRA_CAN_SKIP, canSkip)
        return PendingIntent.getService(
            this,
            requestCode,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val existingChannel = manager.getNotificationChannel(CHANNEL_ID)
        if (existingChannel != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Music playback",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun Intent.mediaSessionToken(): MediaSession.Token? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_MEDIA_SESSION_TOKEN, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_MEDIA_SESSION_TOKEN)
        }
    }

    companion object {
        private const val CHANNEL_ID = "tmusic_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_UPDATE = "dev.teacode.tmusic.playback.UPDATE"
        private const val ACTION_STOP = "dev.teacode.tmusic.playback.STOP"
        private const val ACTION_PREVIOUS = "dev.teacode.tmusic.playback.PREVIOUS"
        private const val ACTION_TOGGLE_PLAYBACK = "dev.teacode.tmusic.playback.TOGGLE_PLAYBACK"
        private const val ACTION_NEXT = "dev.teacode.tmusic.playback.NEXT"
        private const val ACTION_TOGGLE_FAVORITE = "dev.teacode.tmusic.playback.TOGGLE_FAVORITE"
        private const val MEDIA_ACTION_TOGGLE_FAVORITE = "dev.teacode.tmusic.action.TOGGLE_FAVORITE"
        private const val REQUEST_PREVIOUS = 1002
        private const val REQUEST_TOGGLE_PLAYBACK = 1003
        private const val REQUEST_NEXT = 1004
        private const val REQUEST_TOGGLE_FAVORITE = 1005
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_IS_PLAYING = "is_playing"
        private const val EXTRA_IS_FAVORITE = "is_favorite"
        private const val EXTRA_CAN_SKIP = "can_skip"
        private const val EXTRA_MEDIA_SESSION_TOKEN = "media_session_token"

        fun updateIntent(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            isFavorite: Boolean,
            canSkip: Boolean,
            token: MediaSession.Token,
        ): Intent {
            return Intent(context, PlaybackForegroundService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)
                .putExtra(EXTRA_IS_PLAYING, isPlaying)
                .putExtra(EXTRA_IS_FAVORITE, isFavorite)
                .putExtra(EXTRA_CAN_SKIP, canSkip)
                .putExtra(EXTRA_MEDIA_SESSION_TOKEN, token)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, PlaybackForegroundService::class.java).setAction(ACTION_STOP)
        }
    }
}
