package com.kiodl.android.data.repository

import com.kiodl.android.data.remote.KioApiClient
import com.kiodl.android.data.remote.TransferItApiClient
import com.kiodl.android.domain.model.CollectionProbe
import com.kiodl.android.domain.model.DownloadProvider
import com.kiodl.android.domain.model.LoadedCollection
import com.kiodl.android.domain.model.CollectionNode
import com.kiodl.android.domain.model.DirectoryNode
import com.kiodl.android.domain.model.FileNode
import com.kiodl.android.domain.model.ZipEntryMeta
import com.kiodl.android.domain.repository.CollectionSourceRepository
import com.kiodl.android.domain.share.ShareUrlParser
import javax.inject.Inject
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kiodl.android.domain.model.ZipNode
import com.kiodl.android.transfer.zip.EncryptedZipEntryExtractor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DefaultCollectionSourceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kioApiClient: KioApiClient,
    private val transferItApiClient: TransferItApiClient,
) : CollectionSourceRepository {
    override suspend fun probe(url: String): CollectionProbe {
        val parsed = requireNotNull(ShareUrlParser.parse(url)) { "지원하지 않는 공유 URL입니다." }
        return when (parsed.provider) {
            DownloadProvider.KIOSK -> kioApiClient.probe(parsed.id)
            DownloadProvider.TRANSFER -> transferItApiClient.probe(parsed.id)
        }
    }

    override suspend fun load(url: String, password: String?): LoadedCollection {
        val parsed = requireNotNull(ShareUrlParser.parse(url)) { "지원하지 않는 공유 URL입니다." }
        return when (parsed.provider) {
            DownloadProvider.KIOSK -> kioApiClient.load(parsed.id, password)
            DownloadProvider.TRANSFER -> transferItApiClient.load(parsed.id, password)
        }
    }

    override suspend fun listZipEntries(
        collection: LoadedCollection,
        fileId: String,
    ): List<CollectionNode> {
        require(collection.collection.provider == DownloadProvider.KIOSK)
        val archive = collection.collection.tree.findFile(fileId)
            ?: error("ZIP 파일을 찾을 수 없습니다.")
        val entries = kioApiClient.listZipEntries(
            remoteFileId = fileId,
            archiveSize = archive.size,
            segmentSize = collection.collection.segmentSize,
            accessToken = collection.accessToken,
        )
        return buildZipTree(fileId, archive.size, entries)
    }

    override suspend fun verifyZipPassword(
        collection: LoadedCollection,
        fileId: String,
        password: String,
    ) {
        require(collection.collection.provider == DownloadProvider.KIOSK)
        val archive = collection.collection.tree.findFile(fileId) as? ZipNode ?: return
        val entry = archive.entries.orEmpty().firstEncryptedEntry() ?: return
        val meta = requireNotNull(entry.zipEntry)
        val reader = kioApiClient.segmentedReader(
            fileId, archive.size, collection.collection.segmentSize, collection.accessToken,
        )
        val localHeader = reader.read(meta.offset, 30)
        val header = ByteBuffer.wrap(localHeader).order(ByteOrder.LITTLE_ENDIAN)
        require(header.int == 0x04034b50) { "잘못된 ZIP local header입니다." }
        header.position(26)
        val localHeaderSize = 30L + (header.short.toInt() and 0xffff) + (header.short.toInt() and 0xffff)
        val rangeSize = minOf(localHeaderSize + meta.compressedSize + 24L, archive.size - meta.offset)
        val rangeFile = File.createTempFile("zip-password-", ".range", context.cacheDir)
        val outputFile = File.createTempFile("zip-password-", ".out", context.cacheDir)
        try {
            rangeFile.outputStream().buffered().use { output ->
                var downloaded = 0L
                while (downloaded < rangeSize) {
                    val bytes = reader.read(
                        meta.offset + downloaded,
                        minOf(1024 * 1024L, rangeSize - downloaded).toInt(),
                    )
                    output.write(bytes)
                    downloaded += bytes.size
                }
            }
            runCatching {
                EncryptedZipEntryExtractor.extract(
                    rangeFile, outputFile, password, meta.path, meta.compressedSize,
                    meta.uncompressedSize, meta.crc32,
                )
            }.getOrElse { throw IllegalArgumentException("ZIP 비밀번호가 올바르지 않습니다.", it) }
        } finally {
            rangeFile.delete()
            outputFile.delete()
        }
    }
}

private fun List<CollectionNode>.firstEncryptedEntry(): FileNode? = firstNotNullOfOrNull { node ->
    when (node) {
        is DirectoryNode -> node.entries.firstEncryptedEntry()
        is FileNode -> node.takeIf { it.zipEntry?.encrypted == true }
        is ZipNode -> node.entries.orEmpty().firstEncryptedEntry()
    }
}

private fun DirectoryNode.findFile(id: String): com.kiodl.android.domain.model.CollectionFile? =
    entries.firstNotNullOfOrNull { entry ->
        when (entry) {
            is DirectoryNode -> entry.findFile(id)
            is com.kiodl.android.domain.model.CollectionFile -> entry.takeIf { it.id == id }
        }
    }

private class ZipDirectory(val name: String, val path: String) {
    val directories = linkedMapOf<String, ZipDirectory>()
    val files = mutableListOf<FileNode>()
}

private fun buildZipTree(
    archiveId: String,
    archiveSize: Long,
    entries: List<com.kiodl.android.transfer.zip.RemoteZipEntry>,
): List<CollectionNode> {
    val root = ZipDirectory("", "")
    entries.forEach { entry ->
        val parts = entry.path.split('/')
        var currentPath = ""
        val directory = parts.dropLast(1).fold(root) { parent, name ->
            currentPath = listOf(currentPath, name).filter(String::isNotBlank).joinToString("/")
            parent.directories.getOrPut(name) { ZipDirectory(name, currentPath) }
        }
        directory.files += FileNode(
            id = "$archiveId:entry:${entry.path}",
            name = parts.last(),
            size = entry.uncompressedSize,
            zipEntry = ZipEntryMeta(
                path = entry.path,
                offset = entry.localHeaderOffset,
                compressedSize = entry.compressedSize,
                uncompressedSize = entry.uncompressedSize,
                compressionMethod = entry.compressionMethod,
                encrypted = entry.encrypted,
                archiveSize = archiveSize,
                crc32 = entry.crc32,
            ),
        )
    }
    fun ZipDirectory.freeze(): DirectoryNode = DirectoryNode(
        id = "$archiveId:dir:$path",
        name = name,
        entries = directories.values.map(ZipDirectory::freeze) + files,
    )
    return root.directories.values.map(ZipDirectory::freeze) + root.files
}
