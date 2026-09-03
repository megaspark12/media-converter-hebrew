package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTextParserTest {
    @Test
    fun acceptsPlainTextSendAndExtractsFirstSupportedUrl() {
        val result = ShareTextParser.parse(
            action = "android.intent.action.SEND",
            mimeType = "text/plain",
            text = "צפו כאן https://m.facebook.com/reel/123 תודה",
        )

        assertEquals("https://m.facebook.com/reel/123", result?.value)
        assertEquals(SupportedPlatform.FACEBOOK, result?.platform)
    }

    @Test
    fun rejectsOtherActionsAndMimeTypes() {
        assertNull(ShareTextParser.parse("android.intent.action.VIEW", "text/plain", "https://youtu.be/abc"))
        assertNull(ShareTextParser.parse("android.intent.action.SEND", "image/png", "https://youtu.be/abc"))
    }
}
