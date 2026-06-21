package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun toggleAlbumInLibraryAction(
    scope: CoroutineScope,
    album: LibraryAlbum,
    canUseServerRequests: () -> Boolean,
    getOfflineAlbumIds: () -> Set<String>,
    getAlbums: () -> List<LibraryAlbum>,
    setAlbums: (List<LibraryAlbum>) -> Unit,
    getAlbumsByArtist: () -> Map<String, List<LibraryAlbum>>,
    setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    getAppearsOnByArtist: () -> Map<String, List<LibraryAlbum>>,
    setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    setSavedAlbums: (List<LibraryAlbum>) -> Unit,
    getPlaylists: () -> List<Playlist>,
    getTracks: () -> List<Track>,
    musicRepository: RemoteMusicRepository,
    libraryCacheStore: LibraryCacheStore,
    enqueueLibraryMutation: (String, JSONObject) -> Unit,
    saveLibraryCache: () -> Unit,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    if (!canUseServerRequests()) {
        val nextSavedState = !album.savedByCurrentUser
        val updatedAlbum = album.copy(
            savedByCurrentUser = nextSavedState,
            isOfflineEnabled = album.isOfflineEnabled || album.id in getOfflineAlbumIds(),
        )
        setAlbums(getAlbums().updateOrAppendAlbum(updatedAlbum))
        setAlbumsByArtist(
            getAlbumsByArtist().mapValues { (_, artistAlbums) ->
                artistAlbums.updateOrAppendAlbum(updatedAlbum)
            },
        )
        setAppearsOnByArtist(
            getAppearsOnByArtist().mapValues { (_, artistAlbums) ->
                artistAlbums.updateOrAppendAlbum(updatedAlbum)
            },
        )
        setSavedAlbums(
            if (nextSavedState) {
                listOf(updatedAlbum) + getSavedAlbums().filterNot { it.id == updatedAlbum.id }
            } else {
                getSavedAlbums().filterNot { it.id == album.id }
            },
        )
        enqueueLibraryMutation(
            "album.save.set",
            JSONObject()
                .put("albumId", album.id)
                .put("saved", nextSavedState),
        )
        saveLibraryCache()
        return
    }

    scope.launch {
        setLibraryError(null)
        val nextSavedState = !album.savedByCurrentUser
        runCatching {
            if (nextSavedState) {
                musicRepository.saveAlbum(album.id)
            } else {
                musicRepository.unsaveAlbum(album.id)
            }
        }.onSuccess { serverAlbum ->
            val updatedAlbum = (serverAlbum ?: album).copy(
                savedByCurrentUser = serverAlbum?.savedByCurrentUser ?: nextSavedState,
                isOfflineEnabled = serverAlbum?.isOfflineEnabled == true ||
                    album.isOfflineEnabled ||
                    album.id in getOfflineAlbumIds(),
            )
            setAlbums(getAlbums().updateAlbum(updatedAlbum))
            setAlbumsByArtist(
                getAlbumsByArtist().mapValues { (_, artistAlbums) ->
                    artistAlbums.updateAlbum(updatedAlbum)
                },
            )
            setAppearsOnByArtist(
                getAppearsOnByArtist().mapValues { (_, artistAlbums) ->
                    artistAlbums.updateAlbum(updatedAlbum)
                },
            )
            val nextSavedAlbums = if (updatedAlbum.savedByCurrentUser) {
                listOf(updatedAlbum) + getSavedAlbums().filterNot { it.id == updatedAlbum.id }
            } else {
                getSavedAlbums().filterNot { it.id == updatedAlbum.id }
            }
            setSavedAlbums(nextSavedAlbums)
            libraryCacheStore.saveLibrary(
                playlists = getPlaylists(),
                tracks = getTracks(),
                savedAlbums = nextSavedAlbums,
            )
            setAccessToken(refreshAccessToken())
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
    }
}
