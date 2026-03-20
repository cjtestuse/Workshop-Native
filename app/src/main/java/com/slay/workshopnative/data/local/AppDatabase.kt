package com.slay.workshopnative.data.local

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadTaskEntity::class],
    version = 9,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN downloadAuthMode TEXT NOT NULL DEFAULT 'Anonymous'
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN boundAccountName TEXT
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN boundSteamId64 INTEGER
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN runtimeRouteLabel TEXT
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN runtimeTransportLabel TEXT
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN runtimeEndpointLabel TEXT
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN runtimeSourceAddress TEXT
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN runtimeAttemptCount INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN runtimeChunkConcurrency INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN runtimeLastFailure TEXT
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE download_tasks
                    ADD COLUMN boundAccountKeyHash TEXT
                    """.trimIndent(),
                )
            }
        }
    }
}
