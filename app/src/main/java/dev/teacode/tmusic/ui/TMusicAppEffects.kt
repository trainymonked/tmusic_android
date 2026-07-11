package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.Player
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.SavedPlaybackState
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@Composable
internal fun LibrarySearchEffect(
    searchQuery: String,
    syncMode: SyncMode,
    offlineOnly: Boolean,
    tracks: List<Track>,
    searchTrackOffset: Int,
    canUseServerRequests: () -> Boolean,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setSearchLoading: (Boolean) -> Unit,
    getSearchResults: () -> LibrarySearchResults,
    setSearchResults: (LibrarySearchResults) -> Unit,
    setSearchHasMore: (Boolean) -> Unit,
    setAccessToken: (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    LaunchedEffect(searchQuery, syncMode, offlineOnly, tracks, searchTrackOffset) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            setSearchLoading(false)
            setSearchHasMore(false)
            setSearchResults(LibrarySearchResults(emptyList(), emptyList(), emptyList()))
            return@LaunchedEffect
        }

        val appending = searchTrackOffset > 0
        if (!appending) {
            delay(250)
        }
        if (canUseServerRequests()) {
            setSearchLoading(true)
            runCatching {
                musicRepository.search(
                    query = query,
                    limit = SEARCH_TRACK_PAGE_SIZE,
                    offset = searchTrackOffset,
                )
            }.onSuccess { results ->
                setSearchResults(
                    if (appending) {
                        getSearchResults().withAppendedTrackPage(results)
                    } else {
                        results
                    },
                )
                setSearchHasMore(results.tracks.size >= SEARCH_TRACK_PAGE_SIZE)
                setAccessToken(authRepository.accessToken())
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@LaunchedEffect
                }
                markServerUnavailable(error)
                if (!appending) {
                    setSearchResults(tracks.localSearch(query))
                }
                setSearchHasMore(false)
                setLibraryError(error.userMessage())
            }
            setSearchLoading(false)
        } else {
            setSearchLoading(false)
            setSearchHasMore(false)
            setSearchResults(
                tracks
                    .filter { it.downloadState == DownloadState.Downloaded }
                    .localSearch(query),
            )
        }
    }
}

@Composable
internal fun TimedMessageClearEffect(
    message: String?,
    timeoutMs: Long,
    clearIfCurrent: (String) -> Unit,
) {
    LaunchedEffect(message) {
        val currentMessage = message ?: return@LaunchedEffect
        delay(timeoutMs)
        clearIfCurrent(currentMessage)
    }
}

@Composable
internal fun InitialStorageStatsEffect(refreshStorageStats: () -> Unit) {
    LaunchedEffect(Unit) {
        refreshStorageStats()
    }
}

@Composable
internal fun CurrentTrackLyricsEffect(
    playerState: PlayerState,
    syncMode: SyncMode,
    offlineOnly: Boolean,
    loadLyrics: (Track) -> Unit,
) {
    LaunchedEffect(playerState.currentTrack?.id, syncMode, offlineOnly) {
        playerState.currentTrack?.let(loadLyrics)
    }
}

@Composable
internal fun PendingTransitionArtworkEffect(
    pendingArtworkKey: String?,
    loadArtwork: (String, ArtworkImageSize) -> Unit,
    clearPendingArtworkKey: () -> Unit,
) {
    LaunchedEffect(pendingArtworkKey) {
        val artworkKey = pendingArtworkKey ?: return@LaunchedEffect
        loadArtwork(artworkKey, ArtworkImageSize.FullPlayer)
        clearPendingArtworkKey()
    }
}

@Composable
internal fun ProfileAvatarEffect(
    account: Account?,
    loadProfileAvatar: (Account) -> Unit,
    clearProfileAvatar: () -> Unit,
) {
    LaunchedEffect(account?.id, account?.avatarUrl) {
        account?.let(loadProfileAvatar) ?: clearProfileAvatar()
    }
}

