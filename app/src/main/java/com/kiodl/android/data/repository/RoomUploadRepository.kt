package com.kiodl.android.data.repository

import com.kiodl.android.data.local.UploadChunkEntity
import com.kiodl.android.data.local.UploadDao
import com.kiodl.android.data.local.UploadEntity
import com.kiodl.android.data.local.UploadFileEntity
import com.kiodl.android.data.remote.KioUploadClient
import com.kiodl.android.data.remote.UPLOAD_SEGMENT_SIZE
import com.kiodl.android.domain.model.UploadDraft
import com.kiodl.android.domain.model.UploadItem
import com.kiodl.android.domain.model.UploadStatus
import com.kiodl.android.domain.model.UploadFileProgress
import com.kiodl.android.domain.model.FileUploadStatus
import com.kiodl.android.domain.repository.UploadRepository
import com.kiodl.android.transfer.UploadWorkScheduler
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

@Singleton
class RoomUploadRepository @Inject constructor(
    private val dao: UploadDao,
    private val client: KioUploadClient,
    private val scheduler: UploadWorkScheduler,
) : UploadRepository {
    private val speedSampler = TransferSpeedSampler()

    override fun observeUploads() = combine(dao.observeUploads(), dao.observeFiles()) { rows, files ->
        val grouped = files.groupBy(UploadFileEntity::collectionId)
        val now = System.currentTimeMillis()
        rows.map { row ->
            val active = row.status == "UPLOADING"
            UploadItem(
                row.id,
                row.name,
                row.shareLink,
                UploadStatus.valueOf(row.status),
                row.totalBytes,
                row.uploadedBytes,
                row.error,
                grouped[row.id].orEmpty().map { file ->
                    UploadFileProgress(
                        file.id, file.path, FileUploadStatus.valueOf(file.status),
                        file.uploadedBytes, file.size, file.pausedByUser, file.error,
                        speedSampler.sample(
                            "upload-file:${file.id}", file.uploadedBytes, file.status == "UPLOADING", now,
                        ),
                    )
                },
                speedSampler.sample("upload:${row.id}", row.uploadedBytes, active, now),
                row.elapsedMillis + (row.activeStartedAt?.let { (now - it).coerceAtLeast(0) } ?: 0),
            )
        }
    }

    override suspend fun create(draft: UploadDraft, turnstileToken: String): String = withContext(Dispatchers.IO) {
        require(draft.name.isNotBlank()) { "컬렉션 이름을 입력해 주세요." }
        require(draft.sources.isNotEmpty()) { "업로드할 파일을 선택해 주세요." }
        require(draft.sources.size <= 1_000) { "파일은 최대 1,000개까지 업로드할 수 있습니다." }
        require(draft.sources.sumOf { it.size } <= 50L * 1024 * 1024 * 1024) {
            "컬렉션 전체 크기는 50 GiB를 넘을 수 없습니다."
        }
        val requestNow = System.currentTimeMillis()
        require(draft.expiresEpochMillis in requestNow..(requestNow + 30L * 24 * 60 * 60 * 1_000)) {
            "만료 시각은 현재부터 30일 이내여야 합니다."
        }
        val created = client.createCollection(draft, turnstileToken)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val schedulerId = allocateSchedulerId(id)
        val files = created.files.map { file ->
            UploadFileEntity(
                id = UUID.randomUUID().toString(),
                collectionId = id,
                remoteId = file.remoteId,
                uri = file.source.uri,
                path = file.source.path,
                name = file.source.name,
                size = file.source.size,
                lastModified = file.source.lastModified,
                status = "QUEUED",
                uploadedBytes = 0,
                pausedByUser = false,
                error = null,
            )
        }
        dao.insertAll(
            UploadEntity(
                id, draft.name.take(100), draft.description.take(2500), draft.password.ifEmpty { null },
                created.shareLink, created.collectionUuid, created.uploadToken, draft.expiresEpochMillis,
                "QUEUED", files.sumOf { it.size }, 0, schedulerId, 0, null, now, now, null,
            ),
            files,
            files.flatMap { file ->
                val count = maxOf(1, ((file.size + UPLOAD_SEGMENT_SIZE - 1) / UPLOAD_SEGMENT_SIZE).toInt())
                List(count) { index ->
                    val offset = index * UPLOAD_SEGMENT_SIZE
                    UploadChunkEntity(
                        id, file.id, index, offset, minOf(UPLOAD_SEGMENT_SIZE, file.size - offset).coerceAtLeast(0),
                        "QUEUED", 0, 0, now, null,
                    )
                }
            },
        )
        scheduler.enqueue(id, schedulerId)
        id
    }

    override suspend fun pause(id: String) {
        val upload = dao.upload(id) ?: return
        scheduler.cancel(id, upload.schedulerId)
        dao.resetRunningChunks(id, System.currentTimeMillis())
        dao.pauseCollectionFiles(id)
        dao.setStatus(id, "PAUSED", null, System.currentTimeMillis())
    }

    override suspend fun resume(id: String) {
        val upload = dao.upload(id) ?: return
        if (upload.expiresEpochMillis <= System.currentTimeMillis()) {
            dao.setStatus(
                id, "EXPIRED", "업로드 세션이 만료되었거나 더 이상 존재하지 않습니다.",
                System.currentTimeMillis(),
            )
            return
        }
        dao.resumeCollectionFiles(id)
        dao.setStatus(id, "QUEUED", null, System.currentTimeMillis())
        scheduler.enqueue(id, upload.schedulerId)
    }

    override suspend fun pauseFile(id: String, fileId: String) {
        val upload = dao.upload(id) ?: return
        scheduler.cancel(id, upload.schedulerId)
        val now = System.currentTimeMillis()
        dao.resetRunningChunks(id, now)
        dao.resetRunningFiles(id)
        dao.pauseFile(id, fileId)
        dao.refreshProgress(id, now)
        if (dao.countRunnableFiles(id) > 0) {
            dao.setStatus(id, "QUEUED", null, now)
            scheduler.enqueue(id, upload.schedulerId)
        } else {
            dao.setStatus(id, "PAUSED", null, now)
        }
    }

    override suspend fun resumeFile(id: String, fileId: String) {
        val upload = dao.upload(id) ?: return
        if (upload.expiresEpochMillis <= System.currentTimeMillis()) {
            dao.setStatus(id, "EXPIRED", "업로드 세션이 만료되었습니다.", System.currentTimeMillis())
            return
        }
        dao.resumeFile(id, fileId)
        dao.setStatus(id, "QUEUED", null, System.currentTimeMillis())
        scheduler.enqueue(id, upload.schedulerId)
    }

    override suspend fun remove(id: String) {
        val upload = dao.upload(id) ?: return
        scheduler.cancel(id, upload.schedulerId)
        dao.delete(id)
    }

    private suspend fun allocateSchedulerId(id: String): Int {
        var schedulerId = UPLOAD_JOB_ID_MIN or id.hashCode().and(UPLOAD_JOB_ID_MASK)
        while (dao.isSchedulerIdInUse(schedulerId)) {
            schedulerId = if (schedulerId == UPLOAD_JOB_ID_MAX) UPLOAD_JOB_ID_MIN else schedulerId + 1
        }
        return schedulerId
    }
}

private const val UPLOAD_JOB_ID_MIN = 0x40000000
private const val UPLOAD_JOB_ID_MAX = 0x5fffffff
private const val UPLOAD_JOB_ID_MASK = 0x1fffffff
