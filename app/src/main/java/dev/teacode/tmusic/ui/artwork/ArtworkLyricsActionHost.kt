package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.CoroutineScope

internal class ArtworkLyricsActionHost(
    private val scope: CoroutineScope,
    private val musicRepository: RemoteMusicRepository,
    private val authRepository: RemoteAuthRepository,
    private val offlineLyricsStore: OfflineLyricsStore,
    private val artworkCacheStore: ArtworkCacheStore,
    private val canUseMediaServerRequests: () -> Boolean,
    private val isOfflineOnly: () -> Boolean,
    private val getSyncMode: () -> SyncMode,
    private val getAccount: () -> Account?,
    private val mediaDisabledMessage: () -> String,
    private val disableMediaPlaybackForAccount: () -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val getHomeArtists: () -> List<LibraryArtist>,
    private val getArtists: () -> List<LibraryArtist>,
    private val getSearchResults: () -> LibrarySearchResults,
    private val getSimilarArtistsByArtist: () -> Map<String, List<LibraryArtist>>,
    private val getPlaylists: () -> List<Playlist>,
    private val getTracks: () -> List<Track>,
    private val setTracks: (List<Track>) -> Unit,
    private val getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    private val putArtworkBitmap: (String, ImageBitmap) -> Unit,
    private val removeArtworkBitmapsForSource: (String) -> Unit,
    private val getArtworkLoadsInProgress: () -> Set<String>,
    private val setArtworkLoadsInProgress: (Set<String>) -> Unit,
    private val getLyricsByTrackId: () -> Map<String, TrackLyrics>,
    private val setLyricsByTrackId: (Map<String, TrackLyrics>) -> Unit,
    private val getLyricsUnavailableIds: () -> Set<String>,
    private val setLyricsUnavailableIds: (Set<String>) -> Unit,
    private val getLyricsLoadsInProgress: () -> Set<String>,
    private val setLyricsLoadsInProgress: (Set<String>) -> Unit,
    private val setAccessToken: (String?) -> Unit,
    private val refreshStorageStats: () -> Unit,
    private val setLibraryError: (String?) -> Unit,
    private val resolveCachedArtist: (String) -> LibraryArtist?,
    private val getProfileAvatarLoadKey: () -> String?,
    private val setProfileAvatarLoadKey: (String?) -> Unit,
    private val getProfileAvatarBitmap: () -> ImageBitmap?,
    private val setProfileAvatarBitmap: (ImageBitmap?) -> Unit,
) {
    suspend fun cachedArtworkBitmap(artworkKey: String, imageSize: ArtworkImageSize): ImageBitmap? {
        return cachedArtworkBitmapAction(
            artworkKey = artworkKey,
            imageSize = imageSize,
            artworkCacheStore = artworkCacheStore,
        )
    }

    suspend fun cacheArtwork(artworkKey: String, imageSize: ArtworkImageSize): ImageBitmap? {
        return cacheArtworkAction(
            artworkKey = artworkKey,
            imageSize = imageSize,
            artworkCacheStore = artworkCacheStore,
            musicRepository = musicRepository,
            canUseMediaServerRequests = canUseMediaServerRequests,
            getHomeArtists = getHomeArtists,
            getArtists = getArtists,
            getSearchResults = getSearchResults,
            getSimilarArtistsByArtist = getSimilarArtistsByArtist,
            getPlaylists = getPlaylists,
            getTracks = getTracks,
            getArtworkLoadsInProgress = getArtworkLoadsInProgress,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            refreshStorageStats = refreshStorageStats,
        )
    }

    fun hasCachedArtwork(artworkKey: String): Boolean {
        return artworkCacheStore.cachedPath(artworkCacheKey(artworkKey, ArtworkImageSize.FullPlayer)) != null ||
            artworkCacheStore.cachedPath(legacyArtworkCacheKey(artworkKey, ArtworkImageSize.FullPlayer)) != null
    }

    private suspend fun refreshArtworkCache(
        artworkKey: String,
        imageSize: ArtworkImageSize,
    ): ImageBitmap? {
        return cacheArtworkAction(
            artworkKey = artworkKey,
            imageSize = imageSize,
            artworkCacheStore = artworkCacheStore,
            musicRepository = musicRepository,
            canUseMediaServerRequests = canUseMediaServerRequests,
            getHomeArtists = getHomeArtists,
            getArtists = getArtists,
            getSearchResults = getSearchResults,
            getSimilarArtistsByArtist = getSimilarArtistsByArtist,
            getPlaylists = getPlaylists,
            getTracks = getTracks,
            getArtworkLoadsInProgress = getArtworkLoadsInProgress,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            refreshStorageStats = refreshStorageStats,
            forceRefresh = true,
        )
    }

    fun loadArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize = ArtworkImageSize.AlbumGrid,
    ) {
        loadArtworkAction(
            scope = scope,
            artworkKey = artworkKey,
            imageSize = imageSize,
            getArtworkBitmaps = getArtworkBitmaps,
            putArtworkBitmap = putArtworkBitmap,
            getArtworkLoadsInProgress = getArtworkLoadsInProgress,
            setArtworkLoadsInProgress = setArtworkLoadsInProgress,
            cacheArtwork = ::cacheArtwork,
            disableMediaPlaybackForAccount = disableMediaPlaybackForAccount,
        )
    }

    fun refreshArtwork(
        artworkKey: String,
        imageSize: ArtworkImageSize = ArtworkImageSize.AlbumGrid,
    ) {
        refreshArtworkAction(
            scope = scope,
            artworkKey = artworkKey,
            imageSize = imageSize,
            canUseMediaServerRequests = canUseMediaServerRequests,
            artworkCacheStore = artworkCacheStore,
            putArtworkBitmap = putArtworkBitmap,
            removeArtworkBitmapsForSource = removeArtworkBitmapsForSource,
            getArtworkLoadsInProgress = getArtworkLoadsInProgress,
            setArtworkLoadsInProgress = setArtworkLoadsInProgress,
            refreshArtwork = ::refreshArtworkCache,
            disableMediaPlaybackForAccount = disableMediaPlaybackForAccount,
        )
    }

    fun loadLyrics(track: Track) {
        loadLyricsAction(
            scope = scope,
            track = track,
            getLyricsByTrackId = getLyricsByTrackId,
            setLyricsByTrackId = setLyricsByTrackId,
            getLyricsUnavailableIds = getLyricsUnavailableIds,
            setLyricsUnavailableIds = setLyricsUnavailableIds,
            getLyricsLoadsInProgress = getLyricsLoadsInProgress,
            setLyricsLoadsInProgress = setLyricsLoadsInProgress,
            canUseMediaServerRequests = canUseMediaServerRequests,
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            disableMediaPlaybackForAccount = disableMediaPlaybackForAccount,
            markServerUnavailable = markServerUnavailable,
        )
    }

    fun refreshLyrics(track: Track) {
        refreshLyricsAction(
            scope = scope,
            track = track,
            canUseMediaServerRequests = canUseMediaServerRequests,
            isOfflineOnly = isOfflineOnly,
            getSyncMode = getSyncMode,
            getAccount = getAccount,
            mediaDisabledMessage = mediaDisabledMessage,
            getLyricsByTrackId = getLyricsByTrackId,
            setLyricsByTrackId = setLyricsByTrackId,
            getLyricsUnavailableIds = getLyricsUnavailableIds,
            setLyricsUnavailableIds = setLyricsUnavailableIds,
            getLyricsLoadsInProgress = getLyricsLoadsInProgress,
            setLyricsLoadsInProgress = setLyricsLoadsInProgress,
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            disableMediaPlaybackForAccount = disableMediaPlaybackForAccount,
            markServerUnavailable = markServerUnavailable,
            setLibraryError = setLibraryError,
        )
    }

    suspend fun cacheDownloadedAssets(track: Track) {
        cacheDownloadedAssetsAction(
            track = track,
            getTracks = getTracks,
            setTracks = setTracks,
            putArtworkBitmap = putArtworkBitmap,
            cacheArtwork = ::cacheArtwork,
            resolveCachedArtist = resolveCachedArtist,
            canUseMediaServerRequests = canUseMediaServerRequests,
            musicRepository = musicRepository,
            offlineLyricsStore = offlineLyricsStore,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            getLyricsByTrackId = getLyricsByTrackId,
            setLyricsByTrackId = setLyricsByTrackId,
        )
    }

    fun loadProfileAvatar(currentAccount: Account) {
        loadProfileAvatarAction(
            scope = scope,
            currentAccount = currentAccount,
            getProfileAvatarLoadKey = getProfileAvatarLoadKey,
            setProfileAvatarLoadKey = setProfileAvatarLoadKey,
            getProfileAvatarBitmap = getProfileAvatarBitmap,
            setProfileAvatarBitmap = setProfileAvatarBitmap,
            getPlaylists = getPlaylists,
            getTracks = getTracks,
            getArtworkLoadsInProgress = getArtworkLoadsInProgress,
            artworkCacheStore = artworkCacheStore,
            cachedArtworkBitmap = ::cachedArtworkBitmap,
            refreshStorageStats = refreshStorageStats,
        )
    }
}
