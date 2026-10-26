package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

class YtDlpUpdateMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun stalledUpdateServerTimesOutWithoutCallingInstallerOrLeavingPartialMetadata() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = CompletableFuture<java.net.Socket>()
        val worker = thread(isDaemon = true) { accepted.complete(server.accept()) }
        val result = CompletableFuture<Throwable?>()
        var installerCalled = false
        val updater = thread(isDaemon = true) {
            result.complete(runCatching {
                withYtDlpUpdateMetadata(
                    temporary.root,
                    "http://127.0.0.1:${server.localPort}/latest",
                    readTimeoutMillis = 100,
                ) { url ->
                    installerCalled = true
                    // Same metadata read performed by the pinned Android wrapper.
                    URL(url).readText()
                }
            }.exceptionOrNull())
        }
        val socket = accepted.get(3, TimeUnit.SECONDS)
        try {
            val failure = try {
                result.get(2, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                throw AssertionError("Update metadata request never timed out; downloads remain blocked")
            }
            assertTrue("Expected a network timeout, got $failure", failure is SocketTimeoutException)
            assertFalse(installerCalled)
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        } finally {
            socket.close()
            server.close()
            updater.join(3_000)
            worker.join(3_000)
        }
    }

    @Test
    fun completeMetadataIsPassedToInstallerAndTemporaryCopyIsRemoved() {
        val source = temporary.newFile("release.json")
        source.writeText("""{"tag_name":"2026.08.19","assets":[]}""")
        val cache = temporary.newFolder("cache")
        var consumedUrl = ""
        val version = withYtDlpUpdateMetadata(cache, source.toURI().toString()) { url ->
            consumedUrl = url
            URL(url).readText()
        }
        assertEquals("""{"tag_name":"2026.08.19","assets":[]}""", version)
        assertTrue(consumedUrl.startsWith("file:"))
        assertTrue(cache.listFiles()!!.isEmpty())
        assertTrue(source.exists())
    }

    @Test
    fun installerFailureAlsoRemovesTemporaryMetadata() {
        val source = temporary.newFile("release.json").apply { writeText("{}") }
        val cache = temporary.newFolder("cache")
        val expected = IllegalStateException("installation failed")
        val failure = runCatching {
            withYtDlpUpdateMetadata(cache, source.toURI().toString()) { throw expected }
        }.exceptionOrNull()
        assertTrue(failure === expected)
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
