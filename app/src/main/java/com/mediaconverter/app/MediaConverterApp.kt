package com.mediaconverter.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.mediaconverter.app.data.YtDlpRuntimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaConverterApp : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            refreshYtDlp(
                refresh = { YtDlpRuntimeProvider.get(this@MediaConverterApp).refreshIfDue() },
                logFailure = { Log.w("MediaConverterApp", "yt-dlp refresh skipped", it) },
            )
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(1_000_000, 2_000_000)
            .build()
}

internal suspend fun refreshYtDlp(
    refresh: suspend () -> Unit,
    logFailure: (Throwable) -> Unit,
) {
    try {
        refresh()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logFailure(failure)
    }
}
