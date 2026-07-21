package dev.teacode.tmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

private const val HOME_ARTIST_ARTWORK_PRELOAD_LIMIT = 12
private const val HOME_ALBUM_ARTWORK_PRELOAD_LIMIT = 8
private const val HOME_ARTWORK_PRELOAD_CONCURRENCY = 2

@Composable
internal fun HomeArtworkPreloadEffect(
    artists: List<LibraryArtist>,
    recentAlbums: List<LibraryAlbum>,
    getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    putArtworkBitmap: (String, ImageBitmap) -> Unit,
    getArtworkLoadsInProgress: () -> Set<String>,
    setArtworkLoadsInProgress: (Set<String>) -> Unit,
    cacheArtwork: suspend (String, ArtworkImageSize) -> ImageBitmap?,
) {
    val artworkKeys = remember(artists, recentAlbums) {
        (
            artists.take(HOME_ARTIST_ARTWORK_PRELOAD_LIMIT).map(::artistArtworkKey) +
                recentAlbums.take(HOME_ALBUM_ARTWORK_PRELOAD_LIMIT).mapNotNull { album ->
                    albumArtworkKey(album, emptyList(), emptyMap())
                }
            ).distinct()
    }
    LaunchedEffect(artworkKeys) {
        if (artworkKeys.isEmpty()) {
            return@LaunchedEffect
        }
        coroutineScope {
            artworkKeys
                .chunked(
                    (artworkKeys.size + HOME_ARTWORK_PRELOAD_CONCURRENCY - 1) /
                        HOME_ARTWORK_PRELOAD_CONCURRENCY,
                )
                .map { artworkKeysChunk ->
                    launch {
                        artworkKeysChunk.forEach { artworkKey ->
                            val imageSize = ArtworkImageSize.AlbumGrid
                            val bitmapKey = artworkBitmapKey(artworkKey, imageSize)
                            val cacheKey = artworkCacheKey(artworkKey, imageSize)
                            if (
                                getArtworkBitmaps().containsKey(bitmapKey) ||
                                bitmapKey in getArtworkLoadsInProgress() ||
                                cacheKey in getArtworkLoadsInProgress()
                            ) {
                                return@forEach
                            }
                            setArtworkLoadsInProgress(
                                getArtworkLoadsInProgress() + bitmapKey + cacheKey,
                            )
                            try {
                                val bitmap = try {
                                    cacheArtwork(artworkKey, imageSize)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Throwable) {
                                    null
                                }
                                bitmap?.let { bitmap ->
                                    putArtworkBitmap(bitmapKey, bitmap)
                                }
                            } finally {
                                setArtworkLoadsInProgress(
                                    getArtworkLoadsInProgress() - bitmapKey - cacheKey,
                                )
                            }
                        }
                    }
                }
                .joinAll()
        }
    }
}
