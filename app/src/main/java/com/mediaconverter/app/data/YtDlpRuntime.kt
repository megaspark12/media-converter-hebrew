package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

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
    private val gate = ProcessGate()

    @Volatile
    private var initialized = false

    suspend fun refreshIfDue() = gate.exclusive {
        initializeIfNeeded()
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

    suspend fun execute(command: YtDlpCommand): YtDlpResult {
        ensureInitialized()
        return try {
            gate.shared { executeCancellable(command) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Only exceptions are candidates for provider recovery. Errors (OutOfMemoryError,
            // LinkageError, ...) propagate untouched whatever their message says.
            if (!YtDlpFailureClassifier.shouldUpdateAndRetry(failure)) {
                throw failure
            }
            try {
                // The exclusive slot waits for every running download. Only ask for it when the
                // throttle says an update can actually happen; otherwise a routine 403 during a
                // download would park this lookup (and hold back all new ones) for nothing.
                if (isUpdateDue()) gate.exclusive { updateIfDue() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the bundled executable and perform the single controlled retry.
            }
            command.beforeRetry?.invoke()
            gate.shared { executeCancellable(command) }
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
        gate.exclusive { initializeIfNeeded() }
    }

    /** Must be called while holding the exclusive gate. */
    private suspend fun initializeIfNeeded() {
        if (initialized) return
        client.initialize()
        initialized = true
    }

    private suspend fun isUpdateDue(): Boolean = isUpdateDue(updateStore.readState(), nowMillis())

    /**
     * A successful check is good for a day; a failed one only for a few minutes, so a restored
     * connection can recover. A zero timestamp means "never checked", and a clock that moved
     * backwards is treated as due rather than as a lockout.
     */
    private fun isUpdateDue(state: YtDlpUpdateState, now: Long): Boolean {
        fun isRecent(timestamp: Long, interval: Long) =
            timestamp > 0L && now >= timestamp && now - timestamp < interval
        return !isRecent(state.lastSuccessfulCheckMillis, UPDATE_INTERVAL_MILLIS) &&
            !isRecent(state.lastFailedCheckMillis, FAILED_UPDATE_RETRY_MILLIS)
    }

    /** Must be called while holding the exclusive gate: no process may run while the binary is replaced. */
    private suspend fun updateIfDue() {
        // Re-checked under the gate: another caller may have updated while we waited for it.
        val state = updateStore.readState()
        if (!isUpdateDue(state, nowMillis())) return
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

/**
 * Coordinates yt-dlp process execution with initialisation and binary replacement.
 *
 * Any number of processes may run at the same time (youtubedl-android isolates them by
 * process id), so a metadata lookup never waits behind an unrelated download. Initialising
 * or updating the executable needs it to be idle, so an exclusive request waits for running
 * processes to finish and, while it waits, holds back newly requested processes so a steady
 * stream of executions cannot starve it.
 */
internal class ProcessGate {
    private val lock = Any()
    private var running = 0
    private var exclusiveHeld = false
    private var exclusiveWaiting = 0
    private var stateChanged = CompletableDeferred<Unit>()

    suspend fun <T> shared(block: suspend () -> T): T {
        acquire(canEnter = { !exclusiveHeld && exclusiveWaiting == 0 }, enter = { running++ })
        try {
            return block()
        } finally {
            change { running-- }
        }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T {
        change { exclusiveWaiting++ }
        try {
            acquire(canEnter = { !exclusiveHeld && running == 0 }, enter = { exclusiveHeld = true })
        } finally {
            // Runs after a successful acquisition as well as after cancellation while waiting,
            // so held-back processes are released either way.
            change { exclusiveWaiting-- }
        }
        try {
            return block()
        } finally {
            change { exclusiveHeld = false }
        }
    }

    private suspend fun acquire(canEnter: () -> Boolean, enter: () -> Unit) {
        while (true) {
            val changed = synchronized(lock) {
                if (canEnter()) {
                    enter()
                    return
                }
                stateChanged
            }
            changed.await()
        }
    }

    private fun change(mutation: () -> Unit) {
        val waiters = synchronized(lock) {
            mutation()
            stateChanged.also { stateChanged = CompletableDeferred() }
        }
        waiters.complete(Unit)
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
