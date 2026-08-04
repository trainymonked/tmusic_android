package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.Track

internal fun List<Track>.localSearch(query: String): LibrarySearchResults {
    val needle = query.trim()
    if (needle.isBlank()) {
        return LibrarySearchResults(emptyList(), emptyList(), emptyList())
    }

    val matchingTracks = filter { track ->
        track.title.contains(needle, ignoreCase = true) ||
            track.artist.contains(needle, ignoreCase = true) ||
            track.artistLogicNames().any { it.contains(needle, ignoreCase = true) } ||
            track.album.contains(needle, ignoreCase = true)
    }
    val artists = filter { track ->
        track.artist.contains(needle, ignoreCase = true) ||
            track.artistLogicNames().any { it.contains(needle, ignoreCase = true) } ||
            track.albumArtist?.contains(needle, ignoreCase = true) == true
    }.downloadedArtists()
    val albums = filter { track ->
        track.album.contains(needle, ignoreCase = true)
    }.downloadedAlbums()

    return LibrarySearchResults(
        artists = artists,
        albums = albums,
        tracks = matchingTracks,
    )
}

internal fun Track.matchesArtistName(name: String): Boolean {
    return artistLogicNames().any { it.equals(name, ignoreCase = true) }
}

internal fun LibraryAlbum.matchesArtistName(name: String): Boolean {
    return artistLogicNames().any { it.equals(name, ignoreCase = true) }
}

internal fun Track.displayArtistNames(): String {
    val names = resolvedArtistDisplayNames(artists, artist)
    return names.joinToString(" \u2022 ").ifBlank { artist }
}
