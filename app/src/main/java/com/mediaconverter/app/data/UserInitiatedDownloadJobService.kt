package com.mediaconverter.app.data

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(34)
class UserInitiatedDownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val stopDispositions = ConcurrentHashMap<Int, StopDisposition>()

    override fun onStartJob(params: JobParameters): Boolean {
        val downloadId = params.extras.getLong(ApiAwareDownloadScheduler.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId <= 0) return false
        val notifications = DownloadNotifications(this).also { it.createChannel() }
        fun showProgress(progress: Int, eta: String) = setNotification(
            params,
            DownloadNotifications.progressSlot(downloadId).id,
            notifications.ongoing(downloadId, TITLE, progress, eta),
            JOB_END_NOTIFICATION_POLICY_DETACH,
        )
        showProgress(0, "")
        jobs[params.jobId] = scope.launch {
            try {
                val disposition = runDownloadAttempt(
                    canRetry = true,
                    attempt = {
                        DownloadTaskRunner(this@UserInitiatedDownloadJobService).run(downloadId) { progress, eta ->
                            if (shouldPostProgress(progress)) showProgress(progress, eta)
                        }
                    },
                    markFailed = { error, code ->
                        DownloadRepository(this@UserInitiatedDownloadJobService).markFailed(downloadId, error, code)
                    },
                    presenter = notifications.outcomePresenter(downloadId),
                    log = { message, failure -> Log.w(TAG, message, failure) },
                )
                jobFinished(params, disposition == DownloadDisposition.RETRY)
            } catch (cancelled: CancellationException) {
                val disposition = stopDispositions.remove(params.jobId)
                    ?: StopDisposition(downloadId, shouldRetry = true, stopReason = -1)
                notifications.cancel(downloadId)
                withContext(NonCancellable + Dispatchers.IO) {
                    val repository = DownloadRepository(this@UserInitiatedDownloadJobService)
                    val record = repository.getDownloadById(disposition.downloadId)
                    when {
                        !disposition.shouldRetry -> repository.markCancelled(disposition.downloadId)
                        record != null && record.retryCount < DownloadTaskRunner.MAX_SCHEDULE_RETRIES ->
                            repository.markRetryPending(
                                disposition.downloadId,
                                "Job stopped (${disposition.stopReason}); waiting to retry",
                                ConversionFailure.DOWNLOAD_FAILED.code,
                            )
                        else -> repository.markFailed(
                            disposition.downloadId,
                            "Retry limit reached",
                            ConversionFailure.DOWNLOAD_FAILED.code,
                        )
                    }
                }
                throw cancelled
            } finally {
                jobs.remove(params.jobId)
                stopDispositions.remove(params.jobId)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val downloadId = params.extras.getLong(ApiAwareDownloadScheduler.EXTRA_DOWNLOAD_ID, -1L)
        val shouldRetry = params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP &&
            params.stopReason != JobParameters.STOP_REASON_USER
        stopDispositions[params.jobId] = StopDisposition(downloadId, shouldRetry, params.stopReason)
        jobs.remove(params.jobId)?.cancel()
        if (downloadId > 0) {
            DownloadNotifications(this).cancel(downloadId)
        }
        return shouldRetry
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class StopDisposition(
        val downloadId: Long,
        val shouldRetry: Boolean,
        val stopReason: Int,
    )

    private companion object {
        const val TAG = "DownloadJobService"
        const val TITLE = "מוריד מדיה"
    }
}
