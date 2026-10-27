package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attempt wrapper is shared by the WorkManager backend (Android 8-13) and the user-initiated
 * job backend (Android 14+), so both react to a download's outcome, and to a crash inside the
 * attempt, in exactly the same way.
 */
class DownloadAttemptTest {
    @Test
    fun successShowsTheCompletionWithTheSavedMedia() = runTest {
        val saved = SavedMedia("content://media/9", "clip.mp4", "video/mp4", 10, 1_000)
        val presenter = RecordingPresenter()

        val disposition = runDownloadAttempt(
            canRetry = true,
            attempt = { DownloadTaskResult(succeeded = true, shouldRetry = false, savedMedia = saved) },
            markFailed = { _, _ -> throw AssertionError("A success must not be marked failed") },
            presenter = presenter,
            log = { _, _ -> },
        )

        assertEquals(DownloadDisposition.COMPLETED, disposition)
        assertSame(saved, presenter.completed)
        assertFalse(presenter.failed)
    }

    @Test
    fun retryableFailureIsRetriedWhileAttemptsRemain() = runTest {
        val presenter = RecordingPresenter()

        val disposition = runDownloadAttempt(
            canRetry = true,
            attempt = { DownloadTaskResult(succeeded = false, shouldRetry = true) },
            markFailed = { _, _ -> throw AssertionError("A pending retry must stay pending") },
            presenter = presenter,
            log = { _, _ -> },
        )

        assertEquals(DownloadDisposition.RETRY, disposition)
        assertTrue(presenter.progressDismissed)
        assertFalse(presenter.failed)
    }

    @Test
    fun retryableFailurePastTheLimitIsMarkedFailed() = runTest {
        val presenter = RecordingPresenter()
        var marked: Pair<String, String>? = null

        val disposition = runDownloadAttempt(
            canRetry = false,
            attempt = { DownloadTaskResult(succeeded = false, shouldRetry = true) },
            markFailed = { error, code -> marked = error to code },
            presenter = presenter,
            log = { _, _ -> },
        )

        assertEquals(DownloadDisposition.FAILED, disposition)
        assertEquals("Retry limit reached" to "download_failed", marked)
        assertTrue(presenter.failed)
    }

    @Test
    fun permanentFailureShowsTheFailureWithoutRewritingTheRow() = runTest {
        val presenter = RecordingPresenter()

        val disposition = runDownloadAttempt(
            canRetry = true,
            attempt = { DownloadTaskResult(succeeded = false, shouldRetry = false) },
            markFailed = { _, _ -> throw AssertionError("The runner already recorded this failure") },
            presenter = presenter,
            log = { _, _ -> },
        )

        assertEquals(DownloadDisposition.FAILED, disposition)
        assertTrue(presenter.failed)
    }

    @Test
    fun anOrdinaryExceptionMarksTheRowFailedAndShowsTheFailure() = runTest {
        // A database error inside the attempt previously escaped the Android 14+ job service
        // uncaught: the process crashed and the row stayed "downloading" forever.
        val presenter = RecordingPresenter()
        var marked: Pair<String, String>? = null

        val disposition = runDownloadAttempt(
            canRetry = true,
            attempt = { throw IllegalStateException("database unavailable") },
            markFailed = { error, code -> marked = error to code },
            presenter = presenter,
            log = { _, _ -> },
        )

        assertEquals(DownloadDisposition.FAILED, disposition)
        assertEquals("database unavailable" to "download_failed", marked)
        assertTrue(presenter.failed)
    }

    @Test
    fun cancellationPropagatesWithoutMarkingTheRow() = runTest {
        val cancellation = CancellationException("stopped")
        var marked = false

        val thrown = runCatching {
            runDownloadAttempt(
                canRetry = true,
                attempt = { throw cancellation },
                markFailed = { _, _ -> marked = true },
                presenter = RecordingPresenter(),
                log = { _, _ -> },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertFalse(marked)
    }

    @Test
    fun fatalVmErrorsPropagate() = runTest {
        val fatal = OutOfMemoryError("fatal")

        val thrown = runCatching {
            runDownloadAttempt(
                canRetry = true,
                attempt = { throw fatal },
                markFailed = { _, _ -> },
                presenter = RecordingPresenter(),
                log = { _, _ -> },
            )
        }.exceptionOrNull()

        assertSame(fatal, thrown)
    }

    @Test
    fun aFailingRowUpdateIsLoggedInsteadOfEscaping() = runTest {
        var logged: Throwable? = null

        val disposition = runDownloadAttempt(
            canRetry = true,
            attempt = { throw IllegalStateException("database unavailable") },
            markFailed = { _, _ -> throw IllegalStateException("still unavailable") },
            presenter = RecordingPresenter(),
            log = { _, failure -> logged = failure },
        )

        assertEquals(DownloadDisposition.FAILED, disposition)
        assertEquals("still unavailable", logged?.message)
    }

    @Test
    fun progressIsPostedEveryFivePercentAndAtTheEnd() {
        val posted = (0..100).filter(::shouldPostProgress)

        assertEquals((0..100 step 5).toList(), posted)
        assertTrue(shouldPostProgress(100))
        assertFalse(shouldPostProgress(99))
    }
}

private class RecordingPresenter : DownloadOutcomePresenter {
    var completed: SavedMedia? = null
    var failed = false
    var progressDismissed = false

    override fun showComplete(savedMedia: SavedMedia?) {
        assertNull("Completion may be shown only once", completed)
        completed = savedMedia
    }

    override fun showFailed() {
        failed = true
    }

    override fun dismissProgress() {
        progressDismissed = true
    }
}
