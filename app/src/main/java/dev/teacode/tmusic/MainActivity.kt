package dev.teacode.tmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import dev.teacode.tmusic.ui.theme.TMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            TMusicTheme {
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
                )
            }
        }
    }
}
