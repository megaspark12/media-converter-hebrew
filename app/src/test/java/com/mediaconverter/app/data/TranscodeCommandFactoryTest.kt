package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranscodeCommandFactoryTest {
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
            File("/tmp/input.webm"),
            File("/tmp/output.mp4"),
        )

        assertEquals("copy", commands[0].valueAfter("-c"))
        assertEquals("h264_mediacodec", commands[1].valueAfter("-c:v"))
        assertEquals("mpeg4", commands[2].valueAfter("-c:v"))
        assertTrue(commands.all { it.last() == "/tmp/output.mp4" })
    }

    private fun List<String>.valueAfter(option: String): String? =
        getOrNull(indexOf(option) + 1)
}
