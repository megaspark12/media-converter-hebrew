package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpDownloadCommandFactoryTest {
    @Test
    fun youtubeUsesMaintainedClientDefaultsAndCanMergeSeparateStreams() {
        val request = ConversionRequest(
            1,
            NormalizedMediaUrl("https://youtu.be/example", SupportedPlatform.YOUTUBE),
            OutputFormat.MP4,
        )

        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")

        assertFalse(arguments.contains("--extractor-args"))
        assertEquals("bestvideo+bestaudio/best", arguments.valueAfter("-f"))
        assertEquals("mkv", arguments.valueAfter("--merge-output-format"))
        assertFalse(arguments.contains("--no-check-certificates"))
        assertTrue(arguments.contains("--no-playlist"))
        assertEquals("/tmp/source.%(ext)s", arguments.valueAfter("-o"))
    }

    @Test
    fun qualityLimitAppliesToAdaptiveAndCombinedStreams() {
        val request = ConversionRequest(
            1, NormalizedMediaUrl("https://youtu.be/example", SupportedPlatform.YOUTUBE),
            OutputFormat.MP4, "720",
        )
        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")
        assertEquals("bestvideo[height<=720]+bestaudio/best[height<=720]", arguments.valueAfter("-f"))
    }

    @Test
    fun audioDownloadsPreferAudioWithoutRequiringAVideoMerge() {
        val request = ConversionRequest(
            1, NormalizedMediaUrl("https://youtu.be/example", SupportedPlatform.YOUTUBE),
            OutputFormat.MP3,
        )
        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")
        assertEquals("bestaudio/best", arguments.valueAfter("-f"))
        assertFalse(arguments.contains("--merge-output-format"))
        assertFalse(arguments.contains("--extractor-args"))
    }

    @Test
    fun facebookDoesNotReceiveYoutubeExtractorArguments() {
        val request = ConversionRequest(
            1,
            NormalizedMediaUrl("https://fb.watch/example", SupportedPlatform.FACEBOOK),
            OutputFormat.MP4,
        )

        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")

        assertFalse(arguments.contains("--extractor-args"))
    }

    private fun List<String>.valueAfter(option: String): String? =
        indexOf(option).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
}