@Composable
internal fun QueueRequestEffects(
    requestedQueueAdvance: Int,
    requestedQueueWrapPause: Int,
    requestedCurrentTrackRestart: Int,
    playerState: PlayerState,
    playbackQueue: PlaybackQueue,
    skipNext: () -> Unit,
    pauseAtQueueStart: () -> Unit,
    restartCurrentTrack: (Track, PlaybackQueue) -> Unit,
) {
    LaunchedEffect(requestedQueueAdvance) {
        if (requestedQueueAdvance > 0) {
            skipNext()
        }
    }

    LaunchedEffect(requestedQueueWrapPause) {
        if (requestedQueueWrapPause > 0) {
            pauseAtQueueStart()
        }
    }

    LaunchedEffect(requestedCurrentTrackRestart) {
        if (requestedCurrentTrackRestart > 0) {
            val currentTrack = playerState.currentTrack ?: return@LaunchedEffect
            restartCurrentTrack(
                currentTrack,
                playbackQueue.takeIf { it.tracks.isNotEmpty() }
                    ?: PlaybackQueue(tracks = listOf(currentTrack), currentIndex = 0),
            )
        }
    }
}

@Composable
internal fun CrossfadePreparationEffects(
    exoPlayer: ExoPlayer,
    standbyExoPlayer: ExoPlayer,
    mediaCache: SimpleCache,
    playerState: PlayerState,
    playbackQueue: PlaybackQueue,
    playbackQueueGeneration: Long,
    repeatMode: PlaybackRepeatMode,
    crossfadeSeconds: Int,
    prefetchedPlaybackUrls: Map<String, String>,
    crossfadePreparationSerial: Long,
    crossfadeJob: Job?,
    preparedCrossfade: PreparedCrossfade?,
    setPreparedCrossfade: (PreparedCrossfade?) -> Unit,
    nextCrossfadeQueueIndex: (PlaybackQueue) -> Int?,
    localOrCachedPlaybackUrl: (Track) -> String?,
    prefetchNextTrackUrl: (PlaybackQueue) -> Unit,
    beginPreparedCrossfade: (PreparedCrossfade, Long) -> Unit,
) {
    LaunchedEffect(
        exoPlayer,
        playerState.currentTrack?.id,
        playerState.streamUrl,
        playbackQueueGeneration,
        playbackQueue.currentIndex,
        playbackQueue.tracks.map { it.id },
        repeatMode,
        crossfadeSeconds,
        prefetchedPlaybackUrls,
        crossfadePreparationSerial,
    ) {
        if (
            crossfadeSeconds <= 0 ||
            playerState.streamUrl == null ||
            crossfadeJob?.isActive == true
        ) {
            if (crossfadeSeconds <= 0) {
                setPreparedCrossfade(null)
                standbyExoPlayer.stop()
                standbyExoPlayer.clearMediaItems()
            }
            return@LaunchedEffect
        }
        val targetIndex = nextCrossfadeQueueIndex(playbackQueue) ?: return@LaunchedEffect
        val targetTrack = playbackQueue.tracks.getOrNull(targetIndex) ?: return@LaunchedEffect
        val targetUrl = localOrCachedPlaybackUrl(targetTrack)
            ?: prefetchedPlaybackUrls[targetTrack.id]
        if (targetUrl == null) {
            prefetchNextTrackUrl(playbackQueue)
            return@LaunchedEffect
        }
        val existing = preparedCrossfade
        if (
            existing?.player === standbyExoPlayer &&
            existing.queueGeneration == playbackQueueGeneration &&
            existing.queueIndex == targetIndex &&
            existing.trackId == targetTrack.id &&
            existing.url == targetUrl
        ) {
            return@LaunchedEffect
        }
        val mediaId = "crossfade:${System.nanoTime()}:$targetIndex:${targetTrack.id}"
        standbyExoPlayer.prepareCrossfadeItem(
            url = targetUrl,
            mediaId = mediaId,
            cacheKey = mediaCache.resolvePlaybackMediaCacheKey(targetTrack.id, targetUrl),
        )
        setPreparedCrossfade(
            PreparedCrossfade(
                player = standbyExoPlayer,
                queueGeneration = playbackQueueGeneration,
                queueIndex = targetIndex,
                trackId = targetTrack.id,
                url = targetUrl,
                mediaId = mediaId,
            ),
        )
    }

    LaunchedEffect(
        exoPlayer,
        preparedCrossfade?.signature,
        playerState.currentTrack?.id,
        playerState.isPlaying,
        crossfadeSeconds,
    ) {
        while (
            playerState.isPlaying &&
            crossfadeSeconds > 0 &&
            crossfadeJob?.isActive != true
        ) {
            val prepared = preparedCrossfade
            if (
                prepared != null &&
                prepared.player.playbackState == Player.STATE_READY &&
                prepared.player !== exoPlayer
            ) {
                val durationMs = exoPlayer.duration
                val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val requestedFadeMs = crossfadeSeconds.toLong() * 1_000L
                val effectiveFadeMs = requestedFadeMs
                    .coerceAtMost((durationMs / 2L).coerceAtLeast(1L))
                val remainingMs = durationMs - positionMs
                if (durationMs > 0L && remainingMs in 1..effectiveFadeMs) {
                    beginPreparedCrossfade(prepared, effectiveFadeMs.coerceAtMost(remainingMs))
                    break
                }
            }
            delay(16L)
        }
    }
}

