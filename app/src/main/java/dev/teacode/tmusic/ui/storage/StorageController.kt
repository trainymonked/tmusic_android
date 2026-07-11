package dev.teacode.tmusic.ui

import androidx.media3.datasource.cache.SimpleCache
import dev.teacode.tmusic.data.AppCacheStore
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.RecentLibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class StorageController(
    private val scope: CoroutineScope,
    private val appState: TMusicAppMutableState,
    private val userPreferencesStore: UserPreferencesStore,
    private val musicRepository: RemoteMusicRepository,
    private val offlineLyricsStore: OfflineLyricsStore,
    private val artworkCacheStore: ArtworkCacheStore,
    private val libraryCacheStore: LibraryCacheStore,
    private val appCacheStore: AppCacheStore,
    private val mediaCache: SimpleCache,
) {
    fun addRecentItem(item: RecentLibraryItem) {
        userPreferencesStore.addRecentLibraryItem(item)
        appState.recentItems = userPreferencesStore.recentLibraryItems()
    }

    fun refreshStorageStats() {
        scope.launch {
            val retainedArtworkKeys = downloadedArtworkCacheKeys(appState.playlists, appState.tracks)
            val retainedTrackIds = if (appState.playerState.isPlaying) {
                setOfNotNull(appState.playerState.currentTrack?.id)
            } else {
                emptySet()
            }
            val retainedPlaybackCacheKeys = if (appState.playerState.isPlaying) {
                buildSet {
                    appState.playerState.streamUrl?.let { streamUrl ->
                        add(streamUrl)
                        appState.playerState.currentTrack?.id
                            ?.let { trackId -> mediaCache.resolvePlaybackMediaCacheKey(trackId, streamUrl) }
                            ?.let { cacheKey -> add(cacheKey) }
                    }
                }
            } else {
                emptySet()
            }
            appState.downloadedSizeBytes = musicRepository.downloadsSizeBytes() +
                offlineLyricsStore.sizeBytes() +
                artworkCacheStore.sizeBytesFor(retainedArtworkKeys)
            appState.cacheSizeBytes = artworkCacheStore.sizeBytesExcluding(retainedArtworkKeys) +
                libraryCacheStore.sizeBytes() +
                musicRepository.musicCacheSizeBytesExcluding(retainedTrackIds) +
                appCacheStore.androidCacheSizeBytes(setOf(MEDIA3_PLAYBACK_CACHE_DIR)) +
                mediaCache.cacheSpaceExcluding(retainedPlaybackCacheKeys)
        }
    }

    fun clearRecentItems() {
        userPreferencesStore.clearRecentLibraryItems()
        appState.recentItems = emptyList()
    }
}

internal const val MEDIA3_PLAYBACK_CACHE_DIR = "media3_playback_cache"

private fun SimpleCache.cacheSpaceExcluding(keysToExclude: Set<String>): Long {
    if (keysToExclude.isEmpty()) {
        return cacheSpace
    }
    val excludedBytes = keysToExclude.sumOf { key ->
        getCachedSpans(key).sumOf { span -> span.length }
    }
    return (cacheSpace - excludedBytes).coerceAtLeast(0L)
}
