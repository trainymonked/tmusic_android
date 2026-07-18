package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.PlaylistPayload
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

internal class LibraryPayloadActionHost(
    private val musicRepository: RemoteMusicRepository,
    private val libraryCacheStore: LibraryCacheStore,
    private val getPlaylists: () -> List<Playlist>,
    private val setPlaylists: (List<Playlist>) -> Unit,
    private val getTracks: () -> List<Track>,
    private val setTracks: (List<Track>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val getPendingFavoriteStates: () -> Map<String, Boolean>,
) {
    fun mergeLoadedTracks(loadedTracks: List<Track>) {
        if (loadedTracks.isEmpty()) {
            return
        }

        val currentTracks = getTracks()
        val mergedTracks = (currentTracks + loadedTracks.withKnownTrackMetadata(currentTracks))
            .associateBy { it.id }
            .values
            .toList()
        val nextTracks = musicRepository.withOfflineState(mergedTracks)
            .withKnownTrackMetadata(currentTracks)
            .withPendingFavoriteStates(getPendingFavoriteStates())
        setTracks(nextTracks)
        libraryCacheStore.saveLibrary(
            playlists = getPlaylists(),
            tracks = nextTracks,
            savedAlbums = getSavedAlbums(),
        )
    }

    fun applyPlaylistPayload(payload: PlaylistPayload): Playlist? {
        val merged = payload.mergeWithCachedPlaylistData(
            cachedPlaylists = getPlaylists(),
            cachedTracks = getTracks(),
            withOfflineState = musicRepository::withOfflineState,
        )
        val nextTracks = merged.tracks.withPendingFavoriteStates(getPendingFavoriteStates())
        setTracks(nextTracks)
        setPlaylists(merged.playlists)
        libraryCacheStore.saveLibrary(
            playlists = merged.playlists,
            tracks = nextTracks,
            savedAlbums = getSavedAlbums(),
        )
        return merged.playlist
    }

    fun applyPlaylistTrackPage(
        playlist: Playlist,
        payload: PlaylistPayload,
        append: Boolean,
    ): Playlist? {
        val currentTracks = getTracks()
        val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
        val mergedPayload = payload.mergePlaylistTrackPage(
            playlist = playlist,
            currentPlaylist = currentPlaylist,
            append = append,
        )
        val pagePlaylist = mergedPayload.playlists
            .firstOrNull()
            ?.normalizedClientPlaylist()
            ?: return null
        val nextPlaylist = pagePlaylist.copy(
            title = pagePlaylist.title.takeUnless { it == "Untitled playlist" } ?: currentPlaylist.title,
            isOfflineEnabled = pagePlaylist.isOfflineEnabled || currentPlaylist.isOfflineEnabled,
            isFavorites = pagePlaylist.isFavorites || currentPlaylist.isFavorites,
            totalDurationSeconds = pagePlaylist.totalDurationSeconds,
        )
        val mergedTracks = musicRepository.withOfflineState(
            (currentTracks + mergedPayload.tracks.withKnownTrackMetadata(currentTracks))
                .associateBy { it.id }
                .values
                .toList(),
        ).withKnownTrackMetadata(currentTracks)
            .withPendingFavoriteStates(getPendingFavoriteStates())
        val nextPlaylists = getPlaylists()
            .sanitizeClientPlaylists()
            .map { existingPlaylist ->
                if (existingPlaylist.id == nextPlaylist.id) {
                    nextPlaylist
                } else {
                    existingPlaylist
                }
            }
            .let { updatedPlaylists ->
                if (updatedPlaylists.any { it.id == nextPlaylist.id }) {
                    updatedPlaylists
                } else {
                    listOf(nextPlaylist) + updatedPlaylists
                }
            }
        setTracks(mergedTracks)
        setPlaylists(nextPlaylists)
        libraryCacheStore.saveLibrary(
            playlists = nextPlaylists,
            tracks = mergedTracks,
            savedAlbums = getSavedAlbums(),
        )
        return nextPlaylist
    }

    fun playlistDownloadedTrackCount(playlist: Playlist): Int {
        return playlistDownloadedTrackCount(
            playlist = playlist,
            tracks = getTracks(),
            hasLocalPlaybackUrl = { musicRepository.localPlaybackUrl(it) != null },
        )
    }

    fun playlistIsFullyDownloaded(playlist: Playlist): Boolean {
        return playlistIsFullyDownloaded(
            playlist = playlist,
            tracks = getTracks(),
            hasLocalPlaybackUrl = { musicRepository.localPlaybackUrl(it) != null },
        )
    }
}
