package com.iptv.tv.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.iptv.tv.data.preferences.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var recordingScheduler: RecordingScheduler
    @Inject lateinit var recordingManager: RecordingManager
    @Inject lateinit var preferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    reminderScheduler.rescheduleAllReminders()
                    recordingScheduler.rescheduleAll()
                    recordingManager.keepAwakeForRecordings()
                    if (preferences.openOnStartup.first()) {
                        withContext(Dispatchers.Main) {
                            launchHome(context)
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /** Opens Home only. Fire OS may still refuse a background activity start. */
    private fun launchHome(context: Context) {
        val launch = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName("com.iptv.tv", "com.iptv.tv.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(launch) }
    }
}
