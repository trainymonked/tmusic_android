package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Track

@Composable
fun AlbumScreen(
    album: LibraryAlbum?,
    tracks: List<Track>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    isActiveAlbum: Boolean,
    currentTrackId: String?,
    isPlaybackPlaying: Boolean,
    canPlayFromNetwork: Boolean,
    canDownload: Boolean,
    isDownloadActive: Boolean,
    offlinePlayableTrackIds: Set<String>,
    onRefresh: () -> Unit,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    offlineNotice: String?,
    onTogglePlayback: () -> Unit,
    onPlayAlbum: () -> Unit,
    onAddAlbumToLibrary: () -> Unit,
    onDownloadAlbum: () -> Unit,
    onDeleteAlbumDownload: () -> Unit,
    onGoToAlbumArtist: (LibraryAlbum) -> Unit,
    onSelectTrack: (Track) -> Unit,
    onAddTrackToPlaylist: ((Track) -> Unit)?,
    onAddTrackToQueue: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onGoToTrackAlbum: (Track) -> Unit,
    favoriteTrackIds: Set<String>,
    onToggleTrackFavorite: ((Track) -> Unit)?,
) {
    val coverTrackId = album?.let { albumArtworkKey(it, tracks, mapOf(it.id to tracks)) }
    val expectedTrackCount = album?.trackCount?.coerceAtLeast(tracks.size) ?: tracks.size
    val totalDurationSeconds = album?.totalDurationSeconds
        ?: loadedTracksDurationSeconds(tracks, expectedTrackCount)
    val albumDownloadState = aggregateDownloadState(
        isOfflineEnabled = album?.isOfflineEnabled == true,
        expectedTrackCount = expectedTrackCount,
        loadedTrackCount = tracks.size,
        tracks = tracks,
    )
    val albumDownloadProgressPercent = remember(album?.isOfflineEnabled, expectedTrackCount, tracks) {
        if (album?.isOfflineEnabled == true && expectedTrackCount > 0) {
            val downloadedTrackCount = tracks.count { it.downloadState == DownloadState.Downloaded }
            ((downloadedTrackCount * 100) / expectedTrackCount).coerceIn(0, 100)
        } else {
            null
        }
    }
    LaunchedEffect(coverTrackId) {
        coverTrackId?.let { onRequestArtwork(it, ArtworkImageSize.FullPlayer) }
    }
    val displayTracks = tracks.sortedWith(
        compareBy<Track> { it.discNumber ?: Int.MAX_VALUE }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id },
    )
    val playableTrackIds = remember(canPlayFromNetwork, tracks, offlinePlayableTrackIds) {
        if (canPlayFromNetwork) {
            tracks.map { it.id }.toSet()
        } else {
            offlinePlayableTrackIds + tracks
                .filter { it.downloadState == DownloadState.Downloaded }
                .map { it.id }
        }
    }
    LoadMoreEffect(
        listState = listState,
        itemCount = displayTracks.size + 1,
        canLoadMore = canLoadMore,
        isLoading = isRefreshing || isLoadingMore,
        onLoadMore = onLoadMore,
    )
    SwipeRefreshContainer(
        enabled = album != null,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtworkBox(
                        bitmap = artworkBitmaps.artworkBitmap(coverTrackId, ArtworkImageSize.FullPlayer),
                        accentColor = album?.accentColor ?: 0xFF444444,
                        keepPreviousWhileLoading = true,
                        placeholderIcon = Icons.Filled.Album,
                        placeholderIconSize = 72.dp,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Column(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                    AlbumHeader(
                        album = album,
                        subtitle = album?.let { collectionStatsLabel(expectedTrackCount, totalDurationSeconds) }.orEmpty(),
                        onGoToAlbumArtist = onGoToAlbumArtist,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = if (isActiveAlbum) onTogglePlayback else onPlayAlbum,
                            enabled = playableTrackIds.isNotEmpty(),
                            modifier = Modifier
                                .height(44.dp)
                                .width(132.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(
                                imageVector = if (isActiveAlbum && isPlaybackPlaying) {
                                    Icons.Filled.Pause
                                } else {
                                    Icons.Filled.PlayArrow
                                },
                                contentDescription = if (isActiveAlbum && isPlaybackPlaying) {
                                    "Pause album"
                                } else {
                                    "Play album"
                                },
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(if (isActiveAlbum && isPlaybackPlaying) "Pause" else "Play")
                        }
                        CircleIconButton(
                            imageVector = if (album?.savedByCurrentUser == true) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Filled.Add
                            },
                            contentDescription = if (album?.savedByCurrentUser == true) {
                                "Remove album from Library"
                            } else {
                                "Save album to Library"
                            },
                            active = album?.savedByCurrentUser == true,
                            onClick = onAddAlbumToLibrary,
                            enabled = album != null,
                            activeContentColor = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        CollectionDownloadControls(
                            downloadState = albumDownloadState,
                            progressPercent = albumDownloadProgressPercent,
                            isPaused = album?.id?.let(::isAlbumDownloadPaused) == true,
                            isActive = isDownloadActive,
                            enabled = album != null && canDownload,
                            downloadContentDescription = "Download album",
                            deleteContentDescription = "Delete album download",
                            onDownload = onDownloadAlbum,
                            onDeleteDownload = onDeleteAlbumDownload,
                        )
                    }
                    if (!offlineNotice.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OfflineNotice(offlineNotice)
                    }
                }
            }
            if (album == null) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        EmptyState("Album was not found")
                    }
                }
            } else if (isLoading && displayTracks.isEmpty() && !isRefreshing) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        LoadingScreen("Loading album tracks")
                    }
                }
            } else if (displayTracks.isEmpty() && !isRefreshing) {
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                        EmptyState("No tracks loaded for this album yet")
                    }
                }
            } else {
                val showDiscHeaders = displayTracks
                    .map { it.discNumber ?: 1 }
                    .distinct()
                    .size > 1
                var previousDiscNumber: Int? = null
                displayTracks.forEachIndexed { index, track ->
                    val discNumber = track.discNumber ?: 1
                    if (showDiscHeaders && discNumber != previousDiscNumber) {
                        val isFirstDisc = previousDiscNumber == null
                        item(key = "disc-$discNumber-$index") {
                            Text(
                                text = "Disc $discNumber",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = ScreenHorizontalPadding,
                                    end = ScreenHorizontalPadding,
                                    top = if (isFirstDisc) 2.dp else 12.dp,
                                    bottom = 2.dp,
                                ),
                            )
                        }
                    }
                    previousDiscNumber = discNumber
                    item(key = "${track.id}:$index") {
                    TrackRow(
                        track = track,
                        artworkBitmap = null,
                        onRequestArtwork = null,
                        onClick = { onSelectTrack(track) },
                        isActive = isActiveAlbum && track.id == currentTrackId,
                        showActivePlaybackButton = isActiveAlbum && track.id == currentTrackId,
                        isPlaybackPlaying = isPlaybackPlaying,
                        onTogglePlayback = onTogglePlayback,
                        onAddToPlaylist = onAddTrackToPlaylist?.let { add -> { add(track) } },
                        onAddToQueue = { onAddTrackToQueue(track) },
                        onGoToArtist = { onGoToTrackArtist(track) },
                        onGoToAlbum = track.albumId?.let { { onGoToTrackAlbum(track) } },
                        isFavorite = track.id in favoriteTrackIds,
                        onToggleFavorite = onToggleTrackFavorite?.let { toggle -> { toggle(track) } },
                        downloadBadgePlacement = DownloadBadgePlacement.BeforeTitle,
                        leadingLabel = track.trackNumber?.toString().orEmpty(),
                        enabled = track.id in playableTrackIds,
                    )
                    }
                }
                if (isLoadingMore && !isRefreshing) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                album.releaseYear?.let { year ->
                    item {
                        Text(
                            text = year.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = ScreenHorizontalPadding,
                                end = ScreenHorizontalPadding,
                                top = 8.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(
    album: LibraryAlbum?,
    subtitle: String,
    onGoToAlbumArtist: (LibraryAlbum) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = album?.title ?: "Album",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (album != null) {
            val artistClickable = album.hasNavigableDisplayArtist()
            Text(
                text = album.displayArtistNames(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (artistClickable) {
                            Modifier.clickable { onGoToAlbumArtist(album) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
