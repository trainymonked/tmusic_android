package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Track

@Composable
fun ArtistScreen(
    artist: LibraryArtist?,
    albums: List<LibraryAlbum>,
    appearsOn: List<LibraryAlbum>,
    looseTracks: List<Track>,
    similarArtists: List<LibraryArtist>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onSelectAlbum: (LibraryAlbum) -> Unit,
    onSelectArtist: (LibraryArtist) -> Unit,
    offlineNotice: String?,
    onAddTrackToPlaylist: ((Track) -> Unit)?,
    onAddTrackToQueue: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onGoToTrackAlbum: (Track) -> Unit,
    favoriteTrackIds: Set<String>,
    onToggleTrackFavorite: ((Track) -> Unit)?,
    onSelectTrack: (Track) -> Unit,
) {
    val totalItems = albums.size + appearsOn.size + looseTracks.size
    SwipeRefreshContainer(
        enabled = artist != null,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                HeaderBlock(
                    title = artist?.name ?: "Artist",
                    subtitle = "",
                )
            }
            if (!offlineNotice.isNullOrBlank()) {
                item { OfflineNotice(offlineNotice) }
            }
            if (artist == null) {
                item { EmptyState("Artist was not found") }
            } else if (isLoading && totalItems == 0) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            } else if (totalItems == 0) {
                item { EmptyState("No music found for this artist") }
            } else {
                if (albums.isNotEmpty()) {
                    item { SectionTitle("Albums", modifier = Modifier.padding(top = 4.dp)) }
                }
                items(albums, key = { it.id }) { album ->
                    val coverTrackId = albumArtworkKey(album, tracks, albumTracksById)
                    AlbumListRow(
                        album = album,
                        artworkBitmap = artworkBitmaps.artworkBitmap(coverTrackId, ArtworkImageSize.AlbumGrid),
                        coverTrackId = coverTrackId,
                        onRequestArtwork = onRequestArtwork,
                        onClick = { onSelectAlbum(album) },
                    )
                }
                if (appearsOn.isNotEmpty()) {
                    item {
                        SectionTitle("Appears on", modifier = Modifier.padding(top = 12.dp))
                    }
                    items(appearsOn, key = { it.id }) { album ->
                        val coverTrackId = albumArtworkKey(album, tracks, albumTracksById)
                        AlbumListRow(
                            album = album,
                            artworkBitmap = artworkBitmaps.artworkBitmap(coverTrackId, ArtworkImageSize.AlbumGrid),
                            coverTrackId = coverTrackId,
                            onRequestArtwork = onRequestArtwork,
                            onClick = { onSelectAlbum(album) },
                        )
                    }
                }
                if (looseTracks.isNotEmpty()) {
                    item {
                        SectionTitle("Singles", modifier = Modifier.padding(top = 12.dp))
                    }
                    items(looseTracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            artworkBitmap = artworkBitmaps.artworkBitmap(track.listArtworkKey(), ArtworkImageSize.TrackList),
                            onRequestArtwork = onRequestArtwork,
                            onClick = { onSelectTrack(track) },
                            onAddToPlaylist = onAddTrackToPlaylist?.let { add -> { add(track) } },
                            onAddToQueue = { onAddTrackToQueue(track) },
                            onGoToArtist = { onGoToTrackArtist(track) },
                            onGoToAlbum = track.albumId?.let { { onGoToTrackAlbum(track) } },
                            isFavorite = track.id in favoriteTrackIds,
                            onToggleFavorite = onToggleTrackFavorite?.let { toggle -> { toggle(track) } },
                        )
                    }
                }
                if (similarArtists.isNotEmpty()) {
                    item {
                        SectionTitle("Similar artists", modifier = Modifier.padding(top = 12.dp))
                    }
                    item {
                        LazyRow(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(similarArtists, key = { it.id }) { similarArtist ->
                                val coverTrackId = artistArtworkKey(similarArtist)
                                ArtistCard(
                                    artist = similarArtist,
                                    artworkBitmap = artworkBitmaps.artworkBitmap(
                                        coverTrackId,
                                        ArtworkImageSize.AlbumGrid,
                                    ),
                                    coverTrackId = coverTrackId,
                                    onRequestArtwork = onRequestArtwork,
                                    onClick = { onSelectArtist(similarArtist) },
                                    modifier = Modifier.width(112.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
