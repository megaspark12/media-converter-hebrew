package com.mediaconverter.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediaconverter.app.data.MediaInfoProvider
import com.mediaconverter.app.data.NormalizedMediaUrl
import com.mediaconverter.app.data.VideoInfo
import com.mediaconverter.app.data.db.DownloadEntity
import com.mediaconverter.app.ui.screens.HomeScreen
import com.mediaconverter.app.ui.theme.MediaConverterTheme
import com.mediaconverter.app.viewmodel.DownloadCommand
import com.mediaconverter.app.viewmodel.HomeUiState
import com.mediaconverter.app.viewmodel.HomeViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenPostDownloadInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completedDownloadShowsDownloadAnotherAction() {
        val command = ScreenDownloadCommand()
        val viewModel = HomeViewModel(ScreenMediaInfoProvider(), command)
        composeRule.setContent {
            MediaConverterTheme {
                HomeScreen(viewModel = viewModel)
            }
        }

        composeRule.runOnIdle { viewModel.onUrlChange("https://youtu.be/completed") }
        composeRule.waitUntil(timeoutMillis = 3_000) { viewModel.uiState.value.videoInfo != null }
        composeRule.onNodeWithText("הורד").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { viewModel.uiState.value.activeDownloadId == 91L }

        runBlocking {
            command.records.emit(
                DownloadEntity(
                    id = 91,
                    url = "https://youtu.be/completed",
                    status = "completed",
                    progress = 100,
                ),
            )
        }

        composeRule.waitUntil(timeoutMillis = 3_000) { viewModel.uiState.value.downloadCompleted }
        composeRule.onNodeWithText("הורד עוד אחד").assertIsDisplayed()
        composeRule.onNodeWithText("הורד עוד אחד").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { viewModel.uiState.value.url.isEmpty() }
        composeRule.onAllNodesWithText("הורד עוד אחד").assertCountEquals(0)
        composeRule.onAllNodesWithText("Test video").assertCountEquals(0)
    }

    @Test
    fun cancellationRestoresDownloadActionAndDismissesConfirmation() {
        val command = ScreenDownloadCommand()
        val viewModel = HomeViewModel(ScreenMediaInfoProvider(), command)
        composeRule.setContent {
            MediaConverterTheme {
                HomeScreen(viewModel = viewModel)
            }
        }

        composeRule.runOnIdle { viewModel.onUrlChange("https://youtu.be/cancel") }
        composeRule.waitUntil(timeoutMillis = 3_000) { viewModel.uiState.value.videoInfo != null }
        composeRule.onNodeWithText("הורד").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { viewModel.uiState.value.activeDownloadId == 91L }
        composeRule.onNodeWithText("ביטול הורדה").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { !viewModel.uiState.value.isDownloading }
        composeRule.onNodeWithText("הורד").assertIsDisplayed()
        composeRule.onNodeWithText("ההורדה בוטלה").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 4_000) { viewModel.uiState.value.downloadMessage == null }
        composeRule.onAllNodesWithText("ההורדה בוטלה").assertCountEquals(0)
    }
}

private class ScreenMediaInfoProvider : MediaInfoProvider {
    override suspend fun getVideoInfo(url: NormalizedMediaUrl): Result<VideoInfo> =
        Result.success(VideoInfo("Test video", "", 1, url.platform.value))
}

private class ScreenDownloadCommand : DownloadCommand {
    val records = MutableSharedFlow<DownloadEntity?>(replay = 1)

    override suspend fun start(state: HomeUiState): Long = 91

    override fun observe(downloadId: Long): Flow<DownloadEntity?> = records

    override suspend fun cancel(downloadId: Long) {
        records.emit(
            DownloadEntity(
                id = downloadId,
                url = "https://youtu.be/cancel",
                status = "cancelled",
            ),
        )
    }
}
