package com.iptv.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iptv.tv.data.db.ScheduledRecordingDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RecordingAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduledRecordingDao: ScheduledRecordingDao
    @Inject lateinit var recordingManager: RecordingManager

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(RecordingScheduler.EXTRA_SCHEDULED_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val item = scheduledRecordingDao.getById(id) ?: return@launch
                val started = recordingManager.startNow(
                    streamId = item.channelStreamId,
                    title = item.title,
                    channelName = item.channelName,
                    filePath = item.storagePath,
                    endTimeMs = item.endTimeMs,
                    scheduledId = id,
                )
                if (!started) scheduledRecordingDao.updateStatus(id, "FAILED")
            } finally {
                pending.finish()
            }
        }
    }
}
