package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import dev.teacode.tmusic.auth.GoogleSignInTokenProvider
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LastFmAuthTokenStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.PendingLibraryMutationStore
import dev.teacode.tmusic.data.PendingPlayEventStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore

@Composable
@NonRestartableComposable
fun TMusicApp(
    authRepository: RemoteAuthRepository,
    musicRepository: RemoteMusicRepository,
    googleSignInTokenProvider: GoogleSignInTokenProvider,
    userPreferencesStore: UserPreferencesStore,
    libraryCacheStore: LibraryCacheStore,
    offlineLyricsStore: OfflineLyricsStore,
    artworkCacheStore: ArtworkCacheStore,
    playbackStateStore: PlaybackStateStore,
    pendingLibraryMutationStore: PendingLibraryMutationStore,
    pendingPlayEventStore: PendingPlayEventStore,
    lastFmAuthTokenStore: LastFmAuthTokenStore,
    openFullPlayerRequestSerial: Int,
) {
    TMusicAppControllerContent(
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
