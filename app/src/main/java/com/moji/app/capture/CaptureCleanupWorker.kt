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
        (applicationContext as MojiApplication).repository.pruneTerminalCandidates(
            System.currentTimeMillis() - TERMINAL_CANDIDATE_RETENTION_MS
        )
        DebugCaptureSampler.pruneIfExpired(applicationContext)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val TERMINAL_CANDIDATE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CaptureCleanupWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "capture-event-cleanup", ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }
}
