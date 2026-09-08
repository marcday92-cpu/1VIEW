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
import androidx.core.content.ContextCompat
import com.iptv.tv.R
import com.iptv.tv.data.db.ScheduledRecordingDao
import com.iptv.tv.data.db.ScheduledRecordingEntity
import com.iptv.tv.util.formatClock
import com.iptv.tv.util.formatDay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the Fire Stick out of standby while a recording is scheduled or running.
 * Alarms alone are not enough: Fire OS often sleeps through them.
 */
@AndroidEntryPoint
class RecordingStayAwakeService : Service() {

    @Inject lateinit var scheduledRecordingDao: ScheduledRecordingDao
    @Inject lateinit var recordingManager: RecordingManager

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)
    private var monitor: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(null, recordingManager.active.value))
        if (monitor == null) {
            monitor = scope.launch {
                combine(
                    scheduledRecordingDao.observeScheduled(),
                    recordingManager.active,
                ) { scheduled, active -> scheduled to active }
                    .collect { (scheduled, active) ->
                        if (scheduled.isEmpty() && active == null) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            startForeground(NOTIFICATION_ID, buildNotification(scheduled.firstOrNull(), active))
                        }
                    }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        monitor?.cancel()
        job.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(
        next: ScheduledRecordingEntity?,
        active: ActiveRecording?,
    ): Notification {
        val title: String
        val text: String
        when {
            active != null -> {
                title = getString(R.string.recording_active)
                text = active.title
            }
            next != null -> {
                title = getString(R.string.recording_stay_awake_title)
                text = getString(
                    R.string.recording_stay_awake_text,
                    next.title,
                    "${formatDay(next.startTimeMs)} ${formatClock(next.startTimeMs)}",
                )
            }
            else -> {
                title = getString(R.string.recording_stay_awake_title)
                text = getString(R.string.recording_stay_awake_waiting)
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.iptv.tv:recording-stay-awake",
        ).apply { setReferenceCounted(false) }
        runCatching { lock.acquire() }
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
        wakeLock = null
    }

    private fun createChannel() {
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
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingStayAwakeService::class.java),
            )
        }
    }
}
