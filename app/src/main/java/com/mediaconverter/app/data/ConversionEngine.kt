package com.mediaconverter.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.media.MediaExtractor as AndroidMediaExtractor
import android.os.Build
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

fun interface MediaExtractor {
    suspend fun downloadSource(
        request: ConversionRequest,
        tempDirectory: File,
        onProgress: (Int, String) -> Unit,
    ): File
}

interface MediaTranscoder {
    suspend fun transcode(source: File, output: File, format: OutputFormat): File
    fun cancel()
}

fun interface MediaSourceMerger {
    suspend fun merge(sources: List<File>, output: File): File
}

interface MediaPublisher {
    suspend fun publish(source: File, displayName: String, format: OutputFormat): SavedMedia
}

data class MediaInspection(
    val sizeBytes: Long,
    val durationMillis: Long,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
)

interface MediaValidator {
    fun inspect(file: File): MediaInspection
    fun isValid(file: File, format: OutputFormat): Boolean
}

object YtDlpDownloadCommandFactory {
    fun create(request: ConversionRequest, outputTemplate: String): List<String> {
        val formatSelector = when (request.outputFormat) {
            OutputFormat.MP3 -> "bestaudio/best"
            OutputFormat.MP4 -> {
                val height = request.quality.toIntOrNull()?.takeIf { it > 0 }
                val limit = height?.let { "[height<=$it]" }.orEmpty()
                "bestvideo$limit+bestaudio/best$limit"
            }
        }
        val common = listOf(
            "--no-playlist",
            "--socket-timeout", "20",
            "--retries", "3",
            "--fragment-retries", "3",
            "--abort-on-unavailable-fragments",
            "--no-part",
            "--force-overwrites",
            "-f", formatSelector,
            "-o", outputTemplate,
        )
        // Follow yt-dlp's maintained client defaults, just like metadata requests.
        // MKV accepts the source codecs; the existing transcoder produces MP4.
        // If yt-dlp has no CLI merger, its separate files are merged by FFmpegKit.
        return if (request.outputFormat == OutputFormat.MP4) {
            common + listOf("--merge-output-format", "mkv")
        } else {
            common
        }
    }
}

class YtDlpMediaExtractor(
    private val runtime: YtDlpRuntime,
    private val merger: MediaSourceMerger = FfmpegSourceMerger(FfmpegKitExecutor()),
) : MediaExtractor {
    override suspend fun downloadSource(
        request: ConversionRequest,
        tempDirectory: File,
        onProgress: (Int, String) -> Unit,
    ): File {
        check(tempDirectory.exists() || tempDirectory.mkdirs()) { "Unable to create temporary directory" }
        val template = File(tempDirectory, "source.%(ext)s").absolutePath
        val downloadFailure: Exception? = try {
            runtime.execute(
                YtDlpCommand(
                    processId = "download-${request.downloadId}-${UUID.randomUUID()}",
                    sourceUrl = request.source.value,
                    arguments = YtDlpDownloadCommandFactory.create(request, template) +
                        ffmpegLocationOverride(tempDirectory),
                    onProgress = onProgress,
                    beforeRetry = {
                        // Updating the extractor can select different format IDs.
                        // Discard this attempt's files before downloading replacements.
                        tempDirectory.listFiles().orEmpty()
                            .filter { it.name.startsWith("source.") }
                            .forEach { file ->
                                check(file.delete()) { "Unable to remove incomplete download" }
                            }
                    },
                ),
            )
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }
        val outputs = tempDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("source.") && !it.name.endsWith(".part") }
        // yt-dlp downloads the separate video and audio streams first and only then runs its own
        // in-process merge. That merge step is unreliable on some devices (its bundled ffmpeg is
        // half-detected) and can throw even though both complete streams are already on disk. When
        // exactly those two MP4 streams are present, FFmpegKit merges them below -- the same path a
        // clean "ffmpeg not found, keep the files" run already takes -- instead of failing. Any
        // other failure (no source, a single partial file) is surfaced to the caller as before.
        val hasSeparateMp4Streams = outputs.size == 2 &&
            request.outputFormat == OutputFormat.MP4 &&
            outputs.none { it.length() <= 0L }
        if (downloadFailure != null && !hasSeparateMp4Streams) {
            throw ConversionException(
                ConversionFailureMapper.fromThrowable(downloadFailure),
                downloadFailure.message ?: "yt-dlp download failed",
                downloadFailure,
            )
        }
        if (outputs.isEmpty() || outputs.any { it.length() <= 0L } || outputs.size > 2 ||
            (outputs.size == 2 && request.outputFormat != OutputFormat.MP4)
        ) {
            throw ConversionException(
                ConversionFailure.DOWNLOAD_FAILED,
                "yt-dlp did not produce complete media sources",
            )
        }
        return if (outputs.size == 1) {
            outputs.single()
        } else {
            merger.merge(outputs.sortedBy { it.name }, File(tempDirectory, "merged-source.mkv"))
        }
    }
}

