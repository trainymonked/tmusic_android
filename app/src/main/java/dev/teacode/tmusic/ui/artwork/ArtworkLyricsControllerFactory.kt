package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.ArtworkCacheStore
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import kotlinx.coroutines.CoroutineScope

internal fun createArtworkLyricsController(
    appState: TMusicAppMutableState,
    scope: CoroutineScope,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    offlineLyricsStore: OfflineLyricsStore,
    artworkCacheStore: ArtworkCacheStore,
    refreshStorageStats: () -> Unit,
    canUseMediaServerRequests: () -> Boolean,
    mediaDisabledMessage: () -> String,
    disableMediaPlaybackForAccount: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
) = ArtworkLyricsActionHost(
    scope = scope,
    musicRepository = musicRepository,
    authRepository = authRepository,
    offlineLyricsStore = offlineLyricsStore,
    artworkCacheStore = artworkCacheStore,
    canUseMediaServerRequests = canUseMediaServerRequests,
    isOfflineOnly = { appState.offlineOnly },
    getSyncMode = { appState.syncMode },
    getAccount = { appState.account },
    mediaDisabledMessage = mediaDisabledMessage,
    disableMediaPlaybackForAccount = disableMediaPlaybackForAccount,
    markServerUnavailable = markServerUnavailable,
    getHomeArtists = { appState.homeArtists },
    getArtists = { appState.artists },
    getSearchResults = { appState.searchResults },
    getSimilarArtistsByArtist = { appState.similarArtistsByArtist },
    getPlaylists = { appState.playlists },
    getTracks = { appState.tracks },
    getOfflineAlbumIds = { appState.offlineAlbumIds },
    getAlbumTracksById = { appState.albumTracksById },
    setTracks = { appState.tracks = it },
    getArtworkBitmaps = { appState.artworkBitmaps },
    putArtworkBitmap = { key, bitmap -> appState.artworkBitmaps[key] = bitmap },
    removeArtworkBitmapsForSource = appState::removeArtworkBitmapsForSource,
    getArtworkLoadsInProgress = { appState.artworkLoadsInProgress },
    setArtworkLoadsInProgress = { appState.artworkLoadsInProgress = it },
    getLyricsByTrackId = { appState.lyricsByTrackId },
    setLyricsByTrackId = { appState.lyricsByTrackId = it },
    getLyricsUnavailableIds = { appState.lyricsUnavailableIds },
    setLyricsUnavailableIds = { appState.lyricsUnavailableIds = it },
    getLyricsLoadsInProgress = { appState.lyricsLoadsInProgress },
    setLyricsLoadsInProgress = { appState.lyricsLoadsInProgress = it },
    setAccessToken = { appState.accessToken = it },
    refreshStorageStats = refreshStorageStats,
    setLibraryError = { appState.libraryError = it },
    resolveCachedArtist = { artistName ->
        resolveCachedArtist(
            artistName = artistName,
            artists = appState.artists,
            searchResults = appState.searchResults,
            similarArtistsByArtist = appState.similarArtistsByArtist,
        )
    },
    getProfileAvatarLoadKey = { appState.profileAvatarLoadKey },
    setProfileAvatarLoadKey = { appState.profileAvatarLoadKey = it },
    getProfileAvatarBitmap = { appState.profileAvatarBitmap },
    setProfileAvatarBitmap = { appState.profileAvatarBitmap = it },
)
