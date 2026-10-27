package com.mediaconverter.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

class DownloadNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "הורדות", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "מצב הורדות והמרות"
                },
            )
        }
    }

    fun ongoing(downloadId: Long, title: String, progress: Int, eta: String): Notification =
        progress(downloadId, title, progress, eta)
            .setOngoing(true)
            .build()

    private fun progress(downloadId: Long, title: String, progress: Int, eta: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (eta.isBlank()) "$progress%" else "$progress% · $eta")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress <= 0)
            .addAction(0, "ביטול", cancelIntent(downloadId))

    fun foreground(downloadId: Long, title: String, progress: Int, eta: String): ForegroundInfo {
        val notification = ongoing(downloadId, title, progress, eta)
        val slot = progressSlot(downloadId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(slot.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(slot.id, notification)
        }
    }

    /**
     * Posts progress directly when the worker could not become a foreground service. Deliberately
     * not "ongoing": with no service to take it down, a process kill mid-download would otherwise
     * leave a notification the user cannot swipe away.
     */
    fun notifyProgress(downloadId: Long, title: String, progress: Int, eta: String) {
        val slot = progressSlot(downloadId)
        manager.notify(slot.tag, slot.id, progress(downloadId, title, progress, eta).build())
    }

    fun showComplete(downloadId: Long, savedMedia: SavedMedia? = null) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("ההורדה הושלמה")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
        if (savedMedia != null) {
            builder
                .setContentText(savedMedia.displayName)
                .setContentIntent(openIntent(downloadId, savedMedia))
        }
        showResult(downloadId, builder.build())
    }

    fun showFailed(downloadId: Long) {
        showResult(
            downloadId,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("ההורדה נכשלה")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Replaces the progress notice with the result. The progress notice is taken down here
     * explicitly: the Android 14+ job detaches it when the job ends, and on Android 8-13
     * WorkManager cancels it by its bare id as soon as the worker returns, which also removed a
     * result posted under that same id. Results therefore live in a slot of their own.
     */
    private fun showResult(downloadId: Long, result: Notification) {
        val progress = progressSlot(downloadId)
        manager.cancel(progress.tag, progress.id)
        val slot = resultSlot(downloadId)
        manager.notify(slot.tag, slot.id, result)
    }

    fun cancel(downloadId: Long) {
        listOf(progressSlot(downloadId), resultSlot(downloadId)).forEach { manager.cancel(it.tag, it.id) }
    }

    internal fun outcomePresenter(downloadId: Long): DownloadOutcomePresenter = object : DownloadOutcomePresenter {
        override fun showComplete(savedMedia: SavedMedia?) = showComplete(downloadId, savedMedia)
        override fun showFailed() = showFailed(downloadId)
        override fun dismissProgress() = cancel(downloadId)
    }

    private fun openIntent(downloadId: Long, savedMedia: SavedMedia): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(savedMedia.contentUri), savedMedia.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            notificationId(downloadId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(downloadId: Long): PendingIntent {
        val intent = Intent(context, DownloadCancelReceiver::class.java)
            .putExtra(ApiAwareDownloadScheduler.EXTRA_DOWNLOAD_ID, downloadId)
        return PendingIntent.getBroadcast(
            context,
            notificationId(downloadId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "downloads_channel"
        private const val RESULT_TAG = "result"

        fun notificationId(downloadId: Long): Int =
            (downloadId % (Int.MAX_VALUE - 1)).toInt() + 1

        /** The in-progress notice, under the bare id the foreground service and the job own. */
        internal fun progressSlot(downloadId: Long) = NotificationSlot(tag = null, id = notificationId(downloadId))

        /** The completion or failure notice, which must outlive the service that ran the download. */
        internal fun resultSlot(downloadId: Long) = NotificationSlot(tag = RESULT_TAG, id = notificationId(downloadId))
    }
}

internal data class NotificationSlot(val tag: String?, val id: Int)
