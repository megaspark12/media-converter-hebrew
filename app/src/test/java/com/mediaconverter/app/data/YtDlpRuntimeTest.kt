package com.mediaconverter.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
    fun executionIsSerializedAcrossConcurrentRequests() = runTest {
        val client = FakeYtDlpClient(executionDelayMillis = 100)
        val runtime = YtDlpRuntime(client, InMemoryUpdateStore(), nowMillis = { 10L })

        val first = async { runtime.metadata(youtubeUrl("one"), "one") }
        val second = async { runtime.metadata(youtubeUrl("two"), "two") }
        first.await()
        second.await()

        assertEquals(1, client.maxActiveExecutions.get())
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
    private val started: CompletableDeferred<Unit>? = null,
    var updateFailure: Throwable? = null,
    private val updateResult: Boolean = true,
) : YtDlpClient {
    val commands = mutableListOf<YtDlpCommand>()
    val cancelledProcessIds = mutableListOf<String>()
    val maxActiveExecutions = AtomicInteger()
    private val activeExecutions = AtomicInteger()
    var updateCount = 0

    override suspend fun initialize() = Unit

    override suspend fun execute(command: YtDlpCommand): YtDlpResult {
        commands += command
        val active = activeExecutions.incrementAndGet()
        maxActiveExecutions.updateAndGet { maxOf(it, active) }
        started?.complete(Unit)
        return try {
            if (executionDelayMillis > 0) delay(executionDelayMillis)
            if (outcomes.isEmpty()) YtDlpResult("{\"title\":\"ok\"}", "")
            else outcomes.removeFirst().getOrThrow()
        } finally {
            activeExecutions.decrementAndGet()
        }
    }

    override suspend fun update(): Boolean {
        updateCount += 1
        updateFailure?.let { throw it }
        return updateResult
    }

    override fun cancel(processId: String) {
        cancelledProcessIds += processId
    }

    override fun version(): String? = "test"
}
