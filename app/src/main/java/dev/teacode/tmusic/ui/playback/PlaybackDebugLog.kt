package dev.teacode.tmusic.ui

internal fun logPlaybackDebug(message: String) = Unit

internal fun PlaybackQueue.debugSummary(): String {
    val current = tracks.getOrNull(currentIndex)
    val previous = if (tracks.isNotEmpty() && currentIndex >= 0) {
        tracks[(currentIndex - 1).floorMod(tracks.size)]
    } else {
        null
    }
    val next = if (tracks.isNotEmpty() && currentIndex >= 0) {
        tracks[(currentIndex + 1).floorMod(tracks.size)]
    } else {
        null
    }
    return "queue(size=${tracks.size}, sourceSize=${sourceTracks.size}, index=$currentIndex, " +
        "current=${current?.debugTrack()}, previous=${previous?.debugTrack()}, next=${next?.debugTrack()}, " +
        "shuffled=$isShuffled, source=$sourceType:$sourceId)"
}

internal fun dev.teacode.tmusic.domain.Track.debugTrack(): String {
    return "${id}:${title.take(36)}"
}
