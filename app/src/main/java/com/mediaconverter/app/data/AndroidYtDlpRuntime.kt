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
        val installedVersion = runInterruptible {
            YoutubeDL.getInstance().execute(
                YoutubeDLRequest(emptyList()).addOption("--version"),
            ).out.trim()
        }
        check(installedVersion.isNotEmpty()) { "Unable to determine the installed yt-dlp version" }
        val preferences = context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
        if (preferences.getString("dlpVersion", null) != installedVersion) {
            // Android restores preferences, but not the executable in noBackupFilesDir.
            // The wrapper otherwise mistakes the bundled executable for the restored
            // version and reports ALREADY_UP_TO_DATE without installing an update.
            DataStoreYtDlpUpdateStore(context).writeState(YtDlpUpdateState())
            check(
                preferences.edit()
                    .putString("dlpVersion", installedVersion)
                    .putString("dlpVersionName", "yt-dlp $installedVersion")
                    .commit(),
            ) { "Unable to save the installed yt-dlp version" }
        }
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
        when (YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)) {
            YoutubeDL.UpdateStatus.DONE -> true
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> false
            null -> throw YtDlpException("yt-dlp update returned no status")
        }
    }

    override fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    override fun version(): String? = YoutubeDL.getInstance().versionName(context)
}

class DataStoreYtDlpUpdateStore(private val context: Context) : YtDlpUpdateStore {
    override suspend fun readState(): YtDlpUpdateState {
        val preferences = context.ytDlpDataStore.data.first()
        return YtDlpUpdateState(
            lastSuccessfulCheckMillis = preferences[LAST_SUCCESS] ?: 0L,
            lastFailedCheckMillis = preferences[LAST_FAILURE] ?: 0L,
        )
    }

    override suspend fun writeState(state: YtDlpUpdateState) {
        context.ytDlpDataStore.edit {
            it[LAST_SUCCESS] = state.lastSuccessfulCheckMillis
            it[LAST_FAILURE] = state.lastFailedCheckMillis
        }
    }

    private companion object {
        // The old last_stable_update_millis also recorded failures. Do not
        // migrate it as a success and carry its 24-hour outage lockout forward.
        val LAST_SUCCESS = longPreferencesKey("last_stable_check_success_millis")
        val LAST_FAILURE = longPreferencesKey("last_stable_check_failure_millis")
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
