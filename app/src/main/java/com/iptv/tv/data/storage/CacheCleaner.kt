package com.iptv.tv.data.storage

import android.content.Context
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Clear cache" from Home / Settings. Coil owns an open disk cache under cacheDir, so it is
 * emptied through its own API rather than by deleting files out from under its journal.
 */
object CacheCleaner {
    // Coil marks DiskCache.directory/clear as experimental; the API has been stable since 2.0.
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearDisposableCaches(context: Context) = withContext(Dispatchers.IO) {
        val loader = context.imageLoader
        runCatching { loader.memoryCache?.clear() }
        runCatching { loader.diskCache?.clear() }
        val imageCacheDir = loader.diskCache?.directory?.toFile()
        context.cacheDir.listFiles().orEmpty().forEach { child ->
            if (imageCacheDir != null && child.canonicalPath == imageCacheDir.canonicalPath) return@forEach
            runCatching { child.deleteRecursively() }
        }
        context.cacheDir.mkdirs()
    }
}
