package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.Track

@Composable
internal fun SearchTrackResultsSection(
    title: String,
    tracks: List<Track>,
    query: String,
    artworkBitmaps: Map<String, ImageBitmap>,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onSelectTrack: (Track, String) -> Unit,
    onAddTrackToPlaylistClick: (Track) -> Unit,
    onAddTrackToQueue: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onGoToTrackAlbum: (Track) -> Unit,
    favoriteTrackIds: Set<String>,
    onToggleTrackFavorite: ((Track) -> Unit)?,
) {
    SearchResultWindow(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tracks.forEach { track ->
                TrackRow(
                    track = track,
                    artworkBitmap = artworkBitmaps.artworkBitmap(track.listArtworkKey(), ArtworkImageSize.TrackList),
                    onRequestArtwork = onRequestArtwork,
                    onClick = { onSelectTrack(track, query) },
                    showDownloadBadge = false,
                    titleBadge = if (track.foundInLyrics) "Lyrics" else null,
                    onAddToPlaylist = { onAddTrackToPlaylistClick(track) },
                    onAddToQueue = { onAddTrackToQueue(track) },
                    onGoToArtist = { onGoToTrackArtist(track) },
                    onGoToAlbum = track.albumId?.let { { onGoToTrackAlbum(track) } },
                    isFavorite = track.id in favoriteTrackIds,
                    onToggleFavorite = onToggleTrackFavorite?.let { toggle -> { toggle(track) } },
                )
            }
        }
    }
}
