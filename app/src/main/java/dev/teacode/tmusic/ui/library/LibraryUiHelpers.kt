package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

const val ALBUM_ARTWORK_KEY_PREFIX = "album_"
const val ARTIST_ARTWORK_KEY_PREFIX = "artist_"
const val PLAYLIST_ARTWORK_KEY_PREFIX = "playlist_"

fun artistSubtitle(artist: LibraryArtist): String {
    val parts = listOfNotNull(
        artist.trackCount.takeIf { it > 0 }?.let(::trackCountLabel),
        artist.albumCount.takeIf { it > 0 }?.let(::albumCountLabel),
    )
    return parts.ifEmpty { listOf("Artist") }.joinToString(" - ")
}

fun albumListSubtitle(album: LibraryAlbum): String {
    return album.displayArtistNames()
}

fun LibraryAlbum.displayArtistNames(): String {
    val names = artist.artistNameParts()
        .distinctBy { it.lowercase() }
    return names.joinToString(" \u2022 ").ifBlank { artist.replace(';', '\u2022') }
}

fun trackCountLabel(count: Int): String {
    return "$count ${if (count == 1) "track" else "tracks"}"
}

fun collectionStatsLabel(trackCount: Int, totalDurationSeconds: Int?): String {
    val durationLabel = totalDurationSeconds
        ?.takeIf { it > 0 }
        ?.let(::durationMinutesLabel)
    return listOfNotNull(trackCountLabel(trackCount), durationLabel).joinToString(" - ")
}

fun loadedTracksDurationSeconds(tracks: List<Track>, expectedTrackCount: Int): Int? {
    return tracks
        .takeIf { loadedTracks ->
            loadedTracks.isNotEmpty() &&
                (expectedTrackCount <= 0 || loadedTracks.size >= expectedTrackCount)
        }
        ?.sumOf { it.durationSeconds.coerceAtLeast(0) }
        ?.takeIf { it > 0 }
}

private fun durationMinutesLabel(seconds: Int): String {
    val minutes = ((seconds.coerceAtLeast(1) + 59) / 60).coerceAtLeast(1)
    if (minutes < 60) {
        return "$minutes min"
    }
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (remainingMinutes == 0) {
        "$hours h"
    } else {
        "$hours h $remainingMinutes min"
    }
}

fun albumCountLabel(count: Int): String {
    return "$count ${if (count == 1) "album" else "albums"}"
}

fun Playlist.tracksFrom(tracks: List<Track>): List<Track> {
    val tracksById = tracks.associateBy { it.id }
    return trackIds.mapNotNull(tracksById::get)
}

fun Playlist.coverTrackFrom(tracks: List<Track>): Track? {
    val tracksById = tracks.associateBy { it.id }
    val coverTrackId = if (isFavoritesPlaylist()) {
        trackIds.lastOrNull()
    } else {
        trackIds.firstOrNull()
    }
    return coverTrackId?.let(tracksById::get)
}

fun artistArtworkKey(artist: LibraryArtist): String {
    return "$ARTIST_ARTWORK_KEY_PREFIX${artist.id}"
}

fun String.artistIdFromArtworkKey(): String {
    return removePrefix(ARTIST_ARTWORK_KEY_PREFIX)
}

fun playlistArtworkKey(playlist: Playlist): String {
    return "$PLAYLIST_ARTWORK_KEY_PREFIX${playlist.id}"
}

fun String.playlistIdFromArtworkKey(): String {
    return removePrefix(PLAYLIST_ARTWORK_KEY_PREFIX)
}

fun albumArtworkKey(albumId: String): String {
    return "$ALBUM_ARTWORK_KEY_PREFIX$albumId"
}

fun String.albumIdFromArtworkKey(): String {
    return removePrefix(ALBUM_ARTWORK_KEY_PREFIX)
}

fun Track.listArtworkKey(): String {
    return albumId?.takeIf { it.isNotBlank() }?.let(::albumArtworkKey) ?: id
}

fun Track.artistLogicNames(): List<String> {
    return (listOfNotNull(albumArtist) + artist)
        .flatMap { it.artistNameParts() }
        .distinctBy { it.lowercase() }
}

fun LibraryAlbum.artistLogicNames(): List<String> {
    return artist.artistNameParts()
        .distinctBy { it.lowercase() }
}

fun Track.artistReferences(): List<LibraryArtist> {
    val names = artistLogicNames()
    val ids = artistIds
        .ifEmpty { listOfNotNull(artistId, albumArtistId) }
        .distinct()
    return ids.mapIndexed { index, id ->
        LibraryArtist(
            id = id,
            name = names.getOrNull(index) ?: names.firstOrNull() ?: id,
        )
    }
}

fun LibraryAlbum.artistReferences(albumTracks: List<Track>): List<LibraryArtist> {
    val names = artistLogicNames()
    val ids = (
        artistIds.ifEmpty { listOfNotNull(artistId) } +
            albumTracks
                .filter { track -> track.albumId == id || track.album == title }
                .flatMap { track ->
                    listOfNotNull(track.albumArtistId, track.artistId) + track.artistIds
                }
        )
        .filter { it.isNotBlank() }
        .distinct()
    return ids.mapIndexed { index, artistId ->
        LibraryArtist(
            id = artistId,
            name = names.getOrNull(index) ?: names.firstOrNull() ?: artistId,
        )
    }
}

private fun String.artistNameParts(): List<String> {
    return split(';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

fun Playlist.isFavoritesPlaylist(): Boolean {
    val normalizedId = id.lowercase()
    val normalizedTitle = title.trim().lowercase()
    return isFavorites ||
        normalizedTitle == "favorites" ||
        normalizedTitle == "\u043b\u044e\u0431\u0438\u043c\u044b\u0435" ||
        normalizedId == "favorites" ||
        normalizedId.endsWith("_favorites") ||
        normalizedId.endsWith(":favorites") ||
        normalizedId.endsWith("/favorites")
}

fun stableUiColor(value: String): Long {
    val colors = longArrayOf(
        0xFF111111,
        0xFF2A2A2A,
        0xFF444444,
        0xFF5E5E5E,
        0xFF787878,
        0xFF929292,
    )
    return colors[kotlin.math.abs(value.hashCode()) % colors.size]
}
