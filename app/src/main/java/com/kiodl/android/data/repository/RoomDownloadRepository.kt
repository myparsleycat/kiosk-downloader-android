package com.kiodl.android.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.kiodl.android.data.local.DownloadDao
import com.kiodl.android.data.local.DownloadEntity
import com.kiodl.android.data.local.DownloadFileEntity
import com.kiodl.android.domain.model.CollectionFile
import com.kiodl.android.domain.model.CollectionNode
import com.kiodl.android.domain.model.DirectoryNode
import com.kiodl.android.domain.model.FileNode
import com.kiodl.android.domain.model.ZipNode
import com.kiodl.android.domain.model.DownloadItem
import com.kiodl.android.domain.model.DownloadProvider
import com.kiodl.android.domain.model.DownloadStatus
import com.kiodl.android.domain.model.DownloadFileProgress
import com.kiodl.android.domain.model.FileDownloadStatus
import com.kiodl.android.domain.model.LoadedCollection
import com.kiodl.android.domain.repository.DownloadRepository
import com.kiodl.android.transfer.DownloadWorkScheduler
import com.kiodl.android.transfer.KdxCodec
import com.kiodl.android.transfer.KdxCollection
import com.kiodl.android.transfer.KdxEntry
import com.kiodl.android.transfer.KdxFile
import com.kiodl.android.transfer.KdxNode
import com.kiodl.android.transfer.KdxPayload
import com.kiodl.android.data.settings.AppSettingsRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RoomDownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val workScheduler: DownloadWorkScheduler,
    private val settingsRepository: AppSettingsRepository,
) : DownloadRepository {
    private val speedSampler = TransferSpeedSampler()

    override fun observeDownloads(): Flow<List<DownloadItem>> =
        combine(downloadDao.observeAll(), downloadDao.observeAllFiles()) { downloads, files ->
            val grouped = files.groupBy(DownloadFileEntity::collectionId)
            downloads.map { it.toDomain(grouped[it.id].orEmpty(), speedSampler) }
        }

    override suspend fun syncExpiredCollections() {
        downloadDao.expireCollections(System.currentTimeMillis(), COLLECTION_EXPIRED_ERROR)
    }

    override suspend fun cleanupOrphanPartFiles() = withContext(Dispatchers.IO) {
        val expectedCollectionIds = downloadDao.listDownloadIds().toHashSet()
        File(context.filesDir, "transfers").listFiles().orEmpty()
            .filter(File::isDirectory)
            .filterNot { it.name in expectedCollectionIds }
            .forEach(File::deleteRecursively)
    }

    override suspend fun enqueue(
        url: String,
        destinationUri: String,
        loadedCollection: LoadedCollection,
        passwordPlain: String?,
        selectedPaths: Set<String>,
        zipPasswords: Map<String, String>,
        renames: Map<String, String>,
    ): Result<Unit> = runCatching {
        require(destinationUri.isNotBlank()) { "저장할 폴더를 선택해 주세요." }
        val now = System.currentTimeMillis()
        val collection = loadedCollection.collection
        require(collection.expiresEpochSeconds * 1000 > now) { COLLECTION_EXPIRED_ERROR }
        val settings = settingsRepository.settings.value
        val collectionId = UUID.randomUUID().toString()
        val schedulerId = allocateSchedulerId(collectionId)
        val flattenedFiles = flattenCollectionFilesForSelection(collection.tree, selectedPaths, zipPasswords)
        val outputPaths = flattenedFiles.map {
            sanitizeDownloadPath(applyTreeRenames(it.path, renames), settings.asciiFilenames)
        }
        require(outputPaths.distinct().size == outputPaths.size) {
            "파일명 변환 후 중복되는 다운로드 경로가 있습니다."
        }
        val files = flattenedFiles.zip(outputPaths)
            .map { file ->
            val (flatFile, outputPath) = file
            DownloadFileEntity(
                id = UUID.randomUUID().toString(),
                collectionId = collectionId,
                remoteId = flatFile.remoteId,
                path = outputPath,
                name = outputPath.substringAfterLast('/'),
                size = flatFile.node.size,
                selected = flatFile.path in selectedPaths,
                status = "PENDING",
                downloadedBytes = 0,
                pausedByUser = false,
                createdAt = now,
                updatedAt = now,
                error = null,
                sourceKind = flatFile.sourceKind,
                zipEntryJson = flatFile.zipEntryJson,
                sourceMetaJson = loadedCollection.sourceKeys[flatFile.remoteId]?.let { key ->
                    JSONObject().put("nodeKey", key).toString()
                },
                completedElsewhere = false,
            )
            }
        require(files.any(DownloadFileEntity::selected)) { "다운로드할 파일이 없습니다." }
        require(files.none(DownloadFileEntity::isEncryptedZipWithoutPassword)) {
            "암호화된 ZIP 파일의 비밀번호를 입력해 주세요."
        }
        downloadDao.insertDownload(
            download = DownloadEntity(
                id = collectionId,
                schedulerId = schedulerId,
                shareId = collection.shareId,
                sourceUrl = url,
                provider = collection.provider.name,
                name = collection.name,
                rootId = loadedCollection.rootId,
                segmentSize = collection.segmentSize,
                expiresEpochSeconds = collection.expiresEpochSeconds,
                passwordProtected = collection.passwordProtected,
                passwordPlain = passwordPlain,
                treeCbor = KdxCodec.encodeTree(collection.tree.applyTreeRenames(renames).toKdxNode()),
                asciiFilenames = settings.asciiFilenames,
                destinationSubfolder = if (shouldCreateCollectionSubfolder(
                    collection.tree, collection.name, settings.createCollectionSubfolder,
                )) sanitizeDownloadPathSegment(collection.name, settings.asciiFilenames) else "",
                destinationUri = destinationUri,
                status = DownloadStatus.QUEUED.name,
                transferredBytes = 0,
                totalBytes = files.filter(DownloadFileEntity::selected).sumOf(DownloadFileEntity::size),
                elapsedMillis = 0,
                activeStartedAt = null,
                createdAt = now,
                updatedAt = now,
                error = null,
            ),
            files = files,
        )
        workScheduler.enqueue(
            collectionId = collectionId,
            schedulerId = schedulerId,
            estimatedDownloadBytes = files.filter(DownloadFileEntity::selected).sumOf(DownloadFileEntity::size),
        )
    }

    override suspend fun pause(collectionId: String): Result<Unit> = runCatching {
        val download = requireNotNull(downloadDao.getDownload(collectionId))
        downloadDao.pauseCollection(collectionId)
        workScheduler.cancel(collectionId, download.schedulerId)
    }

    override suspend fun resume(collectionId: String): Result<Unit> = runCatching {
        val download = requireNotNull(downloadDao.getDownload(collectionId))
        if (download.expiresEpochSeconds * 1000 <= System.currentTimeMillis()) {
            downloadDao.updateCollectionState(
                collectionId, "EXPIRED", System.currentTimeMillis(), COLLECTION_EXPIRED_ERROR,
            )
            error(COLLECTION_EXPIRED_ERROR)
        }
        workScheduler.cancel(collectionId, download.schedulerId)
        downloadDao.resumeCollection(collectionId)
        workScheduler.enqueue(collectionId, download.schedulerId, download.totalBytes - download.transferredBytes)
    }

    override suspend fun pauseFile(collectionId: String, fileId: String): Result<Unit> = runCatching {
        val download = requireNotNull(downloadDao.getDownload(collectionId))
        workScheduler.cancel(collectionId, download.schedulerId)
        downloadDao.recoverInterruptedCollection(collectionId)
        val now = System.currentTimeMillis()
        downloadDao.resetRunningChunksForFile(fileId, now)
        downloadDao.pauseFile(collectionId, fileId, now)
        downloadDao.recomputeCollectionTotals(collectionId, now)
        if (downloadDao.countRunnableFiles(collectionId) > 0) {
            val updated = requireNotNull(downloadDao.getDownload(collectionId))
            workScheduler.enqueue(collectionId, download.schedulerId, updated.totalBytes - updated.transferredBytes)
        } else {
            downloadDao.updateCollectionState(collectionId, "PAUSED", now)
        }
    }

    override suspend fun resumeFile(collectionId: String, fileId: String): Result<Unit> = runCatching {
        val download = requireNotNull(downloadDao.getDownload(collectionId))
        require(download.expiresEpochSeconds * 1000 > System.currentTimeMillis()) { COLLECTION_EXPIRED_ERROR }
        val now = System.currentTimeMillis()
        downloadDao.resumeFile(collectionId, fileId, now)
        downloadDao.recomputeCollectionTotals(collectionId, now)
        downloadDao.updateCollectionState(collectionId, "QUEUED", now)
        val updated = requireNotNull(downloadDao.getDownload(collectionId))
        workScheduler.enqueue(collectionId, download.schedulerId, updated.totalBytes - updated.transferredBytes)
    }

    override suspend fun remove(collectionId: String): Result<Unit> = runCatching {
        val download = requireNotNull(downloadDao.getDownload(collectionId))
        downloadDao.updateCollectionState(collectionId, "DELETING", System.currentTimeMillis())
        workScheduler.cancel(collectionId, download.schedulerId)
        File(context.filesDir, "transfers/$collectionId").deleteRecursively()
        downloadDao.deleteDownload(collectionId)
    }

    override suspend fun exportKdx(collectionId: String, outputUri: String): Result<Unit> = runCatching {
        val snapshot = requireNotNull(downloadDao.getTransferSnapshot(collectionId))
        val download = snapshot.download
        val files = snapshot.files
        val payload = KdxPayload(
            version = 1,
            kind = "kiosk-download-collection",
            exportedAt = System.currentTimeMillis(),
            collection = KdxCollection(
                shareId = download.shareId,
                sourceUrl = download.sourceUrl,
                passwordPlain = download.passwordPlain,
                name = download.name,
                rootId = download.rootId,
                segmentSize = download.segmentSize,
                expires = download.expiresEpochSeconds,
                tree = download.treeCbor.takeIf(ByteArray::isNotEmpty)
                    ?.let(KdxCodec::decodeTree) ?: buildKdxTree(download.rootId, files),
                asciiFilenames = download.asciiFilenames,
                provider = download.provider.lowercase(),
            ),
            files = files.map { file ->
                KdxFile(
                    remoteId = file.remoteId,
                    path = file.path,
                    name = file.name,
                    size = file.size,
                    selected = file.selected,
                    status = if (file.selected && file.status == "COMPLETED") "completed" else "pending",
                    completedElsewhere = file.selected && file.status == "COMPLETED",
                    sourceKind = file.sourceKind,
                    zipEntryJson = file.zipEntryJson,
                    sourceMetaJson = file.sourceMetaJson,
                )
            },
        )
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(Uri.parse(outputUri), "wt")
                ?.use { it.write(KdxCodec.encode(payload)) }
                ?: error("KDX 파일을 열 수 없습니다.")
        }
    }

    override suspend fun importKdx(inputUri: String, destinationUri: String): Result<Unit> = runCatching {
        require(destinationUri.isNotBlank()) { "저장할 폴더를 선택해 주세요." }
        val payload = withContext(Dispatchers.IO) {
            val raw = context.contentResolver.openInputStream(Uri.parse(inputUri))
                ?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 16 * 1024 * 1024) { "KDX 파일이 너무 큽니다." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("KDX 파일을 열 수 없습니다.")
            KdxCodec.decode(raw)
        }
        val collectionId = UUID.randomUUID().toString()
        val schedulerId = allocateSchedulerId(collectionId)
        val now = System.currentTimeMillis()
        val files = payload.files.map { file ->
            DownloadFileEntity(
                id = UUID.randomUUID().toString(), collectionId = collectionId,
                remoteId = file.remoteId, path = file.path, name = file.name, size = file.size,
                selected = file.selected,
                status = if (file.selected && file.status == "completed") "COMPLETED" else "PENDING",
                downloadedBytes = if (file.selected && file.status == "completed") file.size else 0,
                pausedByUser = false, createdAt = now, updatedAt = now, error = null,
                sourceKind = file.sourceKind, zipEntryJson = file.zipEntryJson,
                sourceMetaJson = file.sourceMetaJson,
                completedElsewhere = file.selected && file.status == "completed",
            )
        }
        val selectedBytes = files.filter(DownloadFileEntity::selected).sumOf(DownloadFileEntity::size)
        val transferred = files.filter { it.selected }.sumOf(DownloadFileEntity::downloadedBytes)
        val runnable = payload.collection.provider in setOf("kiosk", "transfer") && transferred < selectedBytes
        downloadDao.insertDownload(
            DownloadEntity(
                id = collectionId, schedulerId = schedulerId, shareId = payload.collection.shareId,
                sourceUrl = payload.collection.sourceUrl, provider = payload.collection.provider.uppercase(),
                name = payload.collection.name, rootId = payload.collection.rootId,
                segmentSize = payload.collection.segmentSize, expiresEpochSeconds = payload.collection.expires,
                passwordProtected = payload.collection.passwordPlain != null,
                passwordPlain = payload.collection.passwordPlain, destinationUri = destinationUri,
                treeCbor = KdxCodec.encodeTree(payload.collection.tree),
                asciiFilenames = payload.collection.asciiFilenames,
                destinationSubfolder = if (
                    settingsRepository.settings.value.createCollectionSubfolder &&
                    payload.collection.tree.entries.orEmpty().size > 1
                ) sanitizeDownloadPathSegment(payload.collection.name, payload.collection.asciiFilenames) else "",
                status = when {
                    transferred == selectedBytes -> "COMPLETED"
                    payload.collection.expires * 1000 <= now -> "EXPIRED"
                    runnable -> "QUEUED"
                    else -> "PAUSED"
                },
                transferredBytes = transferred, totalBytes = selectedBytes, createdAt = now, updatedAt = now,
                elapsedMillis = 0, activeStartedAt = null,
                error = null,
            ),
            files,
        )
        if (runnable && payload.collection.expires * 1000 > now) {
            workScheduler.enqueue(collectionId, schedulerId, selectedBytes - transferred)
        }
    }

    private suspend fun allocateSchedulerId(collectionId: String): Int {
        var candidate = (collectionId.hashCode() and MAX_DIRECT_JOB_ID).coerceAtLeast(1)
        while (downloadDao.isSchedulerIdInUse(candidate)) {
            candidate = if (candidate == MAX_DIRECT_JOB_ID) 1 else candidate + 1
        }
        return candidate
    }
}

