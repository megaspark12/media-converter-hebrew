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
        // H.264 video with AAC audio is what an MP4 player can rely on, so it is asked for
        // first; anything else is still accepted and transcoded later.
        assertEquals(
            "bestvideo[vcodec^=avc1]+bestaudio[acodec^=mp4a]/bestvideo+bestaudio/best",
            arguments.valueAfter("-f"),
        )
        assertEquals("mkv", arguments.valueAfter("--merge-output-format"))
        assertFalse(arguments.contains("--no-check-certificates"))
        assertTrue(arguments.contains("--no-playlist"))
        assertEquals("/tmp/source.%(ext)s", arguments.valueAfter("-o"))
    }

    @Test
    fun interruptedStreamsKeepTheirPartSuffix() {
        // The extractor treats a stream under its final name as complete. With --no-part an
        // interrupted stream also carried its final name, so a failed download could be
        // merged and published as if it had finished.
        val request = ConversionRequest(
            1, NormalizedMediaUrl("https://youtu.be/example", SupportedPlatform.YOUTUBE), OutputFormat.MP4,
        )

        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")

        assertFalse(arguments.contains("--no-part"))
        assertTrue(arguments.contains("--abort-on-unavailable-fragments"))
    }

    @Test
    fun qualityLimitAppliesToAdaptiveAndCombinedStreams() {
        val request = ConversionRequest(
            1, NormalizedMediaUrl("https://youtu.be/example", SupportedPlatform.YOUTUBE),
            OutputFormat.MP4, "720",
        )
        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")
        assertEquals(
            "bestvideo[vcodec^=avc1][height<=720]+bestaudio[acodec^=mp4a]/" +
                "bestvideo[height<=720]+bestaudio/best[height<=720]",
            arguments.valueAfter("-f"),
        )
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

    @Test
    fun downloadsTheChosenItemOfAMultiItemPost() {
        val request = ConversionRequest(
            1, NormalizedMediaUrl("https://www.instagram.com/p/carousel/", SupportedPlatform.INSTAGRAM),
            OutputFormat.MP4, playlistIndex = 2,
        )

        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")

        assertEquals("2", arguments.valueAfter("--playlist-items"))
        assertTrue(arguments.contains("--no-playlist"))
    }

    @Test
    fun aSingleVideoSelectsNoItem() {
        val request = ConversionRequest(
            1, NormalizedMediaUrl("https://youtu.be/example", SupportedPlatform.YOUTUBE), OutputFormat.MP4,
        )

        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")

        assertFalse(arguments.contains("--playlist-items"))
    }

    private fun List<String>.valueAfter(option: String): String? =
        indexOf(option).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
}
