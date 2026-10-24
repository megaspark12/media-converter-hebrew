package com.mediaconverter.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FfmpegStallWatchdogTest {
    @Test
    fun returnsTheSessionResultWhenItCompletesNormally() = runTest {
        val completion = CompletableDeferred<String>()
        var stalled = false
        launch { delay(1_000); completion.complete("done") }

        val result = awaitWithStallWatchdog(
            completion = completion,
            lastActivityMillis = { 0L },
            nowMillis = { currentTime },
            stallTimeoutMillis = 90_000,
            graceMillis = 10_000,
            onStall = { stalled = true },
            onAbandon = { "abandoned" },
        )

        assertEquals("done", result)
        assertFalse(stalled)
    }

    @Test
    fun steadyActivityNeverTriggersTheWatchdog() = runTest {
        val completion = CompletableDeferred<String>()
        var stalled = false
        // Activity is always "now": the encoder keeps reporting progress for ten minutes.
        launch { delay(600_000); completion.complete("long encode finished") }

        val result = awaitWithStallWatchdog(
            completion = completion,
            lastActivityMillis = { currentTime },
            nowMillis = { currentTime },
            stallTimeoutMillis = 90_000,
            graceMillis = 10_000,
            onStall = { stalled = true },
            onAbandon = { "abandoned" },
        )

        assertEquals("long encode finished", result)
        assertFalse(stalled)
    }

    @Test
    fun aStalledSessionIsCancelledAndItsCancelledResultIsReturned() = runTest {
        val completion = CompletableDeferred<String>()
        var stalledAt = -1L

        val result = async {
            awaitWithStallWatchdog(
                completion = completion,
                lastActivityMillis = { 0L },
                nowMillis = { currentTime },
                stallTimeoutMillis = 90_000,
                graceMillis = 10_000,
                onStall = { stalledAt = currentTime; completion.complete("cancelled by watchdog") },
                onAbandon = { "abandoned" },
            )
        }
        advanceTimeBy(89_999)
        runCurrent()
        assertFalse(result.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(90_000L, stalledAt)
        assertEquals("cancelled by watchdog", result.await())
    }

    @Test
    fun aSessionThatIgnoresCancellationIsAbandonedAfterTheGracePeriod() = runTest {
        val completion = CompletableDeferred<String>()
        var stalls = 0

        val result = async {
            awaitWithStallWatchdog(
                completion = completion,
                lastActivityMillis = { 0L },
                nowMillis = { currentTime },
                stallTimeoutMillis = 90_000,
                graceMillis = 10_000,
                onStall = { stalls += 1 },
                onAbandon = { "abandoned" },
            )
        }
        advanceTimeBy(99_999)
        runCurrent()
        assertEquals(1, stalls)
        assertFalse(result.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals("abandoned", result.await())
    }

    @Test
    fun activityDuringTheWaitPostponesTheStallDeadline() = runTest {
        val completion = CompletableDeferred<String>()
        var lastActivity = 0L
        var stalledAt = -1L

        val result = async {
            awaitWithStallWatchdog(
                completion = completion,
                lastActivityMillis = { lastActivity },
                nowMillis = { currentTime },
                stallTimeoutMillis = 90_000,
                graceMillis = 10_000,
                onStall = { stalledAt = currentTime; completion.complete("cancelled") },
                onAbandon = { "abandoned" },
            )
        }
        advanceTimeBy(60_000)
        lastActivity = currentTime
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse("activity at 60 s must push the deadline to 150 s", result.isCompleted)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(150_000L, stalledAt)
        assertEquals("cancelled", result.await())
    }
}
