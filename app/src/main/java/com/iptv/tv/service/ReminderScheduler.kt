package com.iptv.tv.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.iptv.tv.MainActivity
import com.iptv.tv.R
import com.iptv.tv.data.db.ReminderDao
import com.iptv.tv.data.db.ReminderEntity
import com.iptv.tv.domain.model.ReminderItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    init {
        createNotificationChannel()
    }

    suspend fun scheduleReminder(
        profileId: Long,
        channelStreamId: Int,
        channelName: String,
        programmeTitle: String,
        programmeStartMs: Long,
        offsetMinutes: Int,
    ): Long {
        val alarmId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        val entity = ReminderEntity(
            profileId = profileId,
            channelStreamId = channelStreamId,
            channelName = channelName,
            programmeTitle = programmeTitle,
            programmeStartMs = programmeStartMs,
            offsetMinutes = offsetMinutes,
            alarmId = alarmId,
        )
        val id = reminderDao.insert(entity)
        scheduleAlarm(entity, id)
        return id
    }

    private fun scheduleAlarm(entity: ReminderEntity, id: Long) {
        val triggerMs = entity.programmeStartMs - entity.offsetMinutes * 60_000L
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id)
            putExtra(ReminderReceiver.EXTRA_CHANNEL_STREAM_ID, entity.channelStreamId)
            putExtra(ReminderReceiver.EXTRA_CHANNEL_NAME, entity.channelName)
            putExtra(ReminderReceiver.EXTRA_PROGRAMME_TITLE, entity.programmeTitle)
            putExtra(ReminderReceiver.EXTRA_OFFSET_MINUTES, entity.offsetMinutes)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            entity.alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
        }
    }

    suspend fun cancelReminder(id: Long) {
        val reminder = reminderDao.getById(id) ?: return
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        reminderDao.delete(id)
    }

    fun observeReminders(profileId: Long) = reminderDao.observeByProfile(profileId)

    suspend fun rescheduleAllReminders() {
        val reminders = reminderDao.getAll()
        for (reminder in reminders) {
            if (reminder.programmeStartMs > System.currentTimeMillis()) {
                scheduleAlarm(reminder, reminder.id)
            } else reminderDao.delete(reminder.id)
        }
    }

    suspend fun markFired(id: Long) {
        if (id > 0) reminderDao.delete(id)
    }

    private fun createNotificationChannel() {
        // Notification channels exist from Android 8; Fire OS 6 sticks run Android 7.1.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Programme reminders"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "programme_reminders"
    }
}
