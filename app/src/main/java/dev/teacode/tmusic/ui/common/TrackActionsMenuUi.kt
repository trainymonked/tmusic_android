package dev.teacode.tmusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun TrackActionsMenu(
    onAddToPlaylist: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)?,
    onAddToQueue: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    iconModifier: Modifier = Modifier,
) {
    if (
        onAddToPlaylist == null &&
        onRemoveFromPlaylist == null &&
        onAddToQueue == null &&
        onRemoveFromQueue == null &&
        onGoToArtist == null &&
        onGoToAlbum == null
    ) {
        return
    }

    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = modifier.size(buttonSize),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Track actions",
                modifier = iconModifier,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            onAddToPlaylist?.let { addToPlaylist ->
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    onClick = {
                        expanded = false
                        addToPlaylist()
                    },
                )
            }
            onAddToQueue?.let { addToQueue ->
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = {
                        expanded = false
                        addToQueue()
                    },
                )
            }
            onGoToArtist?.let { goToArtist ->
                DropdownMenuItem(
                    text = { Text("Go to artist") },
                    onClick = {
                        expanded = false
                        goToArtist()
                    },
                )
            }
            onGoToAlbum?.let { goToAlbum ->
                DropdownMenuItem(
                    text = { Text("Go to album") },
                    onClick = {
                        expanded = false
                        goToAlbum()
                    },
                )
            }
            onRemoveFromPlaylist?.let { removeFromPlaylist ->
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    onClick = {
                        expanded = false
                        removeFromPlaylist()
                    },
                )
            }
            onRemoveFromQueue?.let { removeFromQueue ->
                DropdownMenuItem(
                    text = { Text("Remove from queue") },
                    onClick = {
                        expanded = false
                        removeFromQueue()
                    },
                )
            }
        }
    }
}
