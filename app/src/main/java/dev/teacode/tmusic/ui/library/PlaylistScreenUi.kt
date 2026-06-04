package dev.teacode.tmusic.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track

@Composable
fun PlaylistScreen(
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
    onUpdatePlaylist: (String, String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onPlayPlaylist: () -> Unit,
    onShufflePlayPlaylist: () -> Unit,
    isActivePlaylist: Boolean,
    currentTrackId: String?,
    isPlaybackPlaying: Boolean,
    canPlayFromNetwork: Boolean,
    offlinePlayableTrackIds: Set<String>,
    onTogglePlayback: () -> Unit,
    artworkBitmaps: Map<String, ImageBitmap>,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    var showEditPlaylistDialog by remember { mutableStateOf(false) }
    var showDeletePlaylistDialog by remember { mutableStateOf(false) }

    PlaylistContent(
        playlist = playlist,
        tracks = tracks,
        canDownload = canDownload,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        onSelectTrack = onSelectTrack,
        onDownloadPlaylist = onDownloadPlaylist,
        onAddTrackToPlaylist = onAddTrackToPlaylist,
        onAddTrackToQueue = onAddTrackToQueue,
        onGoToTrackArtist = onGoToTrackArtist,
        onGoToTrackAlbum = onGoToTrackAlbum,
        favoriteTrackIds = favoriteTrackIds,
        onToggleTrackFavorite = onToggleTrackFavorite,
        onRemoveTrack = onRemoveTrack,
        onReorderTracks = onReorderTracks,
        onEditPlaylist = { showEditPlaylistDialog = true },
        onDeletePlaylist = { showDeletePlaylistDialog = true },
        onPlayPlaylist = onPlayPlaylist,
        onShufflePlayPlaylist = onShufflePlayPlaylist,
        isActivePlaylist = isActivePlaylist,
        currentTrackId = currentTrackId,
        isPlaybackPlaying = isPlaybackPlaying,
        canPlayFromNetwork = canPlayFromNetwork,
        offlinePlayableTrackIds = offlinePlayableTrackIds,
        onTogglePlayback = onTogglePlayback,
        artworkBitmaps = artworkBitmaps,
        onRequestArtwork = onRequestArtwork,
        isLoadingMore = isLoadingMore,
        canLoadMore = canLoadMore,
        onLoadMore = onLoadMore,
    )

    if (showDeletePlaylistDialog && !playlist.isFavoritesPlaylist()) {
        AlertDialog(
            onDismissRequest = { showDeletePlaylistDialog = false },
            title = { Text("Delete playlist?") },
            text = { Text("This will remove the playlist from your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        onDeletePlaylist()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePlaylistDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
    if (showEditPlaylistDialog && !playlist.isFavoritesPlaylist()) {
        EditPlaylistDialog(
            playlist = playlist,
            onDismiss = { showEditPlaylistDialog = false },
            onSave = { name, description ->
                onUpdatePlaylist(name, description)
                showEditPlaylistDialog = false
            },
        )
    }
}
