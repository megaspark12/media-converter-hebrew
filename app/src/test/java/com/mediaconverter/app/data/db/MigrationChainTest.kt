package com.mediaconverter.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationChainTest {
    @Test
    fun migrationsFormAnUnbrokenChainUpToTheCurrentSchemaVersion() {
        // Room refuses to open an older database when any step is missing ("A migration from 1
        // to 3 was required but not found"), and the instrumented test that proves it costs an
        // emulator run. Every schema bump must add exactly one step here.
        val version = AppDatabase.SCHEMA_VERSION
        val steps = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals((1 until version).map { it to it + 1 }, steps)
    }
}
