package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun cachedArtworkBitmapAction(
    artworkKey: String,
    imageSize: ArtworkImageSize,
    artworkCacheStore: ArtworkCacheStore,
): ImageBitmap? {
    val cachedPath = artworkCacheStore.cachedPath(artworkCacheKey(artworkKey, imageSize))
        ?: artworkCacheStore.cachedPath(legacyArtworkCacheKey(artworkKey, imageSize))
        ?: return null
    return decodeArtworkBitmap(cachedPath, imageSize.maxSizePx)
}

internal suspend fun cacheArtworkAction(
    artworkKey: String,
    imageSize: ArtworkImageSize,
    artworkCacheStore: ArtworkCacheStore,
    musicRepository: RemoteMusicRepository,
    canUseMediaServerRequests: () -> Boolean,
    getHomeArtists: () -> List<LibraryArtist>,
    getArtists: () -> List<LibraryArtist>,
    getSearchResults: () -> LibrarySearchResults,
    getSimilarArtistsByArtist: () -> Map<String, List<LibraryArtist>>,
    getPlaylists: () -> List<Playlist>,
    getTracks: () -> List<Track>,
    getArtworkLoadsInProgress: () -> Set<String>,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    refreshStorageStats: () -> Unit,
    forceRefresh: Boolean = false,
): ImageBitmap? {
    if (!forceRefresh) {
        cachedArtworkBitmapAction(artworkKey, imageSize, artworkCacheStore)?.let { return it }
    }
    val cacheKey = artworkCacheKey(artworkKey, imageSize)
    val cachedPath = if (forceRefresh) {
        null
    } else {
        artworkCacheStore.cachedPath(cacheKey)
            ?: artworkCacheStore.cachedPath(legacyArtworkCacheKey(artworkKey, imageSize))
    }
        ?: run {
            if (!canUseMediaServerRequests()) {
                return null
            }

            val url = when {
                artworkKey.startsWith(ALBUM_ARTWORK_KEY_PREFIX) ->
                    musicRepository.albumArtworkUrl(artworkKey.albumIdFromArtworkKey())
                artworkKey.startsWith(ARTIST_ARTWORK_KEY_PREFIX) -> {
                    val artistId = artworkKey.artistIdFromArtworkKey()
                    val knownArtistHasId = (
                        getHomeArtists() +
                            getArtists() +
                            getSearchResults().artists +
                            getSimilarArtistsByArtist().values.flatten()
                        ).any { it.id == artistId }
                    if (!knownArtistHasId) {
                        return null
                    }
                    musicRepository.artistArtworkUrl(artistId, size = imageSize.maxSizePx)
                }
                artworkKey.startsWith(PLAYLIST_ARTWORK_KEY_PREFIX) ->
                    musicRepository.playlistArtworkUrl(artworkKey.playlistIdFromArtworkKey(), size = imageSize.maxSizePx)
                else -> musicRepository.artworkUrl(artworkKey)
            }
            setAccessToken(refreshAccessToken())
            if (forceRefresh) {
                artworkCacheStore.refresh(cacheKey, url)
            } else {
                artworkCacheStore.cache(cacheKey, url)
            }
        }
    val bitmap = decodeArtworkBitmap(cachedPath, imageSize.maxSizePx)
    val playlists = getPlaylists()
    val tracks = getTracks()
    val downloadedKeys = withContext(Dispatchers.Default) {
        downloadedArtworkCacheKeys(playlists, tracks)
    }
    artworkCacheStore.trimToLimit(
        maxBytes = ARTWORK_CACHE_LIMIT_BYTES,
        keysToKeep = downloadedKeys + getArtworkLoadsInProgress() + cacheKey,
    )
    refreshStorageStats()
    return bitmap
}

internal fun loadArtworkAction(
    scope: CoroutineScope,
    artworkKey: String,
    imageSize: ArtworkImageSize,
    getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    putArtworkBitmap: (String, ImageBitmap) -> Unit,
    getArtworkLoadsInProgress: () -> Set<String>,
    setArtworkLoadsInProgress: (Set<String>) -> Unit,
    cacheArtwork: suspend (String, ArtworkImageSize) -> ImageBitmap?,
    disableMediaPlaybackForAccount: () -> Unit,
) {
    val bitmapKey = artworkBitmapKey(artworkKey, imageSize)
    val cacheKey = artworkCacheKey(artworkKey, imageSize)
    val loadsInProgress = getArtworkLoadsInProgress()
    if (
        getArtworkBitmaps().containsKey(bitmapKey) ||
        bitmapKey in loadsInProgress ||
        cacheKey in loadsInProgress
    ) {
        return
    }

    setArtworkLoadsInProgress(getArtworkLoadsInProgress() + bitmapKey + cacheKey)
    scope.launch {
        runCatching {
            cacheArtwork(artworkKey, imageSize)
        }.onSuccess { bitmap ->
            if (bitmap != null) {
                putArtworkBitmap(bitmapKey, bitmap)
            }
        }.onFailure { error ->
            if (error.isMediaPlaybackDisabledError()) {
                disableMediaPlaybackForAccount()
            }
        }
        setArtworkLoadsInProgress(getArtworkLoadsInProgress() - bitmapKey - cacheKey)
    }
}

