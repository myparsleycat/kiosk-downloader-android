package com.kiodl.android.domain.repository

import com.kiodl.android.domain.model.DownloadItem
import com.kiodl.android.domain.model.LoadedCollection
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun observeDownloads(): Flow<List<DownloadItem>>
    suspend fun syncExpiredCollections()
    suspend fun cleanupOrphanPartFiles()

    suspend fun enqueue(
        url: String,
        destinationUri: String,
        loadedCollection: LoadedCollection,
        passwordPlain: String?,
        selectedPaths: Set<String>,
        zipPasswords: Map<String, String> = emptyMap(),
        renames: Map<String, String> = emptyMap(),
    ): Result<Unit>

    suspend fun pause(collectionId: String): Result<Unit>
    suspend fun resume(collectionId: String): Result<Unit>
    suspend fun pauseFile(collectionId: String, fileId: String): Result<Unit>
    suspend fun resumeFile(collectionId: String, fileId: String): Result<Unit>
    suspend fun remove(collectionId: String): Result<Unit>
    suspend fun exportKdx(collectionId: String, outputUri: String): Result<Unit>
    suspend fun importKdx(inputUri: String, destinationUri: String): Result<Unit>
}
