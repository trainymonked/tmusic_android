package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.CachedLibrary
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.PendingLibraryMutationStore
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.SavedPlaybackState
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track

internal data class InitialAppState(
    val offlineOnly: Boolean,
    val cachedLibrary: CachedLibrary,
    val tracks: List<Track>,
    val savedPlayback: SavedPlaybackState?,
    val activePlayEvent: ActivePlayEvent?,
    val canContinueOffline: Boolean,
    val account: Account?,
    val playerState: PlayerState,
    val playbackQueue: PlaybackQueue,
)

internal fun loadInitialAppState(
    authRepository: RemoteAuthRepository,
    musicRepository: RemoteMusicRepository,
    userPreferencesStore: UserPreferencesStore,
    libraryCacheStore: LibraryCacheStore,
    playbackStateStore: PlaybackStateStore,
    pendingLibraryMutationStore: PendingLibraryMutationStore,
): InitialAppState {
    val offlineOnly = userPreferencesStore.offlineOnly()
    val cachedLibrary = libraryCacheStore.library()
    val tracks = musicRepository.withOfflineState(cachedLibrary.tracks)
        .withPendingFavoriteStates(pendingLibraryMutationStore.pendingFavoriteStates())
    val savedPlayback = playbackStateStore.state()
    val activePlayEvent = savedPlayback?.let { playback ->
        if (
            playback.trackId != null &&
            playback.scrobbleClientEventId != null &&
            playback.scrobblePlayedAt != null
        ) {
            ActivePlayEvent(
                clientEventId = playback.scrobbleClientEventId,
                trackId = playback.trackId,
                playedAt = playback.scrobblePlayedAt,
                durationPlayedMs = playback.scrobbleDurationPlayedMs,
            )
        } else {
            null
        }
    }
    val savedTrack = savedPlayback?.trackId?.let { trackId ->
        tracks.firstOrNull { it.id == trackId } ?: savedPlayback.track
    }
    val savedSourceType = savedPlayback?.sourceType
        ?.let { value -> runCatching { PlaybackSourceType.valueOf(value) }.getOrNull() }
    val savedPlaylist = savedPlayback?.playlistId?.let { playlistId ->
        cachedLibrary.playlists.firstOrNull { it.id == playlistId }
    }
    val queueTracks = savedPlaylist?.tracksFrom(tracks)
        ?.takeIf { it.isNotEmpty() }
        ?: savedTrack?.let(::listOf)
        ?: emptyList()
    val canContinueOffline = authRepository.hasSession() || !cachedLibrary.isEmpty
    val account = authRepository.cachedAccount() ?: if (canContinueOffline) OfflineAccount else null
    val playbackQueue = PlaybackQueue(
        playlistId = savedPlaylist?.id,
        sourceType = if (savedPlaylist != null) {
            PlaybackSourceType.Playlist
        } else {
            savedSourceType ?: PlaybackSourceType.Search
        },
        sourceId = savedPlaylist?.id ?: savedPlayback?.sourceId,
        sourceTitle = savedPlaylist?.title ?: savedPlayback?.sourceTitle,
        tracks = queueTracks,
        currentIndex = savedTrack?.let { track ->
            queueTracks.indexOfFirst { it.id == track.id }
        } ?: -1,
    )
    return InitialAppState(
        offlineOnly = offlineOnly,
        cachedLibrary = cachedLibrary,
        tracks = tracks,
        savedPlayback = savedPlayback,
        activePlayEvent = activePlayEvent,
        canContinueOffline = canContinueOffline,
        account = account,
        playerState = PlayerState(
            currentTrack = savedTrack,
            isPlaying = false,
            progressSeconds = savedPlayback
                ?.positionMs
                ?.div(1000L)
                ?.toInt()
                ?.coerceAtLeast(0)
                ?: 0,
            streamUrl = null,
        ),
        playbackQueue = playbackQueue,
    )
}
