package com.mediaconverter.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediaconverter.app.data.AndroidYtDlpClient
import com.mediaconverter.app.data.DataStoreYtDlpUpdateStore
import com.mediaconverter.app.data.YtDlpRuntimeProvider
import com.mediaconverter.app.data.YtDlpCommand
import com.mediaconverter.app.data.YtDlpUpdateState
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class YtDlpInstallationInstrumentedTest {
    @Test
    fun boundedUpdateMetadataPreservesAlreadyCurrentVersion() = runBlocking {
        withInstallationState { context, actualVersion ->
            withReleaseFixture(context, actualVersion) { channel ->
                val client = AndroidYtDlpClient(context, channel)
                client.initialize()
                assertFalse(client.update())
                assertEquals(actualVersion, YoutubeDL.getInstance().version(context))
            }
        }
    }

    @Test
    fun boundedUpdateMetadataStillInstallsTheReleaseExecutable() = runBlocking {
        withInstallationState { context, actualVersion ->
            withReleaseFixture(context, actualVersion) { channel ->
                val client = AndroidYtDlpClient(context, channel)
                client.initialize()
                context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit()
                    .putString("dlpVersion", "2000.01.01").commit()

                assertTrue(client.update())
                assertEquals(actualVersion, YoutubeDL.getInstance().version(context))
                assertEquals(actualVersion, client.execute(
                    YtDlpCommand("updated-version", "", listOf("--version")),
                ).stdout.trim())
            }
        }
    }

    @Test
    fun initializationRepairsRestoredVersionAndClearsTheRestoredUpdateCooldown() = runBlocking {
        withInstallationState { context, actualVersion ->
            val preferences = context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
            preferences.edit()
                .putString("dlpVersion", "2999.01.01")
                .putString("dlpVersionName", "yt-dlp 2999.01.01")
                .commit()
            val store = DataStoreYtDlpUpdateStore(context)
            store.writeState(YtDlpUpdateState(System.currentTimeMillis(), System.currentTimeMillis()))

            val client = AndroidYtDlpClient(context)
            client.initialize()

            assertEquals(actualVersion, YoutubeDL.getInstance().version(context))
            assertEquals("yt-dlp $actualVersion", client.version())
            assertEquals(YtDlpUpdateState(), store.readState())
        }
    }

    @Test
    fun matchingInstallationKeepsItsSuccessfulUpdateCooldown() = runBlocking {
        withInstallationState { context, actualVersion ->
            context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit()
                .putString("dlpVersion", actualVersion)
                .putString("dlpVersionName", "yt-dlp $actualVersion")
                .commit()
            val state = YtDlpUpdateState(lastSuccessfulCheckMillis = System.currentTimeMillis())
            val store = DataStoreYtDlpUpdateStore(context)
            store.writeState(state)

            AndroidYtDlpClient(context).initialize()

            assertEquals(state, store.readState())
        }
    }

    private suspend fun withInstallationState(block: suspend (Context, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Serialize local initialization with startup without requesting a network update.
        val actualVersion = YtDlpRuntimeProvider.get(context).execute(
            YtDlpCommand("installation-version", "", listOf("--version")),
        ).stdout.trim()
        val preferences = context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
        val originalVersion = preferences.getString("dlpVersion", null)
        val originalName = preferences.getString("dlpVersionName", null)
        val store = DataStoreYtDlpUpdateStore(context)
        val originalState = store.readState()
        try {
            block(context, actualVersion)
        } finally {
            preferences.edit()
                .putString("dlpVersion", originalVersion)
                .putString("dlpVersionName", originalName)
                .commit()
            store.writeState(originalState)
        }
    }

    private suspend fun withReleaseFixture(
        context: Context,
        version: String,
        block: suspend (YoutubeDL.UpdateChannel) -> Unit,
    ) {
        val directory = File(context.cacheDir, "update-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // Reinstall identical bytes so this test does not replace the device's
            // executable with a fake or require a network release.
            val installed = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
            val binary = installed.copyTo(File(directory, "yt-dlp"))
            val release = File(directory, "release.json").apply {
                writeText(JSONObject()
                    .put("tag_name", version).put("name", "yt-dlp $version")
                    .put("assets", JSONArray().put(JSONObject()
                        .put("name", "yt-dlp")
                        .put("browser_download_url", binary.toURI().toString())))
                    .toString())
            }
            block(YoutubeDL.UpdateChannel(release.toURI().toString()))
        } finally {
            directory.deleteRecursively()
        }
    }
}
