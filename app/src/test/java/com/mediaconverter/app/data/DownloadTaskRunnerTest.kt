package com.mediaconverter.app.data

import com.mediaconverter.app.data.db.DownloadEntity
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
}

private class FakeDownloadTaskStore : DownloadTaskStore {
    override suspend fun getDownloadById(id: Long) = DownloadEntity(
        id = id,
        url = "https://youtu.be/test",
        status = "pending",
    )

    override suspend fun updateProgress(id: Long, progress: Int, status: String) = 1
    override suspend fun markFailed(id: Long, error: String, errorCode: String) = 1
    override suspend fun markRetryPending(id: Long, error: String, errorCode: String) = 1
    override suspend fun markCompleted(id: Long, savedMedia: SavedMedia) = 1
}
