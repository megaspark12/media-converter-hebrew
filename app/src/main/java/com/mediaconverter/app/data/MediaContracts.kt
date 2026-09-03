package com.mediaconverter.app.data

enum class SupportedPlatform(val value: String) {
    YOUTUBE("youtube"),
    FACEBOOK("facebook"),
}

data class NormalizedMediaUrl(
    val value: String,
    val platform: SupportedPlatform,
)

data class ConversionRequest(
    val downloadId: Long,
    val source: NormalizedMediaUrl,
    val outputFormat: OutputFormat,
    val quality: String = "best",
)

enum class OutputFormat(val value: String, val extension: String, val mimeType: String) {
    MP4("mp4", "mp4", "video/mp4"),
    MP3("mp3", "mp3", "audio/mpeg"),
}

enum class ConversionFailure(val code: String) {
    INVALID_URL("invalid_url"),
    OFFLINE("offline"),
    LOGIN_REQUIRED("login_required"),
    PROVIDER_CHANGED("provider_changed"),
    NO_COMPATIBLE_FORMAT("no_compatible_format"),
    STORAGE_FULL("storage_full"),
    DOWNLOAD_FAILED("download_failed"),
    PROCESSING_FAILED("processing_failed"),
    CANCELLED("cancelled"),
}

data class SavedMedia(
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMillis: Long,
)

class ConversionException(
    val failure: ConversionFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object ConversionFailureMapper {
    fun fromThrowable(failure: Throwable): ConversionFailure =
        (failure as? ConversionException)?.failure ?: fromMessage(
            generateSequence(failure) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" "),
        )

    fun fromMessage(rawMessage: String): ConversionFailure {
        val message = rawMessage.lowercase()
        return when {
            listOf("sign in", "login required", "private video", "cookies").any(message::contains) ->
                ConversionFailure.LOGIN_REQUIRED
            listOf("requested format", "no video formats", "no compatible format", "not embeddable").any(message::contains) ->
                ConversionFailure.NO_COMPATIBLE_FORMAT
            listOf("http error 403", "forbidden", "signature extraction", "nsig", "javascript runtime").any(message::contains) ->
                ConversionFailure.PROVIDER_CHANGED
            listOf("unable to resolve host", "network is unreachable", "failed to connect", "connection reset", "timed out").any(message::contains) ->
                ConversionFailure.OFFLINE
            listOf("enospc", "no space left", "disk full", "quota exceeded").any(message::contains) ->
                ConversionFailure.STORAGE_FULL
            else -> ConversionFailure.DOWNLOAD_FAILED
        }
    }
}
