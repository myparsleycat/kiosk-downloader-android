package com.kiodl.android.data.local

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DownloadEntity::class,
        DownloadFileEntity::class,
        DownloadChunkEntity::class,
        UploadEntity::class,
        UploadFileEntity::class,
        UploadChunkEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class KioskDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun uploadDao(): UploadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE downloads ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE downloads ADD COLUMN rootId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE downloads ADD COLUMN segmentSize INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    "ALTER TABLE downloads ADD COLUMN expiresEpochSeconds INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE downloads ADD COLUMN passwordProtected INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE downloads ADD COLUMN passwordPlain TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_status ON downloads(status)")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_downloads_createdAt ON downloads(createdAt)",
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_files (
                        id TEXT NOT NULL PRIMARY KEY,
                        collectionId TEXT NOT NULL,
                        remoteId TEXT NOT NULL,
                        path TEXT NOT NULL,
                        name TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        selected INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        downloadedBytes INTEGER NOT NULL,
                        pausedByUser INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        error TEXT,
                        sourceKind TEXT NOT NULL,
                        zipEntryJson TEXT,
                        sourceMetaJson TEXT,
                        completedElsewhere INTEGER NOT NULL,
                        FOREIGN KEY(collectionId) REFERENCES downloads(id)
                            ON UPDATE CASCADE ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_files_collectionId " +
                        "ON download_files(collectionId)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_files_collectionId_selected_status " +
                        "ON download_files(collectionId, selected, status)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_files_status ON download_files(status)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_files_remoteId ON download_files(remoteId)",
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_chunks (
                        collectionId TEXT NOT NULL,
                        fileId TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        offset INTEGER NOT NULL,
                        size INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        downloadedBytes INTEGER NOT NULL,
                        attempts INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        error TEXT,
                        PRIMARY KEY(fileId, chunkIndex),
                        FOREIGN KEY(collectionId) REFERENCES downloads(id)
                            ON UPDATE CASCADE ON DELETE CASCADE,
                        FOREIGN KEY(fileId) REFERENCES download_files(id)
                            ON UPDATE CASCADE ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_chunks_collectionId " +
                        "ON download_chunks(collectionId)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_chunks_status ON download_chunks(status)",
                )
            }
        }

        // JobScheduler requires a stable int id; seed from rowid so existing rows stay unique.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE downloads ADD COLUMN schedulerId INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL("UPDATE downloads SET schedulerId = rowid")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_downloads_schedulerId " +
                        "ON downloads(schedulerId)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE downloads ADD COLUMN treeCbor BLOB NOT NULL DEFAULT X''")
                database.execSQL("ALTER TABLE downloads ADD COLUMN asciiFilenames INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS uploads (
                        id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
                        passwordPlain TEXT, shareLink TEXT NOT NULL, collectionUuid BLOB NOT NULL,
                        uploadToken TEXT NOT NULL, expiresEpochMillis INTEGER NOT NULL,
                        status TEXT NOT NULL, totalBytes INTEGER NOT NULL, uploadedBytes INTEGER NOT NULL,
                        schedulerId INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                        error TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_uploads_status ON uploads(status)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_uploads_schedulerId ON uploads(schedulerId)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS upload_files (
                        id TEXT NOT NULL PRIMARY KEY, collectionId TEXT NOT NULL, remoteId BLOB NOT NULL,
                        uri TEXT NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL,
                        lastModified INTEGER NOT NULL, status TEXT NOT NULL, uploadedBytes INTEGER NOT NULL,
                        error TEXT, FOREIGN KEY(collectionId) REFERENCES uploads(id)
                        ON UPDATE CASCADE ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_upload_files_collectionId ON upload_files(collectionId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_upload_files_status ON upload_files(status)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS upload_chunks (
                        collectionId TEXT NOT NULL, fileId TEXT NOT NULL, chunkIndex INTEGER NOT NULL,
                        offset INTEGER NOT NULL, size INTEGER NOT NULL, status TEXT NOT NULL,
                        uploadedBytes INTEGER NOT NULL, attempts INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                        error TEXT, PRIMARY KEY(fileId, chunkIndex),
                        FOREIGN KEY(collectionId) REFERENCES uploads(id) ON UPDATE CASCADE ON DELETE CASCADE,
                        FOREIGN KEY(fileId) REFERENCES upload_files(id) ON UPDATE CASCADE ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_upload_chunks_collectionId ON upload_chunks(collectionId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_upload_chunks_status ON upload_chunks(status)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE download_chunks ADD COLUMN crc32 INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE upload_files ADD COLUMN pausedByUser INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE downloads ADD COLUMN destinationSubfolder TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE downloads ADD COLUMN elapsedMillis INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE downloads ADD COLUMN activeStartedAt INTEGER")
                database.execSQL("ALTER TABLE uploads ADD COLUMN elapsedMillis INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE uploads ADD COLUMN activeStartedAt INTEGER")
            }
        }
    }
}
