package dev.teacode.tmusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.data.OfflineLyricsStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Track
import dev.teacode.tmusic.domain.TrackLyrics

internal suspend fun cacheDownloadedAssetsAction(
    track: Track,
    getTracks: () -> List<Track>,
    setTracks: (List<Track>) -> Unit,
    getArtworkBitmaps: () -> Map<String, ImageBitmap>,
    setArtworkBitmaps: (Map<String, ImageBitmap>) -> Unit,
    cacheArtwork: suspend (String, ArtworkImageSize) -> ImageBitmap?,
    resolveCachedArtist: (String) -> LibraryArtist?,
    canUseMediaServerRequests: () -> Boolean,
    musicRepository: RemoteMusicRepository,
    offlineLyricsStore: OfflineLyricsStore,
    refreshAccessToken: () -> String?,
    setAccessToken: (String?) -> Unit,
    getLyricsByTrackId: () -> Map<String, TrackLyrics>,
    setLyricsByTrackId: (Map<String, TrackLyrics>) -> Unit,
) {
    val knownTrack = (getTracks().firstOrNull { it.id == track.id } ?: track)
        .copy(downloadState = DownloadState.Downloaded)
    setTracks(
        if (getTracks().any { it.id == knownTrack.id }) {
            getTracks().map { existingTrack -> if (existingTrack.id == knownTrack.id) knownTrack else existingTrack }
        } else {
            getTracks() + knownTrack
        },
    )

    suspend fun cacheArtworkSizes(artworkKey: String) {
        ArtworkImageSize.entries.forEach { imageSize ->
            runCatching {
                cacheArtwork(artworkKey, imageSize)
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    setArtworkBitmaps(getArtworkBitmaps() + (artworkBitmapKey(artworkKey, imageSize) to bitmap))
                }
            }
        }
    }

    val primaryArtworkKey = knownTrack.listArtworkKey()
    cacheArtworkSizes(primaryArtworkKey)
    knownTrack.albumId
        ?.let(::albumArtworkKey)
        ?.takeIf { it != primaryArtworkKey }
        ?.let { cacheArtworkSizes(it) }
    (knownTrack.artistReferences() + knownTrack.artistLogicNames().mapNotNull(resolveCachedArtist))
        .distinctBy { it.id }
        .map(::artistArtworkKey)
        .distinct()
        .forEach { artistKey ->
            runCatching {
                cacheArtwork(artistKey, ArtworkImageSize.AlbumGrid)
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    setArtworkBitmaps(
                        getArtworkBitmaps() + (artworkBitmapKey(artistKey, ArtworkImageSize.AlbumGrid) to bitmap),
                    )
                }
            }
        }
    if (canUseMediaServerRequests()) {
        runCatching {
            musicRepository.lyrics(knownTrack.id)
        }.onSuccess { lyrics ->
            setAccessToken(refreshAccessToken())
            if (lyrics != null) {
                offlineLyricsStore.save(knownTrack.id, lyrics)
                setLyricsByTrackId(getLyricsByTrackId() + (knownTrack.id to lyrics))
            }
        }
    }
}
