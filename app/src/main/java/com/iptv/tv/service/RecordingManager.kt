package com.iptv.tv.service

import android.content.Context
import androidx.core.content.ContextCompat
import com.iptv.tv.data.credentials.CredentialsStore
import com.iptv.tv.data.db.RecordingDao
import com.iptv.tv.data.db.ScheduledRecordingDao
import com.iptv.tv.data.db.ScheduledRecordingEntity
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.storage.RecordingStorage
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.player.PlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveRecording(
    val streamId: Int,
    val title: String,
)

@Singleton
class RecordingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingDao: RecordingDao,
    private val scheduledRecordingDao: ScheduledRecordingDao,
    private val recordingStorage: RecordingStorage,
    private val recordingScheduler: RecordingScheduler,
    private val credentialsStore: CredentialsStore,
    private val preferences: AppPreferences,
    private val iptvRepository: IptvRepository,
    private val playbackController: PlaybackController,
) {
    private val _active = MutableStateFlow<ActiveRecording?>(null)
    val active: StateFlow<ActiveRecording?> = _active.asStateFlow()

    fun setActiveRecording(streamId: Int, title: String) {
        _active.value = ActiveRecording(streamId, title)
        keepAwakeForRecordings()
    }

    fun clearActiveRecording() {
        _active.value = null
        keepAwakeForRecordings()
    }

    fun maxConnections(): Int = credentialsStore.getMaxConnections()

    fun wouldConflict(newStreamId: Int): Boolean {
        val recording = _active.value ?: return false
        if (maxConnections() > 1) return false
        val playing = playbackController.session.value?.streamId
        return recording.streamId != newStreamId && playing != null && playing != newStreamId
    }

    fun conflictMessage(): String {
        val rec = _active.value ?: return ""
        return "Recording ${rec.title} is currently using your available IPTV connection. Changing channel may stop the recording."
    }

    suspend fun startOrSchedule(
        channel: Channel,
        programme: Programme?,
        startBufferMinutes: Int,
        endBufferMinutes: Int,
        startImmediately: Boolean,
    ): Result<String> {
        if (_active.value != null) {
            return Result.failure(IllegalStateException("Another recording is already running."))
        }
        val dest = preferences.recordingStoragePath.first()
            ?: recordingStorage.listOptions().first().path
        val title = programme?.title ?: channel.name
        val file = recordingStorage.newRecordingFile(dest, title)
        if (!recordingStorage.hasMinimumFreeSpace(file)) {
            return Result.failure(IllegalStateException("Not enough free space (need 1 GB). Use USB or a network share."))
        }
        val startMs = (programme?.startTimeMs ?: System.currentTimeMillis()) - startBufferMinutes * 60_000L
        val endMs = (programme?.endTimeMs ?: (System.currentTimeMillis() + 30 * 60_000L)) + endBufferMinutes * 60_000L
        val now = System.currentTimeMillis()
        return if (startImmediately || startMs <= now + 15_000) {
            startNow(channel.streamId, title, channel.name, file.absolutePath, endMs)
            Result.success(file.absolutePath)
        } else {
            val id = scheduledRecordingDao.insert(
                ScheduledRecordingEntity(
                    title = title,
                    channelName = channel.name,
                    channelStreamId = channel.streamId,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    startBufferMinutes = startBufferMinutes,
                    endBufferMinutes = endBufferMinutes,
                    storagePath = file.absolutePath,
                    alarmId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                ),
            )
            recordingScheduler.schedule(id)
            keepAwakeForRecordings()
            Result.success("scheduled")
        }
    }

    fun startNow(streamId: Int, title: String, channelName: String, filePath: String, endTimeMs: Long, scheduledId: Long = -1): Boolean {
        if (_active.value != null) return false
        setActiveRecording(streamId, title)
        val intent = RecordingService.startIntent(
            context, streamId, title, channelName, filePath, endTimeMs, scheduledId,
        )
        ContextCompat.startForegroundService(context, intent)
        keepAwakeForRecordings()
        return true
    }

    suspend fun cancelScheduled(id: Long) {
        recordingScheduler.cancel(id)
        scheduledRecordingDao.delete(id)
        keepAwakeForRecordings()
    }

    /**
     * A recording cannot outlive the process: if the app died mid-recording (crash, Fire TV
     * killing it) its row stays at RECORDING and the file never becomes playable. On start-up,
     * rows that no live job owns become COMPLETED when a file exists (a partial recording still
     * plays) or FAILED when nothing was written.
     */
    suspend fun reconcileInterrupted() {
        val live = _active.value
        val now = System.currentTimeMillis()
        recordingDao.getByStatus("RECORDING").forEach { row ->
            if (live != null && row.channelStreamId == live.streamId && row.endTimeMs > now) return@forEach
            val file = java.io.File(row.filePath)
            val status = if (file.exists() && file.length() > 0L) "COMPLETED" else "FAILED"
            recordingDao.updateStatus(row.id, status)
        }
        scheduledRecordingDao.getByStatus("RECORDING")
            .filter { !(live != null && it.channelStreamId == live.streamId && it.endTimeMs > now) }
            .forEach { scheduledRecordingDao.updateStatus(it.id, if (it.endTimeMs <= now) "COMPLETED" else "FAILED") }
    }

    fun observeRecordings() = recordingDao.observeAll()
    fun observeScheduled() = scheduledRecordingDao.observeScheduled()

    fun keepAwakeForRecordings() {
        RecordingStayAwakeService.start(context)
    }
}
