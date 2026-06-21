package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteAuthRepository
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun openTrackArtistAction(
    scope: CoroutineScope,
    track: Track,
    canAttemptMetadataRequest: () -> Boolean,
    resolveCachedArtist: (String) -> LibraryArtist?,
    openArtistOptions: (List<LibraryArtist>, String) -> Unit,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setAccessToken: (String?) -> Unit,
    mergeLoadedTracks: (List<Track>) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val artistOptions = track.artistOptions(resolveCachedArtist)
    if (artistOptions.isNotEmpty() || !canAttemptMetadataRequest()) {
        openArtistOptions(artistOptions, "Artist id is missing for this track.")
        return
    }
    scope.launch {
        runCatching {
            musicRepository.track(track.id)
        }.onSuccess { loadedTrack ->
            setAccessToken(authRepository.accessToken())
            mergeLoadedTracks(listOf(loadedTrack))
            openArtistOptions(
                loadedTrack.artistOptions(resolveCachedArtist),
                "Artist id is missing for this track.",
            )
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
    }
}

internal fun openAlbumArtistAction(
    scope: CoroutineScope,
    album: LibraryAlbum,
    getAlbumTracksById: () -> Map<String, List<Track>>,
    setAlbumTracksById: (Map<String, List<Track>>) -> Unit,
    getTracks: () -> List<Track>,
    canAttemptMetadataRequest: () -> Boolean,
    resolveCachedArtist: (String) -> LibraryArtist?,
    openArtistOptions: (List<LibraryArtist>, String) -> Unit,
    musicRepository: RemoteMusicRepository,
    authRepository: RemoteAuthRepository,
    setAccessToken: (String?) -> Unit,
    mergeLoadedTracks: (List<Track>) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val artistOptions = album.artistOptions(
        albumTracks = getAlbumTracksById()[album.id].orEmpty(),
        allTracks = getTracks(),
        resolveCachedArtist = resolveCachedArtist,
    )
    if (artistOptions.isNotEmpty() || !canAttemptMetadataRequest()) {
        openArtistOptions(artistOptions, "Artist id is missing for this album.")
        return
    }
    scope.launch {
        runCatching {
            musicRepository.albumTracksPage(
                albumId = album.id,
                limit = DETAIL_TRACK_PAGE_LIMIT,
                offset = 0,
            )
        }.onSuccess { loadedTracks ->
            setAccessToken(authRepository.accessToken())
            mergeLoadedTracks(loadedTracks)
            setAlbumTracksById(
                getAlbumTracksById() + (
                    album.id to (getAlbumTracksById()[album.id].orEmpty() + loadedTracks)
                        .distinctBy { it.id }
                    ),
            )
            openArtistOptions(
                album.artistOptions(
                    albumTracks = getAlbumTracksById()[album.id].orEmpty(),
                    allTracks = getTracks(),
                    resolveCachedArtist = resolveCachedArtist,
                ),
                "Artist id is missing for this album.",
            )
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
    }
}
