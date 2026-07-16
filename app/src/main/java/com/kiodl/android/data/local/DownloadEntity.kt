package com.kiodl.android.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloads",
    indices = [Index("status"), Index("createdAt"), Index("schedulerId", unique = true)],
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val schedulerId: Int,
    val shareId: String,
    val sourceUrl: String,
    val provider: String,
    val name: String,
    val rootId: String,
    val segmentSize: Long,
    val expiresEpochSeconds: Long,
    val passwordProtected: Boolean,
    // Stored plain so resume/token refresh/KDX export work after process death (matches desktop KDX).
    val passwordPlain: String?,
    val treeCbor: ByteArray,
    val asciiFilenames: Boolean,
    val destinationSubfolder: String,
    val destinationUri: String,
    val status: String,
    val transferredBytes: Long,
    val totalBytes: Long,
    val elapsedMillis: Long,
    val activeStartedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String?,
)
