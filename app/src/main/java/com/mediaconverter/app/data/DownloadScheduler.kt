package com.mediaconverter.app.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mediaconverter.app.data.db.AppDatabase
import com.mediaconverter.app.data.db.DownloadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

enum class DownloadBackend { USER_INITIATED_JOB, FOREGROUND_WORKER }

object DownloadBackendSelector {
    fun forApi(apiLevel: Int): DownloadBackend =
        if (apiLevel >= 34) DownloadBackend.USER_INITIATED_JOB else DownloadBackend.FOREGROUND_WORKER
}

data class DownloadSpec(
    val source: NormalizedMediaUrl,
    val outputFormat: OutputFormat,
    val quality: String,
    val title: String,
    val thumbnailUrl: String,
)

interface DownloadScheduler {
    suspend fun schedule(spec: DownloadSpec): Long
    fun observe(downloadId: Long): Flow<DownloadEntity?>
    suspend fun retry(downloadId: Long)
    suspend fun cancel(downloadId: Long)
}

class ApiAwareDownloadScheduler(private val context: Context) : DownloadScheduler {
    private val dao = AppDatabase.getInstance(context).downloadDao()
    private val workManager = WorkManager.getInstance(context)

    override suspend fun schedule(spec: DownloadSpec): Long {
        val downloadId = dao.insertDownload(
            DownloadEntity(
                url = spec.source.value,
                title = spec.title,
                platform = spec.source.platform.value,
                format = spec.outputFormat.value,
                quality = spec.quality,
                thumbnailUrl = spec.thumbnailUrl,
                status = "pending",
                ytDlpVersion = YoutubeDlVersionReader.version(context).orEmpty(),
            ),
        )
        dispatch(downloadId)
        return downloadId
    }

    override fun observe(downloadId: Long): Flow<DownloadEntity?> = dao.observeDownloadById(downloadId)

    override suspend fun retry(downloadId: Long) {
        checkNotNull(dao.getDownloadById(downloadId)) { "Unknown download $downloadId" }
        dao.resetForRetry(downloadId)
        dispatch(downloadId)
    }

    override suspend fun cancel(downloadId: Long) {
        when (DownloadBackendSelector.forApi(Build.VERSION.SDK_INT)) {
            DownloadBackend.USER_INITIATED_JOB -> {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                scheduler.cancel(jobId(downloadId))
            }
            DownloadBackend.FOREGROUND_WORKER -> withContext(Dispatchers.IO) {
                workManager.cancelUniqueWork(workName(downloadId)).result.get()
            }
        }
        dao.markCancelled(downloadId, ConversionFailure.CANCELLED.code)
        DownloadNotifications(context).cancel(downloadId)
    }

    private suspend fun dispatch(downloadId: Long) {
        dispatchOrMarkFailed(
            downloadId = downloadId,
            performDispatch = {
                when (DownloadBackendSelector.forApi(Build.VERSION.SDK_INT)) {
                    DownloadBackend.USER_INITIATED_JOB -> scheduleUserInitiatedJob(downloadId)
                    DownloadBackend.FOREGROUND_WORKER -> scheduleForegroundWorker(downloadId)
                }
            },
            markFailed = { error ->
                dao.markFailed(downloadId, error, ConversionFailure.DOWNLOAD_FAILED.code)
            },
        )
    }

    private suspend fun scheduleForegroundWorker(downloadId: Long): Boolean {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putLong(DownloadWorker.KEY_DOWNLOAD_ID, downloadId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(workName(downloadId))
            .build()
        enqueueForegroundWork {
            workManager.enqueueUniqueWork(workName(downloadId), ExistingWorkPolicy.REPLACE, request).result
        }
        return true
    }

    private fun scheduleUserInitiatedJob(downloadId: Long): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val job = buildUserInitiatedJob(downloadId)
        return context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS
    }

    @RequiresApi(34)
    fun buildUserInitiatedJob(downloadId: Long): JobInfo {
        val extras = PersistableBundle().apply { putLong(EXTRA_DOWNLOAD_ID, downloadId) }
        return JobInfo.Builder(
            jobId(downloadId),
            ComponentName(context, UserInitiatedDownloadJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true)
            .setUserInitiated(true)
            .setExtras(extras)
            .build()
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
        fun jobId(downloadId: Long): Int = (downloadId % 999_999L).toInt() + 1
        fun workName(downloadId: Long) = "download-$downloadId"
    }
}

internal suspend fun dispatchOrMarkFailed(
    downloadId: Long,
    performDispatch: suspend () -> Boolean,
    markFailed: suspend (String) -> Unit,
) {
    withContext(NonCancellable) {
        val scheduled = try {
            performDispatch()
        } catch (failure: Exception) {
            val detail = failure.message?.takeIf { it.isNotBlank() }
            val error = if (detail == null) {
                "Unable to schedule download"
            } else {
                "Unable to schedule download: $detail"
            }
            markFailed(error)
            throw failure
        }
        if (!scheduled) {
            markFailed("Unable to schedule download")
            throw IllegalStateException("Unable to schedule download $downloadId")
        }
    }
}

internal suspend fun enqueueForegroundWork(enqueue: () -> Future<*>) {
    withContext(NonCancellable + Dispatchers.IO) {
        enqueue().get()
    }
}

object YoutubeDlVersionReader {
    fun version(context: Context): String? = runCatching {
        com.yausername.youtubedl_android.YoutubeDL.getInstance().versionName(context)
    }.getOrNull()
}
