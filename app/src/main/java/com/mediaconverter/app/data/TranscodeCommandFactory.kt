package com.mediaconverter.app.data

import java.io.File

object TranscodeCommandFactory {
    /** Video codecs an MP4 may carry that every Android device, and most other players, decode. */
    private val mp4VideoCodecs = setOf("video/avc", "video/hevc")

    /** Audio codecs with the same guarantee; Opus and Vorbis fit in MP4 but are not in this set. */
    private val mp4AudioCodecs = setOf("audio/mp4a-latm", "audio/mpeg")

    fun mp3(input: File, output: File): List<String> = listOf(
        "-y",
        "-i", input.absolutePath,
        "-vn",
        "-c:a", "libmp3lame",
        "-b:a", "192k",
        "-id3v2_version", "3",
        output.absolutePath,
    )

    /**
     * The commands to try in order until one produces a valid MP4. A plain remux comes first only
     * when the source codecs are ones an MP4 player can rely on; VP9, AV1 or Opus would survive a
     * "-c copy" into a .mp4 that older phones and most other apps cannot decode, so those sources,
     * and sources whose codecs could not be read, go straight to the encoders.
     */
    fun mp4Fallbacks(input: File, output: File, codecs: SourceCodecs?): List<List<String>> {
        val mapping = listOf("-y", "-i", input.absolutePath, "-map", "0:v:0", "-map", "0:a:0?")
        val container = listOf("-movflags", "+faststart", output.absolutePath)
        val encodedAudio = listOf("-c:a", "aac", "-b:a", "192k")
        val remux = codecs?.takeIf { it.videoMime in mp4VideoCodecs }?.let { source ->
            val audio = if (source.audioMime == null || source.audioMime in mp4AudioCodecs) {
                listOf("-c:a", "copy")
            } else {
                encodedAudio
            }
            mapping + listOf("-c:v", "copy") + audio + container
        }
        return listOfNotNull(
            remux,
            mapping + listOf("-c:v", "h264_mediacodec") + encodedAudio + container,
            mapping + listOf("-c:v", "mpeg4", "-q:v", "3") + encodedAudio + container,
        )
    }
}
