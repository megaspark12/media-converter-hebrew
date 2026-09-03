package com.mediaconverter.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getLongExtra(ApiAwareDownloadScheduler.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId <= 0) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ApiAwareDownloadScheduler(context.applicationContext).cancel(downloadId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
