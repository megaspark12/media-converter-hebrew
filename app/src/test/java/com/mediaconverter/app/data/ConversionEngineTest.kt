package com.mediaconverter.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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

    @Test
    fun fatalVmErrorsPropagateInsteadOfBecomingDownloadFailures() = runTest {
        val cacheRoot = Files.createTempDirectory("conversion-fatal-test").toFile()
        val fatal = OutOfMemoryError("fatal")
        val engine = ConversionEngine(
            cacheRoot,
            MediaExtractor { _, _, _ -> throw fatal },
            FakeTranscoder(),
            FakePublisher(),
            FakeValidator(),
        )

        val thrown = runCatching {
            engine.convert(request(OutputFormat.MP4), "hello")
        }.exceptionOrNull()

        assertTrue(thrown === fatal)
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
        cacheRoot.delete()
    }

    @Test
    fun ytDlpExtractorDoesNotMapFatalVmErrorsToConversionFailures() = runTest {
        val fatal = OutOfMemoryError("yt-dlp fatal")
        val runtime = YtDlpRuntime(
            client = object : YtDlpClient {
                override suspend fun initialize() = Unit
                override suspend fun execute(command: YtDlpCommand): YtDlpResult = throw fatal
                override suspend fun update() = true
                override fun cancel(processId: String) = Unit
                override fun version(): String? = "test"
            },
            updateStore = object : YtDlpUpdateStore {
                override suspend fun readState() = YtDlpUpdateState()
                override suspend fun writeState(state: YtDlpUpdateState) = Unit
            },
            nowMillis = { 0L },
        )
        val directory = Files.createTempDirectory("extractor-fatal-test").toFile()

        val thrown = runCatching {
            YtDlpMediaExtractor(runtime).downloadSource(request(OutputFormat.MP4), directory) { _, _ -> }
        }.exceptionOrNull()

        assertTrue("Expected the original fatal error, got $thrown", thrown === fatal)
        directory.deleteRecursively()
    }

    @Test
    fun mediaValidatorDoesNotConvertFatalInspectionErrorsToInvalidMedia() {
        val fatal = OutOfMemoryError("inspection fatal")
        val validator = object : AndroidMediaValidator() {
            override fun inspect(file: File): MediaInspection = throw fatal
        }

        val thrown = runCatching {
            validator.isValid(File("output.mp4"), OutputFormat.MP4)
        }.exceptionOrNull()

        assertTrue("Expected the original fatal error, got $thrown", thrown === fatal)
    }

    @Test
    fun separateStreamsAreMergedBeforeConversionAndCleanedAfterPublication() = runTest {
        val cacheRoot = Files.createTempDirectory("separate-stream-test").toFile()
        val runtime = YtDlpRuntime(
            client = object : YtDlpClient {
                override suspend fun initialize() = Unit
                override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                    val template = command.arguments[command.arguments.indexOf("-o") + 1]
                    val directory = File(template).parentFile
                    File(directory, "source.f137.mp4").writeText("video")
                    File(directory, "source.f140.m4a").writeText("audio")
                    return YtDlpResult("", "merging is handled by the application")
                }
                override suspend fun update() = false
                override fun cancel(processId: String) = Unit
                override fun version() = "test"
            },
            updateStore = object : YtDlpUpdateStore {
                override suspend fun readState() = YtDlpUpdateState()
                override suspend fun writeState(state: YtDlpUpdateState) = Unit
            },
        )
        val mergedInputs = mutableListOf<String>()
        val extractor = YtDlpMediaExtractor(runtime, MediaSourceMerger { sources, output ->
            mergedInputs += sources.map { it.readText() }
            output.apply { writeText("merged-video-and-audio") }
        })
        val engine = ConversionEngine(cacheRoot, extractor, FakeTranscoder(), FakePublisher(), FakeValidator())

        val saved = engine.convert(request(OutputFormat.MP4), "fixture").getOrThrow()

        assertEquals("video/mp4", saved.mimeType)
        assertEquals(setOf("video", "audio"), mergedInputs.toSet())
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
        cacheRoot.delete()
    }

    @Test
    fun retryWithDifferentFormatIdsDoesNotMergeAbandonedFiles() = runTest {
        val directory = Files.createTempDirectory("retry-stream-test").toFile()
        var attempt = 0
        val runtime = YtDlpRuntime(
            client = object : YtDlpClient {
                override suspend fun initialize() = Unit
                override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                    if (attempt++ == 0) {
                        File(directory, "source.f137.mp4").writeText("old video")
                        throw YtDlpException("HTTP Error 403: Forbidden")
                    }
                    File(directory, "source.f248.webm").writeText("new video")
                    File(directory, "source.f251.webm").writeText("new audio")
                    return YtDlpResult("", "")
                }
                override suspend fun update() = true
                override fun cancel(processId: String) = Unit
                override fun version() = "test"
            },
            updateStore = object : YtDlpUpdateStore {
                override suspend fun readState() = YtDlpUpdateState()
                override suspend fun writeState(state: YtDlpUpdateState) = Unit
            },
        )
        val extractor = YtDlpMediaExtractor(runtime, MediaSourceMerger { sources, output ->
            assertEquals(setOf("new video", "new audio"), sources.map { it.readText() }.toSet())
            output.apply { writeText("merged") }
        })
        try {
            assertEquals("merged", extractor.downloadSource(request(OutputFormat.MP4), directory) { _, _ -> }.readText())
            assertEquals(2, attempt)
            assertFalse(File(directory, "source.f137.mp4").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellingNativeMergeStopsItsExecutorAndCleansWithoutPublishing() = runTest {
        val cacheRoot = Files.createTempDirectory("cancel-merge-test").toFile()
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val merger = FfmpegSourceMerger(object : FfmpegExecutor {
            override suspend fun execute(arguments: List<String>): FfmpegExecution {
                File(arguments.last()).writeText("partial merge")
                started.complete(Unit)
                awaitCancellation()
            }
            override fun cancel() { cancelled = true }
        })
        val publisher = FakePublisher()
        val engine = ConversionEngine(cacheRoot, MediaExtractor { _, directory, _ ->
            val sources = listOf("source.video", "source.audio").map { name ->
                File(directory, name).apply { writeText(name) }
            }
            merger.merge(sources, File(directory, "merged.mkv"))
        }, FakeTranscoder(), publisher, FakeValidator())
        val job = async { engine.convert(request(OutputFormat.MP4), "fixture") }
        started.await()
        job.cancel()

        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertTrue(cancelled)
        assertEquals(null, publisher.publishedFile)
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
