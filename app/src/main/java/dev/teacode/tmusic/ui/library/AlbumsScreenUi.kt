package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Track

@Composable
fun AlbumsScreen(
    albums: List<LibraryAlbum>,
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onSelectAlbum: (LibraryAlbum) -> Unit,
) {
    LoadMoreEffect(
        listState = listState,
        itemCount = albums.size + 1,
        canLoadMore = canLoadMore,
        isLoading = isRefreshing || isLoadingMore,
        onLoadMore = onLoadMore,
    )

    SwipeRefreshContainer(
        enabled = true,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                    BackHeader(
                        title = "Albums",
                        subtitle = "${albums.size} albums",
                        onBack = onBack,
                    )
                }
            }
            if (albums.isEmpty()) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        EmptyState("No albums loaded yet")
                    }
                }
            } else {
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
                if (isLoadingMore) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScreenHorizontalPadding),
                        )
                    }
                }
            }
        }
    }
}
