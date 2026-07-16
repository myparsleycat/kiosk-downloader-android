package com.kiodl.android.domain.model

data class UploadSource(
    val uri: String,
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
)

enum class UploadStatus { QUEUED, UPLOADING, PAUSED, COMPLETED, ERROR, EXPIRED }

data class UploadItem(
    val id: String,
    val name: String,
    val shareLink: String,
    val status: UploadStatus,
    val totalBytes: Long,
    val uploadedBytes: Long,
    val error: String?,
    val files: List<UploadFileProgress> = emptyList(),
    val speedBytesPerSecond: Long? = null,
    val elapsedMillis: Long = 0,
) {
    val progress: Float get() = if (totalBytes == 0L && status == UploadStatus.COMPLETED) 1f
    else if (totalBytes == 0L) 0f
    else (uploadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

enum class FileUploadStatus { QUEUED, UPLOADING, PAUSED, COMPLETED, ERROR }

data class UploadFileProgress(
    val id: String,
    val path: String,
    val status: FileUploadStatus,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val pausedByUser: Boolean,
    val error: String?,
    val speedBytesPerSecond: Long? = null,
) {
    val progress: Float get() = if (totalBytes <= 0L) {
        if (status == FileUploadStatus.COMPLETED) 1f else 0f
    } else (uploadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

data class UploadDraft(
    val name: String,
    val description: String,
    val password: String,
    val expiresEpochMillis: Long,
    val sources: List<UploadSource>,
)
