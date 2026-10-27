package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranscodeCommandFactoryTest {
    private val input = File("/tmp/input.webm")
    private val output = File("/tmp/output.mp4")

    @Test
    fun mp3UsesLameAtPromisedBitrateWithArgumentArray() {
        val command = TranscodeCommandFactory.mp3(File("/tmp/input.m4a"), File("/tmp/output.mp3"))

        assertEquals("libmp3lame", command.valueAfter("-c:a"))
        assertEquals("192k", command.valueAfter("-b:a"))
        assertTrue(command.contains("-vn"))
        assertEquals("/tmp/input.m4a", command.valueAfter("-i"))
        assertEquals("/tmp/output.mp3", command.last())
    }

    @Test
    fun mp4FallbacksTryRemuxHardwareThenSoftware() {
        val commands = TranscodeCommandFactory.mp4Fallbacks(
            input, output, SourceCodecs(videoMime = "video/avc", audioMime = "audio/mp4a-latm"),
        )

        assertEquals("copy", commands[0].valueAfter("-c:v"))
        assertEquals("copy", commands[0].valueAfter("-c:a"))
        assertEquals("h264_mediacodec", commands[1].valueAfter("-c:v"))
        assertEquals("mpeg4", commands[2].valueAfter("-c:v"))
        assertTrue(commands.all { it.last() == "/tmp/output.mp4" })
    }

    @Test
    fun mp4DoesNotRemuxCodecsAnMp4PlayerCannotRelyOn() {
        // VP9, AV1 and Opus fit inside an MP4 container, but a plain "-c copy" then hands the
        // user a .mp4 that older phones and most other apps cannot decode. Those sources must
        // go straight to the encoders.
        val commands = TranscodeCommandFactory.mp4Fallbacks(
            input, output, SourceCodecs(videoMime = "video/x-vnd.on2.vp9", audioMime = "audio/opus"),
        )

        assertTrue(commands.none { it.contains("copy") })
        assertEquals("h264_mediacodec", commands.first().valueAfter("-c:v"))
    }

    @Test
    fun mp4KeepsH264VideoButReencodesOpusAudio() {
        val commands = TranscodeCommandFactory.mp4Fallbacks(
            input, output, SourceCodecs(videoMime = "video/avc", audioMime = "audio/opus"),
        )

        assertEquals("copy", commands[0].valueAfter("-c:v"))
        assertEquals("aac", commands[0].valueAfter("-c:a"))
    }

    @Test
    fun mp4RemuxesH264WithoutAnAudioTrack() {
        val commands = TranscodeCommandFactory.mp4Fallbacks(
            input, output, SourceCodecs(videoMime = "video/avc", audioMime = null),
        )

        assertEquals("copy", commands[0].valueAfter("-c:v"))
    }

    @Test
    fun mp4WithUnknownCodecsIsTranscoded() {
        val commands = TranscodeCommandFactory.mp4Fallbacks(input, output, null)

        assertFalse(commands.any { it.contains("copy") })
    }

    private fun List<String>.valueAfter(option: String): String? =
        getOrNull(indexOf(option) + 1)
}