/**
 * youtubedl-android passes `--ffmpeg-location <nativeLibraryDir>/libffmpeg.so` on every run, but
 * this app ships no yt-dlp ffmpeg module: merging is FFmpegKit's job. When that path does not
 * exist, yt-dlp falls back to probing the device's PATH, and on some images (API 26/29 x86_64
 * emulators) that leaves its merger "available" with no executable, which crashes or stalls the
 * download. Pointing the option at an existing directory that contains no ffmpeg makes yt-dlp
 * take the deterministic "ffmpeg not found, keep the separate files" path everywhere. Later
 * options win in yt-dlp, and ours follow the library's.
 */
internal fun ffmpegLocationOverride(directory: File): List<String> =
    listOf("--ffmpeg-location", directory.absolutePath)

data class FfmpegExecution(val succeeded: Boolean, val output: String)

interface FfmpegExecutor {
    suspend fun execute(arguments: List<String>): FfmpegExecution
    fun cancel()
}

class FfmpegSourceMerger(private val executor: FfmpegExecutor) : MediaSourceMerger {
    override suspend fun merge(sources: List<File>, output: File): File {
        require(sources.size == 2) { "Expected a video source and an audio source" }
        // Track mapping is independent of filename/format-id order. yt-dlp's
        // bestvideo+bestaudio selection supplies one video-only and one audio-only file.
        val arguments = listOf("-y") + sources.flatMap { listOf("-i", it.absolutePath) } + listOf(
            "-map", "0:v:0?", "-map", "1:v:0?",
            "-map", "0:a:0?", "-map", "1:a:0?",
            "-c", "copy", output.absolutePath,
        )
        val result = try {
            executor.execute(arguments)
        } catch (cancelled: CancellationException) {
            executor.cancel()
            throw cancelled
        }
        if (!result.succeeded || !output.isFile || output.length() <= 0L) {
            throw ConversionException(
                ConversionFailure.PROCESSING_FAILED,
                "Unable to merge video and audio: ${result.output.takeLast(2_000)}",
            )
        }
        return output
    }
}

