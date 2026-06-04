package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.teacode.tmusic.domain.LibraryArtist

@Composable
fun ArtistGridRow(
    artists: List<LibraryArtist>,
    artworkBitmaps: Map<String, ImageBitmap>,
    onRequestArtwork: (String, ArtworkImageSize) -> Unit,
    onSelectArtist: (LibraryArtist) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        artists.forEach { artist ->
            val coverTrackId = artistArtworkKey(artist)
            ArtistCard(
                artist = artist,
                artworkBitmap = artworkBitmaps.artworkBitmap(coverTrackId, ArtworkImageSize.AlbumGrid),
                coverTrackId = coverTrackId,
                onRequestArtwork = onRequestArtwork,
                onClick = { onSelectArtist(artist) },
                modifier = Modifier.weight(1f),
            )
        }
        repeat(3 - artists.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
