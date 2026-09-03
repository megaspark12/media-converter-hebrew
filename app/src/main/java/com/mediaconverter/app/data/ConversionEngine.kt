package com.mediaconverter.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.media.MediaExtractor as AndroidMediaExtractor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

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
    fun create(request: ConversionRequest, outputTemplate: String): List<String> =
        createForClient(
            request,
            outputTemplate,
            youtubeClient = if (request.source.platform == SupportedPlatform.YOUTUBE) {
                "web_embedded"
            } else {
                null
            },
        )

    fun createAttempts(request: ConversionRequest, outputTemplate: String): List<List<String>> =
        if (request.source.platform == SupportedPlatform.YOUTUBE) {
            listOf("web_embedded", "android_vr").map { client ->
                createForClient(request, outputTemplate, youtubeClient = client)
            }
        } else {
            listOf(createForClient(request, outputTemplate, youtubeClient = null))
        }

    private fun createForClient(
        request: ConversionRequest,
        outputTemplate: String,
        youtubeClient: String?,
    ): List<String> {
        val formatSelector = when (request.outputFormat) {
            OutputFormat.MP3 -> "bestaudio/best"
            OutputFormat.MP4 -> {
                val height = request.quality.toIntOrNull()
                if (height == null) {
                    "best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best"
                } else {
                    "best[height<=$height][ext=mp4][vcodec!=none][acodec!=none]/" +
                        "best[height<=$height][vcodec!=none][acodec!=none]/best"
                }
            }
        }
        val common = listOf(
            "--no-playlist",
            "--socket-timeout", "20",
            "--retries", "3",
            "--fragment-retries", "3",
            "--no-part",
            "--force-overwrites",
            "-f", formatSelector,
            "-o", outputTemplate,
        )
        return if (youtubeClient != null) {
            listOf(
                "--extractor-args",
                "youtube:player_client=$youtubeClient",
            ) + common
        } else {
            common
        }
    }
}

class YtDlpMediaExtractor(private val runtime: YtDlpRuntime) : MediaExtractor {
    override suspend fun downloadSource(
        request: ConversionRequest,
        tempDirectory: File,
        onProgress: (Int, String) -> Unit,
    ): File {
        check(tempDirectory.exists() || tempDirectory.mkdirs()) { "Unable to create temporary directory" }
        val template = File(tempDirectory, "source.%(ext)s").absolutePath
        var finalFailure: ConversionException? = null
        for (arguments in YtDlpDownloadCommandFactory.createAttempts(request, template)) {
            tempDirectory.listFiles().orEmpty()
                .filter { it.name.startsWith("source.") }
                .forEach(File::delete)
            try {
                runtime.execute(
                    YtDlpCommand(
                        processId = "download-${request.downloadId}-${UUID.randomUUID()}",
                        sourceUrl = request.source.value,
                        arguments = arguments,
                        onProgress = onProgress,
                    ),
                )
                finalFailure = null
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val mapped = ConversionFailureMapper.fromThrowable(failure)
                finalFailure = ConversionException(
                    mapped,
                    failure.message ?: "yt-dlp download failed",
                    failure,
                )
                if (mapped != ConversionFailure.PROVIDER_CHANGED &&
                    mapped != ConversionFailure.NO_COMPATIBLE_FORMAT
                ) {
                    throw finalFailure
                }
            }
        }
        finalFailure?.let { throw it }
        val outputs = tempDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("source.") && !it.name.endsWith(".part") }
        if (outputs.size != 1 || outputs.single().length() <= 0L) {
            throw ConversionException(
                ConversionFailure.DOWNLOAD_FAILED,
                "yt-dlp did not produce exactly one complete source file",
            )
        }
        return outputs.single()
    }
}

data class FfmpegExecution(val succeeded: Boolean, val output: String)

interface FfmpegExecutor {
    suspend fun execute(arguments: List<String>): FfmpegExecution
    fun cancel()
}

class FfmpegKitExecutor : FfmpegExecutor {
    @Volatile
    private var activeSessionId: Long? = null

    override suspend fun execute(arguments: List<String>): FfmpegExecution =
        suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray()) { completed ->
                activeSessionId = null
                if (continuation.isActive) {
                    continuation.resume(
                        FfmpegExecution(
                            succeeded = ReturnCode.isSuccess(completed.returnCode),
                            output = completed.allLogsAsString.orEmpty(),
                        ),
                    )
                }
            }
            activeSessionId = session.sessionId
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    override fun cancel() {
        activeSessionId?.let(FFmpegKit::cancel)
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
                val safeName = sanitizeDisplayName(displayName, format)
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
            check(directory.exists() || directory.mkdirs()) { "Unable to create Downloads directory" }
            val destination = uniqueLegacyDestination(directory, safeName, format)
            val pending = File(directory, ".${destination.name}.${UUID.randomUUID()}.pending")
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

    private fun sanitizeDisplayName(displayName: String, format: OutputFormat): String {
        val withoutExtension = displayName.substringBeforeLast('.', displayName)
        val safe = withoutExtension
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(120)
            .ifBlank { "media" }
        return "$safe.${format.extension}"
    }
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
