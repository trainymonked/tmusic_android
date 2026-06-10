package dev.teacode.tmusic.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

private data class PlaylistTrackListItem(
    val originalIndex: Int,
    val playlistTrackId: String?,
    val track: Track,
)

@Composable
internal fun PlaylistContent(
    playlist: Playlist,
    tracks: List<Track>,
    canDownload: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectTrack: (Int) -> Unit,
    onDownloadPlaylist: (Playlist) -> Unit,
    onAddTrackToPlaylist: ((Track) -> Unit)?,
    onAddTrackToQueue: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onGoToTrackAlbum: (Track) -> Unit,
    favoriteTrackIds: Set<String>,
    onToggleTrackFavorite: ((Track) -> Unit)?,
    onRemoveTrack: (String) -> Unit,
    onReorderTracks: (List<String>) -> Unit,
    onEditPlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onPlayPlaylist: () -> Unit,
    onShufflePlayPlaylist: () -> Unit,
    isActivePlaylist: Boolean,
    currentTrackId: String?,
    isPlaybackPlaying: Boolean,
    canPlayFromNetwork: Boolean,
    offlinePlayableTrackIds: Set<String>,
    downloadedTrackIds: Set<String>,
    onTogglePlayback: () -> Unit,
    artworkBitmaps: Map<String, ImageBitmap>,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    offlineNotice: String?,
) {
    val isFavorites = playlist.isFavoritesPlaylist()
    val initialItems = remember(playlist.id, playlist.trackIds, playlist.playlistTrackIds, tracks) {
        tracks.mapIndexed { index, track ->
            PlaylistTrackListItem(
                originalIndex = index,
                playlistTrackId = playlist.playlistTrackIds.getOrNull(index)
                    ?: playlist.playlistTrackIdsByTrackId[track.id],
                track = track,
            )
        }
    }
    var displayedItems by remember { mutableStateOf(initialItems) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val reorderStepPx = with(LocalDensity.current) { 56.dp.toPx() }
    val playableTrackIds = if (canPlayFromNetwork) {
        tracks.map { it.id }.toSet()
    } else {
        offlinePlayableTrackIds + tracks.filter { it.downloadState == DownloadState.Downloaded }.map { it.id }
    }
    val expectedTrackCount = playlist.trackCount.coerceAtLeast(tracks.size)
    val downloadedTrackCount = playlist.trackIds.count { it in downloadedTrackIds }
    val playlistDownloadState = when {
        !playlist.isOfflineEnabled -> DownloadState.NotDownloaded
        tracks.any { it.downloadState == DownloadState.Queued } -> DownloadState.Queued
        expectedTrackCount > 0 && downloadedTrackCount >= expectedTrackCount -> DownloadState.Downloaded
        else -> DownloadState.Queued
    }
    val playlistSubtitle = buildString {
        val totalDurationSeconds = playlist.totalDurationSeconds
            ?: loadedTracksDurationSeconds(tracks, expectedTrackCount)
        append(collectionStatsLabel(expectedTrackCount, totalDurationSeconds))
    }
    val downloadProgressPercent = if (playlist.isOfflineEnabled && expectedTrackCount > 0) {
        ((downloadedTrackCount * 100) / expectedTrackCount).coerceIn(0, 100)
    } else {
        null
    }
    val listState = rememberLazyListState()

    LoadMoreEffect(
        listState = listState,
        itemCount = displayedItems.size + 1,
        canLoadMore = canLoadMore,
        isLoading = isRefreshing || isLoadingMore,
        onLoadMore = onLoadMore,
    )
    LaunchedEffect(initialItems) {
        displayedItems = initialItems
    }

    fun finishReorder() {
        val playlistTrackIds = displayedItems.mapNotNull { it.playlistTrackId }
        val initialPlaylistTrackIds = initialItems.mapNotNull { it.playlistTrackId }
        if (playlistTrackIds.size == displayedItems.size && playlistTrackIds != initialPlaylistTrackIds) {
            onReorderTracks(playlistTrackIds)
        }
        draggedKey = null
        draggedIndex = null
        dragOffset = 0f
    }

    SwipeRefreshContainer(
        enabled = true,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            state = listState,
            userScrollEnabled = draggedIndex == null,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                PlaylistHeader(
                    playlist = playlist,
                    subtitle = playlistSubtitle,
                    artworkBitmap = artworkBitmaps.artworkBitmap(playlistArtworkKey(playlist), ArtworkImageSize.AlbumGrid),
                    coverKey = playlistArtworkKey(playlist),
                    downloadState = playlistDownloadState,
                    downloadProgressPercent = downloadProgressPercent,
                    isFavorites = isFavorites,
                    canDownload = canDownload,
                    hasPlayableTracks = displayedItems.any { it.track.id in playableTrackIds },
                    isActivePlaylist = isActivePlaylist,
                    isPlaybackPlaying = isPlaybackPlaying,
                    onRequestArtwork = onRequestArtwork,
                    onDeleteClick = onDeletePlaylist,
                    onEditClick = onEditPlaylist,
                    onPlayPlaylist = onPlayPlaylist,
                    onShufflePlayPlaylist = onShufflePlayPlaylist,
                    onTogglePlayback = onTogglePlayback,
                    onDownloadPlaylist = { onDownloadPlaylist(playlist) },
                )
                if (!offlineNotice.isNullOrBlank()) {
                    OfflineNotice(offlineNotice)
                }
            }
            if (displayedItems.isEmpty()) {
                item { EmptyState("This playlist has no loaded tracks") }
            } else {
                itemsIndexed(
                    displayedItems,
                    key = { index, item -> item.playlistTrackId ?: "${item.track.id}:$index" },
                ) { index, item ->
                    val track = item.track
                    val itemKey = item.playlistTrackId ?: "${track.id}:$index"
                    TrackRow(
                        track = track,
                        artworkBitmap = artworkBitmaps.artworkBitmap(track.listArtworkKey(), ArtworkImageSize.TrackList),
                        onRequestArtwork = onRequestArtwork,
                        onClick = { onSelectTrack(item.originalIndex) },
                        isActive = isActivePlaylist && track.id == currentTrackId,
                        showActivePlaybackButton = isActivePlaylist && track.id == currentTrackId,
                        isPlaybackPlaying = isPlaybackPlaying,
                        onTogglePlayback = onTogglePlayback,
                        onAddToPlaylist = onAddTrackToPlaylist?.let { add -> { add(track) } },
                        onAddToQueue = { onAddTrackToQueue(track) },
                        onGoToArtist = { onGoToTrackArtist(track) },
                        onGoToAlbum = track.albumId?.let { { onGoToTrackAlbum(track) } },
                        isFavorite = track.id in favoriteTrackIds,
                        onToggleFavorite = onToggleTrackFavorite?.let { toggle -> { toggle(track) } },
                        onRemoveFromPlaylist = if (!isFavorites && canDownload && item.playlistTrackId != null) {
                            { onRemoveTrack(item.playlistTrackId) }
                        } else {
                            null
                        },
                        downloadBadgePlacement = DownloadBadgePlacement.BeforeTitle,
                        enabled = track.id in playableTrackIds,
                        modifier = Modifier
                            .zIndex(if (draggedKey == itemKey) 1f else 0f)
                            .graphicsLayer { translationY = if (draggedKey == itemKey) dragOffset else 0f }
                            .then(
                                if (canDownload) {
                                    Modifier.pointerInput(itemKey) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggedKey = itemKey
                                                draggedIndex = index
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggedKey = null
                                                draggedIndex = null
                                                dragOffset = 0f
                                            },
                                            onDragEnd = ::finishReorder,
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount.y
                                                var currentIndex = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                                while (dragOffset >= reorderStepPx && currentIndex < displayedItems.lastIndex) {
                                                    displayedItems = displayedItems.moveItem(currentIndex, currentIndex + 1)
                                                    currentIndex += 1
                                                    draggedIndex = currentIndex
                                                    dragOffset -= reorderStepPx
                                                }
                                                while (dragOffset <= -reorderStepPx && currentIndex > 0) {
                                                    displayedItems = displayedItems.moveItem(currentIndex, currentIndex - 1)
                                                    currentIndex -= 1
                                                    draggedIndex = currentIndex
                                                    dragOffset += reorderStepPx
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
                if (isLoadingMore) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

private fun <T> List<T>.moveItem(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) {
        return this
    }
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}
