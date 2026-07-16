package com.kiodl.android.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "uploads", indices = [Index("status"), Index(value = ["schedulerId"], unique = true)])
data class UploadEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val passwordPlain: String?,
    val shareLink: String,
    val collectionUuid: ByteArray,
    val uploadToken: String,
    val expiresEpochMillis: Long,
    val status: String,
    val totalBytes: Long,
    val uploadedBytes: Long,
    val schedulerId: Int,
    val elapsedMillis: Long,
    val activeStartedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String?,
)

@Entity(
    tableName = "upload_files",
    foreignKeys = [ForeignKey(
        entity = UploadEntity::class,
        parentColumns = ["id"],
        childColumns = ["collectionId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE,
    )],
    indices = [Index("collectionId"), Index("status")],
)
data class UploadFileEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val remoteId: ByteArray,
    val uri: String,
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val status: String,
    val uploadedBytes: Long,
    val pausedByUser: Boolean,
    val error: String?,
)

@Entity(
    tableName = "upload_chunks",
    primaryKeys = ["fileId", "chunkIndex"],
    foreignKeys = [
        ForeignKey(
            entity = UploadEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UploadFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("collectionId"), Index("status")],
)
data class UploadChunkEntity(
    val collectionId: String,
    val fileId: String,
    val chunkIndex: Int,
    val offset: Long,
    val size: Long,
    val status: String,
    val uploadedBytes: Long,
    val attempts: Int,
    val updatedAt: Long,
    val error: String?,
)
