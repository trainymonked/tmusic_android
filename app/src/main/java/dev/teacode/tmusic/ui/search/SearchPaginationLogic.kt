package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.LibrarySearchResults

internal const val SEARCH_TRACK_PAGE_SIZE = 10

internal fun LibrarySearchResults.withAppendedTrackPage(page: LibrarySearchResults): LibrarySearchResults {
    return copy(
        tracks = (tracks + page.tracks).distinctBy { it.id },
    )
}
