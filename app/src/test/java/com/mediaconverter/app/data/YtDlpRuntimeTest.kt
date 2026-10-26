package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class YtDlpRuntimeTest {
    @Test
    fun metadataCommandIsBoundedAndTransportSecurityRemainsEnabled() = runTest {
        val client = FakeYtDlpClient()
        val runtime = YtDlpRuntime(client, InMemoryUpdateStore(), nowMillis = { 10L })

        runtime.metadata(NormalizedMediaUrl("https://youtu.be/abc", SupportedPlatform.YOUTUBE), "request-1")

        val command = client.commands.single()
        assertTrue(command.arguments.containsAll(listOf("--dump-single-json", "--skip-download", "--no-playlist")))
        assertEquals("20", command.valueAfter("--socket-timeout"))
        assertEquals("3", command.valueAfter("--retries"))
        assertFalse(command.arguments.contains("--no-check-certificates"))
        assertEquals("request-1", command.processId)
    }

    @Test
    fun metadataResolvesWhileDownloadIsStillRunning() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = FakeYtDlpClient(
            started = started,
            delays = mapOf("download-1" to 10_000L, "meta" to 100L),
        )
        val runtime = YtDlpRuntime(client, InMemoryUpdateStore(), nowMillis = { 10L })
        val download = async { runtime.execute(downloadCommand("download-1")) }
        started.await()

        val info = async { runtime.metadata(youtubeUrl("second"), "meta") }
        advanceTimeBy(1_000)

        assertTrue("metadata must not wait behind the running download", info.isCompleted)
        assertTrue(download.isActive)
        assertEquals("{\"title\":\"ok\"}", info.await().stdout)
        assertEquals(2, client.maxActiveExecutions.get())
        download.await()
    }

    @Test
    fun binaryUpdateWaitsForRunningExecutionToFinish() = runTest {
        val started = CompletableDeferred<Unit>()
        val store = InMemoryUpdateStore()
        val client = FakeYtDlpClient(started = started, executionDelayMillis = 10_000)
        val runtime = YtDlpRuntime(client, store, nowMillis = { 90_000_000L })
        val download = async { runtime.execute(downloadCommand("download-1")) }
        started.await()

        val refresh = async { runtime.refreshIfDue() }
        advanceTimeBy(5_000)
        assertEquals(0, client.updateCount)
        assertTrue(download.isActive)

        download.await()
        refresh.await()
        assertEquals(1, client.updateCount)
        assertEquals(0, client.activeExecutionsAtLastUpdate)
        assertEquals(90_000_000L, store.lastUpdateMillis)
    }

    @Test
    fun pendingBinaryUpdateRunsBeforeNewlyRequestedExecutions() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = FakeYtDlpClient(started = started, delays = mapOf("download-1" to 10_000L))
        val runtime = YtDlpRuntime(client, InMemoryUpdateStore(), nowMillis = { 90_000_000L })
        val download = async { runtime.execute(downloadCommand("download-1")) }
        started.await()
        val refresh = async { runtime.refreshIfDue() }
        runCurrent()

        val info = async { runtime.metadata(youtubeUrl("later"), "meta") }
        advanceTimeBy(5_000)
        assertEquals(listOf("download-1"), client.commands.map { it.processId })

        download.await()
        refresh.await()
        info.await()
        assertEquals(listOf("download-1", "meta"), client.commands.map { it.processId })
        assertEquals(1, client.updateCount)
        assertEquals(0, client.activeExecutionsAtLastUpdate)
    }

    @Test
    fun providerFailureUpdateWaitsForRunningDownloadBeforeRetrying() = runTest {
        val started = CompletableDeferred<Unit>()
        val store = InMemoryUpdateStore()
        val client = FakeYtDlpClient(
            started = started,
            delays = mapOf("download-1" to 10_000L),
            outcomes = ArrayDeque(
                listOf(
                    Result.success(YtDlpResult("download", "")),
                    Result.failure(YtDlpException("HTTP Error 403: Forbidden")),
                    Result.success(YtDlpResult("{\"title\":\"ok\"}", "")),
                ),
            ),
        )
        val runtime = YtDlpRuntime(client, store, nowMillis = { 90_000_000L })
        val download = async { runtime.execute(downloadCommand("download-1")) }
        started.await()

        val info = async { runtime.metadata(youtubeUrl("retry"), "meta") }
        advanceTimeBy(5_000)
        assertEquals(listOf("download-1", "meta"), client.commands.map { it.processId })
        assertEquals(0, client.updateCount)
        assertTrue(info.isActive)

        assertEquals("download", download.await().stdout)
        assertEquals("{\"title\":\"ok\"}", info.await().stdout)
        assertEquals(1, client.updateCount)
        assertEquals(0, client.activeExecutionsAtLastUpdate)
        assertEquals(listOf("download-1", "meta", "meta"), client.commands.map { it.processId })
        assertEquals(90_000_000L, store.lastUpdateMillis)
    }

    @Test
    fun providerFailureRetryDoesNotWaitForDownloadWhenNoUpdateIsDue() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = FakeYtDlpClient(
            started = started,
            delays = mapOf("download-1" to 10_000L),
            outcomes = ArrayDeque(
                listOf(
                    Result.success(YtDlpResult("download", "")),
                    Result.failure(YtDlpException("HTTP Error 403: Forbidden")),
                    Result.success(YtDlpResult("{\"title\":\"ok\"}", "")),
                ),
            ),
        )
        // Last update check succeeded moments ago, so the throttle forbids another one.
        val store = InMemoryUpdateStore().apply { writeState(YtDlpUpdateState(lastSuccessfulCheckMillis = 5L)) }
        val runtime = YtDlpRuntime(client, store, nowMillis = { 10L })
        val download = async { runtime.execute(downloadCommand("download-1")) }
        started.await()

        val info = async { runtime.metadata(youtubeUrl("retry"), "meta") }
        advanceTimeBy(1_000)

        assertTrue("retry must not queue behind the download when no update can happen", info.isCompleted)
        assertTrue(download.isActive)
        assertEquals("{\"title\":\"ok\"}", info.await().stdout)
        assertEquals(listOf("download-1", "meta", "meta"), client.commands.map { it.processId })
        assertEquals(0, client.updateCount)
        download.await()
    }

    @Test
    fun cancellationWhileHeldBackByPendingUpdateNeverStartsAProcess() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = FakeYtDlpClient(started = started, delays = mapOf("download-1" to 10_000L))
        val runtime = YtDlpRuntime(client, InMemoryUpdateStore(), nowMillis = { 90_000_000L })
        val download = async { runtime.execute(downloadCommand("download-1")) }
        started.await()
        val refresh = async { runtime.refreshIfDue() }
        runCurrent()
        val info = async { runtime.metadata(youtubeUrl("held"), "meta") }
        advanceTimeBy(1_000)
        assertEquals(listOf("download-1"), client.commands.map { it.processId })

        info.cancel()
        download.await()
        refresh.await()

        assertTrue(info.isCancelled)
        assertEquals(listOf("download-1"), client.commands.map { it.processId })
        assertTrue("no process was started, so none may be cancelled", client.cancelledProcessIds.isEmpty())
        assertEquals(1, client.updateCount)
        // The gate must be usable afterwards: a cancelled waiter may not leave a permit behind.
        assertEquals("{\"title\":\"ok\"}", runtime.metadata(youtubeUrl("after"), "after").stdout)
    }

    @Test
    fun cancellationWhileWaitingForUpdateSlotDoesNotRetry() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = FakeYtDlpClient(
            started = started,
            delays = mapOf("download-1" to 10_000L),
            outcomes = ArrayDeque(
                listOf(
                    Result.success(YtDlpResult("download", "")),
                    Result.failure(YtDlpException("HTTP Error 403: Forbidden")),
                ),
            ),
        )
        val runtime = YtDlpRuntime(client, InMemoryUpdateStore(), nowMillis = { 90_000_000L })
        val download = async { runtime.execute(downloadCommand("download-1")) }
        started.await()
        val info = async { runtime.metadata(youtubeUrl("retry"), "meta") }
        advanceTimeBy(1_000)
        assertEquals(listOf("download-1", "meta"), client.commands.map { it.processId })

        info.cancel()
        assertEquals("download", download.await().stdout)

        assertTrue(info.isCancelled)
        assertEquals(listOf("download-1", "meta"), client.commands.map { it.processId })
        assertEquals(0, client.updateCount)
        // A cancelled exclusive waiter must release its hold on the gate, or no lookup ever runs again.
        assertEquals("{\"title\":\"ok\"}", runtime.metadata(youtubeUrl("after"), "after").stdout)
        assertEquals(listOf("download-1", "meta", "after"), client.commands.map { it.processId })
    }

    @Test
    fun providerFailureUpdatesOnceAndRetriesOnce() = runTest {
        val store = InMemoryUpdateStore()
        val client = FakeYtDlpClient(
            outcomes = ArrayDeque(
                listOf(
                    Result.failure(YtDlpException("HTTP Error 403: Forbidden")),
                    Result.success(YtDlpResult("{\"title\":\"ok\"}", "")),
                ),
            ),
        )
        val runtime = YtDlpRuntime(client, store, nowMillis = { 86_500_000L })

        val result = runtime.metadata(youtubeUrl("retry"), "retry")

        assertEquals("{\"title\":\"ok\"}", result.stdout)
        assertEquals(1, client.updateCount)
        assertEquals(2, client.commands.size)
        assertEquals(86_500_000L, store.lastUpdateMillis)
    }

    @Test
    fun failedUpdateKeepsBundledExecutableAndStillRetriesOnce() = runTest {
        val store = InMemoryUpdateStore()
        val client = FakeYtDlpClient(
            outcomes = ArrayDeque(
                listOf(
                    Result.failure(YtDlpException("HTTP Error 403: Forbidden")),
                    Result.success(YtDlpResult("bundled-retry", "")),
                ),
            ),
            updateFailure = YtDlpException("update server unavailable"),
        )
        val runtime = YtDlpRuntime(client, store, nowMillis = { 90_000_000L })

        assertEquals("bundled-retry", runtime.metadata(youtubeUrl("retry"), "retry").stdout)
        assertEquals(2, client.commands.size)
        assertEquals(0L, store.lastUpdateMillis)

        runCatching { runtime.refreshIfDue() }
        assertEquals(1, client.updateCount)
    }

    @Test
    fun failedStartupUpdateCanRecoverAfterFiveMinutesAcrossRuntimeRecreation() = runTest {
        val store = InMemoryUpdateStore()
        val client = FakeYtDlpClient(updateFailure = YtDlpException("update server unavailable"))
        val first = YtDlpRuntime(client, store, nowMillis = { 90_000_000L })
        assertTrue(runCatching { first.refreshIfDue() }.isFailure)

        client.updateFailure = null
        YtDlpRuntime(client, store, nowMillis = { 90_299_999L }).refreshIfDue()
        assertEquals(1, client.updateCount)
        YtDlpRuntime(client, store, nowMillis = { 90_300_000L }).refreshIfDue()
        assertEquals(2, client.updateCount)
        assertEquals(90_300_000L, store.lastUpdateMillis)
    }

    @Test
    fun cancellingARecoveryUpdateDoesNotRetryTheDownloadOrThrottleFutureChecks() = runTest {
        val cancelled = CancellationException("cancel update")
        val store = InMemoryUpdateStore()
        val client = FakeYtDlpClient(
            outcomes = ArrayDeque(listOf(Result.failure(YtDlpException("HTTP Error 403: Forbidden")))),
            updateFailure = cancelled,
        )
        val runtime = YtDlpRuntime(client, store, nowMillis = { 90_000_000L })

        val thrown = runCatching { runtime.metadata(youtubeUrl("cancel"), "cancel") }.exceptionOrNull()

        assertTrue(thrown === cancelled)
        assertEquals(1, client.commands.size)
        assertEquals(0L, store.lastUpdateMillis)
        client.updateFailure = null
        runtime.refreshIfDue()
        assertEquals(2, client.updateCount)
    }

    @Test
    fun successfulChecksIncludingNoNewReleaseAreThrottledForOneDay() = runTest {
        val store = InMemoryUpdateStore()
        val client = FakeYtDlpClient(updateResult = false)
        YtDlpRuntime(client, store, nowMillis = { 90_000_000L }).refreshIfDue()
        YtDlpRuntime(client, store, nowMillis = { 176_399_999L }).refreshIfDue()
        assertEquals(1, client.updateCount)
        YtDlpRuntime(client, store, nowMillis = { 176_400_000L }).refreshIfDue()
        assertEquals(2, client.updateCount)
    }

    @Test
    fun firstCheckAndClockRollbackDoNotSuppressUpdates() = runTest {
        val store = InMemoryUpdateStore()
        val client = FakeYtDlpClient()
        YtDlpRuntime(client, store, nowMillis = { 10L }).refreshIfDue()
        assertEquals(1, client.updateCount)
        YtDlpRuntime(client, store, nowMillis = { 5L }).refreshIfDue()
        assertEquals(2, client.updateCount)
    }

    @Test
    fun permanentFailuresAndFatalErrorsDoNotTriggerProviderRecovery() = runTest {
        for (failure in listOf(YtDlpException("Sign in to view private video"), OutOfMemoryError("HTTP Error 403"))) {
            val client = FakeYtDlpClient(outcomes = ArrayDeque(listOf(Result.failure(failure))))
            val runtime = YtDlpRuntime(client, InMemoryUpdateStore(), nowMillis = { 90_000_000L })
            assertTrue(runCatching { runtime.metadata(youtubeUrl("private"), "private") }.exceptionOrNull() === failure)
            assertEquals(1, client.commands.size)
            assertEquals(0, client.updateCount)
        }
    }

    @Test
    fun cancellationTerminatesTheMatchingProcess() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = FakeYtDlpClient(started = started, executionDelayMillis = 10_000)
        val runtime = YtDlpRuntime(client, InMemoryUpdateStore(), nowMillis = { 10L })
        val job = async { runtime.metadata(youtubeUrl("cancel"), "cancel-id") }
        started.await()

        job.cancel()
        runCatching { job.await() }

        assertEquals(listOf("cancel-id"), client.cancelledProcessIds)
    }

    private fun youtubeUrl(id: String) =
        NormalizedMediaUrl("https://youtu.be/$id", SupportedPlatform.YOUTUBE)

    private fun downloadCommand(processId: String) = YtDlpCommand(
        processId = processId,
        sourceUrl = "https://youtu.be/long-video",
        arguments = listOf("-f", "best", "-o", "source.%(ext)s"),
    )
}

