package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun loadLyricsAction(
    scope: CoroutineScope,
    track: Track,
    getLyricsByTrackId: () -> Map<String, TrackLyrics>,
    setLyricsByTrackId: (Map<String, TrackLyrics>) -> Unit,
    getLyricsUnavailableIds: () -> Set<String>,
    setLyricsUnavailableIds: (Set<String>) -> Unit,
    getLyricsLoadsInProgress: () -> Set<String>,
    setLyricsLoadsInProgress: (Set<String>) -> Unit,
    canUseMediaServerRequests: () -> Boolean,
    musicRepository: RemoteMusicRepository,
    offlineLyricsStore: OfflineLyricsStore,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    disableMediaPlaybackForAccount: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
) {
    if (
        track.id in getLyricsByTrackId() ||
        track.id in getLyricsUnavailableIds() ||
        track.id in getLyricsLoadsInProgress()
    ) {
        return
    }
    offlineLyricsStore.lyrics(track.id)?.let { cachedLyrics ->
        setLyricsByTrackId(getLyricsByTrackId() + (track.id to cachedLyrics))
        return
    }
    if (!canUseMediaServerRequests()) {
        return
    }

    setLyricsLoadsInProgress(getLyricsLoadsInProgress() + track.id)
    scope.launch {
        runCatching {
            musicRepository.lyrics(track.id)
        }.onSuccess { lyrics ->
            setAccessToken(refreshAccessToken())
            if (lyrics == null) {
                setLyricsUnavailableIds(getLyricsUnavailableIds() + track.id)
            } else {
                setLyricsByTrackId(getLyricsByTrackId() + (track.id to lyrics))
                if (track.downloadState == DownloadState.Downloaded || musicRepository.localPlaybackUrl(track.id) != null) {
                    offlineLyricsStore.save(track.id, lyrics)
                }
            }
        }.onFailure { error ->
            if (error.isMediaPlaybackDisabledError()) {
                disableMediaPlaybackForAccount()
            } else {
                markServerUnavailable(error)
            }
            setLyricsUnavailableIds(getLyricsUnavailableIds() + track.id)
        }
        setLyricsLoadsInProgress(getLyricsLoadsInProgress() - track.id)
    }
}

internal fun refreshLyricsAction(
    scope: CoroutineScope,
    track: Track,
    canUseMediaServerRequests: () -> Boolean,
    isOfflineOnly: () -> Boolean,
    getSyncMode: () -> SyncMode,
    getAccount: () -> Account?,
    mediaDisabledMessage: () -> String,
    getLyricsByTrackId: () -> Map<String, TrackLyrics>,
    setLyricsByTrackId: (Map<String, TrackLyrics>) -> Unit,
    getLyricsUnavailableIds: () -> Set<String>,
    setLyricsUnavailableIds: (Set<String>) -> Unit,
    getLyricsLoadsInProgress: () -> Set<String>,
    setLyricsLoadsInProgress: (Set<String>) -> Unit,
    musicRepository: RemoteMusicRepository,
    offlineLyricsStore: OfflineLyricsStore,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    disableMediaPlaybackForAccount: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseMediaServerRequests()) {
        setLibraryError(
            if (isOfflineOnly() || getSyncMode() == SyncMode.OfflineOnly) {
                "Offline only mode is enabled."
            } else if (getAccount()?.canPlayMedia == false) {
                mediaDisabledMessage()
            } else {
                "Connect to the server before refreshing lyrics."
            },
        )
        return
    }
    if (track.id in getLyricsLoadsInProgress()) {
        return
    }

    setLyricsLoadsInProgress(getLyricsLoadsInProgress() + track.id)
    scope.launch {
        try {
            val lyrics = try {
                musicRepository.refreshLyrics(track.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                musicRepository.lyrics(track.id) ?: throw error
            }
            setAccessToken(refreshAccessToken())
            setLyricsUnavailableIds(getLyricsUnavailableIds() - track.id)
            if (lyrics == null) {
                setLyricsByTrackId(getLyricsByTrackId() - track.id)
                setLyricsUnavailableIds(getLyricsUnavailableIds() + track.id)
            } else {
                setLyricsByTrackId(getLyricsByTrackId() + (track.id to lyrics))
                if (track.downloadState == DownloadState.Downloaded || musicRepository.localPlaybackUrl(track.id) != null) {
                    offlineLyricsStore.save(track.id, lyrics)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error.isMediaPlaybackDisabledError()) {
                disableMediaPlaybackForAccount()
            } else {
                markServerUnavailable(error)
            }
            if (!isOfflineOnly()) {
                setLibraryError(
                    if (error.isMediaPlaybackDisabledError()) {
                        mediaDisabledMessage()
                    } else {
                        error.userMessage()
                    },
                )
            }
        } finally {
            setLyricsLoadsInProgress(getLyricsLoadsInProgress() - track.id)
        }
    }
}
