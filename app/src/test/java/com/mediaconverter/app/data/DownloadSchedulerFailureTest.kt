package com.mediaconverter.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSchedulerFailureTest {
    @Test
    fun dispatchExceptionMarksInsertedDownloadFailedBeforeRethrowing() = runTest {
        var persistedError: String? = null

        val failure = runCatching {
            dispatchOrMarkFailed(
                downloadId = 42,
                performDispatch = { throw IllegalStateException("scheduler unavailable") },
                markFailed = { persistedError = it },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Unable to schedule download: scheduler unavailable", persistedError)
    }

    @Test
    fun foregroundEnqueueDoesNotCompleteUntilWorkManagerAcceptsOperation() = runTest {
        val operation = CompletableFuture<Unit>()

        val enqueueJob = backgroundScope.launch {
            enqueueForegroundWork { operation }
        }
        runCurrent()

        assertFalse(enqueueJob.isCompleted)
        operation.complete(Unit)
        enqueueJob.join()
        assertTrue(enqueueJob.isCompleted)
    }

    @Test
    fun cancellationAfterInsertDoesNotMarkAcceptedWorkAsFailed() = runTest {
        val operation = CompletableFuture<Unit>()
        val enqueueCalled = CompletableDeferred<Unit>()
        var persistedError: String? = null
        val dispatchJob = launch {
            dispatchOrMarkFailed(
                downloadId = 42,
                performDispatch = {
                    enqueueForegroundWork {
                        enqueueCalled.complete(Unit)
                        operation
                    }
                    true
                },
                markFailed = { persistedError = it },
            )
        }
        enqueueCalled.await()

        dispatchJob.cancel()
        operation.complete(Unit)
        dispatchJob.join()

        assertEquals(null, persistedError)
    }
}
