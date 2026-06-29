package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.LibraryCacheStore
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
    private val libraryCacheStore: LibraryCacheStore,
    private val canUseMediaServerRequests: () -> Boolean,
    private val isOfflineOnly: () -> Boolean,
    private val getSyncMode: () -> SyncMode,
    private val getAccount: () -> Account?,
    private val mediaDisabledMessage: () -> String,
    private val disableMediaPlaybackForAccount: () -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val getArtists: () -> List<LibraryArtist>,
    private val getSearchResults: () -> LibrarySearchResults,
    private val getSimilarArtistsByArtist: () -> Map<String, List<LibraryArtist>>,
    private val getPlaylists: () -> List<Playlist>,
    private val getTracks: () -> List<Track>,
    private val setTracks: (List<Track>) -> Unit,
    private val getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    private val setArtworkBitmaps: (Map<String, ImageBitmap>) -> Unit,
    private val getArtworkLoadsInProgress: () -> Set<String>,
    private val setArtworkLoadsInProgress: (Set<String>) -> Unit,
    private val getLyricsByTrackId: () -> Map<String, TrackLyrics>,
    private val setLyricsByTrackId: (Map<String, TrackLyrics>) -> Unit,
    private val getLyricsUnavailableIds: () -> Set<String>,
    private val setLyricsUnavailableIds: (Set<String>) -> Unit,
    private val getLyricsLoadsInProgress: () -> Set<String>,
    private val setLyricsLoadsInProgress: (Set<String>) -> Unit,
    private val getShowLyrics: () -> Boolean,
    private val setAccessToken: (String?) -> Unit,
    private val setCacheSizeBytes: (Long) -> Unit,
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
            libraryCacheStore = libraryCacheStore,
            musicRepository = musicRepository,
            canUseMediaServerRequests = canUseMediaServerRequests,
            getArtists = getArtists,
            getSearchResults = getSearchResults,
            getSimilarArtistsByArtist = getSimilarArtistsByArtist,
            getPlaylists = getPlaylists,
            getTracks = getTracks,
            getArtworkLoadsInProgress = getArtworkLoadsInProgress,
            refreshAccessToken = authRepository::accessToken,
            setAccessToken = setAccessToken,
            setCacheSizeBytes = setCacheSizeBytes,
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
            setArtworkBitmaps = setArtworkBitmaps,
            getArtworkLoadsInProgress = getArtworkLoadsInProgress,
            setArtworkLoadsInProgress = setArtworkLoadsInProgress,
            cacheArtwork = ::cacheArtwork,
            disableMediaPlaybackForAccount = disableMediaPlaybackForAccount,
        )
    }

    fun loadLyrics(track: Track) {
        loadLyricsAction(
            scope = scope,
            track = track,
            showLyrics = getShowLyrics(),
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
            getArtworkBitmaps = getArtworkBitmaps,
            setArtworkBitmaps = setArtworkBitmaps,
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
            libraryCacheStore = libraryCacheStore,
            cachedArtworkBitmap = ::cachedArtworkBitmap,
            setCacheSizeBytes = setCacheSizeBytes,
        )
    }
}
