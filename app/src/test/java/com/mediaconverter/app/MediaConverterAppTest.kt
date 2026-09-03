package com.mediaconverter.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class MediaConverterAppTest {
    @Test
    fun backgroundRefreshRethrowsCancellationWithoutLogging() = runTest {
        val cancellation = CancellationException("scope cancelled")
        var logged = false

        val thrown = runCatching {
            refreshYtDlp(
                refresh = { throw cancellation },
                logFailure = { logged = true },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertFalse(logged)
    }

    @Test
    fun backgroundRefreshDoesNotSwallowFatalVmErrors() = runTest {
        val fatal = OutOfMemoryError("refresh fatal")
        var logged = false

        val thrown = runCatching {
            refreshYtDlp(
                refresh = { throw fatal },
                logFailure = { logged = true },
            )
        }.exceptionOrNull()

        assertSame(fatal, thrown)
        assertFalse(logged)
    }
}
