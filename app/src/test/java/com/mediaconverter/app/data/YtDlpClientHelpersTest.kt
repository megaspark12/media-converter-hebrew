package com.mediaconverter.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpClientHelpersTest {
    @Test
    fun etaIsShownAsClockTimeNotRawSeconds() {
        // youtubedl-android reports the ETA as a number of seconds; "754" is meaningless to a
        // person next to the percentage.
        assertEquals("12:34", formatEtaSeconds(754))
        assertEquals("0:59", formatEtaSeconds(59))
        assertEquals("1:01:01", formatEtaSeconds(3_661))
        assertEquals("0:00", formatEtaSeconds(0))
    }

    @Test
    fun unknownEtaIsBlank() {
        assertEquals("", formatEtaSeconds(-1))
    }

    @Test
    fun installedVersionIsReusedWhileTheExecutableIsUnchanged() = runTest {
        // Spawning "yt-dlp --version" starts a Python interpreter, which costs seconds on every
        // cold start. The executable's fingerprint tells whether the remembered answer holds.
        var probed = false

        val version = resolveInstalledYtDlpVersion(
            fingerprint = "1234:567",
            remembered = InstalledYtDlpVersion("1234:567", "2026.09.01"),
            probe = { probed = true; "2026.10.01" },
            remember = { throw AssertionError("Nothing new to remember") },
        )

        assertEquals("2026.09.01", version)
        assertFalse(probed)
    }

    @Test
    fun aChangedExecutableIsProbedAndRemembered() = runTest {
        var remembered: InstalledYtDlpVersion? = null

        val version = resolveInstalledYtDlpVersion(
            fingerprint = "9999:1",
            remembered = InstalledYtDlpVersion("1234:567", "2026.09.01"),
            probe = { "2026.10.01" },
            remember = { remembered = it },
        )

        assertEquals("2026.10.01", version)
        assertEquals(InstalledYtDlpVersion("9999:1", "2026.10.01"), remembered)
    }

    @Test
    fun anUnknownExecutableIsProbedEveryTime() = runTest {
        var remembered: InstalledYtDlpVersion? = null

        val version = resolveInstalledYtDlpVersion(
            fingerprint = null,
            remembered = InstalledYtDlpVersion("1234:567", "2026.09.01"),
            probe = { "2026.10.01" },
            remember = { remembered = it },
        )

        assertEquals("2026.10.01", version)
        assertNull(remembered)
    }

    @Test
    fun aBlankProbeIsAnError() = runTest {
        val thrown = runCatching {
            resolveInstalledYtDlpVersion(
                fingerprint = "1:1",
                remembered = null,
                probe = { "  " },
                remember = { },
            )
        }.exceptionOrNull()

        assertTrue("Expected IllegalStateException, got $thrown", thrown is IllegalStateException)
    }
}