private const val COLLECTION_EXPIRED_ERROR = "Collection has expired."
private const val MAX_DIRECT_JOB_ID = 0x3fffffff

private class MutableKdxDirectory(val id: String, val name: String) {
    val directories = linkedMapOf<String, MutableKdxDirectory>()
    val files = mutableListOf<KdxNode>()
    fun freeze(): KdxNode = KdxNode(
        type = "dir", id = id, name = name,
        entries = directories.values.map { KdxEntry("dir", it.freeze()) } + files.map { KdxEntry("file", it) },
    )
}

private fun buildKdxTree(rootId: String, files: List<DownloadFileEntity>): KdxNode {
    val root = MutableKdxDirectory(rootId, "")
    files.forEach { file ->
        val parts = file.path.split('/').filter(String::isNotBlank)
        var directoryPath = ""
        val directory = parts.dropLast(1).fold(root) { parent, name ->
            directoryPath = listOf(directoryPath, name).filter(String::isNotBlank).joinToString("/")
            parent.directories.getOrPut(name) {
                MutableKdxDirectory("dir:$directoryPath", name)
            }
        }
        directory.files += KdxNode("file", file.remoteId, file.name, size = file.size)
    }
    return root.freeze()
}

private fun DirectoryNode.toKdxNode(): KdxNode = KdxNode(
    type = "dir",
    id = id,
    name = name,
    entries = entries.map { entry ->
        when (entry) {
            is DirectoryNode -> KdxEntry("dir", entry.toKdxNode())
            is FileNode -> KdxEntry(
                "file",
                KdxNode(
                    type = "file", id = entry.id, name = entry.name, size = entry.size,
                    zipEntry = entry.zipEntry?.let { meta ->
                        com.kiodl.android.transfer.KdxZipEntry(
                            path = meta.path,
                            offset = meta.offset,
                            compressedSize = meta.compressedSize,
                            uncompressedSize = meta.uncompressedSize,
                            compressionMethod = meta.compressionMethod,
                            encrypted = meta.encrypted,
                        )
                    },
                ),
            )
            is ZipNode -> KdxEntry(
                "zip",
                KdxNode(
                    type = "zip",
                    id = entry.id,
                    name = entry.name,
                    size = entry.size,
                    entries = entry.entries?.map { child ->
                        when (child) {
                            is DirectoryNode -> KdxEntry("dir", child.toKdxNode())
                            is FileNode -> KdxEntry(
                                "file",
                                KdxNode(
                                    "file", child.id, child.name, size = child.size,
                                    zipEntry = child.zipEntry?.let { meta ->
                                        com.kiodl.android.transfer.KdxZipEntry(
                                            meta.path, meta.offset, meta.compressedSize,
                                            meta.uncompressedSize, meta.compressionMethod, meta.encrypted,
                                        )
                                    },
                                ),
                            )
                            is ZipNode -> KdxEntry(
                                "zip",
                                KdxNode("zip", child.id, child.name, size = child.size),
                            )
                        }
                    },
                ),
            )
        }
    },
)