class FfmpegKitExecutor(
    private val stallTimeoutMillis: Long = STALL_TIMEOUT_MILLIS,
) : FfmpegExecutor {
    @Volatile
    private var activeSessionId: Long? = null

    override suspend fun execute(arguments: List<String>): FfmpegExecution {
        val lastActivity = AtomicLong(SystemClock.elapsedRealtime())
        val completion = CompletableDeferred<FfmpegExecution>()
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments.toTypedArray(),
            { completed ->
                completion.complete(
                    FfmpegExecution(
                        succeeded = ReturnCode.isSuccess(completed.returnCode),
                        output = completed.allLogsAsString.orEmpty(),
                    ),
                )
            },
            { _ -> lastActivity.set(SystemClock.elapsedRealtime()) },
            { _ -> lastActivity.set(SystemClock.elapsedRealtime()) },
        )
        activeSessionId = session.sessionId
        return try {
            awaitWithStallWatchdog(
                completion = completion,
                lastActivityMillis = lastActivity::get,
                nowMillis = SystemClock::elapsedRealtime,
                stallTimeoutMillis = stallTimeoutMillis,
                graceMillis = STALL_CANCEL_GRACE_MILLIS,
                onStall = { FFmpegKit.cancel(session.sessionId) },
                onAbandon = {
                    FfmpegExecution(
                        succeeded = false,
                        output = "FFmpeg produced no output for ${stallTimeoutMillis / 1_000} s and did not " +
                            "stop when cancelled; the session was abandoned",
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            FFmpegKit.cancel(session.sessionId)
            throw cancelled
        } finally {
            if (activeSessionId == session.sessionId) activeSessionId = null
        }
    }

    override fun cancel() {
        activeSessionId?.let(FFmpegKit::cancel)
    }

    companion object {
        /** FFmpeg logs while probing and reports statistics every few hundred ms while encoding. */
        const val STALL_TIMEOUT_MILLIS = 90_000L
        const val STALL_CANCEL_GRACE_MILLIS = 10_000L
    }
}

/**
 * Waits for [completion] while watching for a stall: when [lastActivityMillis] has not moved for
 * [stallTimeoutMillis], [onStall] is invoked (cancelling the FFmpeg session) and, if the session
 * still has not completed [graceMillis] later, [onAbandon] supplies the result instead.
 *
 * Needed because some encoders never return. On API 26/29 x86_64 emulator images the
 * `h264_mediacodec` encoder blocks forever, which previously parked a download at "100%" with no
 * way out; with the watchdog the transcoder falls through to the next command in its chain.
 */
internal suspend fun <T> awaitWithStallWatchdog(
    completion: Deferred<T>,
    lastActivityMillis: () -> Long,
    nowMillis: () -> Long,
    stallTimeoutMillis: Long,
    graceMillis: Long,
    onStall: () -> Unit,
    onAbandon: () -> T,
): T = coroutineScope {
    val watchdog = launch {
        while (true) {
            val idle = nowMillis() - lastActivityMillis()
            if (idle >= stallTimeoutMillis) break
            delay(stallTimeoutMillis - idle)
        }
        onStall()
        delay(graceMillis)
    }
    try {
        select {
            completion.onAwait { it }
            watchdog.onJoin { onAbandon() }
        }
    } finally {
        watchdog.cancel()
    }
}

class FfmpegMediaTranscoder(
    private val executor: FfmpegExecutor,
    private val validator: MediaValidator,
) : MediaTranscoder {
    override suspend fun transcode(source: File, output: File, format: OutputFormat): File {
        val commands = when (format) {
            OutputFormat.MP3 -> listOf(TranscodeCommandFactory.mp3(source, output))
            OutputFormat.MP4 -> TranscodeCommandFactory.mp4Fallbacks(source, output)
        }
        var lastOutput = ""
        for (command in commands) {
            if (output.exists() && !output.delete()) {
                throw ConversionException(ConversionFailure.PROCESSING_FAILED, "Unable to replace partial output")
            }
            val execution = executor.execute(command)
            lastOutput = execution.output.takeLast(2_000)
            if (execution.succeeded && validator.isValid(output, format)) return output
        }
        throw ConversionException(
            ConversionFailure.PROCESSING_FAILED,
            "No compatible FFmpeg conversion path succeeded: $lastOutput",
        )
    }

    override fun cancel() = executor.cancel()
}

open class AndroidMediaValidator : MediaValidator {
    override fun inspect(file: File): MediaInspection {
        if (!file.isFile || file.length() <= 0L) {
            return MediaInspection(0, 0, hasAudio = false, hasVideo = false)
        }
        val extractor = AndroidMediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasAudio = false
            var hasVideo = false
            var durationMicros = 0L
            repeat(extractor.trackCount) { index ->
                val track = extractor.getTrackFormat(index)
                val mime = track.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                hasAudio = hasAudio || mime.startsWith("audio/")
                hasVideo = hasVideo || mime.startsWith("video/")
                if (track.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    durationMicros = maxOf(
                        durationMicros,
                        track.getLong(android.media.MediaFormat.KEY_DURATION),
                    )
                }
            }
            MediaInspection(file.length(), durationMicros / 1_000L, hasAudio, hasVideo)
        } finally {
            extractor.release()
        }
    }

    override fun isValid(file: File, format: OutputFormat): Boolean {
        if (!file.name.endsWith(".${format.extension}", ignoreCase = true)) return false
        val inspection = try {
            inspect(file)
        } catch (_: Exception) {
            return false
        }
        if (inspection.sizeBytes <= 0L || inspection.durationMillis <= 0L) return false
        return when (format) {
            OutputFormat.MP3 -> inspection.hasAudio && !inspection.hasVideo
            OutputFormat.MP4 -> inspection.hasAudio && inspection.hasVideo
        }
    }
}

class MediaStorePublisher(
    private val context: Context,
    private val validator: MediaValidator,
) : MediaPublisher {
    override suspend fun publish(
        source: File,
        displayName: String,
        format: OutputFormat,
    ): SavedMedia {
        var committedUri: Uri? = null
        var committedFile: File? = null
        return try {
            withContext(Dispatchers.IO) {
                if (!validator.isValid(source, format)) {
                    throw ConversionException(ConversionFailure.PROCESSING_FAILED, "Output validation failed")
                }
                val inspection = validator.inspect(source)
                val safeName = sanitizeMediaDisplayName(displayName, format)
                val resolver = context.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MediaConverter")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw ConversionException(ConversionFailure.STORAGE_FULL, "Unable to create MediaStore row")
            try {
                val copyContext = currentCoroutineContext()
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            copyContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw ConversionException(ConversionFailure.STORAGE_FULL, "Unable to open MediaStore output")
                copyContext.ensureActive()
                val finalized = resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                if (finalized != 1) {
                    throw ConversionException(
                        ConversionFailure.STORAGE_FULL,
                        "Unable to finalize MediaStore output",
                    )
                }
                committedUri = uri
                SavedMedia(
                    contentUri = uri.toString(),
                    displayName = safeName,
                    mimeType = format.mimeType,
                    sizeBytes = inspection.sizeBytes,
                    durationMillis = inspection.durationMillis,
                )
            } catch (failure: Throwable) {
                resolver.delete(uri, null, null)
                throw failure
            }
                } else {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MediaConverter",
            )
            if (!directory.exists() && !directory.mkdirs()) {
                // Android 8-9: the public Downloads folder needs WRITE_EXTERNAL_STORAGE. A denied
                // permission is not transient, so report it as a storage problem instead of a
                // generic download failure that would be retried (and re-downloaded) three times.
                throw ConversionException(
                    ConversionFailure.STORAGE_FULL,
                    "Unable to create Downloads directory (storage permission denied or storage unavailable)",
                )
            }
            val destination = uniqueLegacyDestination(directory, safeName, format)
            // Fixed-length temporary name. Embedding the destination name here could push the
            // temp file past the 255-byte file-name limit even though the destination fits.
            val pending = File(directory, ".${UUID.randomUUID()}.pending")
            var uri: android.net.Uri? = null
            try {
                val copyContext = currentCoroutineContext()
                pending.outputStream().use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            copyContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                copyContext.ensureActive()
                if (!pending.renameTo(destination)) {
                    throw ConversionException(
                        ConversionFailure.STORAGE_FULL,
                        "Unable to atomically publish legacy output",
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, destination.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                    @Suppress("DEPRECATION")
                    put(MediaStore.MediaColumns.DATA, destination.absolutePath)
                    put(MediaStore.MediaColumns.SIZE, destination.length())
                }
                uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: throw ConversionException(ConversionFailure.STORAGE_FULL, "Unable to index legacy output")
                committedUri = uri
                committedFile = destination
                SavedMedia(
                    contentUri = uri.toString(),
                    displayName = destination.name,
                    mimeType = format.mimeType,
                    sizeBytes = destination.length(),
                    durationMillis = inspection.durationMillis,
                )
            } catch (failure: Throwable) {
                uri?.let { resolver.delete(it, null, null) }
                destination.delete()
                throw failure
            } finally {
                pending.delete()
            }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                committedUri?.let { context.contentResolver.delete(it, null, null) }
                committedFile?.delete()
            }
            throw cancelled
        }
    }

    private fun uniqueLegacyDestination(
        directory: File,
        safeName: String,
        format: OutputFormat,
    ): File {
        val preferred = File(directory, safeName)
        if (!preferred.exists()) return preferred
        val stem = safeName.removeSuffix(".${format.extension}")
        return generateSequence(2) { it + 1 }
            .map { File(directory, "$stem-$it.${format.extension}") }
            .first { !it.exists() }
    }

}

/**
 * Upper bound on the UTF-8 size of a published file name. Linux file systems reject names longer
 * than 255 bytes and MediaStore may append " (n)" to de-duplicate, so stay comfortably below that.
 * Hebrew is two bytes per character and emoji four, so a character count alone is not enough.
 * Nothing else may be appended to this name on disk: the legacy publish path writes through a
 * temporary file with its own fixed-length name for exactly that reason.
 */
internal const val MAX_DISPLAY_NAME_BYTES = 240

internal fun sanitizeMediaDisplayName(displayName: String, format: OutputFormat): String {
    val suffix = ".${format.extension}"
    // Only strip the extension we are about to add back. Titles routinely contain periods
    // ("Mr. Bean", "v1.0"), so cutting at the last period would drop most of the name.
    val stem = if (displayName.endsWith(suffix, ignoreCase = true)) {
        displayName.dropLast(suffix.length)
    } else {
        displayName
    }
    val safe = stem
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        // A leading period would make the file hidden in file managers and media scanners.
        .trimStart('.')
        .trim()
        .ifBlank { "media" }
    return truncateToUtf8Bytes(safe, MAX_DISPLAY_NAME_BYTES - suffix.length).trimEnd() + suffix
}

private fun truncateToUtf8Bytes(value: String, maxBytes: Int): String {
    val builder = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        builder.appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    return builder.toString()
}

class ConversionEngine(
    private val cacheRoot: File,
    private val extractor: MediaExtractor,
    private val transcoder: MediaTranscoder,
    private val publisher: MediaPublisher,
    private val validator: MediaValidator,
) {
    suspend fun convert(
        request: ConversionRequest,
        displayName: String,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Result<SavedMedia> {
        val requestDirectory = File(
            cacheRoot,
            "conversion-${request.downloadId}-${UUID.randomUUID()}",
        )
        return try {
            check(requestDirectory.mkdirs()) { "Unable to create request directory" }
            val source = extractor.downloadSource(request, requestDirectory, onProgress)
            val output = File(requestDirectory, "result.${request.outputFormat.extension}")
            val converted = transcoder.transcode(source, output, request.outputFormat)
            if (!validator.isValid(converted, request.outputFormat)) {
                throw ConversionException(ConversionFailure.PROCESSING_FAILED, "Converted file is invalid")
            }
            Result.success(publisher.publish(converted, displayName, request.outputFormat))
        } catch (cancelled: CancellationException) {
            transcoder.cancel()
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(
                failure as? ConversionException
                    ?: ConversionException(
                        ConversionFailureMapper.fromThrowable(failure),
                        failure.message ?: "Conversion failed",
                        failure,
                    ),
            )
        } finally {
            requestDirectory.deleteRecursively()
        }
    }

    companion object {
        fun create(context: Context): ConversionEngine {
            val validator = AndroidMediaValidator()
            return ConversionEngine(
                cacheRoot = File(context.cacheDir, "conversions"),
                extractor = YtDlpMediaExtractor(YtDlpRuntimeProvider.get(context)),
                transcoder = FfmpegMediaTranscoder(FfmpegKitExecutor(), validator),
                publisher = MediaStorePublisher(context, validator),
                validator = validator,
            )
        }
    }
}
