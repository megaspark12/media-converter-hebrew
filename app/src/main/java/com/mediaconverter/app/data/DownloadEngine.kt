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
    /** 1-based item of a carousel or multi-video post that holds the video; null for a single video. */
    val playlistIndex: Int? = null,
)

/**
 * Reads yt-dlp's `--dump-single-json` output. A carousel or a post with several videos comes back
 * as a playlist whose entries list every item, photos included, and a photo has no formats. The
 * first entry that has a video is the one described here and, through [VideoInfo.playlistIndex],
 * the one the download fetches. A post with no video anywhere is [ConversionFailure.NO_VIDEO].
 */
internal fun parseVideoInfo(stdout: String, platform: SupportedPlatform): VideoInfo {
    val json = JSONObject(stdout)
    val entries = json.optJSONArray("entries")
    val chosen = entries?.let { list ->
        (0 until list.length()).firstOrNull { list.optJSONObject(it).hasFormats() }
            ?: throw ConversionException(ConversionFailure.NO_VIDEO, "No item of this post has a video")
    }
    val entry = chosen?.let { entries.optJSONObject(it) }
    if (entry == null && !json.hasFormats()) {
        throw ConversionException(ConversionFailure.NO_VIDEO, "This post has no video")
    }
    return VideoInfo(
        title = json.optString("title").ifBlank { entry?.optString("title").orEmpty() }.ifBlank { "סרטון" },
        thumbnailUrl = entry?.optString("thumbnail").orEmpty().ifBlank { json.optString("thumbnail") },
        duration = (entry?.optLong("duration", 0L) ?: 0L).takeIf { it > 0L } ?: json.optLong("duration", 0L),
        platform = platform.value,
        playlistIndex = chosen?.plus(1),
    )
}

private fun JSONObject?.hasFormats(): Boolean = (this?.optJSONArray("formats")?.length() ?: 0) > 0

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
