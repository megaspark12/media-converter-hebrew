package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class YtDlpCommand(
    val processId: String,
    val sourceUrl: String,
    val arguments: List<String>,
    val onProgress: ((Int, String) -> Unit)? = null,
    val beforeRetry: (() -> Unit)? = null,
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

data class YtDlpUpdateState(
    val lastSuccessfulCheckMillis: Long = 0L,
    val lastFailedCheckMillis: Long = 0L,
)

interface YtDlpUpdateStore {
    suspend fun readState(): YtDlpUpdateState
    suspend fun writeState(state: YtDlpUpdateState)
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!YtDlpFailureClassifier.shouldUpdateAndRetry(failure)) {
                throw failure
            }
            try {
                updateIfDue()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the bundled executable and perform the single controlled retry.
            }
            command.beforeRetry?.invoke()
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
        val state = updateStore.readState()
        fun isRecent(timestamp: Long, interval: Long) =
            timestamp > 0L && now >= timestamp && now - timestamp < interval
        if (isRecent(state.lastSuccessfulCheckMillis, UPDATE_INTERVAL_MILLIS) ||
            isRecent(state.lastFailedCheckMillis, FAILED_UPDATE_RETRY_MILLIS)
        ) return
        try {
            client.update()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            updateStore.writeState(state.copy(lastFailedCheckMillis = nowMillis()))
            throw failure
        }
        // An up-to-date response is also a successful check. An outage has a
        // shorter, persistent cooldown so a restored connection can recover.
        updateStore.writeState(YtDlpUpdateState(lastSuccessfulCheckMillis = nowMillis()))
    }

    companion object {
        const val UPDATE_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
        const val FAILED_UPDATE_RETRY_MILLIS = 5L * 60L * 1_000L
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
