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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.DownloadState
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

private sealed class LibraryListItem {
    abstract val key: String

    data class PlaylistItem(val playlist: Playlist) : LibraryListItem() {
        override val key = "playlist:${playlist.id}"
    }

    data class AlbumItem(val album: LibraryAlbum) : LibraryListItem() {
        override val key = "album:${album.id}"
    }
}

@Composable
fun LibraryScreen(
    playlists: List<Playlist>,
    tracks: List<Track>,
    savedAlbums: List<LibraryAlbum>,
    albumTracksById: Map<String, List<Track>>,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    isRefreshing: Boolean,
    offlineOnly: Boolean,
    onRefresh: () -> Unit,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onSelectAlbum: (LibraryAlbum) -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    val favoritePlaylist = playlists.firstOrNull { it.isFavoritesPlaylist() }
    val regularPlaylists = playlists.filterNot { it.id == favoritePlaylist?.id }
    val libraryItems = remember(playlists, savedAlbums, tracks, albumTracksById, offlineOnly) {
        val orderedItems = buildList {
            favoritePlaylist?.let { add(LibraryListItem.PlaylistItem(it)) }
            savedAlbums.asReversed().forEach { add(LibraryListItem.AlbumItem(it)) }
            regularPlaylists.asReversed().forEach { add(LibraryListItem.PlaylistItem(it)) }
        }
        if (!offlineOnly) {
            orderedItems
        } else {
            orderedItems.filter { item ->
                when (item) {
                    is LibraryListItem.PlaylistItem -> item.playlist.isFavoritesPlaylist() || item.playlist.isOfflineEnabled
                    is LibraryListItem.AlbumItem -> item.album.savedByCurrentUser || item.album.isOfflineEnabled
                }
            }
        }
    }

    SwipeRefreshContainer(
        enabled = true,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Library",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = { showCreatePlaylistDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Create playlist",
                        )
                    }
                }
            }
            if (libraryItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No library items",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(libraryItems, key = { it.key }) { item ->
                    when (item) {
                        is LibraryListItem.PlaylistItem -> {
                            val coverKey = playlistArtworkKey(item.playlist)
                            LibraryPlaylistRow(
                                playlist = item.playlist,
                                trackCount = item.playlist.trackCount,
                                downloadState = item.playlist.downloadState(tracks),
                                artworkBitmap = artworkBitmaps.artworkBitmap(
                                    coverKey,
                                    ArtworkImageSize.AlbumGrid,
                                ),
                                coverKey = coverKey,
                                onRequestArtwork = onRequestArtwork,
                                onClick = { onSelectPlaylist(item.playlist) },
                            )
                        }
                        is LibraryListItem.AlbumItem -> {
                            val coverKey = item.album.artworkKey(tracks, albumTracksById)
                            LibraryAlbumRow(
                                album = item.album,
                                downloadState = item.album.downloadState(albumTracksById),
                                artworkBitmap = artworkBitmaps.artworkBitmap(
                                    coverKey,
                                    ArtworkImageSize.AlbumGrid,
                                ),
                                coverKey = coverKey,
                                onRequestArtwork = onRequestArtwork,
                                onClick = { onSelectAlbum(item.album) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        LibraryCreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                onCreatePlaylist(name)
                showCreatePlaylistDialog = false
            },
        )
    }
}

private fun Playlist.downloadState(tracks: List<Track>): DownloadState {
    val playlistTracks = tracksFrom(tracks)
    val expectedTrackCount = trackCount.coerceAtLeast(trackIds.size)
    return when {
        !isOfflineEnabled -> DownloadState.NotDownloaded
        playlistTracks.any { it.downloadState == DownloadState.Queued } -> DownloadState.Queued
        isOfflineEnabled &&
            expectedTrackCount > 0 &&
            playlistTracks.size >= expectedTrackCount &&
            playlistTracks.all { it.downloadState == DownloadState.Downloaded } -> DownloadState.Downloaded
        else -> DownloadState.Queued
    }
}

private fun LibraryAlbum.downloadState(albumTracksById: Map<String, List<Track>>): DownloadState {
    val albumTracks = albumTracksById[id].orEmpty()
    return when {
        !isOfflineEnabled -> DownloadState.NotDownloaded
        albumTracks.any { it.downloadState == DownloadState.Queued } -> DownloadState.Queued
        isOfflineEnabled &&
            trackCount > 0 &&
            albumTracks.isNotEmpty() &&
            albumTracks.size >= trackCount &&
            albumTracks.all { it.downloadState == DownloadState.Downloaded } -> DownloadState.Downloaded
        else -> DownloadState.Queued
    }
}

private fun LibraryAlbum.artworkKey(
    tracks: List<Track>,
    albumTracksById: Map<String, List<Track>>,
): String? {
    if (hasArtwork) {
        return albumArtworkKey(id)
    }
    return artworkTrackId
        ?: albumTracksById[id]
            ?.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
            ?.firstOrNull()
            ?.id
        ?: tracks
            .filter { it.albumId == id || it.album == title }
            .sortedBy { it.trackNumber ?: Int.MAX_VALUE }
            .firstOrNull()
            ?.id
}
