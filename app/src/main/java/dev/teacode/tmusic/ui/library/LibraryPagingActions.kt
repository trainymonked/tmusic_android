package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.ArtistSortOption
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun loadMoreArtistsAction(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    sortOption: ArtistSortOption,
    getLibraryPaging: () -> LibraryPagingState,
    setLibraryPaging: (LibraryPagingState) -> Unit,
    getArtists: () -> List<LibraryArtist>,
    setArtists: (List<LibraryArtist>) -> Unit,
    setArtistServerSortOption: (ArtistSortOption) -> Unit,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setAccessToken: (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val paging = getLibraryPaging()
    if (!canUseServerRequests() || paging.artistLoadingMore || !paging.artistHasMore) {
        return
    }

    setLibraryPaging(paging.copy(artistLoadingMore = true))
    val offset = paging.artistNextOffset.coerceAtLeast(0)
    scope.launch {
        runCatching {
            musicRepository.libraryArtistsPageWithTotal(
                limit = SCREEN_PAGE_LIMIT,
                offset = offset,
                sortOption = sortOption,
            )
        }.onSuccess { artistPage ->
            val loadedArtists = artistPage.artists
            val nextArtists = (getArtists() + loadedArtists).distinctBy { it.id }
            val nextOffset = offset + loadedArtists.size
            val hasMore = loadedArtists.size >= SCREEN_PAGE_LIMIT
            setAccessToken(authRepository.accessToken())
            setArtists(nextArtists)
            setArtistServerSortOption(sortOption)
            setLibraryPaging(
                getLibraryPaging().copy(
                    artistNextOffset = nextOffset,
                    artistHasMore = hasMore,
                ),
            )
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setLibraryPaging(getLibraryPaging().copy(artistLoadingMore = false))
    }
}

internal fun reloadArtistsAction(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    sortOption: ArtistSortOption,
    getLibraryPaging: () -> LibraryPagingState,
    setLibraryPaging: (LibraryPagingState) -> Unit,
    setArtists: (List<LibraryArtist>) -> Unit,
    setArtistServerSortOption: (ArtistSortOption) -> Unit,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setAccessToken: (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val paging = getLibraryPaging()
    if (!canUseServerRequests() || paging.artistLoadingMore) {
        return
    }

    setLibraryPaging(
        paging.copy(
            artistLoadingMore = true,
            artistNextOffset = 0,
            artistHasMore = true,
        ),
    )
    scope.launch {
        runCatching {
            musicRepository.libraryArtistsPageWithTotal(
                limit = SCREEN_PAGE_LIMIT,
                offset = 0,
                sortOption = sortOption,
            )
        }.onSuccess { artistPage ->
            val loadedArtists = artistPage.artists
            val nextOffset = loadedArtists.size
            val hasMore = loadedArtists.size >= SCREEN_PAGE_LIMIT
            setAccessToken(authRepository.accessToken())
            setArtists(loadedArtists)
            setArtistServerSortOption(sortOption)
            setLibraryPaging(
                getLibraryPaging().copy(
                    artistNextOffset = nextOffset,
                    artistHasMore = hasMore,
                ),
            )
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setLibraryPaging(getLibraryPaging().copy(artistLoadingMore = false))
    }
}

internal fun loadMoreAlbumsAction(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    getLibraryPaging: () -> LibraryPagingState,
    setLibraryPaging: (LibraryPagingState) -> Unit,
    getAlbums: () -> List<LibraryAlbum>,
    setAlbums: (List<LibraryAlbum>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setAccessToken: (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val paging = getLibraryPaging()
    if (!canUseServerRequests() || paging.albumLoadingMore || !paging.albumHasMore) {
        return
    }

    setLibraryPaging(paging.copy(albumLoadingMore = true))
    val offset = paging.albumNextOffset.coerceAtLeast(0)
    scope.launch {
        runCatching {
            musicRepository.libraryAlbumsPage(limit = SCREEN_PAGE_LIMIT, offset = offset)
        }.onSuccess { loadedAlbums ->
            setAccessToken(authRepository.accessToken())
            val savedAlbumIds = getSavedAlbums().map { it.id }.toSet()
            setAlbums(
                (getAlbums() + loadedAlbums.map { album ->
                    album.copy(savedByCurrentUser = album.savedByCurrentUser || album.id in savedAlbumIds)
                })
                    .distinctBy { it.id }
                    .sortedAlbumsForDisplay(),
            )
            setLibraryPaging(
                getLibraryPaging().copy(
                    albumNextOffset = offset + loadedAlbums.size,
                    albumHasMore = loadedAlbums.size >= SCREEN_PAGE_LIMIT,
                ),
            )
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setLibraryPaging(getLibraryPaging().copy(albumLoadingMore = false))
    }
}
