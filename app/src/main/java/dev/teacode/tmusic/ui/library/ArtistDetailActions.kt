package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun loadArtistAlbumsAction(
    scope: CoroutineScope,
    artist: LibraryArtist,
    canUseServerRequests: () -> Boolean,
    getArtistAlbumLoadsInProgress: () -> Set<String>,
    setArtistAlbumLoadsInProgress: (Set<String>) -> Unit,
    getAlbums: () -> List<LibraryAlbum>,
    setAlbums: (List<LibraryAlbum>) -> Unit,
    getSavedAlbums: () -> List<LibraryAlbum>,
    getTracks: () -> List<Track>,
    getAlbumsByArtist: () -> Map<String, List<LibraryAlbum>>,
    setAlbumsByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    getAppearsOnByArtist: () -> Map<String, List<LibraryAlbum>>,
    setAppearsOnByArtist: (Map<String, List<LibraryAlbum>>) -> Unit,
    getLooseTracksByArtist: () -> Map<String, List<Track>>,
    setLooseTracksByArtist: (Map<String, List<Track>>) -> Unit,
    musicRepository: RemoteMusicRepository,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    mergeLoadedTracks: (List<Track>) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val artistContentKey = artist.id.takeIf { it.isNotBlank() } ?: artist.name
    val knownAlbums = getAlbums().filter { album ->
        album.matchesArtistName(artist.name) ||
            getTracks().any { track ->
                (track.albumId == album.id || track.album == album.title) &&
                    track.matchesArtistName(artist.name)
            }
    }
    val localAlbums = getTracks()
        .filter { it.matchesArtistName(artist.name) }
        .downloadedAlbums()
    val localLooseTracks = getTracks()
        .filter { track ->
            track.albumId == null &&
                track.downloadState == DownloadState.Downloaded &&
                track.matchesArtistName(artist.name)
        }
        .sortedWith(compareBy<Track> { it.title.lowercase() }.thenBy { it.id })
    val fallbackAlbums = (knownAlbums + localAlbums).distinctBy { it.id }.sortedAlbumsForDisplay()
    val hasCachedArtistDetails = artistContentKey in getAlbumsByArtist() ||
        artistContentKey in getAppearsOnByArtist() ||
        artistContentKey in getLooseTracksByArtist()
    if (fallbackAlbums.isNotEmpty() && !hasCachedArtistDetails) {
        setAlbumsByArtist(getAlbumsByArtist() + (artistContentKey to fallbackAlbums))
        setAppearsOnByArtist(getAppearsOnByArtist() + (artistContentKey to emptyList()))
    }
    if (localLooseTracks.isNotEmpty() && artistContentKey !in getLooseTracksByArtist()) {
        setLooseTracksByArtist(getLooseTracksByArtist() + (artistContentKey to localLooseTracks))
    }
    val artistId = artist.id.takeIf { it.isNotBlank() }
    if (!canUseServerRequests() || artistContentKey in getArtistAlbumLoadsInProgress() || artistId == null) {
        return
    }

    setArtistAlbumLoadsInProgress(getArtistAlbumLoadsInProgress() + artistContentKey)
    scope.launch {
        runCatching {
            musicRepository.libraryArtistAlbums(artistId)
        }.onSuccess { loadedArtistAlbums ->
            setAccessToken(refreshAccessToken())
            val savedAlbumIds = getSavedAlbums().map { it.id }.toSet()
            val loadedAlbums = loadedArtistAlbums.albums.map { album ->
                album.copy(savedByCurrentUser = album.savedByCurrentUser || album.id in savedAlbumIds)
            }.sortedAlbumsForDisplay()
            val loadedAppearsOn = loadedArtistAlbums.appearsOn.map { album ->
                album.copy(savedByCurrentUser = album.savedByCurrentUser || album.id in savedAlbumIds)
            }.sortedAlbumsForDisplay()
            val loadedLooseTracks = loadedArtistAlbums.tracks.sortedWith(
                compareBy<Track> { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() },
            )
            setAlbumsByArtist(getAlbumsByArtist() + (artistContentKey to loadedAlbums))
            setAppearsOnByArtist(getAppearsOnByArtist() + (artistContentKey to loadedAppearsOn))
            setLooseTracksByArtist(getLooseTracksByArtist() + (artistContentKey to loadedLooseTracks))
            setAlbums((getAlbums() + loadedAlbums + loadedAppearsOn).distinctBy { it.id }.sortedAlbumsForDisplay())
            mergeLoadedTracks(loadedLooseTracks)
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setArtistAlbumLoadsInProgress(getArtistAlbumLoadsInProgress() - artistContentKey)
    }
}

internal fun loadSimilarArtistsAction(
    scope: CoroutineScope,
    artist: LibraryArtist,
    force: Boolean,
    canUseServerRequests: () -> Boolean,
    getSimilarArtistLoadsInProgress: () -> Set<String>,
    setSimilarArtistLoadsInProgress: (Set<String>) -> Unit,
    getSimilarArtistsByArtist: () -> Map<String, List<LibraryArtist>>,
    setSimilarArtistsByArtist: (Map<String, List<LibraryArtist>>) -> Unit,
    musicRepository: RemoteMusicRepository,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    markServerUnavailable: (Throwable) -> Unit,
    setLibraryError: (String?) -> Unit,
) {
    val artistId = artist.id.takeIf { it.isNotBlank() } ?: return
    if (
        !canUseServerRequests() ||
        artistId in getSimilarArtistLoadsInProgress() ||
        (!force && artistId in getSimilarArtistsByArtist())
    ) {
        return
    }

    setSimilarArtistLoadsInProgress(getSimilarArtistLoadsInProgress() + artistId)
    scope.launch {
        runCatching {
            musicRepository.similarArtists(artistId = artistId, limit = 10, offset = 0)
        }.onSuccess { loadedArtists ->
            setAccessToken(refreshAccessToken())
            val normalizedArtists = loadedArtists
                .distinctBy { it.id }
                .manualSimilarArtistsFirst()
            setSimilarArtistsByArtist(getSimilarArtistsByArtist() + (artistId to normalizedArtists))
        }.onFailure { error ->
            markServerUnavailable(error)
            setLibraryError(error.userMessage())
        }
        setSimilarArtistLoadsInProgress(getSimilarArtistLoadsInProgress() - artistId)
    }
}
