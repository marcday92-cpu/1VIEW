package com.iptv.tv.subtitle

import com.iptv.tv.data.db.SubtitleSidecarDao
import com.iptv.tv.data.db.SubtitleSidecarEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleSidecarStore @Inject constructor(
    private val dao: SubtitleSidecarDao,
) {
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    suspend fun binding(contentKey: String): SubtitleSidecarEntity? = dao.get(contentKey)

    suspend fun boundFile(contentKey: String): File? = withContext(Dispatchers.IO) {
        val path = dao.get(contentKey)?.filePath ?: return@withContext null
        val file = File(path)
        file.takeIf { it.exists() && it.length() > 40 }
    }

    /** True if we already searched this title and found nothing worth saving. */
    suspend fun alreadySearchedEmpty(contentKey: String): Boolean {
        val row = dao.get(contentKey) ?: return false
        return row.filePath.isNullOrBlank() &&
            System.currentTimeMillis() - row.updatedAt < EMPTY_RESULT_TTL_MS
    }

    suspend fun remember(contentKey: String, file: File, subtitleId: String, language: String = "en") {
        val bound = withContext(Dispatchers.IO) { copyBound(contentKey, file) }
        dao.upsert(
            SubtitleSidecarEntity(
                contentKey = contentKey,
                filePath = bound.absolutePath,
                subtitleId = subtitleId,
                language = language,
            ),
        )
    }

    suspend fun rememberEmpty(contentKey: String) {
        dao.upsert(
            SubtitleSidecarEntity(
                contentKey = contentKey,
                filePath = null,
                subtitleId = "none",
            ),
        )
    }

    /**
     * One in-flight download per title. A second play of the same movie waits
     * on the first request instead of hitting SubDL again.
     */
    suspend fun sharedFetch(contentKey: String, block: suspend () -> File?): File? {
        inflight[contentKey]?.let { return it.await() }
        val deferred = CompletableDeferred<File?>()
        val winner = inflight.putIfAbsent(contentKey, deferred)
        if (winner != null) return winner.await()
        return try {
            val file = block()
            deferred.complete(file)
            file
        } catch (error: Throwable) {
            deferred.complete(null)
            throw error
        } finally {
            inflight.remove(contentKey, deferred)
        }
    }

    /**
     * The subtitle folder grows with every title watched; keep it bounded. Bound files
     * (attached to a title the user may replay) are kept longest; raw downloads go first.
     */
    suspend fun prune(dir: File, keepFiles: Int = MAX_CACHED_FILES) = withContext(Dispatchers.IO) {
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        if (files.size <= keepFiles) return@withContext
        val ordered = files.sortedWith(
            compareBy<File> { it.name.startsWith("bound_") }.thenBy { it.lastModified() },
        )
        ordered.take(files.size - keepFiles).forEach { runCatching { it.delete() } }
    }

    private fun copyBound(contentKey: String, source: File): File {
        val dir = source.parentFile ?: return source
        val dest = File(dir, "bound_${safeKey(contentKey)}.${source.extension.ifBlank { "srt" }}")
        if (source.canonicalPath != dest.canonicalPath) {
            source.copyTo(dest, overwrite = true)
        }
        return dest
    }

    private fun safeKey(contentKey: String): String =
        contentKey.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val EMPTY_RESULT_TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_CACHED_FILES = 150
    }
}
