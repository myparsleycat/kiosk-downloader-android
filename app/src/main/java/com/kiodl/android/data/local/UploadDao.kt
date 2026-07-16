package com.kiodl.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads ORDER BY createdAt DESC")
    fun observeUploads(): Flow<List<UploadEntity>>

    @Query("SELECT * FROM upload_files ORDER BY collectionId, path")
    fun observeFiles(): Flow<List<UploadFileEntity>>

    @Query("SELECT * FROM uploads WHERE id = :id")
    suspend fun upload(id: String): UploadEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM uploads WHERE schedulerId = :schedulerId)")
    suspend fun isSchedulerIdInUse(schedulerId: Int): Boolean

    @Query("SELECT * FROM upload_files WHERE collectionId = :id ORDER BY path")
    suspend fun files(id: String): List<UploadFileEntity>

    @Query("SELECT * FROM upload_chunks WHERE collectionId = :id ORDER BY fileId, chunkIndex")
    suspend fun chunks(id: String): List<UploadChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUpload(value: UploadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(values: List<UploadFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(values: List<UploadChunkEntity>)

    @Transaction
    suspend fun insertAll(
        upload: UploadEntity,
        files: List<UploadFileEntity>,
        chunks: List<UploadChunkEntity>,
    ) {
        insertUpload(upload)
        insertFiles(files)
        insertChunks(chunks)
    }

    @Query("UPDATE uploads SET status = :status, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?, now: Long)

    @Query("UPDATE uploads SET activeStartedAt = COALESCE(activeStartedAt, :now) WHERE id = :id")
    suspend fun startTimer(id: String, now: Long)

    @Query("UPDATE uploads SET activeStartedAt = NULL WHERE id = :id")
    suspend fun clearActiveTimer(id: String)

    @Query(
        """
        UPDATE uploads SET elapsedMillis = elapsedMillis + CASE
            WHEN activeStartedAt IS NULL THEN 0 ELSE MAX(0, :now - activeStartedAt) END,
            activeStartedAt = NULL WHERE id = :id
        """,
    )
    suspend fun stopTimer(id: String, now: Long)

    @Query("UPDATE upload_files SET status = :status, uploadedBytes = :bytes, error = :error WHERE id = :id")
    suspend fun setFileStatus(id: String, status: String, bytes: Long, error: String?)

    @Query(
        """
        UPDATE upload_files
        SET uploadedBytes = (
            SELECT COALESCE(SUM(uploadedBytes), 0)
            FROM upload_chunks
            WHERE fileId = :fileId
        ),
        status = CASE
            WHEN pausedByUser = 1 THEN 'PAUSED'
            WHEN (SELECT COALESCE(SUM(uploadedBytes), 0) FROM upload_chunks WHERE fileId = :fileId) >= size
                THEN 'COMPLETED'
            ELSE 'UPLOADING'
        END,
        error = NULL
        WHERE id = :fileId
        """,
    )
    suspend fun syncFileProgressFromChunks(fileId: String)

    @Query("UPDATE upload_chunks SET status = :status, uploadedBytes = :bytes, attempts = attempts + :attempt, updatedAt = :now, error = :error WHERE fileId = :fileId AND chunkIndex = :chunkIndex")
    suspend fun setChunkStatus(fileId: String, chunkIndex: Int, status: String, bytes: Long, attempt: Int, now: Long, error: String?)

    @Query("UPDATE uploads SET uploadedBytes = (SELECT COALESCE(SUM(uploadedBytes), 0) FROM upload_files WHERE collectionId = :id), updatedAt = :now WHERE id = :id")
    suspend fun refreshProgress(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM upload_chunks WHERE collectionId = :id AND status != 'COMPLETED'")
    suspend fun countIncompleteChunks(id: String): Int

    @Query("UPDATE upload_chunks SET status = 'QUEUED', updatedAt = :now, error = NULL WHERE collectionId = :id AND status = 'UPLOADING'")
    suspend fun resetRunningChunks(id: String, now: Long)

    @Query("UPDATE upload_files SET status = 'QUEUED', error = NULL WHERE collectionId = :id AND status = 'UPLOADING' AND pausedByUser = 0")
    suspend fun resetRunningFiles(id: String)

    @Query("UPDATE upload_files SET status = 'PAUSED', error = NULL WHERE collectionId = :id AND status IN ('QUEUED', 'UPLOADING', 'ERROR') AND pausedByUser = 0")
    suspend fun pauseCollectionFiles(id: String)

    @Query("UPDATE upload_files SET status = 'QUEUED', error = NULL WHERE collectionId = :id AND status IN ('PAUSED', 'ERROR') AND pausedByUser = 0")
    suspend fun resumeCollectionFiles(id: String)

    @Query("UPDATE upload_files SET pausedByUser = 1, status = 'PAUSED', error = NULL WHERE collectionId = :id AND id = :fileId AND status != 'COMPLETED'")
    suspend fun pauseFile(id: String, fileId: String)

    @Query("UPDATE upload_files SET pausedByUser = 0, status = 'QUEUED', error = NULL WHERE collectionId = :id AND id = :fileId AND status != 'COMPLETED'")
    suspend fun resumeFile(id: String, fileId: String)

    @Query("SELECT COUNT(*) FROM upload_files WHERE collectionId = :id AND pausedByUser = 0 AND status != 'COMPLETED'")
    suspend fun countRunnableFiles(id: String): Int

    @Query("DELETE FROM uploads WHERE id = :id")
    suspend fun delete(id: String)
}
