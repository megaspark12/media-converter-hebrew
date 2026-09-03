package com.mediaconverter.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DownloadWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val runner = DownloadTaskRunner(context)
    private val notifications = DownloadNotifications(context)
    private val repository = DownloadRepository(context)

    override suspend fun doWork(): Result {
        return try {
            val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
            if (downloadId <= 0) return Result.failure()
            notifications.createChannel()
            setForeground(notifications.foreground(downloadId, "מוריד מדיה", 0, ""))
            val outcome = runner.run(downloadId) { progress, eta ->
                if (progress == 100 || progress % 5 == 0) {
                    setForegroundAsync(notifications.foreground(downloadId, "מוריד מדיה", progress, eta))
                }
            }
            when {
                outcome.succeeded -> {
                    notifications.showComplete(downloadId)
                    Result.success()
                }
                outcome.shouldRetry && runAttemptCount < MAX_RETRIES -> Result.retry()
                else -> {
                    if (outcome.shouldRetry) {
                        repository.markFailed(
                            downloadId,
                            "Retry limit reached",
                            ConversionFailure.DOWNLOAD_FAILED.code,
                        )
                    }
                    notifications.showFailed(downloadId)
                    Result.failure()
                }
            }
        } catch (cancelled: CancellationException) {
            val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
            if (downloadId > 0) {
                notifications.cancel(downloadId)
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.markRetryPending(
                        downloadId,
                        "Worker stopped; waiting to retry",
                        ConversionFailure.DOWNLOAD_FAILED.code,
                    )
                }
            }
            throw cancelled
        }
    }

    companion object {
        const val KEY_URL = "URL"
        const val KEY_FORMAT = "FORMAT"
        const val KEY_QUALITY = "QUALITY"
        const val KEY_DOWNLOAD_ID = "DOWNLOAD_ID"
        private const val MAX_RETRIES = 3
    }
}