private fun DownloadEntity.toDomain(
    files: List<DownloadFileEntity>,
    speedSampler: TransferSpeedSampler,
): DownloadItem {
    val now = System.currentTimeMillis()
    val active = status == "DOWNLOADING" || status == "INFLATING"
    return DownloadItem(
    id = id,
    shareId = shareId,
    provider = DownloadProvider.valueOf(provider),
    name = name,
    destinationUri = destinationUri,
    status = DownloadStatus.valueOf(status),
    transferredBytes = transferredBytes,
    totalBytes = totalBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    error = error,
    files = files.map { file ->
        val fileActive = file.status == "DOWNLOADING" || file.status == "INFLATING"
        DownloadFileProgress(
            id = file.id,
            path = file.path,
            status = FileDownloadStatus.valueOf(file.status),
            downloadedBytes = file.downloadedBytes,
            totalBytes = file.size,
            selected = file.selected,
            pausedByUser = file.pausedByUser,
            error = file.error,
            speedBytesPerSecond = speedSampler.sample("download-file:${file.id}", file.downloadedBytes, fileActive, now),
        )
    },
    speedBytesPerSecond = speedSampler.sample("download:$id", transferredBytes, active, now),
    elapsedMillis = elapsedMillis + (activeStartedAt?.let { (now - it).coerceAtLeast(0) } ?: 0),
)
}

