package com.mediaconverter.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediaconverter.app.data.ConversionEngine
import com.mediaconverter.app.data.ConversionRequest
import com.mediaconverter.app.data.MediaUrlParser
import com.mediaconverter.app.data.OutputFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveConversionSmokeTest {
    @Test
    fun shortYoutubeFixtureCompletesAsRealMp4AndMp3() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        @Suppress("DEPRECATION")
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runLive") == "true")
        val context = instrumentation.targetContext
        val source = requireNotNull(MediaUrlParser.parse(YOUTUBE_DL_TEST_VIDEO))
        val engine = ConversionEngine.create(context)

        OutputFormat.entries.forEachIndexed { index, format ->
            val saved = engine.convert(
                ConversionRequest(90_001L + index, source, format),
                "yt-dlp-live-smoke-${format.value}",
            ).getOrThrow()
            val uri = Uri.parse(saved.contentUri)
            try {
                assertEquals("content", uri.scheme)
                assertEquals(format.mimeType, saved.mimeType)
                assertTrue(saved.displayName.endsWith(".${format.extension}"))
                assertTrue(saved.sizeBytes > 0L)
                assertTrue(saved.durationMillis > 0L)
                context.contentResolver.openInputStream(uri).use { input ->
                    assertTrue(input != null && input.read() >= 0)
                }
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    private companion object {
        const val YOUTUBE_DL_TEST_VIDEO = "https://www.youtube.com/watch?v=YE7VzlLtp-4"
    }
}
