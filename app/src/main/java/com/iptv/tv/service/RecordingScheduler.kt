package com.iptv.tv.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.iptv.tv.data.db.ScheduledRecordingDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduledRecordingDao: ScheduledRecordingDao,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    suspend fun schedule(id: Long) {
        val item = scheduledRecordingDao.getById(id) ?: return
        val intent = Intent(context, RecordingAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SCHEDULED_ID, id)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            item.alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val trigger = item.startTimeMs
        val show = PendingIntent.getActivity(
            context,
            item.alarmId,
            Intent(context, com.iptv.tv.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), pending)
    }

    suspend fun cancel(id: Long) {
        val item = scheduledRecordingDao.getById(id) ?: return
        val intent = Intent(context, RecordingAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            item.alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pending)
    }

    suspend fun rescheduleAll() {
        scheduledRecordingDao.getScheduled().forEach { schedule(it.id) }
    }

    companion object {
        const val EXTRA_SCHEDULED_ID = "scheduled_id"
    }
}
