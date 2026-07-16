package com.kiodl.android.domain.model

enum class DownloadProvider {
    KIOSK,
    TRANSFER,
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    INFLATING,
    PAUSED,
    COMPLETED,
    ERROR,
    EXPIRED,
    DELETING,
}

enum class FileDownloadStatus {
    PENDING,
    DOWNLOADING,
    INFLATING,
    PAUSED,
    COMPLETED,
    ERROR,
}

enum class ChunkDownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    ERROR,
}

data class ParsedDownloadUrl(
    val provider: DownloadProvider,
    val id: String,
)

data class DownloadItem(
    val id: String,
    val shareId: String,
    val provider: DownloadProvider,
    val name: String,
    val destinationUri: String,
    val status: DownloadStatus,
    val transferredBytes: Long,
    val totalBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String? = null,
    val files: List<DownloadFileProgress> = emptyList(),
    val speedBytesPerSecond: Long? = null,
    val elapsedMillis: Long = 0,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (transferredBytes.toDouble() / totalBytes).toFloat()
}

data class DownloadFileProgress(
    val id: String,
    val path: String,
    val status: FileDownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val selected: Boolean,
    val pausedByUser: Boolean,
    val error: String?,
    val speedBytesPerSecond: Long? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) {
            if (status == FileDownloadStatus.COMPLETED) 1f else 0f
        } else {
            (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        }
}
