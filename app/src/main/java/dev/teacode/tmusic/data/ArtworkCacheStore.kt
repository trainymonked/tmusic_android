package dev.teacode.tmusic.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArtworkCacheStore(context: Context) {
    private val appContext = context.applicationContext
    private val artworkDirectory = File(appContext.filesDir, "artwork_cache")

    fun cachedPath(trackId: String): String? {
        val file = artworkFile(trackId)
            .takeIf { it.exists() && it.length() > 0L }
            ?: return null
        file.setLastModified(System.currentTimeMillis())
        return file.absolutePath
    }

    suspend fun cache(trackId: String, artworkUrl: String): String = withContext(Dispatchers.IO) {
        artworkDirectory.mkdirs()
        val destination = artworkFile(trackId)
        if (destination.exists() && destination.length() > 0L) {
            destination.setLastModified(System.currentTimeMillis())
            return@withContext destination.absolutePath
        }

        val temporary = File(artworkDirectory, "${trackId.safeFileName()}.tmp")
        val connection = (URL(artworkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw TMusicApiException(statusCode, "Artwork download failed with HTTP $statusCode.")
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }

        if (destination.exists()) {
            destination.delete()
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }

        destination.absolutePath
    }

    suspend fun trimToLimit(maxBytes: Long, keysToKeep: Set<String>) = withContext(Dispatchers.IO) {
        if (maxBytes <= 0L) {
            return@withContext
        }
        val namesToKeep = keysToKeep.map { "${it.safeFileName()}.image" }.toSet()
        val files = artworkDirectory.listFiles()
            ?.filter { it.isFile }
            .orEmpty()
        var totalBytes = files.sumOf { it.length() }
        files
            .filter { it.name !in namesToKeep }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (totalBytes <= maxBytes) {
                    return@forEach
                }
                val length = file.length()
                if (file.delete()) {
                    totalBytes -= length
                }
            }
    }

    fun clear() {
        clearExcept(emptySet())
    }

    fun clearExcept(keysToKeep: Set<String>) {
        val namesToKeep = keysToKeep.map { "${it.safeFileName()}.image" }.toSet()
        artworkDirectory.listFiles()?.forEach { file ->
            if (file.name !in namesToKeep) {
                file.delete()
            }
        }
    }

    fun clearKeys(keys: Set<String>) {
        keys.forEach { key -> artworkFile(key).delete() }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        artworkDirectory.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    suspend fun sizeBytesFor(keys: Set<String>): Long = withContext(Dispatchers.IO) {
        keys.sumOf { key ->
            artworkFile(key)
                .takeIf { it.exists() && it.isFile }
                ?.length()
                ?: 0L
        }
    }

    suspend fun sizeBytesExcluding(keys: Set<String>): Long = withContext(Dispatchers.IO) {
        val namesToExclude = keys.map { "${it.safeFileName()}.image" }.toSet()
        artworkDirectory.walkTopDown()
            .filter { it.isFile && it.name !in namesToExclude }
            .sumOf { it.length() }
    }

    private fun artworkFile(trackId: String): File {
        return File(artworkDirectory, "${trackId.safeFileName()}.image")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

private fun String.safeFileName(): String {
    val readable = replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .take(48)
        .ifBlank { "key" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(16)
    return "${readable}_$digest"
}