@Composable
internal fun NextTrackPrefetchEffect(
    requestedNextPrefetch: Int,
    playbackQueue: PlaybackQueue,
    prefetchNextTrackUrl: (PlaybackQueue) -> Unit,
) {
    LaunchedEffect(requestedNextPrefetch) {
        if (requestedNextPrefetch > 0) {
            prefetchNextTrackUrl(playbackQueue)
        }
    }
}

@Composable
internal fun QueueInsertionAnchorResetEffect(
    currentTrackId: String?,
    clearQueueInsertionAnchor: () -> Unit,
) {
    LaunchedEffect(currentTrackId) {
        clearQueueInsertionAnchor()
    }
}

@Composable
internal fun PendingPlaybackRestoreEffect(
    accountId: String?,
    syncMode: SyncMode,
    tracks: List<Track>,
    playlists: List<Playlist>,
    pendingPlaybackRestore: SavedPlaybackState?,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    setPlayerState: (PlayerState) -> Unit,
    loadArtwork: (String, ArtworkImageSize) -> Unit,
    clearPendingPlaybackRestore: () -> Unit,
) {
    LaunchedEffect(accountId, syncMode, tracks, playlists) {
        val savedPlayback = pendingPlaybackRestore ?: return@LaunchedEffect
        val restored = savedPlayback.restorePlayback(tracks = tracks, playlists = playlists)
            ?: return@LaunchedEffect
        setPlaybackQueue(restored.queue)
        setPlayerState(restored.playerState)
        restored.playerState.currentTrack?.let { track ->
            loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
        }
        clearPendingPlaybackRestore()
    }
}

@Composable
internal fun PlaylistSyncEffects(
    accountId: String?,
    offlineOnly: Boolean,
    syncMode: SyncMode,
    pendingLibraryMutationCount: Int,
    canUseServerRequests: () -> Boolean,
    loadPlaylistPickerPlaylists: (Boolean) -> Unit,
    syncPendingLibraryMutations: () -> Unit,
) {
    LaunchedEffect(accountId, offlineOnly, syncMode) {
        if (canUseServerRequests()) {
            loadPlaylistPickerPlaylists(false)
            syncPendingLibraryMutations()
        }
    }

    LaunchedEffect(accountId, offlineOnly, syncMode, pendingLibraryMutationCount) {
        if (canUseServerRequests()) {
            syncPendingLibraryMutations()
        }
    }
}

@Composable
internal fun OfflineDownloadResumeEffect(
    accountId: String?,
    offlineOnly: Boolean,
    resumePendingOfflineDownloads: () -> Unit,
) {
    LaunchedEffect(accountId, offlineOnly) {
        while (true) {
            delay(5_000)
            resumePendingOfflineDownloads()
            delay(55_000)
        }
    }
}

@Composable
internal fun OfflinePlaybackAvailabilityEffect(
    syncMode: SyncMode,
    offlineOnly: Boolean,
    enforceOfflinePlaybackAvailability: () -> Unit,
) {
    LaunchedEffect(syncMode, offlineOnly) {
        if (offlineOnly || syncMode == SyncMode.OfflineOnly) {
            enforceOfflinePlaybackAvailability()
        }
    }
}

@Composable
internal fun InitialLibraryLoadEffect(
    account: Account?,
    offlineOnly: Boolean,
    loadLibrary: () -> Unit,
    setOfflineOnlySyncMode: () -> Unit,
    clearLibraryError: () -> Unit,
) {
    LaunchedEffect(account?.id, offlineOnly) {
        if (account != null && !offlineOnly) {
            loadLibrary()
        } else if (offlineOnly) {
            setOfflineOnlySyncMode()
            clearLibraryError()
        }
    }
}
