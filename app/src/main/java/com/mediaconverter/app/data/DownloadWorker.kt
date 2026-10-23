package com.mediaconverter.app.data

import android.content.Context
import android.util.Log
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
            val foreground = promoteToForeground(
                setForeground = { setForeground(notifications.foreground(downloadId, TITLE, 0, "")) },
                logFailure = { Log.w(TAG, "Download $downloadId continues without a foreground service", it) },
            )
            val outcome = runner.run(downloadId) { progress, eta ->
                if (progress == 100 || progress % 5 == 0) {
                    if (foreground) {
                        setForegroundAsync(notifications.foreground(downloadId, TITLE, progress, eta))
                    } else {
                        notifications.notifyOngoing(downloadId, TITLE, progress, eta)
                    }
                }
            }
            when {
                outcome.succeeded -> {
                    notifications.showComplete(downloadId, outcome.savedMedia)
                    Result.success()
                }
                outcome.shouldRetry && runAttemptCount < MAX_RETRIES -> {
                    notifications.cancel(downloadId)
                    Result.retry()
                }
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
        private const val TAG = "DownloadWorker"
        private const val TITLE = "מוריד מדיה"
    }
}

/**
 * Tries to promote the worker to a foreground service and reports whether that succeeded.
 *
 * Android 12+ rejects foreground-service starts while the app is in the background, which is
 * exactly the situation for a retry with back-off or a download that waited for connectivity.
 * Before this helper the rejection escaped [DownloadWorker.doWork], WorkManager marked the work
 * failed and the database row stayed "pending" forever. The download is still allowed to run as
 * a regular job, so a rejection must only be logged.
 */
internal suspend fun promoteToForeground(
    setForeground: suspend () -> Unit,
    logFailure: (Throwable) -> Unit,
): Boolean = try {
    setForeground()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (rejected: Exception) {
    logFailure(rejected)
    false
}
