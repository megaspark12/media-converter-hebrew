package com.mediaconverter.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConversionEngineTest {
    @Test
    fun publishesPromisedFormatAndCleansRequestDirectory() = runTest {
        val cacheRoot = Files.createTempDirectory("conversion-test").toFile()
        val publisher = FakePublisher()
        val engine = ConversionEngine(
            cacheRoot,
            FakeExtractor(),
            FakeTranscoder(),
            publisher,
            FakeValidator(),
        )

        val result = engine.convert(request(OutputFormat.MP3), "hello")

        assertTrue(result.isSuccess)
        assertEquals("audio/mpeg", result.getOrThrow().mimeType)
        assertEquals("mp3", publisher.publishedFile?.extension)
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
        cacheRoot.delete()
    }

    @Test
    fun cleansPartialFilesWhenExtractionFails() = runTest {
        val cacheRoot = Files.createTempDirectory("conversion-failure-test").toFile()
        val engine = ConversionEngine(
            cacheRoot,
            MediaExtractor { _, directory, _ ->
                File(directory, "source.part").writeText("partial")
                throw ConversionException(ConversionFailure.DOWNLOAD_FAILED, "disconnect")
            },
            FakeTranscoder(),
            FakePublisher(),
            FakeValidator(),
        )

        val result = engine.convert(request(OutputFormat.MP4), "hello")

        assertFalse(result.isSuccess)
        assertEquals(
            ConversionFailure.DOWNLOAD_FAILED,
            (result.exceptionOrNull() as ConversionException).failure,
        )
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
        cacheRoot.delete()
    }

    private fun request(format: OutputFormat) = ConversionRequest(
        42,
        NormalizedMediaUrl("https://youtu.be/test", SupportedPlatform.YOUTUBE),
        format,
    )
}

private class FakeExtractor : MediaExtractor {
    override suspend fun downloadSource(
        request: ConversionRequest,
        tempDirectory: File,
        onProgress: (Int, String) -> Unit,
    ): File = File(tempDirectory, "source.bin").apply { writeText("source") }
}

private class FakeTranscoder : MediaTranscoder {
    override suspend fun transcode(source: File, output: File, format: OutputFormat): File =
        output.apply { writeText("converted") }
    override fun cancel() = Unit
}

private class FakePublisher : MediaPublisher {
    var publishedFile: File? = null
    override suspend fun publish(source: File, displayName: String, format: OutputFormat): SavedMedia {
        publishedFile = source
        return SavedMedia("content://test/42", "$displayName.${format.extension}", format.mimeType, 9, 1_000)
    }
}

private class FakeValidator : MediaValidator {
    override fun inspect(file: File) = MediaInspection(file.length(), 1_000, true, file.extension == "mp4")
    override fun isValid(file: File, format: OutputFormat): Boolean = file.isFile && file.length() > 0
}