internal fun refreshArtworkAction(
    scope: CoroutineScope,
    artworkKey: String,
    imageSize: ArtworkImageSize,
    canUseMediaServerRequests: () -> Boolean,
    artworkCacheStore: ArtworkCacheStore,
    putArtworkBitmap: (String, ImageBitmap) -> Unit,
    removeArtworkBitmapsForSource: (String) -> Unit,
    getArtworkLoadsInProgress: () -> Set<String>,
    setArtworkLoadsInProgress: (Set<String>) -> Unit,
    refreshArtwork: suspend (String, ArtworkImageSize) -> ImageBitmap?,
    disableMediaPlaybackForAccount: () -> Unit,
) {
    if (!canUseMediaServerRequests()) {
        return
    }
    val refreshKey = "refresh:$artworkKey"
    if (refreshKey in getArtworkLoadsInProgress()) {
        return
    }
    setArtworkLoadsInProgress(getArtworkLoadsInProgress() + refreshKey)
    scope.launch {
        val bitmapKey = artworkBitmapKey(artworkKey, imageSize)
        val cacheKey = artworkCacheKey(artworkKey, imageSize)
        var ownsLoadKeys = false
        try {
            while (
                bitmapKey in getArtworkLoadsInProgress() ||
                cacheKey in getArtworkLoadsInProgress()
            ) {
                delay(25L)
            }
            if (!canUseMediaServerRequests()) {
                return@launch
            }
            setArtworkLoadsInProgress(getArtworkLoadsInProgress() + bitmapKey + cacheKey)
            ownsLoadKeys = true
            runCatching {
                refreshArtwork(artworkKey, imageSize)
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    removeArtworkBitmapsForSource(artworkKey)
                    putArtworkBitmap(bitmapKey, bitmap)
                    artworkCacheStore.clearKeys(
                        ArtworkImageSize.entries
                            .mapTo(mutableSetOf()) { size -> legacyArtworkCacheKey(artworkKey, size) }
                            .apply { remove(cacheKey) },
                    )
                }
            }.onFailure { error ->
                if (error.isMediaPlaybackDisabledError()) {
                    disableMediaPlaybackForAccount()
                }
            }
        } finally {
            val remainingLoads = getArtworkLoadsInProgress() - refreshKey
            setArtworkLoadsInProgress(
                if (ownsLoadKeys) remainingLoads - bitmapKey - cacheKey else remainingLoads,
            )
        }
    }
}

internal fun loadProfileAvatarAction(
    scope: CoroutineScope,
    currentAccount: Account,
    getProfileAvatarLoadKey: () -> String?,
    setProfileAvatarLoadKey: (String?) -> Unit,
    getProfileAvatarBitmap: () -> ImageBitmap?,
    setProfileAvatarBitmap: (ImageBitmap?) -> Unit,
    getPlaylists: () -> List<Playlist>,
    getTracks: () -> List<Track>,
    getArtworkLoadsInProgress: () -> Set<String>,
    artworkCacheStore: ArtworkCacheStore,
    cachedArtworkBitmap: suspend (String, ArtworkImageSize) -> ImageBitmap?,
    refreshStorageStats: () -> Unit,
) {
    val avatarUrl = currentAccount.avatarUrl
    val loadKey = "${currentAccount.id}:${avatarUrl.orEmpty()}"
    if (getProfileAvatarLoadKey() == loadKey && getProfileAvatarBitmap() != null) {
        return
    }

    setProfileAvatarLoadKey(loadKey)
    setProfileAvatarBitmap(null)
    if (avatarUrl.isNullOrBlank()) {
        return
    }

    scope.launch {
        val cacheKey = "profile_${currentAccount.id}_${avatarUrl.hashCode()}"
        runCatching {
            cachedArtworkBitmap(cacheKey, ArtworkImageSize.TrackList) ?: run {
                val avatarCacheKey = artworkCacheKey(cacheKey, ArtworkImageSize.TrackList)
                val cachedPath = artworkCacheStore.cache(avatarCacheKey, avatarUrl)
                val playlists = getPlaylists()
                val tracks = getTracks()
                val downloadedKeys = withContext(Dispatchers.Default) {
                    downloadedArtworkCacheKeys(playlists, tracks)
                }
                artworkCacheStore.trimToLimit(
                    maxBytes = ARTWORK_CACHE_LIMIT_BYTES,
                    keysToKeep = downloadedKeys + getArtworkLoadsInProgress() + avatarCacheKey,
                )
                refreshStorageStats()
                decodeArtworkBitmap(cachedPath, ArtworkImageSize.TrackList.maxSizePx)
            }
        }.onSuccess { bitmap ->
            if (getProfileAvatarLoadKey() == loadKey) {
                setProfileAvatarBitmap(bitmap)
            }
        }
    }
}
