package com.mediaconverter.app.data

import java.io.File

object TranscodeCommandFactory {
    fun mp3(input: File, output: File): List<String> = listOf(
        "-y",
        "-i", input.absolutePath,
        "-vn",
        "-c:a", "libmp3lame",
        "-b:a", "192k",
        "-id3v2_version", "3",
        output.absolutePath,
    )

    fun mp4Fallbacks(input: File, output: File): List<List<String>> = listOf(
        listOf(
            "-y", "-i", input.absolutePath,
            "-map", "0:v:0", "-map", "0:a:0?",
            "-c", "copy", "-movflags", "+faststart",
            output.absolutePath,
        ),
        listOf(
            "-y", "-i", input.absolutePath,
            "-map", "0:v:0", "-map", "0:a:0?",
            "-c:v", "h264_mediacodec", "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart",
            output.absolutePath,
        ),
        listOf(
            "-y", "-i", input.absolutePath,
            "-map", "0:v:0", "-map", "0:a:0?",
            "-c:v", "mpeg4", "-q:v", "3", "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart",
            output.absolutePath,
        ),
    )
}
