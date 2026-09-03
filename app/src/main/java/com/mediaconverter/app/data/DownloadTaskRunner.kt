package com.mediaconverter.app.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class DownloadTaskResult(val succeeded: Boolean, val shouldRetry: Boolean)

class DownloadTaskRunner(private val context: Context) {
    private val repository = DownloadRepository(context)
    private val conversionEngine = ConversionEngine.create(context)

    suspend fun run(downloadId: Long, onProgress: (Int, String) -> Unit): DownloadTaskResult = coroutineScope {
        val record = repository.getDownloadById(downloadId) ?: return@coroutineScope DownloadTaskResult(false, false)
        if (record.status !in setOf("pending", "downloading")) {
            return@coroutineScope DownloadTaskResult(false, false)
        }
        val startedAt = System.currentTimeMillis()
        repository.updateProgress(downloadId, 0, "downloading")
        val progressLock = Any()
        var lastPersistedProgress = 0
        var lastPersistedAt = SystemClock.elapsedRealtime()
        Log.i(DIAGNOSTIC_TAG, "request=$downloadId platform=${record.platform} phase=start ytDlp=${record.ytDlpVersion}")
        try {
            val source = MediaUrlParser.parse(record.url)
                ?: throw ConversionException(ConversionFailure.INVALID_URL, "Unsupported media URL")
            val format = OutputFormat.entries.firstOrNull { it.value == record.format } ?: OutputFormat.MP4
            val result = conversionEngine.convert(
                request = ConversionRequest(downloadId, source, format, record.quality),
                displayName = "${record.title}-$downloadId",
                onProgress = { progress, eta ->
                    onProgress(progress, eta)
                    val now = SystemClock.elapsedRealtime()
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
            if (repository.markCompleted(downloadId, saved) != 1) {
                runCatching { context.contentResolver.delete(Uri.parse(saved.contentUri), null, null) }
                throw CancellationException("Download was cancelled before completion")
            }
            logTerminal(downloadId, record.platform, record.ytDlpVersion, startedAt, "success")
            DownloadTaskResult(true, false)
        } catch (cancelled: CancellationException) {
            logTerminal(downloadId, record.platform, record.ytDlpVersion, startedAt, ConversionFailure.CANCELLED.code)
            throw cancelled
        } catch (failure: Throwable) {
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
        Log.i(
            DIAGNOSTIC_TAG,
            "request=$id platform=$platform phase=complete ytDlp=$version elapsedMs=${System.currentTimeMillis() - startedAt} code=$code",
        )
    }

    companion object {
        const val DIAGNOSTIC_TAG = "ConversionDiagnostic"
        const val MAX_SCHEDULE_RETRIES = 3
    }
}
