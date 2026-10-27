package com.mediaconverter.app.data

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

private val Context.ytDlpDataStore by preferencesDataStore(name = "yt_dlp_runtime")

class AndroidYtDlpClient(
    private val context: Context,
    private val updateChannel: YoutubeDL.UpdateChannel = YoutubeDL.UpdateChannel.STABLE,
) : YtDlpClient {
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        YoutubeDL.getInstance().init(context.applicationContext)
        val store = DataStoreYtDlpUpdateStore(context)
        val installedVersion = resolveInstalledYtDlpVersion(
            fingerprint = executableFingerprint(),
            remembered = store.readInstalledVersion(),
            probe = {
                runInterruptible {
                    YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList()).addOption("--version")).out
                }
            },
            remember = store::writeInstalledVersion,
        )
        val preferences = context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
        if (preferences.getString("dlpVersion", null) != installedVersion) {
            // Android restores preferences, but not the executable in noBackupFilesDir.
            // The wrapper otherwise mistakes the bundled executable for the restored
            // version and reports ALREADY_UP_TO_DATE without installing an update.
            store.writeState(YtDlpUpdateState())
            check(
                preferences.edit()
                    .putString("dlpVersion", installedVersion)
                    .putString("dlpVersionName", "yt-dlp $installedVersion")
                    .commit(),
            ) { "Unable to save the installed yt-dlp version" }
        }
    }

    /**
     * youtubedl-android unpacks yt-dlp to this file, replaces it on update, and re-extracts the
     * bundled copy after a restore (the directory is not backed up). Its size and modification
     * time therefore change exactly when the installed version can.
     */
    private fun executableFingerprint(): String? {
        val executable = File(context.applicationContext.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        if (!executable.isFile) return null
        return "${executable.length()}:${executable.lastModified()}"
    }

    override suspend fun execute(command: YtDlpCommand): YtDlpResult =
        runInterruptible(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(command.sourceUrl).addCommands(command.arguments)
                val response = YoutubeDL.getInstance().execute(request, command.processId) { progress, eta, _ ->
                    command.onProgress?.invoke(progress.toInt().coerceIn(0, 100), formatEtaSeconds(eta))
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

    override suspend fun update(): Boolean = runInterruptible(Dispatchers.IO) {
        val status = withYtDlpUpdateMetadata(context.cacheDir, updateChannel.apiUrl) { url ->
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel(url))
        }
        when (status) {
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

    internal suspend fun readInstalledVersion(): InstalledYtDlpVersion? {
        val preferences = context.ytDlpDataStore.data.first()
        val fingerprint = preferences[INSTALLED_FINGERPRINT] ?: return null
        val version = preferences[INSTALLED_VERSION] ?: return null
        return InstalledYtDlpVersion(fingerprint, version)
    }

    internal suspend fun writeInstalledVersion(installed: InstalledYtDlpVersion) {
        context.ytDlpDataStore.edit {
            it[INSTALLED_FINGERPRINT] = installed.fingerprint
            it[INSTALLED_VERSION] = installed.version
        }
    }

    private companion object {
        // The old last_stable_update_millis also recorded failures. Do not
        // migrate it as a success and carry its 24-hour outage lockout forward.
        val LAST_SUCCESS = longPreferencesKey("last_stable_check_success_millis")
        val LAST_FAILURE = longPreferencesKey("last_stable_check_failure_millis")
        // This store is excluded from backup, so a restored app probes its freshly
        // extracted executable instead of trusting another installation's answer.
        val INSTALLED_FINGERPRINT = stringPreferencesKey("installed_executable_fingerprint")
        val INSTALLED_VERSION = stringPreferencesKey("installed_executable_version")
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
