package com.kiodl.android.transfer

import android.content.Context
import android.net.Network
import com.kiodl.android.data.local.DownloadChunkEntity
import com.kiodl.android.data.local.DownloadDao
import com.kiodl.android.data.local.DownloadEntity
import com.kiodl.android.data.local.DownloadFileEntity
import com.kiodl.android.data.remote.KioApiClient
import com.kiodl.android.data.remote.KioSegmentedReader
import com.kiodl.android.data.remote.TransferItApiClient
import com.kiodl.android.data.remote.TransferRateLimitException
import com.kiodl.android.data.settings.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import com.kiodl.android.transfer.zip.EncryptedZipEntryExtractor
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Dns
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val kioApiClient: KioApiClient,
    private val transferItApiClient: TransferItApiClient,
    private val httpClient: OkHttpClient,
    private val safFileFinalizer: SafFileFinalizer,
    private val settingsRepository: AppSettingsRepository,
    private val segmentPools: SegmentPoolCoordinator,
    private val bandwidth: TransferBandwidthCoordinator,
    private val transferAdaptive: TransferAdaptiveCoordinator,
) {
    suspend fun run(
        collectionId: String,
        network: Network? = null,
        onProgress: suspend (DownloadEntity, Long) -> Unit,
    ) {
        val current = downloadDao.getDownload(collectionId) ?: return
        if (current.status !in setOf("QUEUED", "DOWNLOADING", "ERROR")) return
        downloadDao.clearActiveTimer(collectionId)
        downloadDao.startTimer(collectionId, System.currentTimeMillis())
        try {
            runInternal(collectionId, network, onProgress)
        } finally {
            kotlinx.coroutines.withContext(NonCancellable) {
                downloadDao.stopTimer(collectionId, System.currentTimeMillis())
            }
        }
    }

    private suspend fun runInternal(
        collectionId: String,
        network: Network? = null,
        onProgress: suspend (DownloadEntity, Long) -> Unit,
    ) {
        downloadDao.recoverInterruptedCollection(collectionId)
        val collection = downloadDao.getDownload(collectionId)
            ?: throw IOException("Download collection not found.")
        if (collection.status !in setOf("QUEUED", "DOWNLOADING", "ERROR")) return
        if (collection.expiresEpochSeconds * 1000 <= System.currentTimeMillis()) {
            downloadDao.updateCollectionState(
                collectionId, "EXPIRED", System.currentTimeMillis(), COLLECTION_EXPIRED_ERROR,
            )
            throw DownloadCollectionExpiredException(COLLECTION_EXPIRED_ERROR)
        }
        onProgress(collection, collection.transferredBytes)
        downloadDao.updateCollectionState(
            collectionId = collectionId,
            status = "DOWNLOADING",
            updatedAt = System.currentTimeMillis(),
        )
        // UIDT may pin a Network; bind sockets and DNS so traffic stays on that network.
        val boundHttpClient = network?.let { activeNetwork ->
            httpClient.newBuilder()
                .socketFactory(activeNetwork.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = activeNetwork.getAllByName(hostname).toList()
                })
                .build()
        }
        val activeKioClient = boundHttpClient?.let(::KioApiClient) ?: kioApiClient
        val activeTransferClient = boundHttpClient?.let(::TransferItApiClient) ?: transferItApiClient
        val accessToken = if (collection.provider == "KIOSK") {
            activeKioClient.refreshToken(collection.shareId, collection.passwordPlain).also { refreshed ->
                downloadDao.updateCollectionExpiry(
                    collectionId, refreshed.expiresEpochSeconds, System.currentTimeMillis(),
                )
                if (refreshed.expiresEpochSeconds * 1000 <= System.currentTimeMillis()) {
                    downloadDao.updateCollectionState(
                        collectionId, "EXPIRED", System.currentTimeMillis(), COLLECTION_EXPIRED_ERROR,
                    )
                    throw DownloadCollectionExpiredException(COLLECTION_EXPIRED_ERROR)
                }
            }.token
        } else {
            ""
        }
        val settings = settingsRepository.settings.value
        val limiter = bandwidth
        val progressMutex = Mutex()
        val activeFiles = Semaphore(settings.segmentPoolSize)
        coroutineScope {
            downloadDao.listPendingSelectedFiles(collectionId).map { file ->
                async {
                    try {
                        activeFiles.withPermit {
                            val reportProgress: suspend (DownloadEntity, Long) -> Unit = { item, bytes ->
                                progressMutex.withLock { onProgress(item, bytes) }
                            }
                            if (collection.provider == "TRANSFER") {
                                downloadTransferFile(
                                    collection, file, activeTransferClient, limiter,
                                    settings.segmentPoolSize, reportProgress,
                                )
                            } else {
                                downloadFile(
                                    collection, file, accessToken, activeKioClient, limiter,
                                    settings.segmentPoolSize, reportProgress,
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val current = downloadDao.listFiles(collectionId).firstOrNull { it.id == file.id }
                        downloadDao.updateFileState(
                            file.id, "ERROR", current?.downloadedBytes ?: file.downloadedBytes,
                            System.currentTimeMillis(), error.message ?: "Download failed.",
                        )
                        throw error
                    }
                }
            }.awaitAll()
        }
        downloadDao.recomputeCollectionProgress(collectionId, System.currentTimeMillis())
        if (downloadDao.countIncompleteSelectedFiles(collectionId) == 0) {
            downloadDao.updateCollectionState(
                collectionId = collectionId,
                status = "COMPLETED",
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun recover(collectionId: String) {
        downloadDao.recoverInterruptedCollection(collectionId)
    }

    suspend fun markError(collectionId: String, error: Throwable) {
        if (error is DownloadCollectionExpiredException || downloadDao.getDownload(collectionId)?.status == "EXPIRED") {
            return
        }
        downloadDao.updateCollectionState(
            collectionId = collectionId,
            status = "ERROR",
            updatedAt = System.currentTimeMillis(),
            error = error.message ?: "Download failed.",
        )
    }

    private suspend fun downloadFile(
        collection: DownloadEntity,
        file: DownloadFileEntity,
        accessToken: String,
        activeKioClient: KioApiClient,
        limiter: TransferBandwidthCoordinator,
        segmentPoolSize: Int,
        onProgress: suspend (DownloadEntity, Long) -> Unit,
    ) {
        if (file.sourceKind == "zip_entry") {
            downloadZipEntry(
                collection, file, limiter, accessToken, activeKioClient,
                segmentPoolSize, onProgress,
            )
            return
        }
        val stagingFile = File(context.filesDir, "transfers/${collection.id}/${file.id}.part")
        stagingFile.parentFile?.mkdirs()
        downloadDao.updateFileState(
            fileId = file.id,
            status = "DOWNLOADING",
            downloadedBytes = file.downloadedBytes,
            updatedAt = System.currentTimeMillis(),
        )

        if (file.size > 0) {
            val segments = activeKioClient.getSegments(file.remoteId, accessToken)
            val chunks = buildKioChunkLayout(file.size, collection.segmentSize)
            if (segments.size < chunks.size) {
                throw IOException("File segment count is smaller than expected.")
            }
            RandomAccessFile(stagingFile, "rw").use { it.setLength(file.size) }
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        segmentPools.withDownloadPermit(segmentPoolSize) {
                            withNetworkRetries(settingsRepository.settings.value.downloadMaxRetries) {
                                downloadKioChunk(
                                    collection = collection,
                                    file = file,
                                    stagingFile = stagingFile,
                                    chunk = chunk,
                                    activeKioClient = activeKioClient,
                                    segment = segments[chunk.index],
                                    limiter = limiter,
                                    onProgress = onProgress,
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        } else {
            stagingFile.outputStream().use { }
        }

        safFileFinalizer.finalize(
            stagingFile = stagingFile,
            destinationTreeUri = collection.destinationUri,
            relativePath = collection.destinationPath(file.path),
        )
        downloadDao.updateFileState(
            fileId = file.id,
            status = "COMPLETED",
            downloadedBytes = file.size,
            updatedAt = System.currentTimeMillis(),
        )
        downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
        stagingFile.delete()
    }

    private suspend fun downloadKioChunk(
        collection: DownloadEntity,
        file: DownloadFileEntity,
        stagingFile: File,
        chunk: KioChunk,
        activeKioClient: KioApiClient,
        segment: com.kiodl.android.data.remote.KioSegment,
        limiter: TransferBandwidthCoordinator,
        onProgress: suspend (DownloadEntity, Long) -> Unit,
    ) = RandomAccessFile(stagingFile, "rw").use { output ->
        val stored = downloadDao.getChunk(file.id, chunk.index)
        val completedCrc = if (
            stored?.status == "COMPLETED" && output.length() >= chunk.offset + chunk.size
        ) crc32(output, chunk.offset, chunk.size) else null
        if (
            stored?.status == "COMPLETED" &&
            completedCrc != null &&
            (stored.crc32 == null || stored.crc32 == completedCrc)
        ) {
            if (stored.crc32 == null) downloadDao.upsertChunk(stored.copy(crc32 = completedCrc))
            downloadDao.syncFileProgressFromChunks(file.id, System.currentTimeMillis())
            return@use
        }

        val localStart = stored?.downloadedBytes
            ?.takeIf { it in 1 until chunk.size && output.length() >= chunk.offset + it }
            ?: 0L
        var chunkDownloaded = localStart
        var bytesSinceForce = 0
        var bytesSinceCheckpoint = 0
        val attempts = (stored?.attempts ?: 0) + 1
        val chunkCrc = CRC32().apply {
            if (localStart > 0) updateCrcRange(this, output, chunk.offset, localStart)
        }
        downloadDao.upsertChunk(
            DownloadChunkEntity(
                collectionId = collection.id, fileId = file.id, chunkIndex = chunk.index,
                offset = chunk.offset, size = chunk.size, status = "DOWNLOADING",
                downloadedBytes = localStart, attempts = attempts,
                updatedAt = System.currentTimeMillis(), error = null,
            ),
        )
        activeKioClient.streamSegment(
            segment = segment,
            localStart = localStart,
            expectedBytes = chunk.size - localStart,
        ) { bytes ->
            limiter.consumeDownload(bytes.size, settingsRepository.settings.value.downloadBandwidthMiBps)
            val buffer = ByteBuffer.wrap(bytes)
            var position = chunk.offset + chunkDownloaded
            while (buffer.hasRemaining()) {
                position += output.channel.write(buffer, position)
            }
            chunkCrc.update(bytes)
            bytesSinceForce += bytes.size
            if (bytesSinceForce >= settingsRepository.settings.value.streamWriteBatchBytes) {
                output.channel.force(false)
                bytesSinceForce = 0
            }
            chunkDownloaded += bytes.size
            bytesSinceCheckpoint += bytes.size
            if (bytesSinceCheckpoint >= settingsRepository.settings.value.streamWriteBatchBytes) {
                bytesSinceCheckpoint = 0
                downloadDao.upsertChunk(
                    DownloadChunkEntity(
                        collectionId = collection.id, fileId = file.id, chunkIndex = chunk.index,
                        offset = chunk.offset, size = chunk.size, status = "DOWNLOADING",
                        downloadedBytes = chunkDownloaded, attempts = attempts,
                        updatedAt = System.currentTimeMillis(), error = null,
                    ),
                )
                downloadDao.syncFileProgressFromChunks(file.id, System.currentTimeMillis())
                downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
                onProgress(collection, downloadDao.getDownload(collection.id)?.transferredBytes ?: 0L)
            }
        }
        output.channel.force(false)
        downloadDao.upsertChunk(
            DownloadChunkEntity(
                collectionId = collection.id, fileId = file.id, chunkIndex = chunk.index,
                offset = chunk.offset, size = chunk.size, status = "COMPLETED",
                downloadedBytes = chunk.size, attempts = attempts,
                updatedAt = System.currentTimeMillis(), error = null, crc32 = chunkCrc.value,
            ),
        )
        downloadDao.syncFileProgressFromChunks(file.id, System.currentTimeMillis())
        downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
        onProgress(collection, downloadDao.getDownload(collection.id)?.transferredBytes ?: 0L)
    }

    private fun crc32(file: RandomAccessFile, offset: Long, size: Long): Long = CRC32().also { crc ->
        updateCrcRange(crc, file, offset, size)
    }.value

    private fun updateCrcRange(crc: CRC32, file: RandomAccessFile, offset: Long, size: Long) {
        val original = file.filePointer
        try {
            file.seek(offset)
            val buffer = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                file.readFully(buffer, 0, count)
                crc.update(buffer, 0, count)
                remaining -= count
            }
        } finally {
            file.seek(original)
        }
    }

    private suspend fun downloadZipEntry(
        collection: DownloadEntity,
        file: DownloadFileEntity,
        limiter: TransferBandwidthCoordinator,
        accessToken: String,
        activeKioClient: KioApiClient,
        segmentPoolSize: Int,
        onProgress: suspend (DownloadEntity, Long) -> Unit,
    ) {
        val meta = ZipDownloadMeta.parse(requireNotNull(file.zipEntryJson))
        if (meta.encrypted) {
            downloadEncryptedZipEntry(
                collection, file, meta, limiter, accessToken, activeKioClient,
                segmentPoolSize, onProgress,
            )
            return
        }
        require(meta.compressionMethod == 0 || meta.compressionMethod == 8) {
            "지원하지 않는 ZIP 압축 방식입니다: ${meta.compressionMethod}"
        }
        val directory = File(context.filesDir, "transfers/${collection.id}").apply(File::mkdirs)
        val outputFile = File(directory, "${file.id}.part")
        val compressedFile = if (meta.compressionMethod == 8) File(directory, "${file.id}.part.z") else outputFile
        val segments = activeKioClient.getSegments(file.remoteId, accessToken)
        val reader = KioSegmentedReader(
            meta.archiveSize, collection.segmentSize, segments, activeKioClient,
        ) { block -> segmentPools.withDownloadPermit(segmentPoolSize, block) }
        val localHeader = withNetworkRetries(settingsRepository.settings.value.downloadMaxRetries) {
            reader.read(meta.offset, 30)
        }
        val header = ByteBuffer.wrap(localHeader).order(ByteOrder.LITTLE_ENDIAN)
        require(header.int == 0x04034b50) { "잘못된 ZIP local header입니다." }
        header.position(26)
        val nameLength = header.short.toInt() and 0xffff
        val extraLength = header.short.toInt() and 0xffff
        val dataOffset = meta.offset + 30 + nameLength + extraLength
        val stored = downloadDao.listChunks(file.id).firstOrNull()
        val downloaded = stored?.downloadedBytes
            ?.takeIf { it in 1..meta.compressedSize && compressedFile.length() >= it }
            ?: 0L
        downloadDao.updateFileState(file.id, "DOWNLOADING", file.downloadedBytes, System.currentTimeMillis())
        compressedFile.parentFile?.mkdirs()
        java.io.RandomAccessFile(compressedFile, "rw").use { output ->
            output.setLength(downloaded)
            output.seek(downloaded)
            var transferred = downloaded
            var bytesSinceForce = 0
            while (transferred < meta.compressedSize) {
                coroutineContext.ensureActive()
                val count = minOf(1024 * 1024L, meta.compressedSize - transferred).toInt()
                val bytes = withNetworkRetries(settingsRepository.settings.value.downloadMaxRetries) {
                    reader.read(dataOffset + transferred, count)
                }
                output.write(bytes)
                bytesSinceForce += bytes.size
                val checkpoint = bytesSinceForce >= settingsRepository.settings.value.streamWriteBatchBytes
                if (checkpoint) {
                    output.channel.force(false)
                    bytesSinceForce = 0
                }
                transferred += bytes.size
                if (checkpoint) {
                    downloadDao.upsertChunk(
                        DownloadChunkEntity(
                            collectionId = collection.id, fileId = file.id, chunkIndex = 0,
                            offset = 0, size = meta.compressedSize, status = "DOWNLOADING",
                            downloadedBytes = transferred, attempts = (stored?.attempts ?: 0) + 1,
                            updatedAt = System.currentTimeMillis(), error = null,
                        ),
                    )
                    val progress = if (meta.compressedSize == 0L) file.size else {
                        (transferred.toDouble() / meta.compressedSize * file.size).toLong().coerceAtMost(file.size)
                    }
                    downloadDao.updateFileState(file.id, "DOWNLOADING", progress, System.currentTimeMillis())
                    downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
                    onProgress(collection, downloadDao.getDownload(collection.id)?.transferredBytes ?: 0)
                }
            }
            output.channel.force(false)
        }
        downloadDao.upsertChunk(
            DownloadChunkEntity(
                collectionId = collection.id, fileId = file.id, chunkIndex = 0,
                offset = 0, size = meta.compressedSize, status = "COMPLETED",
                downloadedBytes = meta.compressedSize, attempts = (stored?.attempts ?: 0) + 1,
                updatedAt = System.currentTimeMillis(), error = null,
            ),
        )
        if (meta.compressionMethod == 8) {
            downloadDao.updateFileState(
                file.id, "INFLATING", (file.size - 1).coerceAtLeast(0), System.currentTimeMillis(),
            )
            outputFile.outputStream().buffered().use { output ->
                InflaterInputStream(compressedFile.inputStream().buffered(), Inflater(true)).use { input ->
                    val buffer = ByteArray(settingsRepository.settings.value.inflateBufferBytes)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        require(outputFile.length() == file.size) { "ZIP 항목 크기가 일치하지 않습니다." }
        val crc = CRC32()
        outputFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(settingsRepository.settings.value.inflateBufferBytes)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        require(crc.value == meta.crc32) { "ZIP 항목 CRC가 일치하지 않습니다." }
        safFileFinalizer.finalize(outputFile, collection.destinationUri, collection.destinationPath(file.path))
        downloadDao.updateFileState(file.id, "COMPLETED", file.size, System.currentTimeMillis())
        downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
        outputFile.delete()
        compressedFile.takeIf { it != outputFile }?.delete()
    }

    private suspend fun downloadEncryptedZipEntry(
        collection: DownloadEntity,
        file: DownloadFileEntity,
        meta: ZipDownloadMeta,
        limiter: TransferBandwidthCoordinator,
        accessToken: String,
        activeKioClient: KioApiClient,
        segmentPoolSize: Int,
        onProgress: suspend (DownloadEntity, Long) -> Unit,
    ) {
        val password = requireNotNull(meta.password?.takeIf(String::isNotEmpty)) {
            "암호화된 ZIP 파일의 비밀번호가 필요합니다."
        }
        val directory = File(context.filesDir, "transfers/${collection.id}").apply(File::mkdirs)
        val entryFile = File(directory, "${file.id}.encrypted-entry.zip")
        val outputFile = File(directory, "${file.id}.part")
        val segments = activeKioClient.getSegments(file.remoteId, accessToken)
        val reader = KioSegmentedReader(
            meta.archiveSize, collection.segmentSize, segments, activeKioClient,
        ) { block -> segmentPools.withDownloadPermit(segmentPoolSize, block) }
        val localHeader = withNetworkRetries(settingsRepository.settings.value.downloadMaxRetries) {
            reader.read(meta.offset, 30)
        }
        val headerBuffer = ByteBuffer.wrap(localHeader).order(ByteOrder.LITTLE_ENDIAN)
        require(headerBuffer.int == 0x04034b50) { "잘못된 ZIP local header입니다." }
        headerBuffer.position(26)
        val nameLength = headerBuffer.short.toInt() and 0xffff
        val extraLength = headerBuffer.short.toInt() and 0xffff
        val localHeaderSize = 30L + nameLength + extraLength
        val entryRangeSize = minOf(
            localHeaderSize + meta.compressedSize + 24L,
            meta.archiveSize - meta.offset,
        )
        val stored = downloadDao.listChunks(file.id).firstOrNull()
        var downloaded = stored?.downloadedBytes
            ?.takeIf { it in 1..entryRangeSize && entryFile.length() >= it }
            ?: 0L
        java.io.RandomAccessFile(entryFile, "rw").use { output ->
            output.setLength(downloaded)
            output.seek(downloaded)
            var bytesSinceForce = 0
            while (downloaded < entryRangeSize) {
                coroutineContext.ensureActive()
                val bytes = withNetworkRetries(settingsRepository.settings.value.downloadMaxRetries) {
                    reader.read(
                        meta.offset + downloaded,
                        minOf(1024 * 1024L, entryRangeSize - downloaded).toInt(),
                    )
                }
                limiter.consumeDownload(bytes.size, settingsRepository.settings.value.downloadBandwidthMiBps)
                output.write(bytes)
                bytesSinceForce += bytes.size
                val checkpoint = bytesSinceForce >= settingsRepository.settings.value.streamWriteBatchBytes
                if (checkpoint) {
                    output.channel.force(false)
                    bytesSinceForce = 0
                }
                downloaded += bytes.size
                if (checkpoint) {
                    downloadDao.upsertChunk(
                        DownloadChunkEntity(
                            collection.id, file.id, 0, 0, entryRangeSize, "DOWNLOADING", downloaded,
                            (stored?.attempts ?: 0) + 1, System.currentTimeMillis(), null,
                        ),
                    )
                    val progress = (downloaded.toDouble() / entryRangeSize * file.size).toLong()
                        .coerceIn(0, file.size)
                    downloadDao.updateFileState(file.id, "DOWNLOADING", progress, System.currentTimeMillis())
                    downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
                    onProgress(collection, downloadDao.getDownload(collection.id)?.transferredBytes ?: 0)
                }
            }
            output.channel.force(false)
        }
        downloadDao.updateFileState(
            file.id, "INFLATING", (file.size - 1).coerceAtLeast(0), System.currentTimeMillis(),
        )
        val extractionContext = coroutineContext
        EncryptedZipEntryExtractor.extract(
            entryFile, outputFile, password, meta.path, meta.compressedSize, file.size, meta.crc32,
            settingsRepository.settings.value.inflateBufferBytes,
        ) { extractionContext.ensureActive() }
        require(outputFile.length() == file.size) { "ZIP 비밀번호가 틀렸거나 항목 크기가 일치하지 않습니다." }
        val crc = CRC32()
        outputFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(settingsRepository.settings.value.inflateBufferBytes)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        require(crc.value == meta.crc32) { "ZIP 비밀번호가 틀렸거나 CRC가 일치하지 않습니다." }
        safFileFinalizer.finalize(outputFile, collection.destinationUri, collection.destinationPath(file.path))
        downloadDao.upsertChunk(
            DownloadChunkEntity(
                collection.id, file.id, 0, 0, entryRangeSize, "COMPLETED", entryRangeSize,
                (stored?.attempts ?: 0) + 1, System.currentTimeMillis(), null,
            ),
        )
        downloadDao.updateFileState(file.id, "COMPLETED", file.size, System.currentTimeMillis())
        downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
        entryFile.delete()
        outputFile.delete()
    }

    private suspend fun downloadTransferFile(
        collection: DownloadEntity,
        file: DownloadFileEntity,
        activeTransferClient: TransferItApiClient,
        limiter: TransferBandwidthCoordinator,
        segmentPoolSize: Int,
        onProgress: suspend (DownloadEntity, Long) -> Unit,
    ) {
        val nodeKey = activeTransferClient.decodeNodeKey(
            JSONObject(requireNotNull(file.sourceMetaJson)).getString("nodeKey"),
        )
        var url = withTransferAdaptiveRetries(countSuccess = false) {
            withNetworkRetries(settingsRepository.settings.value.downloadMaxRetries, linearBackoff = true) {
                activeTransferClient.getDownloadUrl(collection.shareId, file.remoteId, collection.passwordPlain)
            }
        }
        val urlMutex = Mutex()
        val stagingFile = File(context.filesDir, "transfers/${collection.id}/${file.id}.part")
        stagingFile.parentFile?.mkdirs()
        val chunks = buildKioChunkLayout(file.size, collection.segmentSize)
        if (file.size > 0) RandomAccessFile(stagingFile, "rw").use { it.setLength(file.size) }
        coroutineScope {
            chunks.map { chunk ->
                async {
                    withTransferAdaptiveRetries {
                        segmentPools.withDownloadPermit(segmentPoolSize) {
                            withNetworkRetries(
                                settingsRepository.settings.value.downloadMaxRetries,
                                linearBackoff = true,
                            ) {
                                downloadTransferChunk(
                                    collection = collection,
                                    file = file,
                                    stagingFile = stagingFile,
                                    chunk = chunk,
                                    nodeKey = nodeKey,
                                    activeTransferClient = activeTransferClient,
                                    currentUrl = { urlMutex.withLock { url } },
                                    refreshUrl = {
                                        urlMutex.withLock {
                                            activeTransferClient.getDownloadUrl(
                                                collection.shareId, file.remoteId, collection.passwordPlain,
                                            ).also { url = it }
                                        }
                                    },
                                    limiter = limiter,
                                    onProgress = onProgress,
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (file.size == 0L) stagingFile.outputStream().use { }
        safFileFinalizer.finalize(stagingFile, collection.destinationUri, collection.destinationPath(file.path))
        downloadDao.updateFileState(file.id, "COMPLETED", file.size, System.currentTimeMillis())
        downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
        stagingFile.delete()
    }

    private suspend fun downloadTransferChunk(
        collection: DownloadEntity,
        file: DownloadFileEntity,
        stagingFile: File,
        chunk: KioChunk,
        nodeKey: ByteArray,
        activeTransferClient: TransferItApiClient,
        currentUrl: suspend () -> String,
        refreshUrl: suspend () -> String,
        limiter: TransferBandwidthCoordinator,
        onProgress: suspend (DownloadEntity, Long) -> Unit,
    ) = RandomAccessFile(stagingFile, "rw").use { output ->
        val stored = downloadDao.getChunk(file.id, chunk.index)
        val completedCrc = if (
            stored?.status == "COMPLETED" && output.length() >= chunk.offset + chunk.size
        ) crc32(output, chunk.offset, chunk.size) else null
        if (stored != null && completedCrc != null && (stored.crc32 == null || stored.crc32 == completedCrc)) {
            if (stored.crc32 == null) downloadDao.upsertChunk(stored.copy(crc32 = completedCrc))
            downloadDao.syncFileProgressFromChunks(file.id, System.currentTimeMillis())
            return@use
        }
        val localStart = stored?.downloadedBytes
            ?.takeIf { it in 1 until chunk.size && output.length() >= chunk.offset + it } ?: 0L
        var downloaded = localStart
        var bytesSinceForce = 0
        var bytesSinceCheckpoint = 0
        val attempts = (stored?.attempts ?: 0) + 1
        val chunkCrc = CRC32().apply {
            if (localStart > 0) updateCrcRange(this, output, chunk.offset, localStart)
        }
        downloadDao.upsertChunk(
            DownloadChunkEntity(
                collection.id, file.id, chunk.index, chunk.offset, chunk.size,
                "DOWNLOADING", downloaded, attempts, System.currentTimeMillis(), null,
            ),
        )

        suspend fun downloadRange(targetUrl: String) {
            val requestStart = downloaded
            activeTransferClient.downloadEncryptedRange(
                targetUrl, chunk.offset + requestStart, chunk.size - requestStart, nodeKey,
            ) { bytes ->
                limiter.consumeDownload(bytes.size, settingsRepository.settings.value.downloadBandwidthMiBps)
                val buffer = ByteBuffer.wrap(bytes)
                var position = chunk.offset + downloaded
                while (buffer.hasRemaining()) position += output.channel.write(buffer, position)
                bytesSinceForce += bytes.size
                if (bytesSinceForce >= settingsRepository.settings.value.streamWriteBatchBytes) {
                    output.channel.force(false)
                    bytesSinceForce = 0
                }
                chunkCrc.update(bytes)
                downloaded += bytes.size
                bytesSinceCheckpoint += bytes.size
                if (bytesSinceCheckpoint >= settingsRepository.settings.value.streamWriteBatchBytes) {
                    bytesSinceCheckpoint = 0
                    downloadDao.upsertChunk(
                        DownloadChunkEntity(
                            collection.id, file.id, chunk.index, chunk.offset, chunk.size,
                            "DOWNLOADING", downloaded, attempts, System.currentTimeMillis(), null,
                        ),
                    )
                    downloadDao.syncFileProgressFromChunks(file.id, System.currentTimeMillis())
                    downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
                    onProgress(collection, downloadDao.getDownload(collection.id)?.transferredBytes ?: 0L)
                }
            }
        }

        try {
            downloadRange(currentUrl())
        } catch (error: IOException) {
            if (!error.message.orEmpty().contains("HTTP 403")) throw error
            if (downloaded < chunk.size) downloadRange(refreshUrl())
        }
        output.channel.force(false)
        downloadDao.upsertChunk(
            DownloadChunkEntity(
                collection.id, file.id, chunk.index, chunk.offset, chunk.size,
                "COMPLETED", chunk.size, attempts, System.currentTimeMillis(), null, chunkCrc.value,
            ),
        )
        downloadDao.syncFileProgressFromChunks(file.id, System.currentTimeMillis())
        downloadDao.recomputeCollectionProgress(collection.id, System.currentTimeMillis())
        onProgress(collection, downloadDao.getDownload(collection.id)?.transferredBytes ?: 0L)
    }

    private suspend fun <T> withNetworkRetries(
        maxRetries: Int,
        linearBackoff: Boolean = false,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var slowReconnects = 0
        while (true) {
            try {
                return block()
            } catch (error: IOException) {
                if (error is TransferRateLimitException) throw error
                if (error is SocketTimeoutException && slowReconnects < MAX_SLOW_RECONNECTS) {
                    slowReconnects += 1
                    delay(SLOW_RECONNECT_DELAY_MS + Random.nextLong(SLOW_RECONNECT_JITTER_MS + 1))
                    continue
                }
                if (attempt >= maxRetries) throw error
                val waitMillis = if (linearBackoff) 1_000L * (attempt + 1) else 1_000L shl attempt
                delay(waitMillis.coerceAtMost(30_000L))
                attempt += 1
            }
        }
    }

    private suspend fun <T> withTransferAdaptiveRetries(
        countSuccess: Boolean = true,
        block: suspend () -> T,
    ): T {
        while (true) {
            try {
                return transferAdaptive.run(countSuccess, block)
            } catch (error: TransferRateLimitException) {
                val decision = transferAdaptive.registerRateLimit(error)
                if (decision.consecutiveRateLimits >= 3) {
                    throw IOException("Transfer bandwidth quota exceeded.", error)
                }
                delay(decision.cooldownMillis)
            }
        }
    }
}

class DownloadCollectionExpiredException(message: String) : Exception(message)

private const val COLLECTION_EXPIRED_ERROR = "Collection has expired."
private const val MAX_SLOW_RECONNECTS = 2
private const val SLOW_RECONNECT_DELAY_MS = 500L
private const val SLOW_RECONNECT_JITTER_MS = 250L

private fun DownloadEntity.destinationPath(path: String): String =
    listOf(destinationSubfolder, path).filter(String::isNotBlank).joinToString("/")

private data class ZipDownloadMeta(
    val path: String,
    val offset: Long,
    val compressedSize: Long,
    val compressionMethod: Int,
    val encrypted: Boolean,
    val archiveSize: Long,
    val crc32: Long,
    val password: String?,
) {
    companion object {
        fun parse(raw: String): ZipDownloadMeta = JSONObject(raw).let { value ->
            ZipDownloadMeta(
                path = value.getString("path"),
                offset = value.getLong("offset"),
                compressedSize = value.getLong("compressedSize"),
                compressionMethod = value.getInt("compressionMethod"),
                encrypted = value.getBoolean("encrypted"),
                archiveSize = value.getLong("archiveSize"),
                crc32 = value.optLong("crc32"),
                password = value.optString("password").takeIf(String::isNotEmpty),
            )
        }
    }
}
