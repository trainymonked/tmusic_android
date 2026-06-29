package dev.teacode.tmusic.ui

import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track

internal class PlaybackQueueControlActionHost(
    private val exoPlayer: ExoPlayer,
    private val userPreferencesStore: UserPreferencesStore,
    private val getPlaybackQueue: () -> PlaybackQueue,
    private val setPlaybackQueue: (PlaybackQueue) -> Unit,
    private val getPlayerState: () -> PlayerState,
    private val setPlayerState: (PlayerState) -> Unit,
    private val getActivePlayEvent: () -> ActivePlayEvent?,
    private val setActivePlayEvent: (ActivePlayEvent?) -> Unit,
    private val getQueueInsertionAnchorTrackId: () -> String?,
    private val setQueueInsertionAnchorTrackId: (String?) -> Unit,
    private val getQueueInsertionCursor: () -> Int?,
    private val setQueueInsertionCursor: (Int?) -> Unit,
    private val getShuffleEnabled: () -> Boolean,
    private val setShuffleEnabledState: (Boolean) -> Unit,
    private val setRepeatModeState: (PlaybackRepeatMode) -> Unit,
    private val cancelCrossfade: () -> Unit,
    private val clearGaplessPlaybackState: () -> Unit,
    private val removePreparedPlaybackItemsAfterCurrent: () -> Unit,
    private val clearNowPlayingEvent: (ActivePlayEvent?) -> Unit,
    private val canUseMediaServerRequests: () -> Boolean,
    private val localOrCachedPlaybackUrl: (Track) -> String?,
    private val completeActivePlayEvent: (Boolean) -> Unit,
    private val ensureActivePlayEvent: (Track, Boolean) -> Unit,
    private val seekPreparedQueueMediaItem: (Int, Int) -> Boolean,
    private val playQueuedTrack: (Track, PlaybackQueue, Int?, Int) -> Unit,
    private val mergeLoadedTracks: (List<Track>) -> Unit,
    private val loadArtwork: (String, ArtworkImageSize) -> Unit,
    private val incrementRequestedNextPrefetch: () -> Unit,
    private val applyPlaybackQueueOrderWithoutInterrupt: (PlaybackQueue) -> Unit,
    private val setLibraryNotice: (String?) -> Unit,
) {
    fun skipInQueue(direction: Int, restartCurrentWhenPrevious: Boolean = true) {
        skipInQueueAction(
            direction = direction,
            restartCurrentWhenPrevious = restartCurrentWhenPrevious,
            exoPlayer = exoPlayer,
            getPlaybackQueue = getPlaybackQueue,
            getPlayerState = getPlayerState,
            setPlayerState = setPlayerState,
            cancelCrossfade = cancelCrossfade,
            canUseMediaServerRequests = canUseMediaServerRequests,
            localOrCachedPlaybackUrl = localOrCachedPlaybackUrl,
            completeActivePlayEvent = completeActivePlayEvent,
            ensureActivePlayEvent = ensureActivePlayEvent,
            seekPreparedQueueMediaItem = seekPreparedQueueMediaItem,
            playQueuedTrack = playQueuedTrack,
        )
    }

    fun pauseAtQueueStart() {
        val queue = getPlaybackQueue()
        val firstTrack = queue.tracks.firstOrNull() ?: return
        clearNowPlayingEvent(getActivePlayEvent())
        setActivePlayEvent(null)
        clearGaplessPlaybackState()
        setPlaybackQueue(queue.copy(currentIndex = 0))
        setPlayerState(
            PlayerState(
                currentTrack = firstTrack,
                isPlaying = false,
                progressSeconds = 0,
                streamUrl = null,
            ),
        )
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        loadArtwork(firstTrack.listArtworkKey(), ArtworkImageSize.FullPlayer)
    }

    fun playTrackFromCurrentQueueAt(index: Int) {
        cancelCrossfade()
        val queue = getPlaybackQueue()
        val track = queue.tracks.getOrNull(index) ?: return
        val direction = if (index < queue.currentIndex) -1 else 1
        if (seekPreparedQueueMediaItem(index, direction)) {
            return
        }
        playQueuedTrack(track, queue.copy(currentIndex = index), index, 1)
    }

    fun addTrackToQueue(track: Track) {
        cancelCrossfade()
        val insertResult = getPlaybackQueue().withManualTrackInsertedAfterCurrent(
            track = track,
            currentTrack = getPlayerState().currentTrack,
            insertionAnchorTrackId = getQueueInsertionAnchorTrackId(),
            insertionCursor = getQueueInsertionCursor(),
        )
        clearGaplessPlaybackState()
        removePreparedPlaybackItemsAfterCurrent()
        setPlaybackQueue(insertResult.queue)
        setQueueInsertionAnchorTrackId(insertResult.anchorTrackId)
        setQueueInsertionCursor(insertResult.insertionIndex)
        incrementRequestedNextPrefetch()
        mergeLoadedTracks(listOf(track))
        loadArtwork(track.listArtworkKey(), ArtworkImageSize.TrackList)
        setLibraryNotice("Added to queue.")
    }

    fun removeTrackFromQueueAt(index: Int) {
        cancelCrossfade()
        val removal = getPlaybackQueue().withTrackRemovedAt(index, getQueueInsertionCursor()) ?: return
        if (removal.queue.tracks.isEmpty()) {
            clearNowPlayingEvent(getActivePlayEvent())
            setActivePlayEvent(null)
            clearGaplessPlaybackState()
            setPlaybackQueue(removal.queue)
            setPlayerState(PlayerState(null, isPlaying = false, progressSeconds = 0, streamUrl = null))
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            return
        }
        clearGaplessPlaybackState()
        setPlaybackQueue(removal.queue)
        setQueueInsertionCursor(removal.nextQueueInsertionCursor)
        removal.nextTrackToPlay?.let { nextTrack ->
            playQueuedTrack(nextTrack, removal.queue, removal.nextTrackIndex, 1)
        }
    }

    fun reorderQueueTracks(reorderedIndices: List<Int>) {
        cancelCrossfade()
        val nextQueue = getPlaybackQueue().withReorderedTracks(
            reorderedIndices = reorderedIndices,
            currentTrackId = getPlayerState().currentTrack?.id,
        ) ?: return
        setQueueInsertionCursor(null)
        clearGaplessPlaybackState()
        setPlaybackQueue(nextQueue)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        if (getShuffleEnabled() == enabled) {
            return
        }
        cancelCrossfade()
        val currentTrack = getPlayerState().currentTrack
        val nextQueue = if (enabled) {
            shuffleQueue(getPlaybackQueue(), currentTrack)
        } else {
            restoreNaturalQueue(getPlaybackQueue(), currentTrack)
        }
        setShuffleEnabledState(enabled)
        applyPlaybackQueueOrderWithoutInterrupt(nextQueue)
        userPreferencesStore.setShuffleEnabled(enabled)
    }

    fun setRepeatMode(mode: PlaybackRepeatMode) {
        setRepeatModeState(mode)
        userPreferencesStore.setPlaybackRepeatMode(mode.name)
    }

    fun seekTo(seconds: Int) {
        cancelCrossfade()
        val currentState = getPlayerState()
        val currentTrack = currentState.currentTrack ?: return
        val boundedSeconds = seconds.coerceIn(0, currentTrack.durationSeconds.coerceAtLeast(0))
        setPlayerState(currentState.copy(progressSeconds = boundedSeconds))
        if (currentState.streamUrl != null) {
            exoPlayer.seekTo(boundedSeconds.toLong() * 1000L)
        }
    }
}
