package com.mediaconverter.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediaconverter.app.data.DownloadEngine
import com.mediaconverter.app.data.MediaFormat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadEngineTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
        // Ensure we are updated before test
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
    }

    @Test
    fun testGetVideoInfo() = runBlocking {
        val engine = DownloadEngine(context)
        val result = engine.getVideoInfo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(result.isSuccess)
    }
}
