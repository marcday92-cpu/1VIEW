package com.iptv.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class TvApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(false)
        .respectCacheHeaders(false)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    /**
     * Keeps the last crash on disk so it can be pulled with
     * `adb exec-out run-as com.iptv.tv cat files/crash.log`. The previous
     * handler still runs, so the process dies exactly as it would otherwise.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { appendCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun appendCrash(thread: Thread, error: Throwable) {
        val log = File(filesDir, "crash.log")
        if (log.length() > MAX_CRASH_LOG_BYTES) log.writeText("")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val trace = com.iptv.tv.ui.SecretRedactor.redact(
            StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString(),
        )
        log.appendText("\n===== $stamp on ${thread.name} =====\n$trace")
    }


    private companion object {
        const val MAX_CRASH_LOG_BYTES = 200L * 1024
    }
}
