package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.LibraryArtist

internal data class ArtistListCacheEntry(
    val artists: List<LibraryArtist>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

internal data class ArtistSortChangeResult(
    val cache: Map<ArtistSortOption, ArtistListCacheEntry>,
    val artists: List<LibraryArtist>,
    val paging: LibraryPagingState,
)

internal fun Map<ArtistSortOption, ArtistListCacheEntry>.withArtistListCache(
    sortOption: ArtistSortOption,
    artists: List<LibraryArtist>,
    nextOffset: Int,
    hasMore: Boolean,
): Map<ArtistSortOption, ArtistListCacheEntry> {
    return this + (
        sortOption to ArtistListCacheEntry(
            artists = artists,
            nextOffset = nextOffset.coerceAtLeast(0),
            hasMore = hasMore,
        )
    )
}

internal fun changeArtistSortWithCache(
    currentSortOption: ArtistSortOption,
    nextSortOption: ArtistSortOption,
    currentArtists: List<LibraryArtist>,
    currentPaging: LibraryPagingState,
    cache: Map<ArtistSortOption, ArtistListCacheEntry>,
): ArtistSortChangeResult {
    val nextCache = cache.withArtistListCache(
        sortOption = currentSortOption,
        artists = currentArtists,
        nextOffset = currentPaging.artistNextOffset,
        hasMore = currentPaging.artistHasMore,
    )
    val cached = nextCache[nextSortOption]
    return if (cached == null) {
        ArtistSortChangeResult(
            cache = nextCache,
            artists = emptyList(),
            paging = currentPaging.copy(
                artistNextOffset = 0,
                artistHasMore = true,
                artistLoadingMore = false,
            ),
        )
    } else {
        ArtistSortChangeResult(
            cache = nextCache,
            artists = cached.artists,
            paging = currentPaging.copy(
                artistNextOffset = cached.nextOffset,
                artistHasMore = cached.hasMore,
                artistLoadingMore = false,
            ),
        )
    }
}
