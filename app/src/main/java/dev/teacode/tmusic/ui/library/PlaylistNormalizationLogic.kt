package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.Playlist

internal fun Playlist.isSyntheticPlaceholderPlaylist(): Boolean {
    return title == "Untitled playlist" && !isFavoritesPlaylist()
}

internal fun Playlist.normalizedClientPlaylist(): Playlist {
    val normalizedPlaylist = if (isFavoritesPlaylist() && title == "Untitled playlist") {
        copy(title = "Favorites", isFavorites = true)
    } else {
        this
    }
    return if (normalizedPlaylist.isFavoritesPlaylist()) {
        normalizedPlaylist.normalizedFavoriteMembership()
    } else {
        normalizedPlaylist
    }
}

internal fun List<Playlist>.sanitizeClientPlaylists(): List<Playlist> {
    return map { it.normalizedClientPlaylist() }
        .filterNot { it.isSyntheticPlaceholderPlaylist() }
        .distinctBy { it.id }
}
