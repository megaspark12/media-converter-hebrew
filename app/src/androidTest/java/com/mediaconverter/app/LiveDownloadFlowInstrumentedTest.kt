package com.mediaconverter.app

import android.app.KeyguardManager
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mediaconverter.app.data.ApiAwareDownloadScheduler
import com.mediaconverter.app.data.DownloadNotifications
import com.mediaconverter.app.data.MediaUrlParser
import com.mediaconverter.app.data.db.AppDatabase
import com.mediaconverter.app.data.db.DownloadEntity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveDownloadFlowInstrumentedTest {
    @Test
    fun downloadButtonSchedulesSavesAndOffersToShareAPlayableVideo() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runLiveFlow") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        assertFalse(
            "Unlock the device before running the live download-button test",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        )
        val url = arguments.getString("liveUrl") ?: "https://www.youtube.com/watch?v=YE7VzlLtp-4"
        val normalized = requireNotNull(MediaUrlParser.parse(url)).value
        val dao = AppDatabase.getInstance(context).downloadDao()
        val originalIds = dao.getAllDownloads().first().map { it.id }.toSet()
        var created: DownloadEntity? = null
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            try {
                device.wait(
                    Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")),
                    2_000,
                )?.click()
                val download = device.wait(Until.findObject(By.text("הורד")), 60_000)
                assertNotNull("Metadata must expose the real download button", download)
                download!!.click()
                created = withTimeout(15_000) {
                    dao.getAllDownloads().first { rows ->
                        rows.any { it.id !in originalIds && it.url == normalized }
                    }.first { it.id !in originalIds && it.url == normalized }
                }
                val completed = withTimeout(300_000) {
                    dao.observeDownloadById(created!!.id).filterNotNull().first { row ->
                        row.status in setOf("completed", "failed", "cancelled")
                    }
                }
                assertEquals(completed.errorMessage, "completed", completed.status)
                assertEquals("video/mp4", completed.mimeType)
                assertTrue(completed.fileSize > 0)
                assertTrue(completed.outputUri.startsWith("content://"))
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(completed.outputUri))
                    assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
                    val frame = retriever.getFrameAtTime(0)
                    assertNotNull("The saved video must decode on this device", frame)
                    frame?.recycle()
                } finally {
                    retriever.release()
                }
                assertNotNull(
                    "The completed download must offer the share action",
                    device.wait(Until.findObject(By.text("שתף")), 5_000),
                )
            } finally {
                created?.let { row ->
                    val latest = dao.getDownloadById(row.id)
                    if (latest?.status in setOf("pending", "downloading")) {
                        ApiAwareDownloadScheduler(context).cancel(row.id)
                    }
                    latest?.outputUri?.takeIf { it.isNotEmpty() }?.let { uri ->
                        context.contentResolver.delete(Uri.parse(uri), null, null)
                    }
                    DownloadNotifications(context).cancel(row.id)
                    dao.deleteDownload(row)
                }
            }
        }
    }
}
