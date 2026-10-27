package com.mediaconverter.app.data

import com.mediaconverter.app.data.db.DownloadEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskRunnerTest {
    @Test
    fun databaseFailureAfterPublicationDeletesUncommittedMedia() = runTest {
        var deleted = false

        val failure = runCatching {
            commitPublishedMedia(
                markCompleted = { throw IllegalStateException("database unavailable") },
                deleteSavedMedia = { deleted = true },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(deleted)
    }

    @Test
    fun fatalCommitFailureStillDeletesUncommittedMediaBeforeRethrowing() = runTest {
        val fatal = OutOfMemoryError("database fatal")
        var deleted = false

        val thrown = runCatching {
            commitPublishedMedia(
                markCompleted = { throw fatal },
                deleteSavedMedia = { deleted = true },
            )
        }.exceptionOrNull()

        assertTrue("Expected the original fatal error, got $thrown", thrown === fatal)
        assertTrue(deleted)
    }

    @Test
    fun fatalVmErrorsFromConversionPropagateOutOfTaskRunner() = runTest {
        val fatal = OutOfMemoryError("fatal")
        val runner = DownloadTaskRunner(
            DownloadTaskDependencies(
                repository = FakeDownloadTaskStore(),
                convert = { _, _, _ -> throw fatal },
                deleteSavedMedia = {},
                elapsedRealtime = { 0L },
                log = {},
            ),
        )

        val thrown = runCatching {
            runner.run(42) { _, _ -> }
        }.exceptionOrNull()

        assertTrue("Expected fatal error, got $thrown", thrown is OutOfMemoryError)
        assertTrue(thrown?.message == "fatal")
    }

    @Test
    fun successfulRunReturnsTheSavedMediaSoCallersCanOpenIt() = runTest {
        val saved = SavedMedia("content://media/external/downloads/9", "clip.mp4", "video/mp4", 10, 1_000)
        val runner = DownloadTaskRunner(
            DownloadTaskDependencies(
                repository = FakeDownloadTaskStore(),
                convert = { _, _, _ -> Result.success(saved) },
                deleteSavedMedia = {},
                elapsedRealtime = { 0L },
                log = {},
            ),
        )

        val result = runner.run(42) { _, _ -> }

        assertTrue(result.succeeded)
        assertEquals(saved, result.savedMedia)
    }
    @Test
    fun theChosenItemOfAMultiItemPostReachesTheConversion() = runTest {
        var request: ConversionRequest? = null
        val saved = SavedMedia("content://media/external/downloads/9", "clip.mp4", "video/mp4", 10, 1_000)
        val runner = DownloadTaskRunner(
            DownloadTaskDependencies(
                repository = FakeDownloadTaskStore(playlistIndex = 2),
                convert = { conversion, _, _ -> request = conversion; Result.success(saved) },
                deleteSavedMedia = {},
                elapsedRealtime = { 0L },
                log = {},
            ),
        )

        runner.run(42) { _, _ -> }

        assertEquals(2, request?.playlistIndex)
    }

    @Test
    fun aSingleVideoRecordSelectsNoItem() = runTest {
        var request: ConversionRequest? = null
        val saved = SavedMedia("content://media/external/downloads/9", "clip.mp4", "video/mp4", 10, 1_000)
        val runner = DownloadTaskRunner(
            DownloadTaskDependencies(
                repository = FakeDownloadTaskStore(),
                convert = { conversion, _, _ -> request = conversion; Result.success(saved) },
                deleteSavedMedia = {},
                elapsedRealtime = { 0L },
                log = {},
            ),
        )

        runner.run(42) { _, _ -> }

        assertEquals(null, request?.playlistIndex)
    }

    @Test
    fun aLateProgressWriteCannotOverwriteTheRetryDecision() = runTest {
        // Progress writes were launched and forgotten. One still in flight when the download
        // failed landed after markRetryPending and flipped the row back to "downloading" at the
        // old progress, so the scheduled retry never showed and the row looked stuck.
        val store = StatefulDownloadTaskStore(progressWriteDelayMillis = 100)
        val runner = DownloadTaskRunner(
            DownloadTaskDependencies(
                repository = store,
                convert = { _, _, onProgress ->
                    onProgress(50, "")
                    Result.failure(ConversionException(ConversionFailure.DOWNLOAD_FAILED, "disconnect"))
                },
                deleteSavedMedia = {},
                elapsedRealtime = { 0L },
                log = {},
            ),
        )

        val result = runner.run(42) { _, _ -> }

        assertTrue(result.shouldRetry)
        assertEquals("pending", store.status)
        assertEquals(1, store.retryCount)
    }
}

private class StatefulDownloadTaskStore(private val progressWriteDelayMillis: Long) : DownloadTaskStore {
    var status = "pending"
    var progress = 0
    var retryCount = 0

    override suspend fun getDownloadById(id: Long) = DownloadEntity(
        id = id,
        url = "https://youtu.be/test",
        status = status,
        retryCount = retryCount,
    )

    override suspend fun updateProgress(id: Long, progress: Int, status: String): Int {
        delay(progressWriteDelayMillis)
        return whileActive {
            this.progress = progress
            this.status = status
        }
    }

    override suspend fun markFailed(id: Long, error: String, errorCode: String) = whileActive { status = "failed" }

    override suspend fun markRetryPending(id: Long, error: String, errorCode: String) = whileActive {
        status = "pending"
        retryCount++
    }

    override suspend fun markCompleted(id: Long, savedMedia: SavedMedia) = whileActive { status = "completed" }

    /** Mirrors the DAO guards: terminal rows are never touched again. */
    private inline fun whileActive(update: () -> Unit): Int {
        if (status !in setOf("pending", "downloading")) return 0
        update()
        return 1
    }
}

private class FakeDownloadTaskStore(private val playlistIndex: Int = 0) : DownloadTaskStore {
    override suspend fun getDownloadById(id: Long) = DownloadEntity(
        id = id,
        url = "https://youtu.be/test",
        status = "pending",
        playlistIndex = playlistIndex,
    )

    override suspend fun updateProgress(id: Long, progress: Int, status: String) = 1
    override suspend fun markFailed(id: Long, error: String, errorCode: String) = 1
    override suspend fun markRetryPending(id: Long, error: String, errorCode: String) = 1
    override suspend fun markCompleted(id: Long, savedMedia: SavedMedia) = 1
}
