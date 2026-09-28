package com.mediaconverter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.mediaconverter.app.data.DownloadWorker
import com.mediaconverter.app.data.db.AppDatabase
import com.mediaconverter.app.data.db.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadWorkerFailureInstrumentedTest {
    @Test
    fun foregroundRejectionMarksDownloadFailedInsteadOfLeavingItPendingAtZero() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.getInstance(context).downloadDao()
        val id = dao.insertDownload(DownloadEntity(url = "https://youtu.be/foreground-rejected"))
        try {
            val worker = TestListenableWorkerBuilder<DownloadWorker>(
                context, inputData = workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id),
            ).setForegroundUpdater { _, _, _ ->
                throw SecurityException("Foreground execution rejected")
            }.build()

            val outcome = runCatching { worker.doWork() }

            val record = requireNotNull(dao.getDownloadById(id))
            assertEquals("failed", record.status)
            assertTrue(record.errorMessage.contains("Foreground execution rejected"))
            assertEquals(ListenableWorker.Result.failure(), outcome.getOrThrow())
        } finally {
            dao.getDownloadById(id)?.let { dao.deleteDownload(it) }
        }
    }

    @Test
    fun cancellationDuringForegroundSetupRemainsCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.getInstance(context).downloadDao()
        val id = dao.insertDownload(DownloadEntity(url = "https://youtu.be/foreground-cancelled"))
        try {
            val cancelled = CancellationException("Worker stopped")
            val worker = TestListenableWorkerBuilder<DownloadWorker>(
                context, inputData = workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id),
            ).setForegroundUpdater { _, _, _ -> throw cancelled }.build()

            assertTrue(runCatching { worker.doWork() }.exceptionOrNull() === cancelled)
            val record = requireNotNull(dao.getDownloadById(id))
            assertEquals("pending", record.status)
            assertEquals(1, record.retryCount)
        } finally {
            dao.getDownloadById(id)?.let { dao.deleteDownload(it) }
        }
    }
}
