package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import dev.teacode.tmusic.data.SavedPlaybackState
import dev.teacode.tmusic.domain.PlayerState

internal fun capturePlaybackSnapshot(
    player: Player,
    state: PlayerState,
    queue: PlaybackQueue,
    activeEvent: ActivePlayEvent?,
): SavedPlaybackState? {
    val currentTrack = state.currentTrack ?: return null
    val matchingEvent = activeEvent?.takeIf { it.trackId == currentTrack.id }
    val playbackState = runCatching { player.playbackState }
        .getOrDefault(Player.STATE_IDLE)
    val positionMs = if (state.streamUrl != null && playbackState != Player.STATE_IDLE) {
        runCatching {
            player.currentPosition.coerceAtLeast(0L)
        }.getOrDefault(state.progressSeconds.toLong().coerceAtLeast(0L) * 1000L)
    } else {
        state.progressSeconds.toLong().coerceAtLeast(0L) * 1000L
    }
    return SavedPlaybackState(
        playlistId = queue.playlistId,
        sourceType = queue.sourceType.name,
        sourceId = queue.sourceId,
        sourceTitle = queue.sourceTitle,
        queueTrackIds = queue.tracks.map { it.id },
        sourceTrackIds = queue.sourceTracks.map { it.id },
        manualQueueFlags = queue.normalizedManualQueueFlags(),
        queueTracks = queue.tracks,
        sourceTracks = queue.sourceTracks,
        isShuffled = queue.isShuffled,
        currentIndex = queue.currentIndex,
        trackId = currentTrack.id,
        track = currentTrack,
        positionMs = positionMs,
        wasPlaying = state.isPlaying,
        scrobbleClientEventId = matchingEvent?.clientEventId,
        scrobblePlayedAt = matchingEvent?.playedAt,
        scrobbleDurationPlayedMs = matchingEvent?.durationPlayedMs ?: 0L,
    )
}

@Composable
internal fun PlaybackPersistenceEffect(onSave: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val saveAction = rememberUpdatedState(onSave)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveAction.value.invoke()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            saveAction.value.invoke()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
