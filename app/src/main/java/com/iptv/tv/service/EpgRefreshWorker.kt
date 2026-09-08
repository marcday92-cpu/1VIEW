package com.iptv.tv.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iptv.tv.data.repository.EpgRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class EpgRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val epgRepository: EpgRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = epgRepository.refreshEpg()
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "epg_refresh"

        fun enqueue(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(6, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
