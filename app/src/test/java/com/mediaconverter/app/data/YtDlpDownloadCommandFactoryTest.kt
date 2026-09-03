package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class YtDlpDownloadCommandFactoryTest {
    @Test
    fun youtubeUsesNonPoTokenClientsBeforeProviderFallback() {
        val request = ConversionRequest(
            1,
            NormalizedMediaUrl("https://youtu.be/example", SupportedPlatform.YOUTUBE),
            OutputFormat.MP4,
        )

        val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")

        assertEquals(
            "youtube:player_client=web_embedded",
            arguments.valueAfter("--extractor-args"),
        )
        assertEquals(
            "best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best",
            arguments.valueAfter("-f"),
        )
        assertFalse(arguments.contains("--no-check-certificates"))

        val clients = YtDlpDownloadCommandFactory.createAttempts(request, "/tmp/source.%(ext)s")
            .map { it.valueAfter("--extractor-args") }
        assertEquals(
            listOf("youtube:player_client=web_embedded", "youtube:player_client=android_vr"),
            clients,
        )
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

    private fun List<String>.valueAfter(option: String): String? = getOrNull(indexOf(option) + 1)
}
