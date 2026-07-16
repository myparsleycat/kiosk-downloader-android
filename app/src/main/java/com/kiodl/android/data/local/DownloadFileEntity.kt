package com.kiodl.android.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_files",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("collectionId"),
        Index(value = ["collectionId", "selected", "status"]),
        Index("status"),
        Index("remoteId"),
    ],
)
data class DownloadFileEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val remoteId: String,
    val path: String,
    val name: String,
    val size: Long,
    val selected: Boolean,
    val status: String,
    val downloadedBytes: Long,
    val pausedByUser: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String?,
    val sourceKind: String,
    val zipEntryJson: String?,
    val sourceMetaJson: String?,
    val completedElsewhere: Boolean,
)

