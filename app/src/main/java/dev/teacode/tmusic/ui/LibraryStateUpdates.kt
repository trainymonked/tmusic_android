package dev.teacode.tmusic.ui

import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.PlayerState
import dev.teacode.tmusic.domain.Track

internal data class TrackDownloadStateUpdate(
    val tracks: List<Track>,
    val albumTracksById: Map<String, List<Track>>,
    val playbackQueue: PlaybackQueue,
    val playerState: PlayerState,
)

internal fun trackDownloadStateUpdate(
    trackId: String,
    downloadState: DownloadState,
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
    playbackQueue: PlaybackQueue,
    playerState: PlayerState,
): TrackDownloadStateUpdate {
    fun List<Track>.updatedDownloadState(): List<Track> {
        return map { track ->
            if (track.id == trackId) {
                track.copy(downloadState = downloadState)
            } else {
                track
            }
        }
    }

    return TrackDownloadStateUpdate(
        tracks = tracks.updatedDownloadState(),
        albumTracksById = albumTracksById.mapValues { (_, albumTracks) ->
            albumTracks.updatedDownloadState()
        },
        playbackQueue = playbackQueue.copy(
            tracks = playbackQueue.tracks.updatedDownloadState(),
            sourceTracks = playbackQueue.sourceTracks.updatedDownloadState(),
        ),
        playerState = playerState.currentTrack
            ?.takeIf { it.id == trackId }
            ?.let { currentTrack ->
                playerState.copy(currentTrack = currentTrack.copy(downloadState = downloadState))
            }
            ?: playerState,
    )
}

internal data class AlbumOfflineFlagUpdate(
    val offlineAlbumIds: Set<String>,
    val albums: List<LibraryAlbum>,
    val savedAlbums: List<LibraryAlbum>,
    val albumsByArtist: Map<String, List<LibraryAlbum>>,
    val appearsOnByArtist: Map<String, List<LibraryAlbum>>,
)

internal fun albumOfflineFlagUpdate(
    albumId: String,
    enabled: Boolean,
    offlineAlbumIds: Set<String>,
    albums: List<LibraryAlbum>,
    savedAlbums: List<LibraryAlbum>,
    albumsByArtist: Map<String, List<LibraryAlbum>>,
    appearsOnByArtist: Map<String, List<LibraryAlbum>>,
): AlbumOfflineFlagUpdate {
    fun LibraryAlbum.updated(): LibraryAlbum {
        return if (id == albumId) copy(isOfflineEnabled = enabled) else this
    }

    return AlbumOfflineFlagUpdate(
        offlineAlbumIds = if (enabled) offlineAlbumIds + albumId else offlineAlbumIds - albumId,
        albums = albums.map { it.updated() },
        savedAlbums = savedAlbums.map { it.updated() },
        albumsByArtist = albumsByArtist.mapValues { (_, artistAlbums) ->
            artistAlbums.map { it.updated() }
        },
        appearsOnByArtist = appearsOnByArtist.mapValues { (_, artistAlbums) ->
            artistAlbums.map { it.updated() }
        },
    )
}

internal data class KnownTrackLikedStateUpdate(
    val tracks: List<Track>,
    val albumTracksById: Map<String, List<Track>>,
    val looseTracksByArtist: Map<String, List<Track>>,
    val searchResults: LibrarySearchResults,
    val playbackQueue: PlaybackQueue,
    val playerState: PlayerState,
)

internal fun knownTrackLikedStateUpdate(
    trackId: String,
    isLiked: Boolean,
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
    looseTracksByArtist: Map<String, List<Track>>,
    searchResults: LibrarySearchResults,
    playbackQueue: PlaybackQueue,
    playerState: PlayerState,
): KnownTrackLikedStateUpdate {
    fun List<Track>.updatedLikedState(): List<Track> {
        return map { track ->
            if (track.id == trackId) track.copy(isLiked = isLiked) else track
        }
    }

    return KnownTrackLikedStateUpdate(
        tracks = tracks.updatedLikedState(),
        albumTracksById = albumTracksById.mapValues { (_, albumTracks) ->
            albumTracks.updatedLikedState()
        },
        looseTracksByArtist = looseTracksByArtist.mapValues { (_, artistTracks) ->
            artistTracks.updatedLikedState()
        },
        searchResults = searchResults.copy(tracks = searchResults.tracks.updatedLikedState()),
        playbackQueue = playbackQueue.copy(
            tracks = playbackQueue.tracks.updatedLikedState(),
            sourceTracks = playbackQueue.sourceTracks.updatedLikedState(),
        ),
        playerState = playerState.currentTrack
            ?.takeIf { it.id == trackId }
            ?.let { currentTrack -> playerState.copy(currentTrack = currentTrack.copy(isLiked = isLiked)) }
            ?: playerState,
    )
}
