package com.mediaconverter.app.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class VideoInfo(
    val title: String,
    val thumbnailUrl: String,
    val duration: Long,
    val platform: String,
)

enum class MediaFormat(val value: String, val extension: String) {
    MP4("mp4", "mp4"),
    MP3("mp3", "mp3"),
}

enum class VideoQuality(val value: String, val label: String) {
    Q360("360", "360p"),
    Q480("480", "480p"),
    Q720("720", "720p"),
    Q1080("1080", "1080p"),
    BEST("best", "הכי טוב"),
}

enum class AudioQuality(val value: String, val label: String) {
    Q128("128", "128 kbps"),
    Q192("192", "192 kbps"),
    Q320("320", "320 kbps"),
}

class DownloadEngine(private val context: Context) {

    companion object {
        private const val TAG = "DownloadEngine"

        fun detectPlatform(url: String): String {
            return when {
                url.contains("youtube.com") || url.contains("youtu.be") -> "youtube"
                url.contains("facebook.com") || url.contains("fb.watch") || url.contains("fb.com") -> "facebook"
                else -> "other"
            }
        }

        fun isValidUrl(url: String): Boolean {
            return url.startsWith("http://") || url.startsWith("https://")
        }
    }

    private val downloadDir: File
        get() {
            // Use app-specific external files dir to avoid Permission Denied on Android 11+
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MediaConverterTemp")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    suspend fun getVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--dump-json")
            request.addOption("--no-download")

            val response = YoutubeDL.getInstance().execute(request)
            val output = response.out

            // Parse JSON with JSONObject to properly decode Unicode escapes like \u05e4
            val jsonObj = org.json.JSONObject(output)
            val title = jsonObj.optString("title", "סרטון")
            val thumbnail = jsonObj.optString("thumbnail", "")
            val duration = jsonObj.optLong("duration", 0L)

            Result.success(
                VideoInfo(
                    title = title,
                    thumbnailUrl = thumbnail,
                    duration = duration,
                    platform = detectPlatform(url),
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video info", e)
            Result.failure(e)
        }
    }

    suspend fun download(
        url: String,
        format: MediaFormat,
        videoQuality: VideoQuality = VideoQuality.BEST,
        audioQuality: AudioQuality = AudioQuality.Q192,
        onProgress: (Int, String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(url)

            // Set output template
            val outputTemplate = "${downloadDir.absolutePath}/%(title)s.%(ext)s"
            request.addOption("-o", outputTemplate)

            when (format) {
                MediaFormat.MP4 -> {
                    // Use pre-merged mp4 to avoid FFmpeg dependency and merging issues
                    request.addOption("-f", "best[ext=mp4]/best")
                }
                MediaFormat.MP3 -> {
                    // Download raw m4a/audio to avoid FFmpeg extraction crash on Android 15
                    request.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                }
            }

            // Avoid geo-restriction issues
            request.addOption("--no-check-certificates")

            var lastProgress = 0
            var outputFilePath = ""

            val response = YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                val progressInt = progress.toInt().coerceIn(0, 100)
                if (progressInt > lastProgress) {
                    lastProgress = progressInt
                    val eta = if (etaInSeconds > 0) "${etaInSeconds} שניות" else ""
                    onProgress(progressInt, eta)
                }
                // Try to capture output file path
                if (line != null && line.contains("[download] Destination:")) {
                    outputFilePath = line.substringAfter("[download] Destination:").trim()
                }
            }

            // Find the downloaded file
            val downloadedFile = if (outputFilePath.isNotEmpty()) {
                File(outputFilePath)
            } else {
                // Find the most recently modified file in the download directory
                downloadDir.listFiles()
                    ?.filter { it.isFile }
                    ?.maxByOrNull { it.lastModified() }
            }

            if (downloadedFile != null && downloadedFile.exists()) {
                val publicFile = copyToPublicDownloads(downloadedFile, format)
                Result.success(publicFile ?: downloadedFile)
            } else {
                Result.failure(Exception("קובץ ההורדה לא נמצא"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }

    private fun copyToPublicDownloads(tempFile: File, format: MediaFormat): File? {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, tempFile.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (format == MediaFormat.MP4) "video/mp4" else "audio/*")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/MediaConverter")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        tempFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    tempFile.delete()
                    val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MediaConverter")
                    return File(publicDir, tempFile.name)
                }
            } else {
                // For older Android versions
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MediaConverter")
                if (!publicDir.exists()) publicDir.mkdirs()
                val destFile = File(publicDir, tempFile.name)
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
                return destFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to public downloads", e)
        }
        return null
    }

}
