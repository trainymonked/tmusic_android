package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.RecentLibraryItemType
import dev.teacode.tmusic.domain.Track

internal fun handleRecentItemClickAction(
    item: RecentLibraryItem,
    artists: List<LibraryArtist>,
    albums: List<LibraryAlbum>,
    savedAlbums: List<LibraryAlbum>,
    searchResults: LibrarySearchResults,
    similarArtistsByArtist: Map<String, List<LibraryArtist>>,
    albumsByArtist: Map<String, List<LibraryAlbum>>,
    appearsOnByArtist: Map<String, List<LibraryAlbum>>,
    tracks: List<Track>,
    searchQuery: String,
    setSearchQuery: (String) -> Unit,
    resolveCachedArtist: (String) -> LibraryArtist?,
    openArtist: (LibraryArtist) -> Unit,
    openAlbum: (LibraryAlbum) -> Unit,
    selectSearchTrack: (Track, String) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    when (item.type) {
        RecentLibraryItemType.Artist -> {
            val cachedArtist = item.id?.let { artistId ->
                (artists + searchResults.artists + similarArtistsByArtist.values.flatten())
                    .firstOrNull { it.id == artistId }
                    ?: LibraryArtist(id = artistId, name = item.title)
            } ?: resolveCachedArtist(item.title)
            if (cachedArtist != null) {
                openArtist(cachedArtist)
            } else {
                setLibraryError("Artist id is missing for ${item.title}.")
            }
        }
        RecentLibraryItemType.Album -> {
            val cachedAlbum = item.id?.let { albumId ->
                (albums + savedAlbums + searchResults.albums + albumsByArtist.values.flatten() + appearsOnByArtist.values.flatten())
                    .firstOrNull { it.id == albumId }
            }
            openAlbum(
                cachedAlbum ?: LibraryAlbum(
                    id = item.id ?: item.title,
                    title = item.title,
                    artist = item.subtitle ?: "Unknown artist",
                    accentColor = stableUiColor(item.id ?: item.title),
                ),
            )
        }
        RecentLibraryItemType.Track -> {
            val track = (searchResults.tracks + tracks).firstOrNull { it.id == item.id }
            if (track != null) {
                selectSearchTrack(track, searchQuery)
            } else {
                setSearchQuery(item.title)
            }
        }
    }
}
