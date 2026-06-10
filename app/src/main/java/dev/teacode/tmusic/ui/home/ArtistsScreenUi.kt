package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.LibraryArtist

@Composable
fun ArtistsScreen(
    artists: List<LibraryArtist>,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    offlineNotice: String?,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onSelectArtist: (LibraryArtist) -> Unit,
) {
    val rowCount = artists.chunked(3).size

    LoadMoreEffect(
        listState = listState,
        itemCount = rowCount + 1,
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
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeaderBlock(title = "Artists", subtitle = "")
            }
            if (!offlineNotice.isNullOrBlank()) {
                item { OfflineNotice(offlineNotice) }
            }
            if (artists.isEmpty()) {
                item { EmptyState("No artists loaded yet") }
            } else {
                items(artists.chunked(3), key = { rowArtists -> rowArtists.joinToString("|") { it.id } }) { rowArtists ->
                    ArtistGridRow(
                        artists = rowArtists,
                        artworkBitmaps = artworkBitmaps,
                        onRequestArtwork = onRequestArtwork,
                        onSelectArtist = onSelectArtist,
                    )
                }
                if (isLoadingMore) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}
