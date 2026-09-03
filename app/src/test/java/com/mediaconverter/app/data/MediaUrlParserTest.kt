package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlParserTest {
    @Test
    fun extractsYoutubeUrlFromSharedText() {
        val result = MediaUrlParser.parse("כדאי לצפות https://youtu.be/abc123?t=5 עכשיו")

        assertEquals("https://youtu.be/abc123?t=5", result?.value)
        assertEquals(SupportedPlatform.YOUTUBE, result?.platform)
    }

    @Test
    fun normalizesRecognizedHttpLinksToHttps() {
        val result = MediaUrlParser.parse("http://m.facebook.com/reel/12345")

        assertEquals("https://m.facebook.com/reel/12345", result?.value)
        assertEquals(SupportedPlatform.FACEBOOK, result?.platform)
    }

    @Test
    fun supportsYoutubeAndFacebookHostVariants() {
        val cases = mapOf(
            "https://youtube.com/watch?v=abc" to SupportedPlatform.YOUTUBE,
            "https://www.youtube.com/shorts/abc" to SupportedPlatform.YOUTUBE,
            "https://music.youtube.com/watch?v=abc" to SupportedPlatform.YOUTUBE,
            "https://fb.watch/abc/" to SupportedPlatform.FACEBOOK,
            "https://www.facebook.com/watch/?v=123" to SupportedPlatform.FACEBOOK,
            "https://mobile.facebook.com/reel/123" to SupportedPlatform.FACEBOOK,
        )

        cases.forEach { (url, platform) ->
            assertEquals(platform, MediaUrlParser.parse(url)?.platform)
        }
    }

    @Test
    fun rejectsLookalikeAndUnsupportedHosts() {
        val cases = listOf(
            "https://youtube.com.evil.example/watch?v=abc",
            "https://evil.example/?next=https://youtube.com/watch?v=abc",
            "javascript:https://youtu.be/abc",
            "https://vimeo.com/123",
        )

        cases.forEach { value -> assertNull(value, MediaUrlParser.parse(value)) }
    }

    @Test
    fun stripsPunctuationAroundSharedUrl() {
        val result = MediaUrlParser.parse("Watch this: (https://www.facebook.com/reel/12345).")

        assertEquals("https://www.facebook.com/reel/12345", result?.value)
    }
}
