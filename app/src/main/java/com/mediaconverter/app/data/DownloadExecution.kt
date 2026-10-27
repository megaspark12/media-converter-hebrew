package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** How a backend reports the finished attempt to its scheduler. */
internal enum class DownloadDisposition { COMPLETED, RETRY, FAILED }

/** The notices a backend posts for one download; [DownloadNotifications] implements it. */
internal interface DownloadOutcomePresenter {
    fun showComplete(savedMedia: SavedMedia?)
    fun showFailed()
    fun dismissProgress()
}

/** Progress notices are posted every five percent and at the end. */
internal fun shouldPostProgress(progress: Int): Boolean = progress == 100 || progress % 5 == 0

/**
 * Runs one download attempt and turns its outcome into notices and a disposition. Shared by the
 * WorkManager backend (Android 8-13) and the user-initiated job backend (Android 14+) so both
 * react to an outcome, and to a crash inside the attempt, in the same way.
 *
 * An ordinary exception from [attempt] (a database error, a rejected notification) marks the
 * app's own row failed and is reported as [DownloadDisposition.FAILED]; it never escapes. The job
 * service used to let it propagate out of its coroutine, which crashed the process and left the
 * row "downloading" forever. Cancellation and fatal errors propagate untouched.
 */
internal suspend fun runDownloadAttempt(
    canRetry: Boolean,
    attempt: suspend () -> DownloadTaskResult,
    markFailed: suspend (error: String, errorCode: String) -> Unit,
    presenter: DownloadOutcomePresenter,
    log: (String, Throwable) -> Unit,
): DownloadDisposition {
    val outcome = try {
        attempt()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        withContext(NonCancellable) {
            recordFailure(
                markFailed,
                failure.message ?: "Unable to run download",
                ConversionFailureMapper.fromThrowable(failure).code,
                log,
            )
        }
        present(log) { presenter.showFailed() }
        return DownloadDisposition.FAILED
    }
    return when {
        outcome.succeeded -> {
            present(log) { presenter.showComplete(outcome.savedMedia) }
            DownloadDisposition.COMPLETED
        }
        outcome.shouldRetry && canRetry -> {
            present(log) { presenter.dismissProgress() }
            DownloadDisposition.RETRY
        }
        else -> {
            if (outcome.shouldRetry) {
                recordFailure(markFailed, "Retry limit reached", ConversionFailure.DOWNLOAD_FAILED.code, log)
            }
            present(log) { presenter.showFailed() }
            DownloadDisposition.FAILED
        }
    }
}

private suspend fun recordFailure(
    markFailed: suspend (String, String) -> Unit,
    error: String,
    errorCode: String,
    log: (String, Throwable) -> Unit,
) {
    try {
        markFailed(error, errorCode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // The attempt already failed; a database that is still unavailable must not turn that
        // into a crash. The row stays as the runner left it and the backend still finishes.
        log("Unable to record the failed download", failure)
    }
}

private inline fun present(log: (String, Throwable) -> Unit, notice: () -> Unit) {
    try {
        notice()
    } catch (failure: Exception) {
        // A notification problem must not hide the persisted outcome.
        log("Unable to post the download notice", failure)
    }
}
