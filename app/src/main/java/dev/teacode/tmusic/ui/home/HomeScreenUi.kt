package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist

@Composable
fun HomeScreen(
    artists: List<LibraryArtist>,
    recentAlbums: List<LibraryAlbum>,
    databaseTrackCount: Int?,
    offlineTrackCount: Int,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    syncMode: SyncMode,
    isLoading: Boolean,
    isLoadingMoreRecentAlbums: Boolean,
    canLoadMoreRecentAlbums: Boolean,
    onRefresh: () -> Unit,
    onLoadMoreRecentAlbums: () -> Unit,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onShowAllArtists: () -> Unit,
    onSelectArtist: (LibraryArtist) -> Unit,
    onSelectAlbum: (LibraryAlbum) -> Unit,
) {
    val status = syncMode.connectionStatusLabel().takeUnless { it == "Online" }
    val offlineNotice = if (syncMode == SyncMode.Offline || syncMode == SyncMode.OfflineOnly) {
        "Offline. Only cached and downloaded data is available."
    } else {
        null
    }
    val showLoadingSkeleton = isLoading && artists.isEmpty() && recentAlbums.isEmpty()
    val homeLazyItemCount = 2 + (if (offlineNotice != null) 1 else 0) + if (recentAlbums.isNotEmpty()) {
        1 + recentAlbums.size + if (isLoadingMoreRecentAlbums) 1 else 0
    } else {
        0
    }
    LaunchedEffect(artists) {
        artists.take(12)
            .map(::artistArtworkKey)
            .distinct()
            .forEach { artworkKey -> onRequestArtwork(artworkKey, ArtworkImageSize.AlbumGrid) }
    }
    LoadMoreEffect(
        listState = listState,
        itemCount = homeLazyItemCount,
        canLoadMore = canLoadMoreRecentAlbums,
        isLoading = isLoading || isLoadingMoreRecentAlbums,
        onLoadMore = onLoadMoreRecentAlbums,
    )

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
            contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "home-header") {
                Box(modifier = Modifier.padding(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, bottom = 14.dp)) {
                    HomeHeader(
                        trackCount = databaseTrackCount,
                        offlineTrackCount = offlineTrackCount,
                        showOfflineTrackCount = syncMode == SyncMode.Offline || syncMode == SyncMode.OfflineOnly,
                        status = status,
                    )
                }
            }
            if (offlineNotice != null) {
                item(key = "home-offline-notice") {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 2.dp)) {
                        OfflineNotice(offlineNotice)
                    }
                }
            }
            item(key = "home-artists") {
                Box(modifier = Modifier.padding(bottom = 14.dp)) {
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
            }
            if (recentAlbums.isNotEmpty()) {
                item(key = "latest-albums-title") {
                    SectionTitle(
                        "Latest albums",
                        modifier = Modifier.padding(
                            start = ScreenHorizontalPadding,
                            end = ScreenHorizontalPadding,
                            top = 2.dp,
                            bottom = 6.dp,
                        ),
                    )
                }
                itemsIndexed(
                    items = recentAlbums,
                    key = { index, album -> "recent-album-${album.id}-$index" },
                ) { _, album ->
                    val coverTrackId = albumArtworkKey(album, emptyList(), emptyMap())
                    AlbumListRow(
                        album = album,
                        artworkBitmap = artworkBitmaps.artworkBitmap(coverTrackId, ArtworkImageSize.AlbumGrid),
                        coverTrackId = coverTrackId,
                        onRequestArtwork = onRequestArtwork,
                        onClick = { onSelectAlbum(album) },
                        recentBadge = album.recentChangeBadgeLabel(),
                    )
                }
                if (isLoadingMoreRecentAlbums && !isLoading) {
                    item(key = "latest-albums-loading") {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun LibraryAlbum.recentChangeBadgeLabel(): String? {
    return when ((recentChangeType ?: recentChange?.type).orEmpty().lowercase()) {
        "new" -> "NEW"
        "updated", "upd" -> "UPD"
        else -> null
    }
}
