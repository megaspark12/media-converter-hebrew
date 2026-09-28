package com.mediaconverter.app

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediaconverter.app.data.MediaInfoProvider
import com.mediaconverter.app.data.NormalizedMediaUrl
import com.mediaconverter.app.data.OutputFormat
import com.mediaconverter.app.data.VideoInfo
import com.mediaconverter.app.data.db.DownloadEntity
import com.mediaconverter.app.ui.screens.HomeScreen
import com.mediaconverter.app.ui.theme.MediaConverterTheme
import com.mediaconverter.app.viewmodel.DownloadCommand
import com.mediaconverter.app.viewmodel.HomeUiState
import com.mediaconverter.app.viewmodel.HomeViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadShareInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var launchedIntent: Intent? = null

    @Test
    fun completedVideoSharesSavedFileEvenWhenSelectedFormatChanges() {
        val viewModel = showCompletedDownload("mp4", "video/mp4")
        composeRule.runOnIdle { viewModel.onFormatChange(OutputFormat.MP3) }

        composeRule.onNodeWithText("שתף").performScrollTo().assertIsDisplayed().performClick()
        assertSharedFile("video/mp4")

        composeRule.onNodeWithText("הורד עוד אחד").performScrollTo().performClick()
        composeRule.onNodeWithText("שתף").assertDoesNotExist()
    }

    @Test
    fun completedAudioSharesAudioFile() {
        showCompletedDownload("mp3", "audio/mpeg")

        composeRule.onNodeWithText("שתף").performScrollTo().performClick()

        assertSharedFile("audio/mpeg")
    }

    @Test
    fun enteringAnotherUrlRemovesThePreviousFileShareAction() {
        val viewModel = showCompletedDownload("mp4", "video/mp4")
        composeRule.onNodeWithText("שתף").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { viewModel.onUrlChange("https://youtu.be/another") }

        composeRule.onNodeWithText("שתף").assertDoesNotExist()
    }

    private fun showCompletedDownload(format: String, mimeType: String): HomeViewModel {
        val records = MutableSharedFlow<DownloadEntity?>(replay = 1)
        val command = object : DownloadCommand {
            override suspend fun start(state: HomeUiState) = 91L
            override fun observe(downloadId: Long) = records
            override suspend fun cancel(downloadId: Long) = Unit
        }
        val provider = object : MediaInfoProvider {
            override suspend fun getVideoInfo(url: NormalizedMediaUrl) =
                Result.success(VideoInfo("Test video", "", 1, url.platform.value))
        }
        val viewModel = HomeViewModel(provider, command)
        composeRule.setContent {
            val baseContext = LocalContext.current
            val context = object : ContextWrapper(baseContext) {
                override fun startActivity(intent: Intent) {
                    launchedIntent = intent
                }
            }
            CompositionLocalProvider(LocalContext provides context) {
                MediaConverterTheme { HomeScreen(viewModel = viewModel) }
            }
        }
        composeRule.onNodeWithText("שתף").assertDoesNotExist()
        composeRule.runOnIdle {
            viewModel.onUrlChange("https://youtu.be/completed")
            viewModel.onFormatChange(if (format == "mp3") OutputFormat.MP3 else OutputFormat.MP4)
        }
        composeRule.waitUntil(3_000) { viewModel.uiState.value.videoInfo != null }
        composeRule.onNodeWithText("הורד").performScrollTo().performClick()
        composeRule.waitUntil(3_000) { viewModel.uiState.value.activeDownloadId == 91L }
        composeRule.onNodeWithText("שתף").assertDoesNotExist()
        runBlocking {
            records.emit(
                DownloadEntity(
                    id = 91,
                    url = "https://youtu.be/completed",
                    title = "Test video",
                    format = format,
                    status = "completed",
                    progress = 100,
                    outputUri = "content://media/external/downloads/91",
                    mimeType = mimeType,
                ),
            )
        }
        composeRule.waitUntil(3_000) { viewModel.uiState.value.downloadCompleted }
        return viewModel
    }

    @Suppress("DEPRECATION")
    private fun assertSharedFile(mimeType: String) {
        composeRule.runOnIdle {
            val chooser = launchedIntent
            assertNotNull("Sharing must open Android's chooser", chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals(mimeType, send.type)
            assertEquals(
                Uri.parse("content://media/external/downloads/91"),
                send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM),
            )
            assertEquals("content://media/external/downloads/91", send.clipData!!.getItemAt(0).uri.toString())
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
        }
    }
}
