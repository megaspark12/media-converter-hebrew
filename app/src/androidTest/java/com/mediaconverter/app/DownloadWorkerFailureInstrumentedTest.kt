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
    fun foregroundRejectionDoesNotStopTheWorkerOrLeaveTheRowPendingAtZero() = runBlocking {
        // Android 12+ rejects a foreground-service start while the app is in the background.
        // The worker must keep running as a regular job and record the download's own outcome,
        // never die on the rejection and leave the row "pending" at 0% forever. An unsupported
        // URL makes that outcome deterministic (invalid_url, no network) so the test proves the
        // run proceeded past the rejection.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.getInstance(context).downloadDao()
        val id = dao.insertDownload(DownloadEntity(url = "https://example.com/not-a-supported-site"))
        try {
            val worker = TestListenableWorkerBuilder<DownloadWorker>(
                context, inputData = workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id),
            ).setForegroundUpdater { _, _, _ ->
                throw SecurityException("Foreground execution rejected")
            }.build()

            val outcome = runCatching { worker.doWork() }

            val record = requireNotNull(dao.getDownloadById(id))
            assertEquals("failed", record.status)
            assertEquals("invalid_url", record.errorCode)
            assertTrue(record.progress == 0 && record.retryCount == 0)
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
