package com.kiodl.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    data class TransferSnapshot(
        val download: DownloadEntity,
        val files: List<DownloadFileEntity>,
    )

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download_files ORDER BY collectionId, path ASC")
    fun observeAllFiles(): Flow<List<DownloadFileEntity>>

    @Query("SELECT * FROM downloads WHERE id = :collectionId LIMIT 1")
    suspend fun getDownload(collectionId: String): DownloadEntity?

    @Query("SELECT * FROM download_files WHERE collectionId = :collectionId ORDER BY path ASC")
    suspend fun listFiles(collectionId: String): List<DownloadFileEntity>

    @Transaction
    suspend fun getTransferSnapshot(collectionId: String): TransferSnapshot? {
        val download = getDownload(collectionId) ?: return null
        return TransferSnapshot(download, listFiles(collectionId))
    }

    @Query("SELECT COUNT(*) FROM downloads WHERE schedulerId = :schedulerId")
    suspend fun isSchedulerIdInUse(schedulerId: Int): Boolean

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFiles(files: List<DownloadFileEntity>)

    @Transaction
    suspend fun insertDownload(
        download: DownloadEntity,
        files: List<DownloadFileEntity>,
    ) {
        upsert(download)
        insertFiles(files)
    }

    @Query(
        """
        SELECT * FROM download_files
        WHERE collectionId = :collectionId AND selected = 1 AND status = 'PENDING'
        ORDER BY path ASC
        """,
    )
    suspend fun listPendingSelectedFiles(collectionId: String): List<DownloadFileEntity>

    @Query("SELECT * FROM download_chunks WHERE fileId = :fileId ORDER BY chunkIndex ASC")
    suspend fun listChunks(fileId: String): List<DownloadChunkEntity>

    @Query("SELECT * FROM download_chunks WHERE fileId = :fileId AND chunkIndex = :chunkIndex LIMIT 1")
    suspend fun getChunk(fileId: String, chunkIndex: Int): DownloadChunkEntity?

    @Upsert
    suspend fun upsertChunk(chunk: DownloadChunkEntity)

    @Query(
        """
        UPDATE download_chunks
        SET status = 'PENDING', downloadedBytes = 0, updatedAt = :updatedAt, error = NULL
        WHERE fileId = :fileId AND chunkIndex >= :chunkIndex
        """,
    )
    suspend fun resetChunksFrom(fileId: String, chunkIndex: Int, updatedAt: Long)

    @Query(
        """
        UPDATE download_files
        SET status = :status, downloadedBytes = :downloadedBytes,
            updatedAt = :updatedAt, error = :error
        WHERE id = :fileId
        """,
    )
    suspend fun updateFileState(
        fileId: String,
        status: String,
        downloadedBytes: Long,
        updatedAt: Long,
        error: String? = null,
    )

    @Query("SELECT id FROM downloads")
    suspend fun listDownloadIds(): List<String>

    @Query(
        """
        UPDATE download_files
        SET downloadedBytes = (
            SELECT COALESCE(SUM(downloadedBytes), 0)
            FROM download_chunks
            WHERE fileId = :fileId
        ), status = 'DOWNLOADING', updatedAt = :updatedAt, error = NULL
        WHERE id = :fileId
        """,
    )
    suspend fun syncFileProgressFromChunks(fileId: String, updatedAt: Long)

    @Query(
        """
        UPDATE downloads
        SET status = :status, updatedAt = :updatedAt, error = :error
        WHERE id = :collectionId
        """,
    )
    suspend fun updateCollectionState(
        collectionId: String,
        status: String,
        updatedAt: Long,
        error: String? = null,
    )

    @Query("UPDATE downloads SET expiresEpochSeconds = :expires, updatedAt = :updatedAt WHERE id = :collectionId")
    suspend fun updateCollectionExpiry(collectionId: String, expires: Long, updatedAt: Long)

    @Query("UPDATE downloads SET activeStartedAt = COALESCE(activeStartedAt, :now) WHERE id = :collectionId")
    suspend fun startTimer(collectionId: String, now: Long)

    @Query("UPDATE downloads SET activeStartedAt = NULL WHERE id = :collectionId")
    suspend fun clearActiveTimer(collectionId: String)

    @Query(
        """
        UPDATE downloads
        SET elapsedMillis = elapsedMillis + CASE
                WHEN activeStartedAt IS NULL THEN 0 ELSE MAX(0, :now - activeStartedAt) END,
            activeStartedAt = NULL
        WHERE id = :collectionId
        """,
    )
    suspend fun stopTimer(collectionId: String, now: Long)

    @Query(
        """
        UPDATE downloads
        SET status = 'EXPIRED', updatedAt = :updatedAt, error = :error
        WHERE status NOT IN ('COMPLETED', 'EXPIRED', 'DELETING')
          AND expiresEpochSeconds * 1000 <= :updatedAt
        """,
    )
    suspend fun expireCollections(updatedAt: Long, error: String): Int

    @Query("SELECT COUNT(*) FROM download_files WHERE collectionId = :collectionId AND selected = 1 AND status != 'COMPLETED'")
    suspend fun countIncompleteSelectedFiles(collectionId: String): Int

    @Query("DELETE FROM downloads WHERE id = :collectionId")
    suspend fun deleteDownload(collectionId: String)

    @Query(
        """
        UPDATE downloads
        SET transferredBytes = (
            SELECT COALESCE(SUM(downloadedBytes), 0)
            FROM download_files
            WHERE collectionId = :collectionId AND selected = 1
        ), updatedAt = :updatedAt
        WHERE id = :collectionId
        """,
    )
    suspend fun recomputeCollectionProgress(collectionId: String, updatedAt: Long)

    @Query(
        """
        UPDATE downloads
        SET totalBytes = (
            SELECT COALESCE(SUM(size), 0) FROM download_files
            WHERE collectionId = :collectionId AND selected = 1
        ), transferredBytes = (
            SELECT COALESCE(SUM(downloadedBytes), 0) FROM download_files
            WHERE collectionId = :collectionId AND selected = 1
        ), updatedAt = :updatedAt
        WHERE id = :collectionId
        """,
    )
    suspend fun recomputeCollectionTotals(collectionId: String, updatedAt: Long)

    @Query(
        """
        UPDATE download_chunks SET status = 'PENDING', updatedAt = :updatedAt, error = NULL
        WHERE fileId = :fileId AND status = 'DOWNLOADING'
        """,
    )
    suspend fun resetRunningChunksForFile(fileId: String, updatedAt: Long)

    @Query(
        """
        UPDATE download_files
        SET pausedByUser = 1, status = 'PAUSED', updatedAt = :updatedAt, error = NULL
        WHERE id = :fileId AND collectionId = :collectionId AND selected = 1
          AND status NOT IN ('COMPLETED')
        """,
    )
    suspend fun pauseFile(collectionId: String, fileId: String, updatedAt: Long)

    @Query(
        """
        UPDATE download_files
        SET pausedByUser = 0, selected = 1, status = 'PENDING', updatedAt = :updatedAt, error = NULL
        WHERE id = :fileId AND collectionId = :collectionId AND status != 'COMPLETED'
        """,
    )
    suspend fun resumeFile(collectionId: String, fileId: String, updatedAt: Long)

    @Query(
        """
        SELECT COUNT(*) FROM download_files
        WHERE collectionId = :collectionId AND selected = 1 AND pausedByUser = 0
          AND status IN ('PENDING', 'DOWNLOADING', 'INFLATING', 'ERROR')
        """,
    )
    suspend fun countRunnableFiles(collectionId: String): Int

    @Query(
        """
        UPDATE download_chunks
        SET status = 'PENDING', updatedAt = :updatedAt, error = NULL
        WHERE collectionId = :collectionId AND status = 'DOWNLOADING'
        """,
    )
    suspend fun resetRunningChunks(collectionId: String, updatedAt: Long)

    @Query(
        """
        UPDATE download_files
        SET status = 'PENDING', updatedAt = :updatedAt, error = NULL
        WHERE collectionId = :collectionId AND status IN ('DOWNLOADING', 'INFLATING')
            AND pausedByUser = 0
        """,
    )
    suspend fun resetRunningFiles(collectionId: String, updatedAt: Long)

    @Query(
        """
        UPDATE downloads SET status = 'QUEUED', updatedAt = :updatedAt, error = NULL
        WHERE id = :collectionId AND status NOT IN ('PAUSED', 'COMPLETED', 'EXPIRED', 'DELETING')
        """,
    )
    suspend fun markQueuedIfRunnable(collectionId: String, updatedAt: Long)

    @Query(
        """
        UPDATE download_files SET status = 'PAUSED', updatedAt = :updatedAt, error = NULL
        WHERE collectionId = :collectionId AND selected = 1
          AND status IN ('PENDING', 'DOWNLOADING', 'INFLATING') AND pausedByUser = 0
        """,
    )
    suspend fun pauseFiles(collectionId: String, updatedAt: Long)

    @Query(
        """
        UPDATE download_files SET status = 'PENDING', updatedAt = :updatedAt, error = NULL
        WHERE collectionId = :collectionId AND selected = 1
          AND status IN ('PAUSED', 'ERROR') AND pausedByUser = 0
        """,
    )
    suspend fun resumeFiles(collectionId: String, updatedAt: Long)

    @Transaction
    suspend fun pauseCollection(collectionId: String) {
        val now = System.currentTimeMillis()
        resetRunningChunks(collectionId, now)
        pauseFiles(collectionId, now)
        updateCollectionState(collectionId, "PAUSED", now)
    }

    @Transaction
    suspend fun resumeCollection(collectionId: String) {
        val now = System.currentTimeMillis()
        resumeFiles(collectionId, now)
        updateCollectionState(collectionId, "QUEUED", now)
    }

    @Transaction
    suspend fun recoverInterruptedCollection(collectionId: String) {
        val now = System.currentTimeMillis()
        resetRunningChunks(collectionId, now)
        resetRunningFiles(collectionId, now)
        markQueuedIfRunnable(collectionId, now)
        recomputeCollectionProgress(collectionId, now)
    }
}
