package com.mediaconverter.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.mediaconverter.app.R
import com.mediaconverter.app.data.db.DownloadEntity
import kotlinx.coroutines.delay

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = DownloadRepository(context)
    private val downloadEngine = DownloadEngine(context)

    companion object {
        const val KEY_URL = "URL"
        const val KEY_FORMAT = "FORMAT" // "mp4" or "mp3"
        const val KEY_QUALITY = "QUALITY" // VideoQuality or AudioQuality enum value
        const val KEY_DOWNLOAD_ID = "DOWNLOAD_ID"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "downloads_channel"
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val formatStr = inputData.getString(KEY_FORMAT) ?: MediaFormat.MP4.value
        val format = MediaFormat.entries.find { it.value == formatStr } ?: MediaFormat.MP4
        val qualityStr = inputData.getString(KEY_QUALITY) ?: "best"
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1L)

        if (downloadId == -1L) return Result.failure()

        // 1. Create notification channel
        createNotificationChannel()

        // 2. Initial foreground info
        val initialDownload = repository.getDownloadById(downloadId)
        val title = initialDownload?.title ?: context.getString(R.string.downloading)
        setForeground(createForegroundInfo(0, title))

        return try {
            // Update status in DB
            repository.updateProgress(downloadId, 0, "downloading")

            val videoQuality = VideoQuality.entries.find { it.value == qualityStr } ?: VideoQuality.BEST
            val audioQuality = AudioQuality.entries.find { it.value == qualityStr } ?: AudioQuality.Q192

            // Start actual download
            val result = downloadEngine.download(
                url = url,
                format = format,
                videoQuality = videoQuality,
                audioQuality = audioQuality
            ) { progress, eta ->
                // Update notification and DB every progress change
                // But don't spam it too much
                try {
                    setForegroundAsync(createForegroundInfo(progress, title, eta))
                    // Using a coroutine builder here can be tricky, so we just launch it in a way that doesn't block
                    // For a real app, you might want to use a state flow or something similar to decouple UI updates from the worker
                    // repository.updateProgress(downloadId, progress, "downloading") // Removed due to CoroutineWorker threading issues if called too often
                } catch (e: Exception) {
                    // Ignore foreground exception
                }
            }

            if (result.isSuccess) {
                val file = result.getOrThrow()
                repository.markCompleted(downloadId, file.absolutePath, file.length())

                // Show success notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val successNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.notification_complete, title))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .build()
                notificationManager.notify(downloadId.toInt(), successNotification)

                Result.success()
            } else {
                val exception = result.exceptionOrNull()
                repository.markFailed(downloadId, exception?.message ?: "Unknown error")
                showErrorNotification(title)
                Result.failure()
            }
        } catch (e: Exception) {
            repository.markFailed(downloadId, e.message ?: "Worker exception")
            showErrorNotification(title)
            Result.failure()
        }
    }

    private fun createForegroundInfo(progress: Int, title: String, eta: String = ""): ForegroundInfo {
        val titleText = context.getString(R.string.notification_downloading, title)
        val contentText = if (eta.isNotEmpty()) "$progress% - נותר: $eta" else "$progress%"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showErrorNotification(title: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val errorNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_failed, title))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), errorNotification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't make a sound every time it updates
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
