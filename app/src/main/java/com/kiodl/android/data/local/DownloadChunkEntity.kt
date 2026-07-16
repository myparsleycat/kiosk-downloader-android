package com.kiodl.android.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "download_chunks",
    primaryKeys = ["fileId", "chunkIndex"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DownloadFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("collectionId"), Index("status")],
)
data class DownloadChunkEntity(
    val collectionId: String,
    val fileId: String,
    val chunkIndex: Int,
    val offset: Long,
    val size: Long,
    val status: String,
    val downloadedBytes: Long,
    val attempts: Int,
    val updatedAt: Long,
    val error: String?,
    val crc32: Long? = null,
)
