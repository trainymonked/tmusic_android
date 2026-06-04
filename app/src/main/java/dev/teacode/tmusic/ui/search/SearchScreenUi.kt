package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.LibraryArtist
import dev.teacode.tmusic.domain.LibrarySearchResults
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.RecentLibraryItem
import dev.teacode.tmusic.domain.Track

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
    onlineMode: Boolean,
    artworkBitmaps: Map<String, ImageBitmap>,
    listState: LazyListState,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onQueryChange: (String) -> Unit,
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
    val matchingArtists = results.artists
    val matchingAlbums = results.albums
    val matchingPlaylists = (results.playlists + playlists.filter { playlist ->
        playlist.title.contains(normalizedQuery, ignoreCase = true)
    }).distinctBy { it.id }
    val artworkTracks = (matchingTracks + offlineTracks).distinctBy { it.id }

    LaunchedEffect(focusRequestSerial) {
        if (focusRequestSerial > 0) {
            searchFocusRequester.requestFocus()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
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
        if (normalizedQuery.isBlank()) {
            item {
                RecentSearchesCard(
                    recentItems = recentItems,
                    onClear = onClearRecentItems,
                    onItemClick = onRecentItemClick,
                )
            }
        } else {
            if (isSearching) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (
                !isSearching &&
                matchingAlbums.isEmpty() &&
                matchingArtists.isEmpty() &&
                matchingPlaylists.isEmpty() &&
                matchingTracks.isEmpty()
            ) {
                item { EmptyState("No results") }
            }
            if (matchingAlbums.isNotEmpty()) {
                item {
                    SearchResultWindow(title = "Albums") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                item {
                    SearchResultWindow(title = "Tracks") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            matchingTracks.forEach { track ->
                                TrackRow(
                                    track = track,
                                    artworkBitmap = artworkBitmaps.artworkBitmap(track.listArtworkKey(), ArtworkImageSize.TrackList),
                                    onRequestArtwork = onRequestArtwork,
                                    onClick = { onSelectTrack(track, normalizedQuery) },
                                    showDownloadBadge = false,
                                    onAddToPlaylist = if (onlineMode) {
                                        { onAddTrackToPlaylistClick(track) }
                                    } else {
                                        null
                                    },
                                    onAddToQueue = { onAddTrackToQueue(track) },
                                    onGoToArtist = { onGoToTrackArtist(track) },
                                    onGoToAlbum = track.albumId?.let { { onGoToTrackAlbum(track) } },
                                    isFavorite = track.id in favoriteTrackIds,
                                    onToggleFavorite = onToggleTrackFavorite?.let { toggle -> { toggle(track) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
