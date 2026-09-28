package com.mediaconverter.app

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.mediaconverter.app.data.AndroidMediaValidator
import com.mediaconverter.app.data.AndroidYtDlpClient
import com.mediaconverter.app.data.ConversionEngine
import com.mediaconverter.app.data.ConversionRequest
import com.mediaconverter.app.data.FfmpegKitExecutor
import com.mediaconverter.app.data.FfmpegMediaTranscoder
import com.mediaconverter.app.data.NormalizedMediaUrl
import com.mediaconverter.app.data.OutputFormat
import com.mediaconverter.app.data.MediaPublisher
import com.mediaconverter.app.data.SavedMedia
import com.mediaconverter.app.data.SupportedPlatform
import com.mediaconverter.app.data.YtDlpClient
import com.mediaconverter.app.data.YtDlpCommand
import com.mediaconverter.app.data.YtDlpMediaExtractor
import com.mediaconverter.app.data.YtDlpResult
import com.mediaconverter.app.data.YtDlpRuntime
import com.mediaconverter.app.data.YtDlpUpdateState
import com.mediaconverter.app.data.YtDlpUpdateStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

/** Uses local HTTP media and real yt-dlp/FFmpeg, without depending on a provider or updates. */
@RunWith(AndroidJUnit4::class)
class YtDlpDownloadInstrumentedTest {
    @Test
    fun missingHlsFragmentFailsWithoutPublishingATruncatedVideo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "missing-fragment-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (name in listOf("playlist0.ts", "playlist2.ts")) {
                instrumentation.context.assets.open("missing-fragment/$name").use { input ->
                    File(directory, name).outputStream().use { input.copyTo(it) }
                }
            }
            val playlist = File(directory, "playlist.m3u8").apply {
                writeText("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n" +
                    "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2.0,\nplaylist0.ts\n" +
                    "#EXTINF:2.0,\nplaylist1.ts\n#EXTINF:2.0,\nplaylist2.ts\n#EXT-X-ENDLIST\n")
            }
            FixtureServer(directory).use { server ->
                val info = File(directory, "info.json").apply {
                    writeText(JSONObject().put("id", "missing-fragment").put("title", "HLS fixture")
                        .put("extractor", "fixture").put("webpage_url", "https://example.invalid/fixture")
                        .put("formats", JSONArray().put(JSONObject()
                            .put("format_id", "hls").put("url", server.url(playlist))
                            .put("protocol", "m3u8_native").put("ext", "mp4")
                            .put("vcodec", "h264").put("acodec", "aac").put("height", 48)))
                        .toString())
                }
                val client = AndroidYtDlpClient(context)
                val runtime = YtDlpRuntime(object : YtDlpClient by client {
                    override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                        val response = YoutubeDL.getInstance().execute(
                            YoutubeDLRequest(emptyList<String>()).addCommands(command.arguments)
                                .addOption("--load-info-json", info.path),
                        )
                        return YtDlpResult(response.out, response.err)
                    }
                    override suspend fun update() = false
                }, object : YtDlpUpdateStore {
                    override suspend fun readState() = YtDlpUpdateState()
                    override suspend fun writeState(state: YtDlpUpdateState) = Unit
                })
                var published = false
                val validator = AndroidMediaValidator()
                val engine = ConversionEngine(directory, YtDlpMediaExtractor(runtime),
                    FfmpegMediaTranscoder(FfmpegKitExecutor(), validator),
                    object : MediaPublisher {
                        override suspend fun publish(source: File, displayName: String, format: OutputFormat): SavedMedia {
                            published = true
                            return SavedMedia("content://fixture/output", displayName, format.mimeType, source.length(), 6_000)
                        }
                    }, validator)
                val result = engine.convert(ConversionRequest(123,
                    NormalizedMediaUrl("https://youtu.be/fixture", SupportedPlatform.YOUTUBE), OutputFormat.MP4), "fixture")

                assertTrue("The missing middle segment must actually be requested", server.requests.contains("playlist1.ts"))
                assertTrue("A video with a missing segment must fail", result.isFailure)
                val failureMessage = result.exceptionOrNull()?.message.orEmpty()
                assertTrue("Must fail on the missing fragment, not an unrelated conversion error: $failureMessage",
                    failureMessage.contains("fragment 2 not found, unable to continue"))
                assertTrue("Truncated video must not reach publication", !published)
                assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("conversion-") })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun adaptiveOnlyFormatsMergeWithAudioAndRespectTheHeightLimit() = runBlocking {
        downloadFixture(adaptive = true)
    }

    @Test
    fun combinedStreamFallbackRespectsTheHeightLimit() = runBlocking {
        downloadFixture(adaptive = false)
    }

    @Test
    fun adaptiveWebmSourcesBecomeMp4WithVideoAndAudio() = runBlocking {
        downloadFixture(adaptive = true, webm = true)
    }

    private suspend fun downloadFixture(adaptive: Boolean, webm: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "adaptive-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val videoExtension = if (webm) "webm" else "mp4"
            val audioCodec = if (webm) "opus" else "aac"
            val videoCodec = if (webm) "vp9" else "mpeg4"
            val low = File(directory, "low.$videoExtension")
            val high = File(directory, "high.$videoExtension")
            val audio = File(directory, if (webm) "audio.webm" else "audio.m4a")
            encode(listOf("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", if (webm) "libopus" else "aac", audio.path))
            for ((file, size) in listOf(low to "160x90", high to "320x180")) {
                val inputs = listOf("-f", "lavfi", "-i", "color=c=blue:s=$size:r=10:d=1") +
                    if (adaptive) emptyList() else listOf("-i", audio.path)
                val videoOptions = if (webm) listOf(
                    "-c:v", "libvpx-vp9", "-b:v", "100k", "-pix_fmt", "yuv420p",
                    "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
                )
                    else listOf("-c:v", "mpeg4", "-q:v", "5")
                encode(inputs + videoOptions +
                    (if (adaptive) listOf("-an") else listOf("-c:a", "copy", "-shortest")) + file.path)
            }
            if (webm) {
                val decoded = FFmpegKit.executeWithArguments(arrayOf("-v", "error", "-i", low.path, "-f", "null", "-"))
                assertTrue("Generated VP9 fixture must decode before downloading: ${decoded.allLogsAsString}", ReturnCode.isSuccess(decoded.returnCode))
            }
            FixtureServer(directory).use { server ->
                val formats = JSONArray()
                if (adaptive) formats.put(format(server, audio, "none", audioCodec))
                formats.put(format(server, low, videoCodec, if (adaptive) "none" else audioCodec, 90))
                formats.put(format(server, high, videoCodec, if (adaptive) "none" else audioCodec, 180))
                val info = File(directory, "info.json").apply {
                    writeText(JSONObject().put("id", "local-fixture").put("title", "Local fixture")
                        .put("extractor", "fixture").put("webpage_url", "https://example.invalid/fixture")
                        .put("duration", 1).put("formats", formats).toString())
                }
                val androidClient = AndroidYtDlpClient(context)
                val runtime = YtDlpRuntime(
                    client = object : YtDlpClient by androidClient {
                        override suspend fun execute(command: YtDlpCommand): YtDlpResult {
                            // Only substitute provider extraction. Keep the real selector, HTTP
                            // downloads, source handling, native merge, and conversion.
                            val response = YoutubeDL.getInstance().execute(
                                YoutubeDLRequest(emptyList<String>()).addCommands(command.arguments)
                                    .addOption("--load-info-json", info.path).addOption("--verbose"),
                            )
                            return YtDlpResult(response.out, response.err)
                        }
                        override suspend fun update() = false
                    },
                    updateStore = object : YtDlpUpdateStore {
                        override suspend fun readState() = YtDlpUpdateState()
                        override suspend fun writeState(state: YtDlpUpdateState) = Unit
                    },
                )
                val source = YtDlpMediaExtractor(runtime).downloadSource(
                    ConversionRequest(1, NormalizedMediaUrl("https://youtu.be/fixture", SupportedPlatform.YOUTUBE), OutputFormat.MP4, "90"),
                    directory,
                ) { _, _ -> }
                assertTrue("The requested video was not downloaded", server.requests.contains(low.name))
                assertTrue("The height limit was exceeded", !server.requests.contains(high.name))
                if (adaptive) assertTrue("Audio was not downloaded", server.requests.contains(audio.name))
                val validator = AndroidMediaValidator()
                val output = FfmpegMediaTranscoder(FfmpegKitExecutor(), validator).transcode(
                    source, File(directory, "result.mp4"), OutputFormat.MP4,
                )
                assertTrue("Expected a real MP4 with both tracks", validator.isValid(output, OutputFormat.MP4))
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.path)
                    val video = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                        .first { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
                    assertEquals(90, video.getInteger(MediaFormat.KEY_HEIGHT))
                } finally {
                    extractor.release()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun format(server: FixtureServer, file: File, videoCodec: String, audioCodec: String, height: Int? = null) =
        JSONObject().put("format_id", file.nameWithoutExtension).put("url", server.url(file))
            .put("ext", file.extension).put("vcodec", videoCodec).put("acodec", audioCodec)
            .put("height", height).put("protocol", "http")

    private fun encode(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments((listOf("-y") + arguments).toTypedArray())
        assertTrue(session.allLogsAsString, ReturnCode.isSuccess(session.returnCode))
    }
}

private class FixtureServer(private val directory: File) : Closeable {
    private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val worker = thread(name = "media-fixture-http", isDaemon = true) {
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { /* drain headers */ }
                    val name = request.split(' ').getOrNull(1).orEmpty().removePrefix("/")
                    val file = File(directory, name)
                    val found = name in setOf("low.mp4", "high.mp4", "audio.m4a", "low.webm", "high.webm", "audio.webm",
                        "playlist.m3u8", "playlist0.ts", "playlist1.ts", "playlist2.ts") && file.isFile
                    val body = if (found) file.readBytes() else ByteArray(0)
                    requests += name
                    val response = "HTTP/1.1 ${if (found) "200 OK" else "404 Not Found"}\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().apply {
                        write(response.toByteArray(Charsets.US_ASCII))
                        if (!request.startsWith("HEAD ")) write(body)
                        flush()
                    }
                }
            } catch (closed: SocketException) {
                if (!socket.isClosed) throw closed
            }
        }
    }

    fun url(file: File) = "http://127.0.0.1:${socket.localPort}/${file.name}"

    override fun close() {
        socket.close()
        worker.join(5_000)
    }
}
