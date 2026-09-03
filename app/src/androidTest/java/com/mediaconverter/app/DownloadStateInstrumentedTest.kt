package com.mediaconverter.app

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediaconverter.app.data.db.AppDatabase
import com.mediaconverter.app.data.db.DownloadEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadStateInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun cancellationCannotBeOverwrittenByLateProgressOrCompletion() = runBlocking {
        val dao = database.downloadDao()
        val id = dao.insertDownload(DownloadEntity(url = "https://youtu.be/test"))

        assertEquals(1, dao.updateProgress(id, 25, "downloading"))
        assertEquals(1, dao.markCancelled(id, "cancelled"))
        assertEquals(0, dao.updateProgress(id, 80, "downloading"))
        assertEquals(0, dao.markCompleted(id, "content://late", "video/mp4", 123))
        assertEquals("cancelled", dao.getDownloadById(id)?.status)
    }

    @Test
    fun completionCannotBeOverwrittenByLateCancellationOrFailure() = runBlocking {
        val dao = database.downloadDao()
        val id = dao.insertDownload(DownloadEntity(url = "https://fb.watch/test"))

        assertEquals(1, dao.markCompleted(id, "content://saved", "audio/mpeg", 456))
        assertEquals(0, dao.markCancelled(id, "cancelled"))
        assertEquals(0, dao.markFailed(id, "late failure", "download_failed"))
        assertEquals("completed", dao.getDownloadById(id)?.status)
        assertEquals("content://saved", dao.getDownloadById(id)?.outputUri)
    }

    @Test
    fun versionOneDatabaseMigratesWithoutLosingLegacyRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `platform` TEXT NOT NULL, `format` TEXT NOT NULL, `quality` TEXT NOT NULL, `progress` INTEGER NOT NULL, `status` TEXT NOT NULL, `filePath` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `errorMessage` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL)""",
                        )
                        db.execSQL(
                            """INSERT INTO downloads (url, title, platform, format, quality, progress, status, filePath, fileSize, thumbnailUrl, errorMessage, createdAt, completedAt) VALUES ('https://youtu.be/legacy', 'legacy', 'youtube', 'mp4', 'best', 100, 'completed', '/legacy/file.mp4', 12, '', '', 1, 2)""",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase
        helper.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            val row = migrated.downloadDao().getDownloadById(1)
            assertEquals("legacy", row?.title)
            assertEquals("/legacy/file.mp4", row?.filePath)
            assertEquals("", row?.outputUri)
            assertEquals("", row?.errorCode)
            assertEquals(0, row?.retryCount)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
