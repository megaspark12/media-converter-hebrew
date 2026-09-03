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

class DownloadEngine(
    context: Context,
    private val ytDlpRuntime: YtDlpRuntime = YtDlpRuntimeProvider.get(context),
) : MediaInfoProvider {

    override suspend fun getVideoInfo(url: NormalizedMediaUrl): Result<VideoInfo> = try {
        val response = ytDlpRuntime.metadata(url, "metadata-${UUID.randomUUID()}")
        val json = JSONObject(response.stdout)
        Result.success(
            VideoInfo(
                title = json.optString("title", "סרטון"),
                thumbnailUrl = json.optString("thumbnail", ""),
                duration = json.optLong("duration", 0L),
                platform = url.platform.value,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    suspend fun getVideoInfo(url: String): Result<VideoInfo> =
        MediaUrlParser.parse(url)?.let { getVideoInfo(it) }
            ?: Result.failure(IllegalArgumentException("Unsupported media URL"))
}