private fun DownloadFileEntity.isEncryptedZipWithoutPassword(): Boolean {
    if (!selected || sourceKind != "zip_entry" || zipEntryJson == null) return false
    return JSONObject(zipEntryJson).let { it.optBoolean("encrypted") && it.optString("password").isEmpty() }
}

internal data class FlatCollectionFile(
    val node: CollectionFile,
    val path: String,
    val remoteId: String = node.id,
    val sourceKind: String = "file",
    val zipEntryJson: String? = null,
)

internal fun flattenCollectionFiles(
    directory: DirectoryNode,
    prefix: List<String> = emptyList(),
): List<FlatCollectionFile> = directory.entries.flatMap { entry ->
        when (entry) {
            is DirectoryNode -> flattenCollectionFiles(entry, prefix + entry.name)
            is CollectionFile -> listOf(
                FlatCollectionFile(
                    node = entry,
                    path = (prefix + entry.name).joinToString("/"),
                ),
            )
        }
    }

private fun flattenCollectionFilesForSelection(
    directory: DirectoryNode,
    selectedPaths: Set<String>,
    zipPasswords: Map<String, String>,
    prefix: List<String> = emptyList(),
): List<FlatCollectionFile> = directory.entries.flatMap { entry ->
    when (entry) {
        is DirectoryNode -> flattenCollectionFilesForSelection(
            entry, selectedPaths, zipPasswords, prefix + entry.name,
        )
        is FileNode -> listOf(FlatCollectionFile(entry, (prefix + entry.name).joinToString("/")))
        is ZipNode -> {
            val archivePath = prefix + entry.name
            val extracted = entry.entries.orEmpty().flatMap { child ->
                flattenZipFiles(child, archivePath, entry.id, zipPasswords[entry.id])
            }
            if (extracted.any { it.path in selectedPaths }) {
                extracted
            } else {
                listOf(FlatCollectionFile(entry, archivePath.joinToString("/")))
            }
        }
    }
}

private fun flattenZipFiles(
    node: CollectionNode,
    prefix: List<String>,
    archiveId: String,
    zipPassword: String?,
): List<FlatCollectionFile> = when (node) {
    is DirectoryNode -> node.entries.flatMap {
        flattenZipFiles(it, prefix + node.name, archiveId, zipPassword)
    }
    is FileNode -> listOf(
        FlatCollectionFile(
            node = node,
            path = (prefix + node.name).joinToString("/"),
            remoteId = archiveId,
            sourceKind = "zip_entry",
            zipEntryJson = node.zipEntry?.let { meta ->
                JSONObject()
                    .put("path", meta.path)
                    .put("offset", meta.offset)
                    .put("compressedSize", meta.compressedSize)
                    .put("uncompressedSize", meta.uncompressedSize)
                    .put("compressionMethod", meta.compressionMethod)
                    .put("encrypted", meta.encrypted)
                    .put("archiveSize", meta.archiveSize)
                    .put("crc32", meta.crc32)
                    .apply { if (meta.encrypted && zipPassword != null) put("password", zipPassword) }
                    .toString()
            },
        ),
    )
    is ZipNode -> listOf(FlatCollectionFile(node, (prefix + node.name).joinToString("/")))
}
