package com.mediaconverter.app.data

import kotlinx.coroutines.CompletableDeferred
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
        assertEquals(90_000_000L, store.lastUpdateMillis)

        runCatching { runtime.refreshIfDue() }
        assertEquals(1, client.updateCount)
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
    var lastUpdateMillis = 0L
    override suspend fun readLastUpdateMillis(): Long = lastUpdateMillis
    override suspend fun writeLastUpdateMillis(value: Long) {
        lastUpdateMillis = value
    }
}

private class FakeYtDlpClient(
    private val outcomes: ArrayDeque<Result<YtDlpResult>> = ArrayDeque(),
    private val executionDelayMillis: Long = 0,
    private val started: CompletableDeferred<Unit>? = null,
    private val updateFailure: Throwable? = null,
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
        return true
    }

    override fun cancel(processId: String) {
        cancelledProcessIds += processId
    }

    override fun version(): String? = "test"
}
