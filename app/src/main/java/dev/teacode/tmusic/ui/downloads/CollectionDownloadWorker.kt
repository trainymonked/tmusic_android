package dev.teacode.tmusic.ui

import android.util.Log
import dev.teacode.tmusic.data.PlaylistPayload
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

private const val DOWNLOAD_LOG_TAG = "TMusicDownload"

internal data class PlaylistDownloadSource(
    val playlist: Playlist,
    val tracks: List<Track>,
    val loadError: Throwable? = null,
)

internal data class AlbumDownloadSource(
    val tracks: List<Track>,
    val loadError: Throwable? = null,
)

internal data class TrackDownloadBatchResult(
    val failedTrackIds: Set<String>,
    val interruptedByPolicy: Boolean,
)

internal suspend fun loadPlaylistDownloadSource(
    musicRepository: RemoteMusicRepository,
    playlist: Playlist,
    pageLimit: Int,
    mergePage: (current: Playlist, payload: PlaylistPayload, append: Boolean) -> Playlist,
    fallbackTracks: (Playlist) -> List<Track>,
): PlaylistDownloadSource {
    val sourceTracksById = linkedMapOf<String, Track>()
    val seenPageSignatures = mutableSetOf<String>()
    var loadedPlaylist = playlist.copy(
        trackIds = emptyList(),
        playlistTrackIds = emptyList(),
        playlistTrackIdsByTrackId = emptyMap(),
    )
    var trackOffset = 0
    while (currentCoroutineContext().isActive) {
        Log.d(
            DOWNLOAD_LOG_TAG,
            "playlist page request id=${playlist.id} favorites=${playlist.isFavoritesPlaylist()} " +
                "limit=$pageLimit offset=$trackOffset",
        )
        val payload = try {
            if (playlist.isFavoritesPlaylist()) {
                musicRepository.favoritesPlaylistPayloadTrackPage(
                    trackLimit = pageLimit,
                    trackOffset = trackOffset,
                )
            } else {
                musicRepository.playlistPayloadTrackPage(
                    playlistId = playlist.id,
                    trackLimit = pageLimit,
                    trackOffset = trackOffset,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return PlaylistDownloadSource(
                playlist = loadedPlaylist.takeIf { it.trackIds.isNotEmpty() } ?: playlist,
                tracks = sourceTracksById.values.toList().ifEmpty { fallbackTracks(playlist) },
                loadError = error,
            )
        }

        val pagePlaylist = payload.playlists.firstOrNull()
        val pageTrackIds = pagePlaylist?.trackIds.orEmpty()
        val pagePlaylistTrackIds = pagePlaylist?.playlistTrackIds.orEmpty()
        val pageSize = maxOf(payload.tracks.size, pageTrackIds.size, pagePlaylistTrackIds.size)
        val pageSignature = buildString {
            append(pageTrackIds.joinToString(separator = "\u001F"))
            append('\u001E')
            append(pagePlaylistTrackIds.joinToString(separator = "\u001F"))
            append('\u001E')
            append(payload.tracks.joinToString(separator = "\u001F") { it.id })
        }
        if (pageSize == 0 || !seenPageSignatures.add(pageSignature)) {
            Log.d(
                DOWNLOAD_LOG_TAG,
                "playlist page stop id=${playlist.id} offset=$trackOffset pageSize=$pageSize duplicatePage=" +
                    (pageSize > 0),
            )
            break
        }
        loadedPlaylist = mergePage(loadedPlaylist, payload, trackOffset > 0)
        payload.tracks.forEach { track -> sourceTracksById[track.id] = track }
        val loadedCount = trackOffset + pageSize
        val totalCount = loadedPlaylist.trackCount
        val hasKnownRemainingTracks = totalCount > 0 && loadedCount < totalCount
        val canTrustTotalCount = totalCount > pageLimit || pageSize < pageLimit
        Log.d(
            DOWNLOAD_LOG_TAG,
            "playlist page result id=${playlist.id} offset=$trackOffset pageSize=$pageSize " +
                "tracks=${payload.tracks.size} trackIds=${pageTrackIds.size} " +
                "playlistTrackIds=${pagePlaylistTrackIds.size} loaded=$loadedCount total=$totalCount",
        )
        if (
            (pageSize < pageLimit && !hasKnownRemainingTracks) ||
            (canTrustTotalCount && totalCount > 0 && loadedCount >= totalCount)
        ) {
            Log.d(
                DOWNLOAD_LOG_TAG,
                "playlist page complete id=${playlist.id} offset=$trackOffset pageSize=$pageSize " +
                    "loaded=$loadedCount total=$totalCount",
            )
            break
        }
        trackOffset += pageLimit
    }

    return PlaylistDownloadSource(
        playlist = loadedPlaylist.takeIf { it.trackIds.isNotEmpty() } ?: playlist,
        tracks = sourceTracksById.values.toList().ifEmpty { fallbackTracks(playlist) },
    )
}

internal suspend fun loadAlbumDownloadSource(
    musicRepository: RemoteMusicRepository,
    album: LibraryAlbum,
    initialTracks: List<Track>,
    pageLimit: Int,
): AlbumDownloadSource {
    val expectedTrackCount = album.trackCount.coerceAtLeast(initialTracks.size)
    if (initialTracks.isNotEmpty() && expectedTrackCount > 0 && initialTracks.size >= expectedTrackCount) {
        return AlbumDownloadSource(initialTracks)
    }

    val loadedTracksById = linkedMapOf<String, Track>()
    var trackOffset = 0
    while (currentCoroutineContext().isActive) {
        val pageTracks = try {
            musicRepository.albumTracksPage(
                albumId = album.id,
                limit = pageLimit,
                offset = trackOffset,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return AlbumDownloadSource(
                tracks = loadedTracksById.values.orderedAlbumTracks().ifEmpty { initialTracks },
                loadError = error,
            )
        }
        pageTracks.forEach { track -> loadedTracksById[track.id] = track }
        if (
            pageTracks.size < pageLimit ||
            (expectedTrackCount > 0 && loadedTracksById.size >= expectedTrackCount) ||
            pageTracks.isEmpty()
        ) {
            break
        }
        trackOffset += pageTracks.size
    }

    return AlbumDownloadSource(
        tracks = loadedTracksById.values.orderedAlbumTracks().ifEmpty { initialTracks },
    )
}

internal suspend fun downloadTracksSequentially(
    tracks: List<Track>,
    canContinue: () -> Boolean,
    onQueued: (Track) -> Unit,
    downloadTrackAssets: suspend (Track) -> Unit,
    onDownloaded: (Track) -> Unit,
    onFailed: (Track) -> Unit,
    isFatalFailure: (Throwable) -> Boolean = { false },
): TrackDownloadBatchResult {
    val failedIds = mutableSetOf<String>()
    for (track in tracks) {
        if (!currentCoroutineContext().isActive) {
            throw CancellationException()
        }
        if (!canContinue()) {
            return TrackDownloadBatchResult(
                failedTrackIds = failedIds,
                interruptedByPolicy = true,
            )
        }
        onQueued(track)
        try {
            downloadTrackAssets(track)
            currentCoroutineContext().ensureActive()
            onDownloaded(track)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isFatalFailure(error)) {
                failedIds += track.id
                onFailed(track)
                return TrackDownloadBatchResult(
                    failedTrackIds = failedIds,
                    interruptedByPolicy = true,
                )
            }
            failedIds += track.id
            onFailed(track)
        }
    }
    return TrackDownloadBatchResult(
        failedTrackIds = failedIds,
        interruptedByPolicy = false,
    )
}

private fun Collection<Track>.orderedAlbumTracks(): List<Track> {
    return sortedWith(
        compareBy<Track> { it.discNumber ?: 1 }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE },
    )
}
