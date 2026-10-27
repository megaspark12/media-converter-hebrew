package com.mediaconverter.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files

class CancellableCopyTest {
    @Test
    fun copiesTheWholeFile() = runTest {
        val source = Files.createTempFile("copy", ".bin").toFile()
        val bytes = ByteArray(DEFAULT_BUFFER_SIZE * 3 + 17) { it.toByte() }
        source.writeBytes(bytes)
        val output = ByteArrayOutputStream()

        try {
            copyCancellable(source, output)
            assertArrayEquals(bytes, output.toByteArray())
        } finally {
            source.delete()
        }
    }

    @Test
    fun stopsCopyingOnceTheCoroutineIsCancelled() = runTest {
        val source = Files.createTempFile("copy-cancel", ".bin").toFile()
        source.writeBytes(ByteArray(DEFAULT_BUFFER_SIZE * 4))
        var written = 0
        lateinit var job: Job
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                job.cancel()
            }
        }

        job = launch { copyCancellable(source, output) }
        job.join()

        try {
            assertTrue(job.isCancelled)
            assertTrue("Copy continued after cancellation: $written bytes", written <= DEFAULT_BUFFER_SIZE)
        } finally {
            source.delete()
        }
    }

    @Test
    fun cancellationSurfacesAsCancellation() = runTest {
        val source = Files.createTempFile("copy-cancel-type", ".bin").toFile()
        source.writeBytes(ByteArray(DEFAULT_BUFFER_SIZE * 2))
        lateinit var job: Job
        var thrown: Throwable? = null
        val output = object : OutputStream() {
            override fun write(b: Int) = job.cancel()
            override fun write(b: ByteArray, off: Int, len: Int) = job.cancel()
        }

        job = launch {
            try {
                copyCancellable(source, output)
            } catch (failure: Throwable) {
                thrown = failure
                throw failure
            }
        }
        job.join()

        try {
            assertTrue("Expected CancellationException, got $thrown", thrown is CancellationException)
        } finally {
            source.delete()
        }
    }
}
