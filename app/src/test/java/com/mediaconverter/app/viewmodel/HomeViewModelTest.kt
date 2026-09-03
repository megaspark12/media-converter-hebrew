package com.mediaconverter.app.viewmodel

import com.mediaconverter.app.data.MediaInfoProvider
import com.mediaconverter.app.data.NormalizedMediaUrl
import com.mediaconverter.app.data.SupportedPlatform
import com.mediaconverter.app.data.VideoInfo
import com.mediaconverter.app.data.db.DownloadEntity
import com.mediaconverter.app.test.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun waitsForDebounceBeforeFetchingMetadata() = runTest(mainDispatcherRule.dispatcher) {
        val provider = FakeMediaInfoProvider()
        val viewModel = HomeViewModel(provider)

        viewModel.onUrlChange("https://youtu.be/abc123")
        advanceTimeBy(599)
        runCurrent()
        assertTrue(provider.requestedUrls.isEmpty())

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(listOf("https://youtu.be/abc123"), provider.requestedUrls)
        assertTrue(viewModel.uiState.value.linkState is LinkUiState.Ready)
    }

    @Test
    fun latestInputCancelsStaleMetadataAndWinsState() = runTest(mainDispatcherRule.dispatcher) {
        val provider = FakeMediaInfoProvider(delays = mapOf("first" to 5_000L))
        val viewModel = HomeViewModel(provider)

        viewModel.onUrlChange("https://youtu.be/first")
        advanceTimeBy(600)
        runCurrent()
        viewModel.onUrlChange("https://youtu.be/second")
        advanceTimeBy(600)
        advanceUntilIdle()

        val ready = viewModel.uiState.value.linkState as LinkUiState.Ready
        assertEquals("https://youtu.be/second", ready.url.value)
        assertEquals(listOf("https://youtu.be/first", "https://youtu.be/second"), provider.requestedUrls)
        assertEquals(listOf("https://youtu.be/first"), provider.cancelledUrls)
    }

    @Test
    fun invalidSupportedUrlNeverStartsExtractor() = runTest(mainDispatcherRule.dispatcher) {
        val provider = FakeMediaInfoProvider()
        val viewModel = HomeViewModel(provider)

        viewModel.onUrlChange("https://youtube.com.evil.example/watch?v=abc")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.linkState is LinkUiState.Invalid)
        assertTrue(provider.requestedUrls.isEmpty())
    }

    @Test
    fun observesPersistedProgressFromScheduler() = runTest(mainDispatcherRule.dispatcher) {
        val command = FakeDownloadCommand()
        val viewModel = HomeViewModel(FakeMediaInfoProvider(), command)
        viewModel.onUrlChange("https://youtu.be/progress")
        advanceTimeBy(600)
        advanceUntilIdle()

        viewModel.startDownload()
        runCurrent()
        assertTrue(viewModel.uiState.value.isDownloading)
        assertEquals(7L, viewModel.uiState.value.activeDownloadId)

        command.emit(
            DownloadEntity(id = 7, url = "https://youtu.be/progress", status = "downloading", progress = 45),
        )
        runCurrent()
        assertEquals(45, viewModel.uiState.value.downloadProgress)

        viewModel.onUrlChange("")
        viewModel.clear()
        assertTrue(viewModel.uiState.value.isDownloading)
        assertEquals(7L, viewModel.uiState.value.activeDownloadId)
    }

    @Test
    fun completedDownloadPrimaryActionResetsForANewDownload() = runTest(mainDispatcherRule.dispatcher) {
        val command = FakeDownloadCommand()
        val viewModel = HomeViewModel(FakeMediaInfoProvider(), command)
        viewModel.onUrlChange("https://youtu.be/completed")
        advanceTimeBy(600)
        advanceUntilIdle()
        viewModel.startDownload()
        runCurrent()

        command.emit(
            DownloadEntity(id = 7, url = "https://youtu.be/completed", status = "completed", progress = 100),
        )
        runCurrent()
        assertEquals("ההורדה הושלמה", viewModel.uiState.value.downloadMessage)

        viewModel.startDownload()
        runCurrent()

        assertEquals("", viewModel.uiState.value.url)
        assertTrue(viewModel.uiState.value.linkState is LinkUiState.Empty)
        assertEquals(null, viewModel.uiState.value.downloadMessage)
        assertEquals(false, viewModel.uiState.value.isDownloading)
    }

    @Test
    fun cancelledDownloadMessageClearsAfterBriefConfirmation() = runTest(mainDispatcherRule.dispatcher) {
        val command = FakeDownloadCommand()
        val viewModel = HomeViewModel(FakeMediaInfoProvider(), command)
        viewModel.onUrlChange("https://youtu.be/cancel")
        advanceTimeBy(600)
        advanceUntilIdle()
        viewModel.startDownload()
        runCurrent()

        viewModel.cancelDownload()
        runCurrent()
        assertEquals(false, viewModel.uiState.value.isDownloading)
        assertEquals(null, viewModel.uiState.value.activeDownloadId)
        assertTrue(viewModel.uiState.value.downloadMessage!!.contains("בוטלה"))

        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(viewModel.uiState.value.downloadMessage!!.contains("בוטלה"))

        advanceTimeBy(1)
        runCurrent()
        assertEquals(null, viewModel.uiState.value.downloadMessage)
    }

    @Test
    fun newUrlAfterCompletionClearsTheDownloadAnotherState() = runTest(mainDispatcherRule.dispatcher) {
        val command = FakeDownloadCommand()
        val viewModel = HomeViewModel(FakeMediaInfoProvider(), command)
        viewModel.onUrlChange("https://youtu.be/first")
        advanceTimeBy(600)
        advanceUntilIdle()
        viewModel.startDownload()
        runCurrent()
        command.emit(
            DownloadEntity(id = 7, url = "https://youtu.be/first", status = "completed", progress = 100),
        )
        runCurrent()

        viewModel.onUrlChange("https://youtu.be/second")
        advanceTimeBy(600)
        advanceUntilIdle()

        val ready = viewModel.uiState.value.linkState as LinkUiState.Ready
        assertEquals("https://youtu.be/second", ready.url.value)
        assertEquals(false, viewModel.uiState.value.downloadCompleted)
        assertEquals(null, viewModel.uiState.value.downloadMessage)
    }

    @Test
    fun sharedUrlOverridesActiveDownloadUiWithoutCancellingBackgroundWork() = runTest(mainDispatcherRule.dispatcher) {
        val command = FakeDownloadCommand()
        val viewModel = HomeViewModel(FakeMediaInfoProvider(), command)
        viewModel.onUrlChange("https://youtu.be/first")
        advanceTimeBy(600)
        advanceUntilIdle()
        viewModel.startDownload()
        runCurrent()

        viewModel.onSharedUrl("https://youtu.be/second")
        advanceTimeBy(600)
        advanceUntilIdle()

        val ready = viewModel.uiState.value.linkState as LinkUiState.Ready
        assertEquals("https://youtu.be/second", ready.url.value)
        assertEquals(false, viewModel.uiState.value.isDownloading)
        assertEquals(null, viewModel.uiState.value.activeDownloadId)
        assertTrue(command.cancelledIds.isEmpty())

        command.emit(
            DownloadEntity(id = 7, url = "https://youtu.be/first", status = "completed", progress = 100),
        )
        runCurrent()
        assertEquals("https://youtu.be/second", viewModel.uiState.value.url)
        assertEquals(false, viewModel.uiState.value.downloadCompleted)
    }

    @Test
    fun sharingCompletedUrlAgainStartsAFreshDownloadSession() = runTest(mainDispatcherRule.dispatcher) {
        val command = FakeDownloadCommand()
        val viewModel = HomeViewModel(FakeMediaInfoProvider(), command)
        val url = "https://youtu.be/repeated-after-completion"
        viewModel.onUrlChange(url)
        advanceTimeBy(600)
        advanceUntilIdle()
        viewModel.startDownload()
        runCurrent()
        command.emit(
            DownloadEntity(id = 7, url = url, status = "completed", progress = 100),
        )
        runCurrent()

        viewModel.onSharedUrl(url)
        advanceTimeBy(600)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.linkState is LinkUiState.Ready)
        assertEquals(false, viewModel.uiState.value.downloadCompleted)
        assertEquals(null, viewModel.uiState.value.downloadMessage)

        viewModel.startDownload()
        runCurrent()
        assertEquals(8L, viewModel.uiState.value.activeDownloadId)
    }

    @Test
    fun completedOlderStartDoesNotReclaimUiAfterNewShare() = runTest(mainDispatcherRule.dispatcher) {
        val command = SuspendedStartDownloadCommand()
        val viewModel = HomeViewModel(FakeMediaInfoProvider(), command)
        viewModel.onUrlChange("https://youtu.be/first")
        advanceTimeBy(600)
        advanceUntilIdle()

        viewModel.startDownload()
        runCurrent()
        assertTrue(command.startEntered.isCompleted)

        viewModel.onSharedUrl("https://youtu.be/second")
        command.startResult.complete(41L)
        runCurrent()
        advanceTimeBy(600)
        advanceUntilIdle()

        val ready = viewModel.uiState.value.linkState as LinkUiState.Ready
        assertEquals("https://youtu.be/second", ready.url.value)
        assertEquals(false, viewModel.uiState.value.isDownloading)
        assertEquals(null, viewModel.uiState.value.activeDownloadId)
    }

    @Test
    fun olderCancellationTimerCannotClearANewerConfirmation() = runTest(mainDispatcherRule.dispatcher) {
        val command = FakeDownloadCommand()
        val viewModel = HomeViewModel(FakeMediaInfoProvider(), command)
        viewModel.onUrlChange("https://youtu.be/cancel-twice")
        advanceTimeBy(600)
        advanceUntilIdle()

        viewModel.startDownload()
        runCurrent()
        viewModel.cancelDownload()
        runCurrent()
        advanceTimeBy(1_000)

        viewModel.startDownload()
        runCurrent()
        assertEquals(8L, viewModel.uiState.value.activeDownloadId)
        viewModel.cancelDownload()
        runCurrent()

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(viewModel.uiState.value.downloadMessage!!.contains("בוטלה"))

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(null, viewModel.uiState.value.downloadMessage)
    }
}

