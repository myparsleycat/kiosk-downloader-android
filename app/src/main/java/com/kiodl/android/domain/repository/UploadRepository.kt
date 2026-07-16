package com.kiodl.android.domain.repository

import com.kiodl.android.domain.model.UploadDraft
import com.kiodl.android.domain.model.UploadItem
import kotlinx.coroutines.flow.Flow

interface UploadRepository {
    fun observeUploads(): Flow<List<UploadItem>>
    suspend fun create(draft: UploadDraft, turnstileToken: String): String
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun pauseFile(id: String, fileId: String)
    suspend fun resumeFile(id: String, fileId: String)
    suspend fun remove(id: String)
}
