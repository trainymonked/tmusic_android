package dev.teacode.tmusic.ui

import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.TMusicApiException
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track
import java.io.File
import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class PlayQueuedTrackRequest(
    val track: Track,
    val queue: PlaybackQueue,
    val resumePositionMs: Long = 0L,
    val preferredIndex: Int? = null,
    val allowResume: Boolean = false,
    val newQueue: Boolean = false,
    val skippedQueueIndices: Set<Int> = emptySet(),
    val unavailableSkipDirection: Int = 1,
)

internal fun playQueuedTrackAction(
    request: PlayQueuedTrackRequest,
    scope: CoroutineScope,
    exoPlayer: ExoPlayer,
    musicRepository: RemoteMusicRepository,
    getAccount: () -> Account?,
    getShuffleEnabled: () -> Boolean,
    getPlaybackQueueGeneration: () -> Long,
    setPlaybackQueueGeneration: (Long) -> Unit,
    getPlaybackQueue: () -> PlaybackQueue,
    setPlaybackQueue: (PlaybackQueue) -> Unit,
    getPlayerState: () -> PlayerState,
    setPlayerState: (PlayerState) -> Unit,
    setPlaybackBufferedFraction: (Float) -> Unit,
    getPrefetchedPlaybackUrls: () -> Map<String, String>,
    setPrefetchedPlaybackUrls: (Map<String, String>) -> Unit,
    getOfflineOnly: () -> Boolean,
    getSyncMode: () -> SyncMode,
    currentStreamRequestSerial: () -> Long,
    nextStreamRequestSerial: () -> Long,
    cancelCrossfade: () -> Unit,
    canUseMediaServerRequests: () -> Boolean,
    disableMediaPlaybackForAccount: () -> Unit,
    mediaDisabledMessage: () -> String,
    clearGaplessPlaybackState: () -> Unit,
    localOrCachedPlaybackUrl: (Track) -> String?,
    loadArtwork: (String, ArtworkImageSize) -> Unit,
    prefetchNextTrackUrl: (PlaybackQueue) -> Unit,
    startGaplessPlayback: (Track, PlaybackQueue, List<String>, Long) -> Unit,
    startPlayback: (Track, String, Long) -> Unit,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryNotice: (String?) -> Unit,
    setPlayerError: (String?) -> Unit,
    replay: (PlayQueuedTrackRequest) -> Unit,
) {
    val track = request.track
    cancelCrossfade()
    if (request.newQueue) {
        nextStreamRequestSerial()
    }
    if (request.newQueue) {
        setPlaybackQueueGeneration(getPlaybackQueueGeneration() + 1L)
    }
    val startPositionMs = if (request.allowResume) request.resumePositionMs.coerceAtLeast(0L) else 0L
    val preparedQueue = prepareQueueForPlayback(
        queue = request.queue,
        track = track,
        shuffleEnabled = getShuffleEnabled(),
        preserveExistingShuffle = request.newQueue && request.queue.isShuffled,
    )
    val currentIndex = request.preferredIndex
        ?.takeIf { index -> index in preparedQueue.tracks.indices && preparedQueue.tracks[index].id == track.id }
        ?: preparedQueue.currentIndex
            .takeIf { index -> index in preparedQueue.tracks.indices && preparedQueue.tracks[index].id == track.id }
        ?: preparedQueue.tracks.indexOfFirst { it.id == track.id }
    val updatedQueue = preparedQueue.copy(
        currentIndex = currentIndex.takeIf { it >= 0 } ?: preparedQueue.currentIndex,
    )
    logPlaybackDebug(
        "play queued track=${track.debugTrack()} newQueue=${request.newQueue} preferred=${request.preferredIndex} " +
            "allowResume=${request.allowResume} ${updatedQueue.debugSummary()}",
    )

    fun canPlayWithoutServer(queuedTrack: Track): Boolean {
        return localOrCachedPlaybackUrl(queuedTrack) != null
    }

    fun stopForMediaDisabled() {
        disableMediaPlaybackForAccount()
        clearGaplessPlaybackState()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        setPlaybackBufferedFraction(0f)
        setLibraryNotice(null)
        setPlayerError(mediaDisabledMessage())
        setPlayerState(
            PlayerState(
                currentTrack = track,
                isPlaying = false,
                progressSeconds = (startPositionMs / 1000L).toInt().coerceAtLeast(0),
                streamUrl = null,
            ),
        )
    }

    fun skipUnavailableTrack(message: String): Boolean {
        if (!updatedQueue.canSkip || updatedQueue.tracks.size <= request.skippedQueueIndices.size + 1) {
            return false
        }
        val unavailableIndex = updatedQueue.currentIndex.coerceIn(0, updatedQueue.tracks.lastIndex)
        val skippedIndices = request.skippedQueueIndices + unavailableIndex
        val skipDirection = if (request.unavailableSkipDirection < 0) -1 else 1
        val nextIndex = (1 until updatedQueue.tracks.size)
            .map { offset -> (unavailableIndex + offset * skipDirection).floorMod(updatedQueue.tracks.size) }
            .firstOrNull { index ->
                index !in skippedIndices &&
                    (canUseMediaServerRequests() || canPlayWithoutServer(updatedQueue.tracks[index]))
            }
            ?: return false
        setLibraryNotice("$message Skipped unavailable track.")
        replay(
            request.copy(
                track = updatedQueue.tracks[nextIndex],
                queue = updatedQueue.copy(currentIndex = nextIndex),
                resumePositionMs = 0L,
                preferredIndex = nextIndex,
                allowResume = false,
                newQueue = false,
                skippedQueueIndices = skippedIndices,
                unavailableSkipDirection = request.unavailableSkipDirection,
            ),
        )
        return true
    }

    if (getAccount()?.canPlayMedia == false) {
        setPlaybackQueue(updatedQueue)
        stopForMediaDisabled()
        return
    }
    val previousTrack = getPlayerState().currentTrack
    val currentQueue = getPlaybackQueue()
    val isDifferentQueueItem = request.newQueue ||
        previousTrack?.id != track.id ||
        currentQueue.currentIndex != updatedQueue.currentIndex ||
        currentQueue.playlistId != updatedQueue.playlistId ||
        currentQueue.sourceType != updatedQueue.sourceType ||
        currentQueue.sourceId != updatedQueue.sourceId
    if (isDifferentQueueItem) {
        clearGaplessPlaybackState()
        setPlayerState(
            PlayerState(
                currentTrack = track,
                isPlaying = true,
                progressSeconds = 0,
                streamUrl = null,
            ),
        )
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }
    setPlaybackQueue(updatedQueue)
    setPlayerError(null)
    loadArtwork(track.listArtworkKey(), ArtworkImageSize.FullPlayer)
    if (updatedQueue.canSkip) {
        prefetchNextTrackUrl(updatedQueue)
    }
    val gaplessLocalUrls = if (updatedQueue.canSkip) {
        updatedQueue.tracks.map { queuedTrack ->
            localOrCachedPlaybackUrl(queuedTrack)
        }.takeIf { urls -> urls.all { it != null } }
            ?.filterNotNull()
    } else {
        null
    }
    if (gaplessLocalUrls != null) {
        startGaplessPlayback(track, updatedQueue, gaplessLocalUrls, startPositionMs)
        return
    }

    val localPlaybackUrl = musicRepository.localPlaybackUrl(track.id)
        ?: track.serverPath
            .takeIf { track.downloadState == DownloadState.Downloaded && it.isNotBlank() }
            ?.let { path -> File(path).takeIf { it.exists() && it.length() > 0L }?.toURI()?.toString() }
    if (localPlaybackUrl != null) {
        startPlayback(track.copy(downloadState = DownloadState.Downloaded), localPlaybackUrl, startPositionMs)
        prefetchNextTrackUrl(updatedQueue)
        return
    }

    val cachedPlaybackUrl = musicRepository.cachedPlaybackUrl(track.id)
    if (cachedPlaybackUrl != null) {
        startPlayback(track, cachedPlaybackUrl, startPositionMs)
        prefetchNextTrackUrl(updatedQueue)
        return
    }

    getPrefetchedPlaybackUrls()[track.id]?.let { prefetchedPlaybackUrl ->
        setPrefetchedPlaybackUrls(getPrefetchedPlaybackUrls() - track.id)
        startPlayback(track, prefetchedPlaybackUrl, startPositionMs)
        prefetchNextTrackUrl(updatedQueue)
        return
    }

    if (!canUseMediaServerRequests()) {
        if (getAccount()?.canPlayMedia == false) {
            stopForMediaDisabled()
            return
        }
        val unavailableMessage = if (getOfflineOnly() || getSyncMode() == SyncMode.OfflineOnly) {
            "Offline only mode is enabled."
        } else {
            "Track is not available offline."
        }
        if (!canPlayWithoutServer(track) && updatedQueue.canSkip) {
            if (skipUnavailableTrack(unavailableMessage)) {
                return
            }
        }
        setPlayerError(unavailableMessage.trimEnd('.'))
        setPlayerState(
            PlayerState(
                currentTrack = track,
                isPlaying = false,
                progressSeconds = (startPositionMs / 1000L).toInt().coerceAtLeast(0),
                streamUrl = null,
            ),
        )
        return
    }

    setPlayerState(
        PlayerState(
            currentTrack = track,
            isPlaying = true,
            progressSeconds = (startPositionMs / 1000L).toInt().coerceAtLeast(0),
            streamUrl = null,
        ),
    )
    val requestSerial = nextStreamRequestSerial()
    fun playbackQueueStillTargetsTrack(): Boolean {
        val currentQueue = getPlaybackQueue()
        return currentQueue.tracks
            .getOrNull(currentQueue.currentIndex)
            ?.id == track.id
    }
    scope.launch {
        runCatching {
            musicRepository.streamUrl(track.id)
        }.onSuccess { streamUrl ->
            if (
                currentStreamRequestSerial() == requestSerial &&
                getPlayerState().currentTrack?.id == track.id &&
                playbackQueueStillTargetsTrack()
            ) {
                setAccessToken(refreshAccessToken())
                startPlayback(track, streamUrl, startPositionMs)
                prefetchNextTrackUrl(updatedQueue)
            }
        }.onFailure { error ->
            if (currentStreamRequestSerial() == requestSerial && getPlayerState().currentTrack?.id == track.id) {
                if (error.isMediaPlaybackDisabledError()) {
                    stopForMediaDisabled()
                    return@onFailure
                }
                markServerUnavailable(error)
                val canSkipServerUnavailable = error is TMusicApiException &&
                    error.statusCode in setOf(
                        HttpURLConnection.HTTP_NOT_FOUND,
                        HttpURLConnection.HTTP_GONE,
                    )
                if (canSkipServerUnavailable && skipUnavailableTrack("Track is not available on the server.")) {
                    return@onFailure
                }
                setPlayerError(error.userMessage())
                setPlayerState(getPlayerState().copy(isPlaying = false))
            }
        }
    }
}
