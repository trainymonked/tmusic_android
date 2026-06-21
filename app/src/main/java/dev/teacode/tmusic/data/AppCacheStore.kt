package dev.teacode.tmusic.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppCacheStore(context: Context) {
    private val appContext = context.applicationContext

    suspend fun clearAndroidCache(excludedCacheDirNames: Set<String>) = withContext(Dispatchers.IO) {
        appContext.cacheDir.deleteChildrenExcept(excludedCacheDirNames)
        appContext.codeCacheDir?.deleteRecursively()
    }

    suspend fun androidCacheSizeBytes(excludedCacheDirNames: Set<String>): Long = withContext(Dispatchers.IO) {
        appContext.cacheDir.sizeBytesExcluding(excludedCacheDirNames) +
            (appContext.codeCacheDir?.sizeBytes() ?: 0L)
    }
}

private fun File.deleteChildrenExcept(excludedNames: Set<String>) {
    listFiles()?.forEach { child ->
        if (child.name !in excludedNames) {
            child.deleteRecursively()
        }
    }
}

private fun File.sizeBytesExcluding(excludedNames: Set<String>): Long {
    return listFiles()
        ?.filter { child -> child.name !in excludedNames }
        ?.sumOf { child -> child.sizeBytes() }
        ?: 0L
}

private fun File.sizeBytes(): Long {
    return walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
}
