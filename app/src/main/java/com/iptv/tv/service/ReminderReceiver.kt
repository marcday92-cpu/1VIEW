package com.iptv.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.iptv.tv.MainActivity
import com.iptv.tv.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val channelStreamId = intent.getIntExtra(EXTRA_CHANNEL_STREAM_ID, -1)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: return
        val programmeTitle = intent.getStringExtra(EXTRA_PROGRAMME_TITLE) ?: return
        val offsetMinutes = intent.getIntExtra(EXTRA_OFFSET_MINUTES, 0)
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHANNEL_STREAM_ID, channelStreamId)
            action = ACTION_WATCH_NOW
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            reminderId.toInt(),
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val message = if (offsetMinutes > 0) {
            "$channelName — $programmeTitle ${context.getString(R.string.reminder_starts_in, offsetMinutes)}"
        } else {
            "$channelName — $programmeTitle"
        }

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(programmeTitle)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_media_play, context.getString(R.string.watch_now), pendingIntent)
            .setContentIntent(pendingIntent)
            .build()

        // Android 13+ treats notifications as a runtime permission; without it notify() is rejected.
        val canNotify = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canNotify) NotificationManagerCompat.from(context).notify(reminderId.toInt(), notification)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { reminderScheduler.markFired(reminderId) } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_CHANNEL_STREAM_ID = "channel_stream_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_PROGRAMME_TITLE = "programme_title"
        const val EXTRA_OFFSET_MINUTES = "offset_minutes"
        const val ACTION_WATCH_NOW = "com.iptv.tv.WATCH_NOW"
    }
}
