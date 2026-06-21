package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun loadMoreRecentAlbumsPage(
    scope: CoroutineScope,
    canUseServerRequests: () -> Boolean,
    currentAlbums: List<LibraryAlbum>,
    nextOffset: Int,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    markServerUnavailable: (Throwable) -> Unit,
    setLoadingMore: (Boolean) -> Unit,
    setAccessToken: (String?) -> Unit,
    setRecentAlbums: (List<LibraryAlbum>) -> Unit,
    setNextOffset: (Int) -> Unit,
    setHasMore: (Boolean) -> Unit,
    setLibraryError: (String) -> Unit,
) {
    if (
        !canUseServerRequests() ||
        isLoadingMore ||
        !hasMore ||
        currentAlbums.size >= HOME_RECENT_ALBUM_MAX_COUNT
    ) {
        return
    }

    setLoadingMore(true)
    val offset = nextOffset.coerceAtLeast(currentAlbums.size)
    val limit = minOf(HOME_RECENT_ALBUM_PAGE_LIMIT, HOME_RECENT_ALBUM_MAX_COUNT - currentAlbums.size)
    scope.launch {
        runCatching {
            musicRepository.recentAlbums(limit = limit, offset = offset)
        }.onSuccess { loadedAlbums ->
            setAccessToken(authRepository.accessToken())
            val updatedAlbums = (currentAlbums + loadedAlbums)
                .distinctBy { it.id }
                .take(HOME_RECENT_ALBUM_MAX_COUNT)
            setRecentAlbums(updatedAlbums)
            setNextOffset(offset + loadedAlbums.size)
            setHasMore(loadedAlbums.size >= limit && updatedAlbums.size < HOME_RECENT_ALBUM_MAX_COUNT)
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setLoadingMore(false)
    }
}
