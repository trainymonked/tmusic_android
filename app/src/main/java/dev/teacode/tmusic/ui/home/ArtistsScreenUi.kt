package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.ArtistSortOption
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
    sortOption: ArtistSortOption,
    onSortOptionChange: (ArtistSortOption) -> Unit,
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
            contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                    HeaderBlock(title = "Artists", subtitle = "")
                }
            }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArtistSortOption.entries.forEach { option ->
                        FilterChip(
                            selected = option == sortOption,
                            onClick = { onSortOptionChange(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
            if (!offlineNotice.isNullOrBlank()) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        OfflineNotice(offlineNotice)
                    }
                }
            }
            if (artists.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScreenHorizontalPadding),
                    ) {
                        if (isLoadingMore && !isRefreshing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else if (!isRefreshing) {
                            EmptyState("No artists loaded yet")
                        }
                    }
                }
            } else {
                items(artists.chunked(3), key = { rowArtists -> rowArtists.joinToString("|") { it.id } }) { rowArtists ->
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        ArtistGridRow(
                            artists = rowArtists,
                            artworkBitmaps = artworkBitmaps,
                            onRequestArtwork = onRequestArtwork,
                            onSelectArtist = onSelectArtist,
                        )
                    }
                }
                if (isLoadingMore && !isRefreshing) {
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

private val ArtistSortOption.label: String
    get() = when (this) {
        ArtistSortOption.Name -> "Name"
        ArtistSortOption.TrackCount -> "Tracks"
        ArtistSortOption.LatestReleases -> "Latest"
    }
