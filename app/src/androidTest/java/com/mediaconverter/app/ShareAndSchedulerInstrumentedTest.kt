package com.mediaconverter.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.SpannableString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mediaconverter.app.data.ApiAwareDownloadScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareAndSchedulerInstrumentedTest {
    @Before
    fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @Test
    fun styledSharedTextPopulatesInputOnColdStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://youtu.be/styled-fixture"
        val intent = shareIntent(context, SpannableString("Shared text $url"))

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            dismissNotificationPermissionIfShown(device)
            assertTrue(device.wait(Until.hasObject(By.text(url)), 5_000))
        }
    }

    @Test
    fun sharedUrlPopulatesInputOnColdStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://youtu.be/test-fixture"
        val intent = shareIntent(context, "Shared text $url")

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            dismissNotificationPermissionIfShown(device)
            assertTrue(device.wait(Until.hasObject(By.text(url)), 5_000))
        }
    }

    @Test
    fun identicalSharedUrlIsHandledAgainOnWarmLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://youtu.be/repeated-fixture"
        val intent = shareIntent(context, url)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            dismissNotificationPermissionIfShown(device)
            assertTrue(device.wait(Until.hasObject(By.text(url)), 5_000))

            device.findObject(By.desc("נקה קישור")).click()
            assertTrue(device.wait(Until.gone(By.text(url)), 5_000))

            var originalActivityIdentity = 0
            scenario.onActivity { activity ->
                originalActivityIdentity = System.identityHashCode(activity)
                activity.startActivity(shareIntent(activity, url))
            }
            assertTrue(device.wait(Until.hasObject(By.text(url)), 5_000))

            var resumedActivityIdentity = 0
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val resumedActivities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                assertEquals(1, resumedActivities.size)
                resumedActivityIdentity = System.identityHashCode(resumedActivities.single())
            }
            assertEquals(originalActivityIdentity, resumedActivityIdentity)
        }
    }

    @Test
    fun manifestResolvesImplicitPlainTextShareToMainActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val matches = context.packageManager.queryIntentActivities(
            shareIntent(context, "https://youtu.be/resolution-fixture"),
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        val mainActivityMatch = matches.firstOrNull {
            it.activityInfo.name == MainActivity::class.java.name
        }
        assertTrue(mainActivityMatch != null)
        assertTrue(mainActivityMatch!!.activityInfo.exported)
    }

    @Test
    fun recreationDoesNotReplayTheOriginalShareIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://youtu.be/recreation-fixture"
        val intent = shareIntent(context, url)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            dismissNotificationPermissionIfShown(device)
            assertTrue(device.wait(Until.hasObject(By.text(url)), 5_000))

            device.findObject(By.desc("נקה קישור")).click()
            assertTrue(device.wait(Until.gone(By.text(url)), 5_000))

            scenario.recreate()

            assertTrue(device.wait(Until.gone(By.text(url)), 5_000))
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

    private fun dismissNotificationPermissionIfShown(device: UiDevice) {
        device.wait(
            Until.findObject(
                By.res("com.android.permissioncontroller", "permission_deny_button"),
            ),
            2_000,
        )?.click()
    }

    private fun shareIntent(context: Context, text: CharSequence) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        `package` = context.packageName
        putExtra(Intent.EXTRA_TEXT, text)
    }
}