private fun YtDlpCommand.valueAfter(option: String): String? =
    arguments.getOrNull(arguments.indexOf(option) + 1)

private class InMemoryUpdateStore : YtDlpUpdateStore {
    private var state = YtDlpUpdateState()
    val lastUpdateMillis get() = state.lastSuccessfulCheckMillis
    override suspend fun readState() = state
    override suspend fun writeState(state: YtDlpUpdateState) { this.state = state }
}

private class FakeYtDlpClient(
    private val outcomes: ArrayDeque<Result<YtDlpResult>> = ArrayDeque(),
    private val executionDelayMillis: Long = 0,
    private val delays: Map<String, Long> = emptyMap(),
    private val started: CompletableDeferred<Unit>? = null,
    var updateFailure: Throwable? = null,
    private val updateResult: Boolean = true,
) : YtDlpClient {
    val commands = mutableListOf<YtDlpCommand>()
    val cancelledProcessIds = mutableListOf<String>()
    val maxActiveExecutions = AtomicInteger()
    private val activeExecutions = AtomicInteger()
    var updateCount = 0
    var activeExecutionsAtLastUpdate = -1

    override suspend fun initialize() = Unit

    override suspend fun execute(command: YtDlpCommand): YtDlpResult {
        commands += command
        // Outcomes are consumed in start order so concurrent executions stay deterministic.
        val outcome = outcomes.removeFirstOrNull() ?: Result.success(YtDlpResult("{\"title\":\"ok\"}", ""))
        val active = activeExecutions.incrementAndGet()
        maxActiveExecutions.updateAndGet { maxOf(it, active) }
        started?.complete(Unit)
        return try {
            val delayMillis = delays[command.processId] ?: executionDelayMillis
            if (delayMillis > 0) delay(delayMillis)
            outcome.getOrThrow()
        } finally {
            activeExecutions.decrementAndGet()
        }
    }

    override suspend fun update(): Boolean {
        updateCount += 1
        activeExecutionsAtLastUpdate = activeExecutions.get()
        updateFailure?.let { throw it }
        return updateResult
    }

    override fun cancel(processId: String) {
        cancelledProcessIds += processId
    }

    override fun version(): String? = "test"
}
