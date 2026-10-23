package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWorkerForegroundTest {
    @Test
    fun reportsSuccessWhenForegroundPromotionIsAccepted() = runTest {
        var logged: Throwable? = null

        val promoted = promoteToForeground(setForeground = {}, logFailure = { logged = it })

        assertTrue(promoted)
        assertNull(logged)
    }

    @Test
    fun keepsRunningInBackgroundWhenForegroundStartIsNotAllowed() = runTest {
        val denied = IllegalStateException("Foreground service start not allowed")
        var logged: Throwable? = null

        val promoted = promoteToForeground(setForeground = { throw denied }, logFailure = { logged = it })

        assertFalse(promoted)
        assertSame(denied, logged)
    }

    @Test
    fun keepsRunningWhenTheSystemRejectsTheServiceType() = runTest {
        val rejected = SecurityException("Starting FGS with type dataSync requires permission")
        var logged: Throwable? = null

        val promoted = promoteToForeground(setForeground = { throw rejected }, logFailure = { logged = it })

        assertFalse(promoted)
        assertSame(rejected, logged)
    }

    @Test
    fun rethrowsCancellationWithoutLogging() = runTest {
        val cancellation = CancellationException("worker stopped")
        var logged = false

        val thrown = runCatching {
            promoteToForeground(setForeground = { throw cancellation }, logFailure = { logged = true })
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertFalse(logged)
    }

    @Test
    fun rethrowsFatalVmErrors() = runTest {
        val fatal = OutOfMemoryError("fatal")
        var logged = false

        val thrown = runCatching {
            promoteToForeground(setForeground = { throw fatal }, logFailure = { logged = true })
        }.exceptionOrNull()

        assertSame(fatal, thrown)
        assertFalse(logged)
    }
}
