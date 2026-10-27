package com.mediaconverter.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.UUID

data class VideoInfo(
    val title: String,
    val thumbnailUrl: String,
    val duration: Long,
    val platform: String,
)

/**
 * Reads yt-dlp's `--dump-single-json` output. A carousel or a post with several videos comes back
 * as a playlist object whose own fields are sparse; the first entry (the only one, with
 * `--playlist-items 1`) then supplies what the top level lacks.
 */
internal fun parseVideoInfo(stdout: String, platform: SupportedPlatform): VideoInfo {
    val json = JSONObject(stdout)
    val entry = json.optJSONArray("entries")?.optJSONObject(0)
    fun text(name: String): String =
        json.optString(name).ifBlank { entry?.optString(name).orEmpty() }
    val duration = json.optLong("duration", 0L).takeIf { it > 0L }
        ?: entry?.optLong("duration", 0L)
        ?: 0L
    return VideoInfo(
        title = text("title").ifBlank { "סרטון" },
        thumbnailUrl = text("thumbnail"),
        duration = duration,
        platform = platform.value,
    )
}

class DownloadEngine(
    context: Context,
    private val ytDlpRuntime: YtDlpRuntime = YtDlpRuntimeProvider.get(context),
) : MediaInfoProvider {

    override suspend fun getVideoInfo(url: NormalizedMediaUrl): Result<VideoInfo> = try {
        val response = ytDlpRuntime.metadata(url, "metadata-${UUID.randomUUID()}")
        Result.success(parseVideoInfo(response.stdout, url.platform))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    suspend fun getVideoInfo(url: String): Result<VideoInfo> =
        MediaUrlParser.parse(url)?.let { getVideoInfo(it) }
            ?: Result.failure(IllegalArgumentException("Unsupported media URL"))
}
