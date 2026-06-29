package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.LibraryCacheStore
import dev.teacode.tmusic.data.RemoteMusicRepository
import dev.teacode.tmusic.domain.LibraryAlbum
import dev.teacode.tmusic.domain.Playlist
import dev.teacode.tmusic.domain.Track
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

internal class PlaylistPlaybackActionHost(
    private val scope: CoroutineScope,
    private val getPlaylists: () -> List<Playlist>,
    private val setPlaylists: (List<Playlist>) -> Unit,
    private val getTracks: () -> List<Track>,
    private val setTracks: (List<Track>) -> Unit,
    private val getSavedAlbums: () -> List<LibraryAlbum>,
    private val getPlaybackQueue: () -> PlaybackQueue,
    private val setShuffleEnabled: (Boolean) -> Unit,
    private val userSetShuffleEnabled: (Boolean) -> Unit,
    private val queueStartRequestSerial: AtomicLong,
    private val canUseServerRequests: () -> Boolean,
    private val canUseMediaServerRequests: () -> Boolean,
    private val localOrCachedPlaybackUrl: (Track) -> String?,
    private val musicRepository: RemoteMusicRepository,
    private val libraryCacheStore: LibraryCacheStore,
    private val nextQueueStartRequestSerial: () -> Long,
    private val playQueuedTrack: (Track, PlaybackQueue, Int?) -> Unit,
    private val replacePlaybackQueueSnapshotIfRequestCurrent: (Long, PlaybackQueue, String) -> Unit,
    private val markServerUnavailable: (Throwable) -> Unit,
    private val setPlayerError: (String?) -> Unit,
) {
    fun knownPlaylistTracksForPlayback(
        playlist: Playlist,
        fallbackTracks: List<Track>,
    ): List<Track> {
        val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
        val knownTracks = currentPlaylist.tracksFrom(getTracks()).let { candidateTracks ->
            if (canUseMediaServerRequests()) {
                candidateTracks
            } else {
                candidateTracks.filter { track -> localOrCachedPlaybackUrl(track) != null }
            }
        }
        return knownTracks.takeIf { it.size > fallbackTracks.size } ?: fallbackTracks
    }

    suspend fun resolvePlaylistTracksForPlayback(
        playlist: Playlist,
        fallbackTracks: List<Track>,
    ): List<Track> {
        val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
        val cachedTracks = currentPlaylist.tracksFrom(getTracks())
        val expectedTrackCount = currentPlaylist.trackCount.coerceAtLeast(currentPlaylist.trackIds.size)
        if (
            cachedTracks.isNotEmpty() &&
            (expectedTrackCount <= 0 || cachedTracks.size >= expectedTrackCount || !canUseServerRequests())
        ) {
            return cachedTracks
        }
        if (!canUseServerRequests()) {
            return cachedTracks.takeIf { it.isNotEmpty() } ?: fallbackTracks
        }

        val payload = if (playlist.isFavoritesPlaylist()) {
            musicRepository.favoritesPlaylistPayload()
        } else {
            musicRepository.playlistPayload(playlist.id)
        }
        val merged = payload.mergeWithCachedPlaylistData(
            cachedPlaylists = getPlaylists(),
            cachedTracks = getTracks(),
            withOfflineState = musicRepository::withOfflineState,
        )
        setTracks(merged.tracks)
        setPlaylists(merged.playlists)
        libraryCacheStore.saveLibrary(
            playlists = merged.playlists,
            tracks = merged.tracks,
            savedAlbums = getSavedAlbums(),
        )
        val loadedPlaylist = merged.playlist ?: currentPlaylist
        return loadedPlaylist.tracksFrom(getTracks()).takeIf { it.isNotEmpty() } ?: fallbackTracks
    }

    fun playPlaylistTrackAt(playlist: Playlist, playlistTracks: List<Track>, trackIndex: Int) {
        val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
        val knownPlaylistTracks = knownPlaylistTracksForPlayback(currentPlaylist, playlistTracks)
        val selectedTrack = playlistTracks.getOrNull(trackIndex)
        val knownTrackIndex = selectedTrack?.let { track ->
            selectedTrackIndexInResolvedTracks(
                selectedTrack = track,
                selectedIndex = trackIndex,
                sourceTracks = playlistTracks,
                resolvedTracks = knownPlaylistTracks,
            )
        } ?: trackIndex.coerceIn(0, (knownPlaylistTracks.size - 1).coerceAtLeast(0))
        playPlaylistTrackAtWithBackgroundResolve(
            scope = scope,
            playlist = currentPlaylist,
            playlistTracks = knownPlaylistTracks,
            trackIndex = knownTrackIndex,
            canUseServerRequests = canUseServerRequests,
            nextRequestSerial = nextQueueStartRequestSerial,
            playQueue = { track, queue, index -> playQueuedTrack(track, queue, index) },
            resolveTracks = { currentPlaylist, fallbackTracks ->
                resolvePlaylistTracksForPlayback(currentPlaylist, fallbackTracks)
            },
            replaceQueue = replacePlaybackQueueSnapshotIfRequestCurrent,
            isRequestCurrent = { it == queueStartRequestSerial.get() },
            markServerUnavailable = markServerUnavailable,
            setPlayerError = setPlayerError,
        )
    }

    fun playPlaylist(playlist: Playlist, playlistTracks: List<Track>) {
        if (playlistTracks.isEmpty()) {
            return
        }
        playPlaylistTrackAt(
            playlist = playlist,
            playlistTracks = playlistTracks,
            trackIndex = 0,
        )
    }

    fun shufflePlayPlaylist(playlist: Playlist, playlistTracks: List<Track>) {
        val currentPlaylist = getPlaylists().firstOrNull { it.id == playlist.id } ?: playlist
        val knownPlaylistTracks = knownPlaylistTracksForPlayback(currentPlaylist, playlistTracks)
        shufflePlayPlaylistWithBackgroundResolve(
            scope = scope,
            playlist = currentPlaylist,
            playlistTracks = knownPlaylistTracks,
            existingQueueTracks = getPlaybackQueue().tracks,
            nextRequestSerial = nextQueueStartRequestSerial,
            setShuffleEnabled = {
                setShuffleEnabled(true)
                userSetShuffleEnabled(true)
            },
            playQueue = { track, queue, index -> playQueuedTrack(track, queue, index) },
            isRequestCurrent = { it == queueStartRequestSerial.get() },
            replaceQueue = replacePlaybackQueueSnapshotIfRequestCurrent,
        )
    }
}
