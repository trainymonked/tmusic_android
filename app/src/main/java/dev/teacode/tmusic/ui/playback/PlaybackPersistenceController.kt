package dev.teacode.tmusic.ui

import androidx.media3.exoplayer.ExoPlayer
import dev.teacode.tmusic.data.PlaybackStateStore
import dev.teacode.tmusic.domain.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PlaybackPersistenceController(
    private val scope: CoroutineScope,
    private val playbackStateStore: PlaybackStateStore,
    private val playbackSnapshotSaveMutex: Mutex,
    private val appState: TMusicAppMutableState,
    private val getExoPlayer: () -> ExoPlayer,
    private val getStandbyExoPlayer: () -> ExoPlayer,
) {
    fun savePlaybackSnapshot(
        state: PlayerState = appState.playerState,
        queue: PlaybackQueue = appState.playbackQueue,
        runtimeOnly: Boolean = false,
    ) {
        val snapshot = capturePlaybackSnapshot(
            player = getExoPlayer(),
            state = state,
            queue = queue,
            activeEvent = appState.activePlayEventState.value,
        ) ?: return
        scope.launch(Dispatchers.IO) {
            playbackSnapshotSaveMutex.withLock {
                if (runtimeOnly) {
                    playbackStateStore.saveRuntime(snapshot)
                } else {
                    playbackStateStore.save(snapshot)
                }
            }
        }
    }

    fun savePlaybackRuntimeSnapshot(
        state: PlayerState = appState.playerState,
        queue: PlaybackQueue = appState.playbackQueue,
    ) {
        savePlaybackSnapshot(
            state = state,
            queue = queue,
            runtimeOnly = true,
        )
    }

    fun clearGaplessPlaybackState() {
        appState.gaplessPlaybackRequest = null
        appState.gaplessMediaQueueIndices = emptyMap()
        appState.gaplessMediaUrls = emptyMap()
    }

    fun removePreparedPlaybackItemsAfterCurrent() {
        val exoPlayer = getExoPlayer()
        val currentMediaItemIndex = exoPlayer.currentMediaItemIndex
        if (currentMediaItemIndex !in 0 until exoPlayer.mediaItemCount) {
            return
        }
        val firstPreparedIndex = currentMediaItemIndex + 1
        if (firstPreparedIndex < exoPlayer.mediaItemCount) {
            exoPlayer.removeMediaItems(firstPreparedIndex, exoPlayer.mediaItemCount)
        }
    }

    fun cancelCrossfade() {
        val exoPlayer = getExoPlayer()
        val standbyExoPlayer = getStandbyExoPlayer()
        appState.crossfadeJob?.cancel()
        appState.crossfadeJob = null
        appState.preparedCrossfade?.player
            ?.takeIf { it !== exoPlayer }
            ?.run {
                stop()
                clearMediaItems()
                volume = 0f
            }
        appState.preparedCrossfade = null
        standbyExoPlayer.stop()
        standbyExoPlayer.clearMediaItems()
        standbyExoPlayer.volume = 0f
        exoPlayer.volume = 1f
        exoPlayer.configurePlaybackAudioFocus(handleAudioFocus = true)
        appState.crossfadePreparationSerial += 1
    }
}
