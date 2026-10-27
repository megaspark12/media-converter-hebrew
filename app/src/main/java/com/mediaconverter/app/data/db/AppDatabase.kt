package com.mediaconverter.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DownloadEntity::class], version = AppDatabase.SCHEMA_VERSION, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        /** Bump together with a new step in [MIGRATIONS]; MigrationChainTest ties the two. */
        const val SCHEMA_VERSION = 3

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Re-check under the lock: two callers racing here (UI scheduler and the job
                // service) must share one instance, otherwise Room's invalidation tracker on
                // one copy never sees progress written through the other.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "media_converter_db"
                ).addMigrations(*MIGRATIONS).build().also { INSTANCE = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN outputUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN mimeType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN errorCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN ytDlpVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN playlistIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Every step from version 1 to the current one, in order. The only list: the builder
         * above and the migration test both read it, so a schema bump cannot add its step to one
         * and not the other (MigrationChainTest checks the chain is unbroken).
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
