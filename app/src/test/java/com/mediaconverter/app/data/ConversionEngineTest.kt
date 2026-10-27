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
    fun ytDlpExtractorPointsFfmpegLocationAtTheRequestDirectory() = runTest {
        val directory = Files.createTempDirectory("extractor-ffmpeg-location-test").toFile()
        val executed = mutableListOf<YtDlpCommand>()
        val runtime = YtDlpRuntime(
            client = object : YtDlpClient {
                override suspend fun initialize() = Unit
                override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                    executed += command
                    File(directory, "source.mp4").writeText("video+audio")
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

        try {
            YtDlpMediaExtractor(runtime).downloadSource(request(OutputFormat.MP4), directory) { _, _ -> }

            // The wrapper library injects its own --ffmpeg-location first; ours must follow it
            // and name an existing directory without ffmpeg so yt-dlp never tries to merge.
            val arguments = executed.single().arguments
            assertEquals(directory.absolutePath, arguments[arguments.lastIndexOf("--ffmpeg-location") + 1])
            assertTrue(directory.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
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
        val engine = ConversionEngine(cacheRoot, extractor, FakeTranscoder(), FakePublisher())

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
    fun aFailedInProcessMergeStillMergesTheDownloadedStreams() = runTest {
        val directory = Files.createTempDirectory("merge-fallback-test").toFile()
        val runtime = YtDlpRuntime(
            client = object : YtDlpClient {
                override suspend fun initialize() = Unit
                override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                    // yt-dlp finishes downloading both streams, then its own in-process merge
                    // throws (observed on some emulators as a half-detected ffmpeg). The complete
                    // separate streams are left on disk and must still be merged by FFmpegKit.
                    File(directory, "source.f137.mp4").writeText("video")
                    File(directory, "source.f140.m4a").writeText("audio")
                    throw YtDlpException("ERROR: Postprocessing: 'NoneType' object has no attribute 'lower'")
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
        val extractor = YtDlpMediaExtractor(runtime, MediaSourceMerger { sources, output ->
            output.apply { writeText(sources.joinToString("+") { it.readText() }) }
        })
        try {
            val merged = extractor.downloadSource(request(OutputFormat.MP4), directory) { _, _ -> }
            assertEquals(setOf("video", "audio"), merged.readText().split("+").toSet())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aFailedDownloadWithoutCompleteStreamsStillSurfacesTheError() = runTest {
        val directory = Files.createTempDirectory("merge-fallback-failure-test").toFile()
        val runtime = YtDlpRuntime(
            client = object : YtDlpClient {
                override suspend fun initialize() = Unit
                override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                    // Only one stream reached disk, so there is nothing for FFmpegKit to merge:
                    // the yt-dlp failure must still surface instead of being silently recovered.
                    File(directory, "source.f137.mp4").writeText("video only")
                    throw YtDlpException("ERROR: unable to download video data")
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
        try {
            val thrown = runCatching {
                YtDlpMediaExtractor(runtime).downloadSource(request(OutputFormat.MP4), directory) { _, _ -> }
            }.exceptionOrNull()
            assertTrue("Expected a ConversionException, got $thrown", thrown is ConversionException)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ytdlpSidecarFilesAreNotMistakenForASecondStream() = runTest {
        val directory = Files.createTempDirectory("merge-fallback-sidecar-test").toFile()
        val runtime = YtDlpRuntime(
            client = object : YtDlpClient {
                override suspend fun initialize() = Unit
                override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                    // An aborted single-stream download leaves one partial media file plus yt-dlp's
                    // own resume sidecar. The sidecar must not be counted as a second stream and
                    // merged; the real yt-dlp failure must surface instead.
                    File(directory, "source.mp4").writeText("partial video+audio")
                    File(directory, "source.mp4.ytdl").writeText("{\"resume\":true}")
                    throw YtDlpException("ERROR: fragment 2 not found, unable to continue")
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
        val extractor = YtDlpMediaExtractor(runtime, MediaSourceMerger { _, _ ->
            throw AssertionError("The sidecar file must not trigger a merge")
        })
        try {
            val thrown = runCatching {
                extractor.downloadSource(request(OutputFormat.MP4), directory) { _, _ -> }
            }.exceptionOrNull()
            assertTrue("Expected a ConversionException, got $thrown", thrown is ConversionException)
            assertTrue(
                "The original yt-dlp failure must surface: ${thrown?.message}",
                thrown?.message.orEmpty().contains("fragment 2 not found"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun anInFlightSidecarBlocksTheMergeFallback() = runTest {
        val directory = Files.createTempDirectory("merge-fallback-inflight-test").toFile()
        val runtime = YtDlpRuntime(
            client = object : YtDlpClient {
                override suspend fun initialize() = Unit
                override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                    // The video stream finished; the audio stream was aborted on a missing
                    // fragment, so it is truncated and yt-dlp's resume record still sits next to
                    // it. Two non-empty files are on disk, but one is not a finished stream and
                    // merging them would publish a video whose sound stops halfway.
                    File(directory, "source.f137.mp4").writeText("video")
                    File(directory, "source.f140.m4a").writeText("truncated audio")
                    File(directory, "source.f140.m4a.ytdl").writeText("{\"downloader\":{\"current_fragment\":{\"index\":3}}}")
                    throw YtDlpException("ERROR: fragment 3 not found, unable to continue")
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
        val extractor = YtDlpMediaExtractor(runtime, MediaSourceMerger { _, _ ->
            throw AssertionError("A stream still being downloaded must not be merged")
        })
        try {
            val thrown = runCatching {
                extractor.downloadSource(request(OutputFormat.MP4), directory) { _, _ -> }
            }.exceptionOrNull()
            assertTrue("Expected a ConversionException, got $thrown", thrown is ConversionException)
            assertTrue(
                "The original yt-dlp failure must surface: ${thrown?.message}",
                thrown?.message.orEmpty().contains("fragment 3 not found"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun transcoderSkipsTheRemuxWhenTheSourceCodecsDoNotBelongInMp4() = runTest {
        val executed = mutableListOf<List<String>>()
        val executor = object : FfmpegExecutor {
            override suspend fun execute(arguments: List<String>): FfmpegExecution {
                executed += arguments
                File(arguments.last()).writeText("encoded")
                return FfmpegExecution(succeeded = true, output = "")
            }
            override fun cancel() = Unit
        }
        val validator = object : MediaValidator {
            override fun inspect(file: File) = MediaInspection(
                file.length(), 6_000, hasAudio = true, hasVideo = true,
                codecs = SourceCodecs("video/x-vnd.on2.vp9", "audio/opus"),
            )
            override fun isValid(file: File, format: OutputFormat) = file.isFile && file.length() > 0
        }
        val directory = Files.createTempDirectory("transcoder-codecs-test").toFile()
        val source = File(directory, "merged-source.mkv").apply { writeText("vp9+opus") }
        try {
            FfmpegMediaTranscoder(executor, validator)
                .transcode(source, File(directory, "result.mp4"), OutputFormat.MP4)

            val command = executed.single()
            assertEquals("h264_mediacodec", command[command.indexOf("-c:v") + 1])
            assertFalse(command.contains("copy"))
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
        }, FakeTranscoder(), publisher)
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
