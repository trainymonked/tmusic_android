package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

internal fun playPlaylistTrackAtWithBackgroundResolve(
    scope: CoroutineScope,
    playlist: Playlist,
    playlistTracks: List<Track>,
    trackIndex: Int,
    canUseServerRequests: () -> Boolean,
    nextRequestSerial: () -> Long,
    playQueue: (Track, PlaybackQueue, Int) -> Unit,
    resolveTracks: suspend (Playlist, List<Track>) -> List<Track>,
    replaceQueue: (Long, PlaybackQueue, String) -> Unit,
    isRequestCurrent: (Long) -> Boolean,
    markServerUnavailable: (Throwable) -> Unit,
    setPlayerError: (String) -> Unit,
) {
    if (playlistTracks.isEmpty()) {
        return
    }
    val requestSerial = nextRequestSerial()
    val selectedIndex = trackIndex.coerceIn(playlistTracks.indices)
    val selectedTrack = playlistTracks[selectedIndex]
    playQueue(
        selectedTrack,
        PlaybackQueue(
            playlistId = playlist.id,
            sourceType = PlaybackSourceType.Playlist,
            sourceId = playlist.id,
            sourceTitle = playlist.title,
            tracks = playlistTracks,
            sourceTracks = playlistTracks,
            currentIndex = selectedIndex,
        ),
        selectedIndex,
    )
    if (!canUseServerRequests()) {
        return
    }

    scope.launch {
        runCatching {
            resolveTracks(playlist, playlistTracks)
        }.onSuccess { resolvedTracks ->
            val resolvedIndex = selectedTrackIndexInResolvedTracks(
                selectedTrack = selectedTrack,
                selectedIndex = selectedIndex,
                sourceTracks = playlistTracks,
                resolvedTracks = resolvedTracks,
            )
            if (resolvedTracks.isNotEmpty() && resolvedIndex >= 0 && isRequestCurrent(requestSerial)) {
                replaceQueue(
                    requestSerial,
                    PlaybackQueue(
                        playlistId = playlist.id,
                        sourceType = PlaybackSourceType.Playlist,
                        sourceId = playlist.id,
                        sourceTitle = playlist.title,
                        tracks = resolvedTracks,
                        sourceTracks = resolvedTracks,
                        currentIndex = resolvedIndex,
                    ),
                    selectedTrack.id,
                )
            }
            logPlaybackDebug(
                "playlist background resolve cached playlist=${playlist.id} " +
                    "resolved=${resolvedTracks.size} requestCurrent=${isRequestCurrent(requestSerial)}",
            )
        }.onFailure { error ->
            if (isRequestCurrent(requestSerial)) {
                markServerUnavailable(error)
                setPlayerError(error.userMessage())
            }
        }
    }
}

internal fun shufflePlayPlaylistWithBackgroundResolve(
    scope: CoroutineScope,
    playlist: Playlist,
    playlistTracks: List<Track>,
    existingQueueTracks: List<Track>,
    canUseServerRequests: () -> Boolean,
    nextRequestSerial: () -> Long,
    setShuffleEnabled: () -> Unit,
    playQueue: (Track, PlaybackQueue, Int) -> Unit,
    resolveTracks: suspend (Playlist, List<Track>) -> List<Track>,
    isRequestCurrent: (Long) -> Boolean,
    replaceQueue: (Long, PlaybackQueue, String) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setPlayerError: (String) -> Unit,
) {
    if (playlistTracks.isEmpty()) {
        return
    }
    val requestSerial = nextRequestSerial()
    fun startShuffledQueue(sourceTracks: List<Track>): Track? {
        if (sourceTracks.isEmpty()) {
            return null
        }
        val firstTrack = randomizedFirstPlaylistTrack(sourceTracks, existingQueueTracks)
        logPlaybackDebug(
            "shuffle playlist start playlist=${playlist.id} title=${playlist.title} " +
                "tracks=${sourceTracks.size} expected=${playlist.trackCount} playlistTrackIds=${playlist.trackIds.size}",
        )
        val randomizedTracks = randomizedPlaylistTracksStartingWith(sourceTracks, existingQueueTracks, firstTrack)
        setShuffleEnabled()
        playQueue(
            firstTrack,
            PlaybackQueue(
                playlistId = playlist.id,
                sourceType = PlaybackSourceType.Playlist,
                sourceId = playlist.id,
                sourceTitle = playlist.title,
                tracks = randomizedTracks,
                sourceTracks = sourceTracks,
                isShuffled = true,
                currentIndex = 0,
            ),
            0,
        )
        return firstTrack
    }

    val firstTrack = startShuffledQueue(playlistTracks) ?: return
    if (!canUseServerRequests()) {
        return
    }

    scope.launch {
        runCatching {
            resolveTracks(playlist, playlistTracks)
        }.onSuccess { resolvedTracks ->
            if (resolvedTracks.isEmpty() || !isRequestCurrent(requestSerial)) {
                return@onSuccess
            }
            val resolvedFirstTrack = resolvedTracks.firstOrNull { it.id == firstTrack.id }
                ?: firstTrack
            val randomizedTracks = randomizedPlaylistTracksStartingWith(
                tracks = resolvedTracks,
                existingQueueTracks = existingQueueTracks,
                firstTrack = resolvedFirstTrack,
            )
            replaceQueue(
                requestSerial,
                PlaybackQueue(
                    playlistId = playlist.id,
                    sourceType = PlaybackSourceType.Playlist,
                    sourceId = playlist.id,
                    sourceTitle = playlist.title,
                    tracks = randomizedTracks,
                    sourceTracks = resolvedTracks,
                    isShuffled = true,
                    currentIndex = 0,
                ),
                resolvedFirstTrack.id,
            )
            logPlaybackDebug(
                "shuffle playlist background resolve playlist=${playlist.id} " +
                    "resolved=${resolvedTracks.size} requestCurrent=${isRequestCurrent(requestSerial)}",
            )
        }.onFailure { error ->
            if (isRequestCurrent(requestSerial)) {
                markServerUnavailable(error)
                setPlayerError(error.userMessage())
            }
        }
    }
}

