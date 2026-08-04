package dev.teacode.tmusic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import dev.teacode.tmusic.auth.GoogleSignInTokenProvider
import dev.teacode.tmusic.data.AppConfig
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.OfflineTrackStore
import dev.teacode.tmusic.data.PendingLibraryMutationStore
import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.SessionStore
import dev.teacode.tmusic.data.TMusicApiClient
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.ui.TMusicApp
import dev.teacode.tmusic.ui.theme.AppThemeController
import dev.teacode.tmusic.ui.theme.AppThemeMode
import dev.teacode.tmusic.ui.theme.LocalAppThemeController
import dev.teacode.tmusic.ui.theme.TMusicTheme

class MainActivity : ComponentActivity() {
    private var openFullPlayerRequestSerial by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)

        val sessionStore = SessionStore(applicationContext)
        val userPreferencesStore = UserPreferencesStore(applicationContext)
        val libraryCacheStore = LibraryCacheStore(applicationContext)
        val offlineTrackStore = OfflineTrackStore(applicationContext)
        val offlineLyricsStore = OfflineLyricsStore(applicationContext)
        val artworkCacheStore = ArtworkCacheStore(applicationContext)
        val playbackStateStore = PlaybackStateStore(applicationContext)
        val pendingLibraryMutationStore = PendingLibraryMutationStore(applicationContext)
        val pendingPlayEventStore = PendingPlayEventStore(applicationContext)
        val lastFmAuthTokenStore = LastFmAuthTokenStore(applicationContext)
        val useLocalBackend = userPreferencesStore.useLocalBackend()
        val apiClient = TMusicApiClient(
            initialBaseUrl = AppConfig.apiBaseUrl(useLocalBackend),
            sessionStore = sessionStore,
        )
        val authRepository = RemoteAuthRepository(
            apiClient = apiClient,
            sessionStore = sessionStore,
        )
        val musicRepository = RemoteMusicRepository(
            apiClient = apiClient,
            offlineTrackStore = offlineTrackStore,
        )
        val googleSignInTokenProvider = GoogleSignInTokenProvider(
            context = this,
            serverClientId = AppConfig.GOOGLE_SERVER_CLIENT_ID,
        )

        setContent {
            var appThemeMode by remember {
                mutableStateOf(AppThemeMode.fromStorageValue(userPreferencesStore.themeMode()))
            }
            val systemInDarkTheme = isSystemInDarkTheme()
            val useDarkSystemBars = when (appThemeMode) {
                AppThemeMode.Dark -> true
                AppThemeMode.Light -> false
                AppThemeMode.System -> systemInDarkTheme
            }
            SideEffect {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                WindowCompat.getInsetsController(window, window.decorView).run {
                    isAppearanceLightStatusBars = !useDarkSystemBars
                    isAppearanceLightNavigationBars = !useDarkSystemBars
                }
            }
            CompositionLocalProvider(
                LocalAppThemeController provides AppThemeController(
                    themeMode = appThemeMode,
                    onThemeModeChange = { mode ->
                        appThemeMode = mode
                        userPreferencesStore.setThemeMode(mode.storageValue)
                    },
                ),
            ) {
                TMusicTheme(themeMode = appThemeMode) {
                    TMusicApp(
                        authRepository = authRepository,
                        musicRepository = musicRepository,
                        googleSignInTokenProvider = googleSignInTokenProvider,
                        userPreferencesStore = userPreferencesStore,
                        libraryCacheStore = libraryCacheStore,
                        offlineLyricsStore = offlineLyricsStore,
                        artworkCacheStore = artworkCacheStore,
                        playbackStateStore = playbackStateStore,
                        pendingLibraryMutationStore = pendingLibraryMutationStore,
                        pendingPlayEventStore = pendingPlayEventStore,
                        lastFmAuthTokenStore = lastFmAuthTokenStore,
                        openFullPlayerRequestSerial = openFullPlayerRequestSerial,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_FULL_PLAYER) {
            return
        }
        intent.action = null
        openFullPlayerRequestSerial += 1
    }

    companion object {
        const val ACTION_OPEN_FULL_PLAYER = "dev.teacode.tmusic.action.OPEN_FULL_PLAYER"
        private const val REQUEST_OPEN_FULL_PLAYER = 2001

        fun openFullPlayerPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_FULL_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_FULL_PLAYER,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
