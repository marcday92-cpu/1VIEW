package com.iptv.tv.data.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StorageOption(
    val id: String,
    val label: String,
    val path: String,
    val freeBytes: Long,
    val type: StorageType,
)

enum class StorageType { INTERNAL, USB }

@Singleton
class RecordingStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun listOptions(): List<StorageOption> {
        val options = mutableListOf<StorageOption>()
        val internal = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        options += StorageOption(
            id = "internal",
            label = "Internal Storage",
            path = internal.absolutePath,
            freeBytes = freeBytes(internal),
            type = StorageType.INTERNAL,
        )
        val usbRoots = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            ?.drop(1)
            ?.filterNotNull()
            .orEmpty()
        usbRoots.forEachIndexed { index, dir ->
            options += StorageOption(
                id = "usb_$index",
                label = "USB Drive",
                path = dir.absolutePath,
                freeBytes = freeBytes(dir),
                type = StorageType.USB,
            )
        }
        return options
    }

    fun hasMinimumFreeSpace(file: File, minBytes: Long = MIN_FREE_BYTES): Boolean {
        val dir = file.parentFile ?: return true
        return freeBytes(dir) > minBytes
    }

    fun isLowSpace(file: File, warnBytes: Long = WARN_FREE_BYTES): Boolean {
        val dir = file.parentFile ?: return false
        return freeBytes(dir) < warnBytes
    }

    fun newRecordingFile(basePath: String, title: String): File {
        val safe = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80)
        val dir = File(basePath)
        dir.mkdirs()
        return File(dir, "${safe}_${System.currentTimeMillis()}.ts")
    }

    private fun freeBytes(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024
        const val WARN_FREE_BYTES = 5L * 1024 * 1024 * 1024
    }
}
