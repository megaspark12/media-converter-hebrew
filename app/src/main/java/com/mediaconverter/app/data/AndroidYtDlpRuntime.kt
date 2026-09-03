package com.mediaconverter.app.data

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

private val Context.ytDlpDataStore by preferencesDataStore(name = "yt_dlp_runtime")

class AndroidYtDlpClient(private val context: Context) : YtDlpClient {
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        YoutubeDL.getInstance().init(context.applicationContext)
    }

    override suspend fun execute(command: YtDlpCommand): YtDlpResult =
        runInterruptible(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(command.sourceUrl).addCommands(command.arguments)
                val response = YoutubeDL.getInstance().execute(request, command.processId) { progress, eta, _ ->
                    command.onProgress?.invoke(progress.toInt().coerceIn(0, 100), eta.toString())
                }
                YtDlpResult(response.out, response.err)
            } catch (cancelled: YoutubeDL.CanceledException) {
                throw CancellationException("yt-dlp process cancelled").apply { initCause(cancelled) }
            } catch (interrupted: InterruptedException) {
                throw CancellationException("yt-dlp process interrupted").apply { initCause(interrupted) }
            } catch (failure: Exception) {
                throw YtDlpException(failure.message ?: "yt-dlp execution failed", failure)
            }
        }

    override suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) != null
    }

    override fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    override fun version(): String? = YoutubeDL.getInstance().versionName(context)
}

class DataStoreYtDlpUpdateStore(private val context: Context) : YtDlpUpdateStore {
    override suspend fun readLastUpdateMillis(): Long =
        context.ytDlpDataStore.data.first()[LAST_UPDATE] ?: 0L

    override suspend fun writeLastUpdateMillis(value: Long) {
        context.ytDlpDataStore.edit { it[LAST_UPDATE] = value }
    }

    private companion object {
        val LAST_UPDATE = longPreferencesKey("last_stable_update_millis")
    }
}

object YtDlpRuntimeProvider {
    @Volatile
    private var instance: YtDlpRuntime? = null

    fun get(context: Context): YtDlpRuntime = instance ?: synchronized(this) {
        instance ?: YtDlpRuntime(
            client = AndroidYtDlpClient(context.applicationContext),
            updateStore = DataStoreYtDlpUpdateStore(context.applicationContext),
        ).also { instance = it }
    }
}
