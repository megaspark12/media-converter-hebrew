package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class YtDlpCommand(
    val processId: String,
    val sourceUrl: String,
    val arguments: List<String>,
    val onProgress: ((Int, String) -> Unit)? = null,
)

data class YtDlpResult(
    val stdout: String,
    val stderr: String,
)

class YtDlpException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface YtDlpClient {
    suspend fun initialize()
    suspend fun execute(command: YtDlpCommand): YtDlpResult
    suspend fun update(): Boolean
    fun cancel(processId: String)
    fun version(): String?
}

interface YtDlpUpdateStore {
    suspend fun readLastUpdateMillis(): Long
    suspend fun writeLastUpdateMillis(value: Long)
}

class YtDlpRuntime(
    private val client: YtDlpClient,
    private val updateStore: YtDlpUpdateStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var initialized = false

    suspend fun refreshIfDue() = mutex.withLock {
        ensureInitialized()
        updateIfDue()
    }

    suspend fun metadata(url: NormalizedMediaUrl, processId: String): YtDlpResult {
        val command = YtDlpCommand(
            processId = processId,
            sourceUrl = url.value,
            arguments = listOf(
                "--dump-single-json",
                "--skip-download",
                "--no-playlist",
                "--socket-timeout", "20",
                "--retries", "3",
            ),
        )

        return execute(command)
    }

    suspend fun execute(command: YtDlpCommand): YtDlpResult = mutex.withLock {
        ensureInitialized()
        try {
            executeCancellable(command)
        } catch (failure: Throwable) {
            if (failure is CancellationException || !YtDlpFailureClassifier.shouldUpdateAndRetry(failure)) {
                throw failure
            }
            try {
                updateIfDue()
            } catch (_: Exception) {
                // Keep the bundled executable and perform the single controlled retry.
            }
            executeCancellable(command)
        }
    }

    private suspend fun executeCancellable(command: YtDlpCommand): YtDlpResult {
        return try {
            client.execute(command)
        } catch (cancelled: CancellationException) {
            client.cancel(command.processId)
            throw cancelled
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        client.initialize()
        initialized = true
    }

    private suspend fun updateIfDue() {
        val now = nowMillis()
        val lastUpdate = updateStore.readLastUpdateMillis()
        if (now - lastUpdate < UPDATE_INTERVAL_MILLIS) return
        try {
            client.update()
        } finally {
            // Throttle checks, not only successful replacements. A temporary update
            // outage must not cause every request or app launch to hit the updater.
            updateStore.writeLastUpdateMillis(now)
        }
    }

    companion object {
        const val UPDATE_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

object YtDlpFailureClassifier {
    private val permanentMarkers = listOf(
        "private video",
        "login required",
        "sign in",
        "drm",
        "geo-restricted",
        "not available in your country",
        "video has been removed",
    )
    private val providerChangeMarkers = listOf(
        "http error 403",
        "forbidden",
        "requested format is not available",
        "no video formats found",
        "not embeddable",
        "javascript runtime",
        "signature extraction failed",
        "nsig extraction failed",
    )

    fun shouldUpdateAndRetry(failure: Throwable): Boolean {
        val message = generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        if (permanentMarkers.any(message::contains)) return false
        return providerChangeMarkers.any(message::contains)
    }
}
