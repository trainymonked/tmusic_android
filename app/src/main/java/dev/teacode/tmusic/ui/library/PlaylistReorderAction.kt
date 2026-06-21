package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun reorderPlaylistTracksAction(
    playlist: Playlist,
    playlistTrackIds: List<String>,
    playlists: List<Playlist>,
    tracks: List<Track>,
    savedAlbums: List<LibraryAlbum>,
    scope: CoroutineScope,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    libraryCacheStore: LibraryCacheStore,
    canUseServerRequests: () -> Boolean,
    updatePlaylists: (List<Playlist>) -> Unit,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
    setAccessToken: (String?) -> Unit,
) {
    val reorderPlan = playlistReorderPlan(
        playlist = playlist,
        playlists = playlists,
        playlistTrackIds = playlistTrackIds,
    )
    if (reorderPlan == null) {
        setLibraryError("Could not reorder this playlist.")
        return
    }

    val optimisticPlaylists = playlists.updatePlaylist(reorderPlan.updatedPlaylist)
    updatePlaylists(optimisticPlaylists)
    if (!canUseServerRequests()) {
        enqueueLibraryMutation(
            "playlist.track.move",
            reorderPlan.move.toPendingMovePayload(playlist.id),
        )
        saveLibraryCache()
        return
    }

    libraryCacheStore.saveLibrary(
        playlists = optimisticPlaylists,
        tracks = tracks,
        savedAlbums = savedAlbums,
    )
    scope.launch {
        setLibraryError(null)
        runCatching {
            musicRepository.movePlaylistTrack(
                playlistId = playlist.id,
                playlistTrackId = reorderPlan.move.playlistTrackId,
                position = reorderPlan.move.position,
            )
        }.onSuccess { updatedPlaylist ->
            setAccessToken(authRepository.accessToken())
            if (updatedPlaylist != null) {
                val nextPlaylists = optimisticPlaylists.updatePlaylist(updatedPlaylist)
                updatePlaylists(nextPlaylists)
                libraryCacheStore.saveLibrary(
                    playlists = nextPlaylists,
                    tracks = tracks,
                    savedAlbums = savedAlbums,
                )
            }
        }.onFailure { error ->
            markServerUnavailable(error)
            enqueueLibraryMutation(
                "playlist.track.move",
                reorderPlan.move.toPendingMovePayload(playlist.id),
            )
            setLibraryError(error.userMessage())
        }
    }
}

private fun PlaylistTrackMove.toPendingMovePayload(playlistId: String): JSONObject {
    return JSONObject()
        .put("playlistId", playlistId)
        .put("playlistTrackId", playlistTrackId)
        .put("position", position)
}
