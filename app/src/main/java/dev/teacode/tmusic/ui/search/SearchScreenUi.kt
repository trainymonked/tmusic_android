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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun SearchScreen(
    query: String,
    focusRequestSerial: Int,
    results: LibrarySearchResults,
    playlists: List<Playlist>,
    offlineTracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
    recentItems: List<RecentLibraryItem>,
    isSearching: Boolean,
    canLoadMoreTracks: Boolean,
    onlineMode: Boolean,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onQueryChange: (String) -> Unit,
    onLoadMoreTracks: () -> Unit,
    onClearRecentItems: () -> Unit,
    onRecentItemClick: (RecentLibraryItem) -> Unit,
    onSelectArtist: (LibraryArtist) -> Unit,
    onSelectAlbum: (LibraryAlbum) -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onAddTrackToPlaylistClick: (Track) -> Unit,
    onAddTrackToQueue: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onGoToTrackAlbum: (Track) -> Unit,
    favoriteTrackIds: Set<String>,
    onToggleTrackFavorite: ((Track) -> Unit)?,
    onSelectTrack: (Track, String) -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    val normalizedQuery = query.trim()
    val matchingTracks = results.tracks
        .distinctBy { it.id }
    val matchingArtists = results.artists
    val matchingAlbums = results.albums
    val matchingPlaylists = if (onlineMode) {
        results.playlists
    } else {
        playlists.filter { playlist ->
        playlist.title.contains(normalizedQuery, ignoreCase = true)
        }
    }.distinctBy { it.id }
    val artworkTracks = (matchingTracks + offlineTracks).distinctBy { it.id }
    val latestCanLoadMoreTracks by rememberUpdatedState(canLoadMoreTracks)
    val latestIsSearching by rememberUpdatedState(isSearching)
    val latestTrackCount by rememberUpdatedState(matchingTracks.size)
    val bottomLoadThresholdPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    var lastRequestedTrackCount by remember(normalizedQuery) { mutableIntStateOf(-1) }

    LaunchedEffect(focusRequestSerial) {
        if (focusRequestSerial > 0) {
            searchFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(listState, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val layout = listState.layoutInfo
            SearchTrackPaginationState(
                lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: 0,
                lastVisibleBottom = layout.visibleItemsInfo.lastOrNull()?.let { item ->
                    item.offset + item.size
                } ?: 0,
                totalItemsCount = layout.totalItemsCount,
                viewportEndOffset = layout.viewportEndOffset,
                isScrollInProgress = listState.isScrollInProgress,
            )
        }
            .distinctUntilChanged()
            .collect { paginationState ->
                val shouldLoadMore = paginationState.isScrollInProgress &&
                    paginationState.totalItemsCount > 0 &&
                    paginationState.lastVisibleIndex == paginationState.totalItemsCount - 1 &&
                    paginationState.lastVisibleBottom <=
                    paginationState.viewportEndOffset + bottomLoadThresholdPx
                if (
                    shouldLoadMore &&
                    latestCanLoadMoreTracks &&
                    !latestIsSearching &&
                    lastRequestedTrackCount != latestTrackCount
                ) {
                    lastRequestedTrackCount = latestTrackCount
                    onLoadMoreTracks()
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    trailingIcon = if (query.isNotBlank()) {
                        {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = { Text("Search music") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
        if (!onlineMode) {
            item {
                Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                    OfflineNotice("Offline. Search is limited to cached data.")
                }
            }
        }
        if (normalizedQuery.isBlank()) {
            item {
                Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                    RecentSearchesCard(
                        recentItems = recentItems,
                        onClear = onClearRecentItems,
                        onItemClick = onRecentItemClick,
                    )
                }
            }
        } else {
            if (isSearching && matchingTracks.isEmpty()) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScreenHorizontalPadding),
                    )
                }
            }
            if (
                !isSearching &&
                matchingAlbums.isEmpty() &&
                matchingArtists.isEmpty() &&
                matchingPlaylists.isEmpty() &&
                matchingTracks.isEmpty()
            ) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        EmptyState("No results")
                    }
                }
            }
            if (matchingAlbums.isNotEmpty()) {
                item {
                    SearchResultWindow(title = "Albums") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(matchingAlbums, key = { it.id }) { album ->
                                val coverTrackId = albumArtworkKey(album, artworkTracks, albumTracksById)
                                AlbumCard(
                                    album = album,
                                    artworkBitmap = artworkBitmaps.artworkBitmap(coverTrackId, ArtworkImageSize.AlbumGrid),
                                    coverTrackId = coverTrackId,
                                    onRequestArtwork = onRequestArtwork,
                                    onClick = { onSelectAlbum(album) },
                                    compact = true,
                                )
                            }
                        }
                    }
                }
            }
            if (matchingArtists.isNotEmpty()) {
                item {
                    SearchResultWindow(title = "Artists") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(matchingArtists, key = { it.id }) { artist ->
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
            }
            if (matchingPlaylists.isNotEmpty()) {
                item {
                    SearchResultWindow(title = "Playlists") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(matchingPlaylists, key = { it.id }) { playlist ->
                                val coverKey = playlistArtworkKey(playlist)
                                PlaylistCard(
                                    playlist = playlist,
                                    trackCount = playlist.trackCount,
                                    accentColor = stableUiColor(playlist.id),
                                    artworkBitmap = artworkBitmaps.artworkBitmap(coverKey, ArtworkImageSize.AlbumGrid),
                                    coverTrackId = coverKey,
                                    onRequestArtwork = onRequestArtwork,
                                    onClick = { onSelectPlaylist(playlist) },
                                )
                            }
                        }
                    }
                }
            }
            if (matchingTracks.isNotEmpty()) {
                item(key = "tracks-title") {
                    SectionTitle(
                        "Tracks",
                        modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
                    )
                }
                items(matchingTracks, key = { track -> "track-${track.id}" }) { track ->
                    TrackRow(
                        track = track,
                        artworkBitmap = artworkBitmaps.artworkBitmap(track.listArtworkKey(), ArtworkImageSize.TrackList),
                        onRequestArtwork = onRequestArtwork,
                        onClick = { onSelectTrack(track, normalizedQuery) },
                        showDownloadBadge = false,
                        titleBadge = if (track.foundInLyrics) "Lyrics" else null,
                        onAddToPlaylist = { onAddTrackToPlaylistClick(track) },
                        onAddToQueue = { onAddTrackToQueue(track) },
                        onGoToArtist = { onGoToTrackArtist(track) },
                        onGoToAlbum = track.albumId?.let { { onGoToTrackAlbum(track) } },
                        isFavorite = track.id in favoriteTrackIds,
                        onToggleFavorite = onToggleTrackFavorite?.let { toggle -> { toggle(track) } },
                        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 2.dp),
                    )
                }
                if (isSearching) {
                    item(key = "tracks-loading-more") {
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

private data class SearchTrackPaginationState(
    val lastVisibleIndex: Int,
    val lastVisibleBottom: Int,
    val totalItemsCount: Int,
    val viewportEndOffset: Int,
    val isScrollInProgress: Boolean,
)
