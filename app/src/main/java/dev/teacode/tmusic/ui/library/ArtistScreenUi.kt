package dev.teacode.tmusic.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    isSimilarArtistsLoading: Boolean,
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
    val artistCoverKey = artist?.let(::artistArtworkKey)
    LaunchedEffect(artistCoverKey) {
        artistCoverKey?.let { artworkKey -> onRequestArtwork(artworkKey, ArtworkImageSize.AlbumGrid) }
    }
    SwipeRefreshContainer(
        enabled = artist != null,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                if (artist == null) {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        HeaderBlock(
                            title = "Artist",
                            subtitle = "",
                        )
                    }
                } else {
                    ArtistHeader(
                        artist = artist,
                        artworkBitmap = artworkBitmaps.artworkBitmap(
                            artistCoverKey,
                            ArtworkImageSize.AlbumGrid,
                        ),
                        modifier = Modifier.padding(
                            start = ScreenHorizontalPadding,
                            end = ScreenHorizontalPadding,
                            bottom = 8.dp,
                        ),
                    )
                }
            }
            if (!offlineNotice.isNullOrBlank()) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        OfflineNotice(offlineNotice)
                    }
                }
            }
            if (artist == null) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        EmptyState("Artist was not found")
                    }
                }
            } else if (isLoading && totalItems == 0 && !isRefreshing) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScreenHorizontalPadding),
                    )
                }
            } else if (totalItems == 0 && !isRefreshing) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        EmptyState("No music found for this artist")
                    }
                }
            } else {
                if (albums.isNotEmpty()) {
                    item {
                        SectionTitle(
                            "Albums",
                            modifier = Modifier.padding(
                                start = ScreenHorizontalPadding,
                                end = ScreenHorizontalPadding,
                                top = 4.dp,
                            ),
                        )
                    }
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
                        SectionTitle(
                            "Appears on",
                            modifier = Modifier.padding(
                                start = ScreenHorizontalPadding,
                                end = ScreenHorizontalPadding,
                                top = 12.dp,
                            ),
                        )
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
                        SectionTitle(
                            "Singles",
                            modifier = Modifier.padding(
                                start = ScreenHorizontalPadding,
                                end = ScreenHorizontalPadding,
                                top = 12.dp,
                            ),
                        )
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
                if (isSimilarArtistsLoading || similarArtists.isNotEmpty()) {
                    item {
                        SectionTitle(
                            "Similar artists",
                            modifier = Modifier.padding(
                                start = ScreenHorizontalPadding,
                                end = ScreenHorizontalPadding,
                                top = 12.dp,
                            ),
                        )
                    }
                    item {
                        if (isSimilarArtistsLoading && similarArtists.isEmpty()) {
                            SimilarArtistsSkeleton()
                        } else {
                            LazyRow(
                                modifier = Modifier.padding(top = 4.dp),
                                contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
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
}

@Composable
private fun ArtistHeader(
    artist: LibraryArtist,
    artworkBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(stableUiColor(artist.name))),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkBitmap != null) {
                Image(
                    bitmap = artworkBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = artist.name.firstOrNull()?.uppercaseChar()?.toString() ?: "A",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SimilarArtistsSkeleton() {
    val transition = rememberInfiniteTransition(label = "similar-artists-skeleton")
    val offset by transition.animateFloat(
        initialValue = -360f,
        targetValue = 720f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "similar-artists-skeleton-offset",
    )
    val base = MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    LazyRow(
        modifier = Modifier.padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(5) {
            Column(
                modifier = Modifier.width(112.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    base,
                                    highlight.copy(alpha = 0.82f),
                                    base,
                                ),
                                start = Offset(offset, 0f),
                                end = Offset(offset + 220f, 180f),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(height = 14.dp, width = 112.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    base,
                                    highlight.copy(alpha = 0.72f),
                                    base,
                                ),
                                start = Offset(offset, 0f),
                                end = Offset(offset + 220f, 60f),
                            ),
                        ),
                )
            }
        }
    }
}
