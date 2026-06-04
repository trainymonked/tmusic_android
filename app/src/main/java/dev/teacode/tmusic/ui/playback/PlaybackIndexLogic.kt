package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.Track

internal fun playbackIndexForSourceIndex(
    sourceTracks: List<Track>,
    playbackTracks: List<Track>,
    sourceIndex: Int,
): Int {
    if (sourceIndex !in sourceTracks.indices || playbackTracks.isEmpty()) {
        return -1
    }
    if (sourceTracks.map { it.id } == playbackTracks.map { it.id }) {
        return sourceIndex
    }
    val playableTrackIds = playbackTracks.map { it.id }.toSet()
    val selectedTrack = sourceTracks[sourceIndex]
    if (selectedTrack.id !in playableTrackIds) {
        return -1
    }
    return sourceTracks
        .take(sourceIndex + 1)
        .count { it.id in playableTrackIds } - 1
}

internal fun Int.floorMod(modulus: Int): Int {
    return ((this % modulus) + modulus) % modulus
}
