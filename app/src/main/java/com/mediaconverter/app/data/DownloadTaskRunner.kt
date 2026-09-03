package com.mediaconverter.app.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadTaskResult(val succeeded: Boolean, val shouldRetry: Boolean)

internal interface DownloadTaskStore {
    suspend fun getDownloadById(id: Long): com.mediaconverter.app.data.db.DownloadEntity?
    suspend fun updateProgress(id: Long, progress: Int, status: String): Int
    suspend fun markFailed(id: Long, error: String, errorCode: String): Int
    suspend fun markRetryPending(id: Long, error: String, errorCode: String): Int
    suspend fun markCompleted(id: Long, savedMedia: SavedMedia): Int
}

internal data class DownloadTaskDependencies(
    val repository: DownloadTaskStore,
    val convert: suspend (ConversionRequest, String, (Int, String) -> Unit) -> Result<SavedMedia>,
    val deleteSavedMedia: suspend (SavedMedia) -> Unit,
    val elapsedRealtime: () -> Long,
    val log: (String) -> Unit,
)

class DownloadTaskRunner internal constructor(
    private val dependencies: DownloadTaskDependencies,
) {
    constructor(context: Context) : this(
        DownloadTaskDependencies(
            repository = DownloadRepository(context),
            convert = ConversionEngine.create(context)::convert,
            deleteSavedMedia = { saved ->
                context.contentResolver.delete(Uri.parse(saved.contentUri), null, null)
            },
            elapsedRealtime = SystemClock::elapsedRealtime,
            log = { message -> Log.i(DIAGNOSTIC_TAG, message) },
        ),
    )

    private val repository = dependencies.repository

    suspend fun run(downloadId: Long, onProgress: (Int, String) -> Unit): DownloadTaskResult = coroutineScope {
        val record = repository.getDownloadById(downloadId) ?: return@coroutineScope DownloadTaskResult(false, false)
        if (record.status !in setOf("pending", "downloading")) {
            return@coroutineScope DownloadTaskResult(false, false)
        }
        val startedAt = System.currentTimeMillis()
        repository.updateProgress(downloadId, 0, "downloading")
        val progressLock = Any()
        var lastPersistedProgress = 0
        var lastPersistedAt = dependencies.elapsedRealtime()
        dependencies.log("request=$downloadId platform=${record.platform} phase=start ytDlp=${record.ytDlpVersion}")
        try {
            val source = MediaUrlParser.parse(record.url)
                ?: throw ConversionException(ConversionFailure.INVALID_URL, "Unsupported media URL")
            val format = OutputFormat.entries.firstOrNull { it.value == record.format } ?: OutputFormat.MP4
            val result = dependencies.convert(
                ConversionRequest(downloadId, source, format, record.quality),
                "${record.title}-$downloadId",
                { progress, eta ->
                    onProgress(progress, eta)
                    val now = dependencies.elapsedRealtime()
                    val persist = synchronized(progressLock) {
                        if (progress == 100 || progress >= lastPersistedProgress + 5 || now - lastPersistedAt >= 1_000L) {
                            lastPersistedProgress = progress
                            lastPersistedAt = now
                            true
                        } else {
                            false
                        }
                    }
                    if (persist) {
                        launch { repository.updateProgress(downloadId, progress, "downloading") }
                    }
                },
            )
            val saved = result.getOrThrow()
            commitPublishedMedia(
                markCompleted = { repository.markCompleted(downloadId, saved) },
                deleteSavedMedia = {
                    runCatching { dependencies.deleteSavedMedia(saved) }
                },
            )
            logTerminal(downloadId, record.platform, record.ytDlpVersion, startedAt, "success")
            DownloadTaskResult(true, false)
        } catch (cancelled: CancellationException) {
            logTerminal(downloadId, record.platform, record.ytDlpVersion, startedAt, ConversionFailure.CANCELLED.code)
            throw cancelled
        } catch (failure: Exception) {
            val typed = failure as? ConversionException
            val code = typed?.failure ?: ConversionFailure.DOWNLOAD_FAILED
            val transient = code == ConversionFailure.OFFLINE || code == ConversionFailure.DOWNLOAD_FAILED
            val shouldRetry = transient && record.retryCount < MAX_SCHEDULE_RETRIES
            if (shouldRetry) {
                repository.markRetryPending(downloadId, failure.message ?: "Download failed", code.code)
            } else {
                repository.markFailed(downloadId, failure.message ?: "Download failed", code.code)
            }
            logTerminal(downloadId, record.platform, record.ytDlpVersion, startedAt, code.code)
            DownloadTaskResult(false, shouldRetry)
        }
    }

    private fun logTerminal(id: Long, platform: String, version: String, startedAt: Long, code: String) {
        dependencies.log(
            "request=$id platform=$platform phase=complete ytDlp=$version elapsedMs=${System.currentTimeMillis() - startedAt} code=$code",
        )
    }

    companion object {
        const val DIAGNOSTIC_TAG = "ConversionDiagnostic"
        const val MAX_SCHEDULE_RETRIES = 3
    }
}

internal suspend fun commitPublishedMedia(
    markCompleted: suspend () -> Int,
    deleteSavedMedia: suspend () -> Unit,
) {
    try {
        if (markCompleted() != 1) {
            throw CancellationException("Download was cancelled before completion")
        }
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            runCatching { deleteSavedMedia() }
        }
        throw failure
    }
}
