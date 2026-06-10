package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.Track

@Composable
fun HomeScreen(
    artists: List<LibraryArtist>,
    recentTracks: List<Track>,
    databaseTrackCount: Int?,
    offlineTrackCount: Int,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    onlineMode: Boolean,
    syncMode: SyncMode,
    isLoading: Boolean,
    playableTrackIds: Set<String>,
    onRefresh: () -> Unit,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onShowAllArtists: () -> Unit,
    onSelectArtist: (LibraryArtist) -> Unit,
    onAddTrackToPlaylist: ((Track) -> Unit)?,
    onAddTrackToQueue: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onGoToTrackAlbum: (Track) -> Unit,
    favoriteTrackIds: Set<String>,
    onToggleTrackFavorite: ((Track) -> Unit)?,
    onSelectTrack: (Track) -> Unit,
) {
    val status = when (syncMode) {
        SyncMode.Offline -> "Offline"
        SyncMode.Syncing -> "Syncing"
        SyncMode.Online -> null
        SyncMode.OfflineOnly -> "Offline only"
    }
    val showLoadingSkeleton = isLoading &&
        (syncMode == SyncMode.Syncing || (artists.isEmpty() && recentTracks.isEmpty()))
    LaunchedEffect(artists) {
        artists.take(12)
            .map(::artistArtworkKey)
            .distinct()
            .forEach { artworkKey -> onRequestArtwork(artworkKey, ArtworkImageSize.AlbumGrid) }
    }

    SwipeRefreshContainer(
        enabled = true,
        isRefreshing = isLoading,
        onRefresh = onRefresh,
    ) {
        if (showLoadingSkeleton) {
            HomeLoadingSkeleton(modifier = Modifier.fillMaxSize())
            return@SwipeRefreshContainer
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                HomeHeader(
                    trackCount = databaseTrackCount,
                    offlineTrackCount = offlineTrackCount,
                    showOfflineTrackCount = syncMode == SyncMode.Offline || syncMode == SyncMode.OfflineOnly,
                    status = status,
                )
            }
            item {
                HorizontalLibrarySection(
                    title = "Artists",
                    isEmpty = artists.isEmpty(),
                    emptyText = "No artists loaded yet",
                    onShowAll = onShowAllArtists,
                ) {
                    items(artists.take(12), key = { it.id }) { artist ->
                        val coverTrackId = artistArtworkKey(artist)
                        ArtistCard(
                            artist = artist,
                            artworkBitmap = artworkBitmaps.artworkBitmap(coverTrackId, ArtworkImageSize.AlbumGrid),
                            coverTrackId = coverTrackId,
                            onRequestArtwork = onRequestArtwork,
                            onClick = { onSelectArtist(artist) },
                            modifier = Modifier.width(112.dp),
                        )
                    }
                }
            }
            if (recentTracks.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionTitle("Latest tracks", modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            recentTracks.forEach { track ->
                                TrackRow(
                                    track = track,
                                    artworkBitmap = artworkBitmaps.artworkBitmap(track.listArtworkKey(), ArtworkImageSize.TrackList),
                                    onRequestArtwork = onRequestArtwork,
                                    onClick = { onSelectTrack(track) },
                                    showDownloadBadge = false,
                                    onAddToPlaylist = onAddTrackToPlaylist?.let { add -> { add(track) } },
                                    onAddToQueue = { onAddTrackToQueue(track) },
                                    onGoToArtist = { onGoToTrackArtist(track) },
                                    onGoToAlbum = track.albumId?.let { { onGoToTrackAlbum(track) } },
                                    isFavorite = track.id in favoriteTrackIds,
                                    onToggleFavorite = onToggleTrackFavorite?.let { toggle -> { toggle(track) } },
                                    enabled = onlineMode || track.id in playableTrackIds,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
