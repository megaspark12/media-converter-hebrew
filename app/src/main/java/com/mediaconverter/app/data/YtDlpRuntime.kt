package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

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
        } catch (failure: Throwable) {
            if (failure is CancellationException || !YtDlpFailureClassifier.shouldUpdateAndRetry(failure)) {
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

    private suspend fun isUpdateDue(): Boolean =
        nowMillis() - updateStore.readLastUpdateMillis() >= UPDATE_INTERVAL_MILLIS

    /** Must be called while holding the exclusive gate: no process may run while the binary is replaced. */
    private suspend fun updateIfDue() {
        // Re-checked under the gate: another caller may have updated while we waited for it.
        if (!isUpdateDue()) return
        val now = nowMillis()
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
