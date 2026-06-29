package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.data.UserPreferencesStore
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal class AlbumPlaybackActionHost(
    private val scope: CoroutineScope,
    private val musicRepository: RemoteMusicRepository,
    private val userPreferencesStore: UserPreferencesStore,
    private val getAlbumTracksById: () -> Map<String, List<Track>>,
    private val setAlbumTracksById: (Map<String, List<Track>>) -> Unit,
    private val getTracks: () -> List<Track>,
    private val getShuffleEnabled: () -> Boolean,
    private val setShuffleEnabled: (Boolean) -> Unit,
    private val canUseServerRequests: () -> Boolean,
    private val queueStartRequestSerial: AtomicLong,
    private val nextQueueStartRequestSerial: () -> Long,
    private val playQueuedTrack: (Track, PlaybackQueue, Int?) -> Unit,
    private val replacePlaybackQueueIfRequestCurrent: (Long, PlaybackSourceType, String, List<Track>, List<Track>) -> Unit,
    private val mergeLoadedTracks: (List<Track>) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setPlayerError: (String?) -> Unit,
) {
    suspend fun resolveAlbumTracksForPlayback(
        album: LibraryAlbum,
        fallbackTracks: List<Track>,
    ): List<Track> {
        val cachedTracks = getAlbumTracksById()[album.id].orEmpty().takeIf { it.isNotEmpty() }
            ?: getTracks()
                .filter { track -> track.albumId == album.id || (track.album == album.title && track.matchesAlbumArtist(album)) }
                .sortedBy { it.trackNumber ?: Int.MAX_VALUE }
        if (
            cachedTracks.isNotEmpty() &&
            (!canUseServerRequests() || (album.trackCount > 0 && cachedTracks.size >= album.trackCount))
        ) {
            return cachedTracks
        }
        if (!canUseServerRequests()) {
            return cachedTracks.takeIf { it.isNotEmpty() } ?: fallbackTracks
        }

        val loadedTracks = musicRepository.albumTracks(album.id)
            .sortedBy { it.trackNumber ?: Int.MAX_VALUE }
        setAlbumTracksById(getAlbumTracksById() + (album.id to loadedTracks))
        mergeLoadedTracks(loadedTracks)
        return loadedTracks.takeIf { it.isNotEmpty() } ?: fallbackTracks
    }

    fun playAlbumFromTrack(album: LibraryAlbum, albumTracks: List<Track>, track: Track) {
        if (getShuffleEnabled()) {
            setShuffleEnabled(false)
            userPreferencesStore.setShuffleEnabled(false)
        }
        playAlbumTrackWithBackgroundResolve(
            scope = scope,
            album = album,
            albumTracks = albumTracks,
            track = track,
            canUseServerRequests = canUseServerRequests,
            nextRequestSerial = nextQueueStartRequestSerial,
            playQueue = { selectedTrack, queue, index -> playQueuedTrack(selectedTrack, queue, index) },
            resolveTracks = { currentAlbum, fallbackTracks ->
                resolveAlbumTracksForPlayback(currentAlbum, fallbackTracks)
            },
            replaceResolvedQueue = replacePlaybackQueueIfRequestCurrent,
            isRequestCurrent = { it == queueStartRequestSerial.get() },
            markServerUnavailable = markServerUnavailable,
            setPlayerError = setPlayerError,
        )
    }

    fun playAlbum(album: LibraryAlbum, albumTracks: List<Track>) {
        if (getShuffleEnabled()) {
            setShuffleEnabled(false)
            userPreferencesStore.setShuffleEnabled(false)
        }
        val firstTrack = albumTracks.firstOrNull()
        if (firstTrack != null) {
            playAlbumFromTrack(album, albumTracks, firstTrack)
            return
        }

        if (!canUseServerRequests()) {
            setPlayerError("Album is not available offline")
            return
        }

        scope.launch {
            runCatching {
                resolveAlbumTracksForPlayback(album, emptyList())
            }.onSuccess { resolvedTracks ->
                resolvedTracks.firstOrNull()?.let { track ->
                    playAlbumFromTrack(album, resolvedTracks, track)
                }
            }.onFailure { error ->
                markServerUnavailable(error)
                setPlayerError(error.userMessage())
            }
        }
    }
}
