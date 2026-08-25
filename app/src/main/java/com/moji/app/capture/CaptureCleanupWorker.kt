package com.moji.app.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moji.app.MojiApplication
import java.util.concurrent.TimeUnit

class CaptureCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        (applicationContext as MojiApplication).repository.pruneCaptureEvents(System.currentTimeMillis())
        DebugCaptureSampler.pruneIfExpired(applicationContext)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CaptureCleanupWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "capture-event-cleanup", ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }
}
