package com.iptv.tv.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.iptv.tv.R
import com.iptv.tv.data.db.RecordingDao
import com.iptv.tv.data.db.RecordingEntity
import com.iptv.tv.data.db.ScheduledRecordingDao
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.storage.RecordingStorage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var iptvRepository: IptvRepository
    @Inject lateinit var recordingDao: RecordingDao
    @Inject lateinit var scheduledRecordingDao: ScheduledRecordingDao
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var recordingStorage: RecordingStorage
    @Inject lateinit var recordingManager: RecordingManager

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var recordingJob: Job? = null
    private var recordingId: Long = 0
    private var scheduledId: Long = -1
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recordingJob?.isActive == true) return
        val streamId = intent.getIntExtra(EXTRA_STREAM_ID, -1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Recording"
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
        val endTimeMs = intent.getLongExtra(EXTRA_END_TIME_MS, 0)
        scheduledId = intent.getLongExtra(EXTRA_SCHEDULED_ID, -1)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(title, getString(R.string.recording_active)))
        acquireWakeLock()
        recordingManager.setActiveRecording(streamId, title)

        scope.launch {
            recordingId = recordingDao.insert(
                RecordingEntity(
                    title = title,
                    channelName = channelName,
                    channelStreamId = streamId,
                    filePath = filePath,
                    startTimeMs = System.currentTimeMillis(),
                    endTimeMs = endTimeMs,
                    status = "RECORDING",
                ),
            )
            val url = iptvRepository.buildLiveUrl(streamId)
            recordingJob = scope.launch {
                recordStream(url, File(filePath), endTimeMs)
            }
            if (this@RecordingService.scheduledId > 0) {
                scheduledRecordingDao.updateStatus(this@RecordingService.scheduledId, "RECORDING")
            }
        }
    }

    private suspend fun recordStream(url: String, outputFile: File, endTimeMs: Long) {
        outputFile.parentFile?.mkdirs()
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                recordingDao.updateStatus(recordingId, "FAILED")
                if (scheduledId > 0) scheduledRecordingDao.updateStatus(scheduledId, "FAILED")
                return
            }
            var stoppedForSpace = false
            response.use {
            response.body?.byteStream()?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    while (coroutineContext.isActive) {
                        if (endTimeMs > 0 && System.currentTimeMillis() >= endTimeMs) break
                        if (!recordingStorage.hasMinimumFreeSpace(outputFile)) {
                            stoppedForSpace = true
                            break
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            }
            val status = if (stoppedForSpace || outputFile.length() == 0L) "FAILED" else "COMPLETED"
            recordingDao.updateStatus(recordingId, status)
            if (scheduledId > 0) scheduledRecordingDao.updateStatus(scheduledId, status)
        } catch (_: Exception) {
            recordingDao.updateStatus(recordingId, "FAILED")
            if (scheduledId > 0) scheduledRecordingDao.updateStatus(scheduledId, "FAILED")
        } finally {
            recordingManager.clearActiveRecording()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        scope.launch {
            if (recordingId > 0) {
                recordingDao.updateStatus(recordingId, "COMPLETED")
            }
            if (scheduledId > 0) scheduledRecordingDao.updateStatus(scheduledId, "COMPLETED")
        }
        recordingManager.clearActiveRecording()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job.cancel()
        recordingManager.clearActiveRecording()
        releaseWakeLock()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.iptv.tv:recording")
            .apply { setReferenceCounted(false) }
        runCatching { lock.acquire() }
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
        wakeLock = null
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        // Notification channels exist from Android 8; Fire OS 6 sticks run Android 7.1.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.iptv.tv.RECORDING_START"
        const val ACTION_STOP = "com.iptv.tv.RECORDING_STOP"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_END_TIME_MS = "end_time_ms"
        const val EXTRA_SCHEDULED_ID = "scheduled_id"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(
            context: Context,
            streamId: Int,
            title: String,
            channelName: String,
            filePath: String,
            endTimeMs: Long,
            scheduledId: Long = -1,
        ): Intent = Intent(context, RecordingService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_STREAM_ID, streamId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_CHANNEL_NAME, channelName)
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_END_TIME_MS, endTimeMs)
            putExtra(EXTRA_SCHEDULED_ID, scheduledId)
        }
    }
}
