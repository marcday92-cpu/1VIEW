package com.iptv.tv.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iptv.tv.data.repository.IptvRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class IptvOrgRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val iptvRepository: IptvRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = iptvRepository.refreshIptvOrgFeed(force = false)
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "iptv_org_refresh"

        fun enqueue(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<IptvOrgRefreshWorker>(24, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
