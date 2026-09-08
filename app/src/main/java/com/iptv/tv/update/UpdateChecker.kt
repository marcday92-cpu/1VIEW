package com.iptv.tv.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.iptv.tv.BuildConfig
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.player.MultiViewPool
import com.iptv.tv.player.PlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks for a newer build on GitHub and installs it through the system installer.
 *
 * The check is a background call with a short budget: launch never waits for it, and when it
 * fails nobody is told unless they asked for it from Settings. The install path stops every
 * player first, because a process killed while its hardware decoders are open wedges the
 * stick's video driver until a reboot.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackController: PlaybackController,
    private val multiViewPool: MultiViewPool,
    private val preferences: AppPreferences,
) {
    /** The contents of `version.json` in the releases repository. */
    @Serializable
    data class RemoteVersion(
        val versionCode: Int,
        val versionName: String = "",
        /** Edition name → APK download link. */
        val apk: Map<String, String> = emptyMap(),
        val notes: String = "",
    )

    sealed class Result {
        data class Available(val version: RemoteVersion, val apkUrl: String) : Result()
        data class UpToDate(val version: RemoteVersion) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val quickHttp = OkHttpClient.Builder()
        .connectTimeout(CHECK_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(CHECK_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CHECK_TIMEOUT_S + 1, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _available = MutableStateFlow<Result.Available?>(null)
    /** A newer build than the one running, once a check has found one. */
    val available: StateFlow<Result.Available?> = _available.asStateFlow()

    private val _dismissed = MutableStateFlow(false)
    /**
     * The Home banner is put away for this version: "Later" was pressed, or Install already
     * ran, within the last day. About still offers the update. Persisted, so a manifest that
     * wrongly names a build as newer cannot nag on every launch.
     */
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    private val _progress = MutableStateFlow<Int?>(null)
    /** Download percentage while an install is in progress, else null. */
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError: StateFlow<String?> = _installError.asStateFlow()

    /** Fetches `version.json`; never throws, and only records a result when a newer build exists. */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val result = runCatching {
            val request = Request.Builder()
                .url(BuildConfig.UPDATE_URL)
                .header("Cache-Control", "no-cache")
                .build()
            quickHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching Result.Failed("HTTP ${response.code}")
                val remote = json.decodeFromString<RemoteVersion>(response.body?.string().orEmpty())
                evaluate(remote, BuildConfig.VERSION_CODE, BuildConfig.EDITION)
            }
        }.getOrElse { Result.Failed(describe(it)) }
        if (result is Result.Available) {
            _dismissed.value = runCatching { isSnoozed(result.version.versionCode) }.getOrDefault(false)
            _available.value = result
        }
        result
    }

    private suspend fun isSnoozed(versionCode: Int): Boolean {
        val (code, at) = preferences.updateSnooze.first()
        return code == versionCode && System.currentTimeMillis() - at < SNOOZE_MS
    }

    private suspend fun snooze() {
        val code = _available.value?.version?.versionCode ?: return
        runCatching { preferences.setUpdateSnooze(code, System.currentTimeMillis()) }
        _dismissed.value = true
    }

    suspend fun dismiss() = snooze()

    /**
     * Downloads the newer APK and opens the system installer on it. Playback is stopped first.
     * Returns false when there was nothing to install or the download failed; the reason is in
     * [installError].
     */
    suspend fun install(): Boolean {
        val target = _available.value ?: return false
        _installError.value = null
        snooze()
        withContext(Dispatchers.Main) {
            playbackController.close()
            multiViewPool.releaseAll()
        }
        val file = withContext(Dispatchers.IO) {
            runCatching { download(target) }
                .onFailure { _installError.value = "Download failed: ${describe(it)}" }
                .getOrNull()
        } ?: return false
        return withContext(Dispatchers.Main) { openInstaller(file) }
    }

    private fun download(target: Result.Available): File {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "1VIEW-${target.version.versionCode}.apk")
        val request = Request.Builder().url(target.apkUrl).build()
        try {
            downloadHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty download")
                val total = body.contentLength()
                _progress.value = 0
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            if (total > 0) _progress.value = (done * 100 / total).toInt().coerceIn(0, 100)
                        }
                    }
                }
            }
        } finally {
            _progress.value = null
        }
        return file
    }

    private fun openInstaller(file: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent); true }
            .getOrElse {
                _installError.value = "Fire TV could not open the installer."
                false
            }
    }

    private fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "GitHub could not be reached"
        is SocketTimeoutException -> "GitHub took too long to answer"
        is IOException -> error.message ?: "network error"
        else -> error::class.simpleName ?: "error"
    }

    companion object {
        const val CHECK_TIMEOUT_S = 4L
        const val UPDATE_DIR = "updates"
        /** How long "Later" (or a pressed Install) keeps the banner away for the same version. */
        const val SNOOZE_MS = 24 * 60 * 60 * 1000L

        /** Pure comparison, kept separate so it can be unit-tested. */
        fun evaluate(remote: RemoteVersion, installedCode: Int, edition: String): Result {
            if (remote.versionCode <= installedCode) return Result.UpToDate(remote)
            val url = remote.apk[edition] ?: remote.apk.values.firstOrNull()
                ?: return Result.Failed("No download listed for this edition")
            return Result.Available(remote, url)
        }
    }
}
