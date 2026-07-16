package com.kiodl.android.transfer

import android.content.Context
import android.net.Uri
import android.net.Network
import androidx.documentfile.provider.DocumentFile
import com.kiodl.android.data.local.UploadDao
import com.kiodl.android.data.remote.KioUploadClient
import com.kiodl.android.data.remote.UploadSessionExpiredException
import com.kiodl.android.data.settings.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

@Singleton
class UploadRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: UploadDao,
    private val client: KioUploadClient,
    private val settingsRepository: AppSettingsRepository,
    private val segmentPools: SegmentPoolCoordinator,
    private val bandwidth: TransferBandwidthCoordinator,
) {
    suspend fun run(
        id: String,
        network: Network? = null,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
    ) {
        val current = dao.upload(id) ?: return
        if (current.status !in setOf("QUEUED", "UPLOADING", "ERROR")) return
        dao.clearActiveTimer(id)
        dao.startTimer(id, System.currentTimeMillis())
        try {
            runInternal(id, network, onProgress)
        } finally {
            withContext(NonCancellable) {
                dao.stopTimer(id, System.currentTimeMillis())
            }
        }
    }

    private suspend fun runInternal(
        id: String,
        network: Network? = null,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val upload = dao.upload(id) ?: return@withContext
        if (upload.status == "PAUSED" || upload.status == "COMPLETED") return@withContext
        if (upload.expiresEpochMillis <= System.currentTimeMillis()) {
            dao.setStatus(id, "EXPIRED", UPLOAD_EXPIRED_ERROR, System.currentTimeMillis())
            throw UploadSessionExpiredException(UPLOAD_EXPIRED_ERROR)
        }
        dao.setStatus(id, "UPLOADING", null, System.currentTimeMillis())
        val activeClient = network?.let(client::boundTo) ?: client
        val settings = settingsRepository.settings.value
        try {
            val now = System.currentTimeMillis()
            dao.resetRunningChunks(id, now)
            dao.resetRunningFiles(id)
            val files = dao.files(id).filterNot { it.pausedByUser || it.status == "COMPLETED" }
            val filesById = files.associateBy { it.id }
            files.forEach { dao.syncFileProgressFromChunks(it.id) }
            dao.refreshProgress(id, System.currentTimeMillis())
            files.forEach { file ->
                coroutineContext.ensureActive()
                val current = DocumentFile.fromSingleUri(context, Uri.parse(file.uri))
                    ?: throw IOException("업로드 원본 파일을 찾을 수 없습니다: ${file.path}")
                if ((current.length() > 0 && current.length() != file.size) ||
                    (file.lastModified > 0 && current.lastModified() != file.lastModified)
                ) throw IOException("업로드 원본 파일이 변경되었습니다: ${file.path}")
            }
            coroutineScope {
                dao.chunks(id).filter { it.status != "COMPLETED" && it.fileId in filesById }.map { chunk ->
                    async {
                        try {
                            segmentPools.withUploadPermit(settings.segmentPoolSize) {
                            val file = filesById[chunk.fileId]
                                ?: throw IOException("업로드 청크의 원본 파일을 찾을 수 없습니다.")
                            coroutineContext.ensureActive()
                            val bytes = readRange(file, chunk.offset, chunk.size.toInt())
                            var failure: IOException? = null
                            var attempt = 0
                            var slowReconnects = 0
                            var countAttempt = true
                            while (attempt <= settings.uploadMaxRetries) {
                                try {
                                    dao.setChunkStatus(
                                        file.id, chunk.chunkIndex, "UPLOADING", 0,
                                        if (countAttempt) 1 else 0,
                                        System.currentTimeMillis(), null,
                                    )
                                    countAttempt = false
                                    activeClient.requestSegment(
                                        file.remoteId, chunk.chunkIndex, bytes, upload.uploadToken,
                                    )?.let { target ->
                                        val liveUploaded = AtomicLong()
                                        coroutineScope {
                                            val uploadJob = async {
                                                activeClient.uploadEdge(
                                                    target,
                                                    bytes,
                                                    consumeBandwidth = { count ->
                                                        bandwidth.consumeUpload(
                                                            count,
                                                            settingsRepository.settings.value.uploadBandwidthMiBps,
                                                        )
                                                    },
                                                    onUploaded = { count -> liveUploaded.addAndGet(count.toLong()) },
                                                )
                                            }
                                            val progressJob = launch {
                                                while (uploadJob.isActive) {
                                                    delay(PROGRESS_EMIT_INTERVAL_MS)
                                                    val transferred = liveUploaded.get().coerceAtMost(chunk.size)
                                                    dao.setChunkStatus(
                                                        file.id, chunk.chunkIndex, "UPLOADING", transferred, 0,
                                                        System.currentTimeMillis(), null,
                                                    )
                                                    dao.syncFileProgressFromChunks(file.id)
                                                    dao.refreshProgress(id, System.currentTimeMillis())
                                                    onProgress(dao.upload(id)?.uploadedBytes ?: 0L, upload.totalBytes)
                                                }
                                            }
                                            try {
                                                uploadJob.await()
                                            } finally {
                                                progressJob.cancelAndJoin()
                                            }
                                        }
                                    }
                                    failure = null
                                    break
                                } catch (error: IOException) {
                                    failure = error
                                    if (error is SocketTimeoutException && slowReconnects < MAX_SLOW_RECONNECTS) {
                                        slowReconnects += 1
                                        dao.setChunkStatus(
                                            file.id, chunk.chunkIndex, "QUEUED", 0, 0,
                                            System.currentTimeMillis(), null,
                                        )
                                        delay(SLOW_RECONNECT_DELAY_MS + Random.nextLong(SLOW_RECONNECT_JITTER_MS + 1))
                                        continue
                                    }
                                    dao.setChunkStatus(
                                        file.id,
                                        chunk.chunkIndex,
                                        if (attempt < settings.uploadMaxRetries) "QUEUED" else "ERROR",
                                        0,
                                        0,
                                        System.currentTimeMillis(),
                                        error.message,
                                    )
                                    if (attempt < settings.uploadMaxRetries) {
                                        delay((1_000L shl attempt).coerceAtMost(30_000L))
                                        attempt += 1
                                        countAttempt = true
                                        continue
                                    }
                                    break
                                }
                            }
                            failure?.let { throw it }
                            dao.setChunkStatus(
                                file.id, chunk.chunkIndex, "COMPLETED", chunk.size, 0,
                                System.currentTimeMillis(), null,
                            )
                            dao.syncFileProgressFromChunks(file.id)
                            dao.refreshProgress(id, System.currentTimeMillis())
                            onProgress(dao.upload(id)?.uploadedBytes ?: 0L, upload.totalBytes)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            dao.syncFileProgressFromChunks(chunk.fileId)
                            val current = dao.files(id).firstOrNull { it.id == chunk.fileId }
                            dao.setFileStatus(
                                chunk.fileId, "ERROR", current?.uploadedBytes ?: 0,
                                error.message ?: "업로드에 실패했습니다.",
                            )
                            throw error
                        }
                    }
                }.awaitAll()
            }
            if (dao.countIncompleteChunks(id) == 0) {
                activeClient.complete(upload.uploadToken)
                dao.setStatus(id, "COMPLETED", null, System.currentTimeMillis())
            } else {
                dao.setStatus(id, "PAUSED", null, System.currentTimeMillis())
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                dao.resetRunningChunks(id, System.currentTimeMillis())
                dao.resetRunningFiles(id)
            }
            throw error
        } catch (error: UploadSessionExpiredException) {
            dao.resetRunningChunks(id, System.currentTimeMillis())
            dao.resetRunningFiles(id)
            dao.setStatus(id, "EXPIRED", error.message ?: UPLOAD_EXPIRED_ERROR, System.currentTimeMillis())
            throw error
        } catch (error: Exception) {
            dao.resetRunningChunks(id, System.currentTimeMillis())
            dao.resetRunningFiles(id)
            dao.setStatus(id, "ERROR", error.message ?: "업로드에 실패했습니다.", System.currentTimeMillis())
            throw error
        }
    }

    private fun readRange(file: com.kiodl.android.data.local.UploadFileEntity, offset: Long, size: Int): ByteArray {
        validateSource(file)
        val descriptor = context.contentResolver.openAssetFileDescriptor(Uri.parse(file.uri), "r")
            ?: throw IOException("원본 파일을 열 수 없습니다.")
        descriptor.use {
            validateSource(file)
            FileInputStream(it.fileDescriptor).use { input ->
                input.channel.position(it.startOffset + offset)
                val bytes = ByteArray(size)
                var read = 0
                while (read < size) {
                    val count = input.read(bytes, read, size - read)
                    if (count < 0) throw IOException("업로드 원본 파일이 변경되었거나 잘렸습니다.")
                    read += count
                }
                validateSource(file)
                return bytes
            }
        }
    }

    private fun validateSource(file: com.kiodl.android.data.local.UploadFileEntity) {
        val current = DocumentFile.fromSingleUri(context, Uri.parse(file.uri))
            ?: throw IOException("업로드 원본 파일을 찾을 수 없습니다: ${file.path}")
        if ((current.length() > 0 && current.length() != file.size) ||
            (file.lastModified > 0 && current.lastModified() != file.lastModified)
        ) throw IOException("업로드 원본 파일이 변경되었습니다: ${file.path}")
    }
}

private const val UPLOAD_EXPIRED_ERROR = "업로드 세션이 만료되었거나 더 이상 존재하지 않습니다."
private const val MAX_SLOW_RECONNECTS = 2
private const val SLOW_RECONNECT_DELAY_MS = 500L
private const val SLOW_RECONNECT_JITTER_MS = 250L
private const val PROGRESS_EMIT_INTERVAL_MS = 500L
