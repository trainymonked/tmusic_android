package dev.teacode.tmusic.data

import android.content.Context
import dev.teacode.tmusic.BuildConfig
import dev.teacode.tmusic.domain.OfflineTrackManifest
import dev.teacode.tmusic.domain.TrackDownloadInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

class OfflineTrackStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        OFFLINE_TRACKS_NAME,
        Context.MODE_PRIVATE,
    )
    private val tracksDirectory = File(appContext.filesDir, "offline_tracks")
    private val cacheDirectory = File(appContext.filesDir, "music_cache")

    fun manifest(trackId: String): OfflineTrackManifest? {
        val json = preferences.getString(trackId, null) ?: return null
        return runCatching { JSONObject(json).toManifest() }
            .getOrNull()
            ?.takeIf { File(it.localPath).exists() }
    }

    fun localPlaybackUrl(trackId: String): String? {
        val manifest = manifest(trackId) ?: return null
        return File(manifest.localPath).toURI().toString()
    }

    fun cachedPlaybackUrl(trackId: String): String? {
        return cacheFile(trackId)
            .takeIf { it.exists() && it.length() > 0L }
            ?.also { it.setLastModified(System.currentTimeMillis()) }
            ?.toURI()
            ?.toString()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        preferences.edit().clear().apply()
        tracksDirectory.listFiles()?.forEach { file -> file.delete() }
    }

    suspend fun moveToCache(trackId: String, maxCacheBytes: Long) = withContext(Dispatchers.IO) {
        val source = manifest(trackId)?.localPath?.let(::File)
        cacheDirectory.mkdirs()
        if (source != null && source.exists() && source.length() > 0L) {
            val destination = cacheFile(trackId)
            if (destination.exists()) {
                destination.delete()
            }
            if (!source.renameTo(destination)) {
                source.copyTo(destination, overwrite = true)
                source.delete()
            }
            destination.setLastModified(System.currentTimeMillis())
        }
        preferences.edit().remove(trackId).apply()
        File(tracksDirectory, "${trackId.safeFileName()}.bin").delete()
        File(tracksDirectory, "${trackId.safeFileName()}.tmp").delete()
        trimCacheToLimit(maxCacheBytes)
    }

    suspend fun promoteCachedTrack(trackId: String): OfflineTrackManifest? = withContext(Dispatchers.IO) {
        val source = cacheFile(trackId).takeIf { it.exists() && it.length() > 0L } ?: return@withContext null
        tracksDirectory.mkdirs()
        val destination = File(tracksDirectory, "${trackId.safeFileName()}.bin")
        if (destination.exists()) {
            destination.delete()
        }
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
        val manifest = OfflineTrackManifest(
            trackId = trackId,
            etag = null,
            checksumSha256 = null,
            localPath = destination.absolutePath,
        )
        save(manifest)
        manifest
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheDirectory.listFiles()?.forEach { file -> file.delete() }
    }

    suspend fun clearCacheExcept(retainedTrackIds: Set<String>) = withContext(Dispatchers.IO) {
        val retainedFileNames = retainedTrackIds
            .map { trackId -> "${trackId.safeFileName()}.bin" }
            .toSet()
        cacheDirectory.listFiles()?.forEach { file ->
            if (file.name !in retainedFileNames) {
                file.delete()
            }
        }
    }

    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        cacheDirectory.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        tracksDirectory.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    suspend fun download(downloadInfo: TrackDownloadInfo): OfflineTrackManifest = withContext(Dispatchers.IO) {
        tracksDirectory.mkdirs()
        val destination = File(tracksDirectory, "${downloadInfo.trackId.safeFileName()}.bin")
        val temporary = File(tracksDirectory, "${downloadInfo.trackId.safeFileName()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var responseEtag: String? = null

        val connection = (URL(downloadInfo.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("X-TMusic-Platform", "android")
            setRequestProperty("X-TMusic-Version-Code", BuildConfig.VERSION_CODE.toString())
            setRequestProperty("X-TMusic-Version-Name", BuildConfig.VERSION_NAME)
        }

        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw TMusicApiException(statusCode, "Track download failed with HTTP $statusCode.")
            }

            responseEtag = connection.getHeaderField("ETag")
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }

        currentCoroutineContext().ensureActive()
        val checksum = digest.digest().joinToString(separator = "") { "%02x".format(it) }
        val expectedChecksum = downloadInfo.checksumSha256?.lowercase()?.takeIf { it.isNotBlank() }
        if (expectedChecksum != null && expectedChecksum != checksum.lowercase()) {
            temporary.delete()
            throw TMusicApiException(null, "Downloaded track checksum does not match the server manifest.")
        }

        currentCoroutineContext().ensureActive()
        if (destination.exists()) {
            destination.delete()
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }

        val manifest = OfflineTrackManifest(
            trackId = downloadInfo.trackId,
            etag = downloadInfo.etag ?: responseEtag,
            checksumSha256 = expectedChecksum ?: checksum,
            localPath = destination.absolutePath,
        )
        save(manifest)
        manifest
    }

    private fun save(manifest: OfflineTrackManifest) {
        preferences.edit()
            .putString(manifest.trackId, manifest.toJson().toString())
            .apply()
    }

    private fun trimCacheToLimit(maxCacheBytes: Long) {
        if (maxCacheBytes <= 0L) {
            return
        }
        val files = cacheDirectory.listFiles()
            ?.filter { it.isFile }
            ?.map { file ->
                CachedFileSnapshot(
                    file = file,
                    lastModified = file.lastModified(),
                    name = file.name,
                    length = file.length(),
                )
            }
            .orEmpty()
        var totalBytes = files.sumOf { it.length }
        files
            .sortedWith(compareBy<CachedFileSnapshot> { it.lastModified }.thenBy { it.name })
            .forEach { cachedFile ->
                if (totalBytes <= maxCacheBytes) {
                    return@forEach
                }
                if (cachedFile.file.delete()) {
                    totalBytes -= cachedFile.length
                }
            }
    }

    private fun cacheFile(trackId: String): File {
        return File(cacheDirectory, "${trackId.safeFileName()}.bin")
    }

    private companion object {
        const val OFFLINE_TRACKS_NAME = "tmusic_offline_tracks"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

private data class CachedFileSnapshot(
    val file: File,
    val lastModified: Long,
    val name: String,
    val length: Long,
)

private fun OfflineTrackManifest.toJson(): JSONObject {
    return JSONObject()
        .put("trackId", trackId)
        .put("etag", etag)
        .put("checksumSha256", checksumSha256)
        .put("localPath", localPath)
}

private fun JSONObject.toManifest(): OfflineTrackManifest {
    return OfflineTrackManifest(
        trackId = optString("trackId"),
        etag = optString("etag").takeIf { it.isNotBlank() },
        checksumSha256 = optString("checksumSha256").takeIf { it.isNotBlank() },
        localPath = optString("localPath"),
    )
}

private fun String.safeFileName(): String {
    return replace(Regex("[^A-Za-z0-9._-]"), "_")
}