internal fun playAlbumTrackWithBackgroundResolve(
    scope: CoroutineScope,
    album: LibraryAlbum,
    albumTracks: List<Track>,
    track: Track,
    canUseServerRequests: () -> Boolean,
    nextRequestSerial: () -> Long,
    playQueue: (Track, PlaybackQueue, Int) -> Unit,
    resolveTracks: suspend (LibraryAlbum, List<Track>) -> List<Track>,
    replaceResolvedQueue: (Long, PlaybackSourceType, String, List<Track>, List<Track>) -> Unit,
    isRequestCurrent: (Long) -> Boolean,
    markServerUnavailable: (Throwable) -> Unit,
    setPlayerError: (String) -> Unit,
) {
    if (albumTracks.isEmpty()) {
        return
    }
    val requestSerial = nextRequestSerial()
    val selectedIndex = albumTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    val initialTrack = albumTracks.getOrNull(selectedIndex) ?: track
    playQueue(
        initialTrack,
        PlaybackQueue(
            sourceType = PlaybackSourceType.Album,
            sourceId = album.id,
            sourceTitle = album.title,
            tracks = albumTracks,
            sourceTracks = albumTracks,
            currentIndex = selectedIndex,
        ),
        selectedIndex,
    )
    if (!canUseServerRequests()) {
        return
    }

    scope.launch {
        runCatching {
            resolveTracks(album, albumTracks)
        }.onSuccess { resolvedTracks ->
            if (resolvedTracks.isNotEmpty()) {
                replaceResolvedQueue(
                    requestSerial,
                    PlaybackSourceType.Album,
                    album.id,
                    resolvedTracks,
                    resolvedTracks,
                )
            }
        }.onFailure { error ->
            if (isRequestCurrent(requestSerial)) {
                markServerUnavailable(error)
                setPlayerError(error.userMessage())
            }
        }
    }
}

private fun randomizedPlaylistTracks(
    tracks: List<Track>,
    existingQueueTracks: List<Track>,
): List<Track> {
    val shuffledTracks = tracks.shuffled(Random)
    return if (shuffledTracks.size > 1 && shuffledTracks.map(Track::id) == existingQueueTracks.map(Track::id)) {
        shuffledTracks.drop(1) + shuffledTracks.first()
    } else {
        shuffledTracks
    }
}

private fun randomizedFirstPlaylistTrack(
    tracks: List<Track>,
    existingQueueTracks: List<Track>,
): Track {
    val existingFirstId = existingQueueTracks.firstOrNull()?.id
    return tracks
        .takeIf { it.size > 1 }
        ?.filterNot { it.id == existingFirstId }
        ?.takeIf { it.isNotEmpty() }
        ?.random(Random)
        ?: tracks.random(Random)
}

private fun randomizedPlaylistTracksStartingWith(
    tracks: List<Track>,
    existingQueueTracks: List<Track>,
    firstTrack: Track,
): List<Track> {
    val remainingTracks = tracks.toMutableList().apply {
        val firstIndex = indexOfFirst { it.id == firstTrack.id }
        if (firstIndex >= 0) {
            removeAt(firstIndex)
        }
    }
    val randomizedTracks = listOf(firstTrack) + randomizedPlaylistTracks(remainingTracks, existingQueueTracks)
    return randomizedTracks.ifEmpty { listOf(firstTrack) }
}