private class SuspendedStartDownloadCommand : DownloadCommand {
    val startEntered = CompletableDeferred<Unit>()
    val startResult = CompletableDeferred<Long>()

    override suspend fun start(state: HomeUiState): Long {
        startEntered.complete(Unit)
        return startResult.await()
    }

    override fun observe(downloadId: Long): Flow<DownloadEntity?> = MutableSharedFlow()

    override suspend fun cancel(downloadId: Long) = Unit
}

private class FakeDownloadCommand : DownloadCommand {
    private var nextId = 7L
    private val recordsById = mutableMapOf<Long, MutableSharedFlow<DownloadEntity?>>()
    val cancelledIds = mutableListOf<Long>()

    override suspend fun start(state: HomeUiState): Long = nextId++

    override fun observe(downloadId: Long): Flow<DownloadEntity?> = records(downloadId)

    override suspend fun cancel(downloadId: Long) {
        cancelledIds += downloadId
        emit(
            DownloadEntity(id = downloadId, url = "https://youtu.be/progress", status = "cancelled"),
        )
    }

    suspend fun emit(record: DownloadEntity) {
        records(record.id).emit(record)
    }

    private fun records(downloadId: Long): MutableSharedFlow<DownloadEntity?> =
        recordsById.getOrPut(downloadId) { MutableSharedFlow(replay = 1) }
}

private class FakeMediaInfoProvider(
    private val delays: Map<String, Long> = emptyMap(),
) : MediaInfoProvider {
    val requestedUrls = mutableListOf<String>()
    val cancelledUrls = mutableListOf<String>()

    override suspend fun getVideoInfo(url: NormalizedMediaUrl): Result<VideoInfo> {
        requestedUrls += url.value
        return try {
            delays.entries.firstOrNull { url.value.contains(it.key) }?.value?.let { delay(it) }
            Result.success(VideoInfo(url.value.substringAfterLast('/'), "", 1, url.platform.value))
        } catch (cancelled: CancellationException) {
            cancelledUrls += url.value
            throw cancelled
        }
    }
}
