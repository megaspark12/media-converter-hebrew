package com.mediaconverter.app

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mediaconverter.app.data.ApiAwareDownloadScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareAndSchedulerInstrumentedTest {
    @Test
    fun sharedUrlPopulatesInputOnColdStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://youtu.be/test-fixture"
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Shared text $url")
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.wait(
                Until.findObject(
                    By.res("com.android.permissioncontroller", "permission_deny_button"),
                ),
                2_000,
            )?.click()
            assertTrue(device.wait(Until.hasObject(By.text(url)), 5_000))
        }
    }

    @Test
    fun api34JobIsMarkedUserInitiatedAndCarriesOnlyTheDatabaseId() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val job = ApiAwareDownloadScheduler(context).buildUserInitiatedJob(42)

        assertTrue(job.isUserInitiated)
        assertEquals(42L, job.extras.getLong(ApiAwareDownloadScheduler.EXTRA_DOWNLOAD_ID))
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertTrue(job.isRequireStorageNotLow)
    }
}
