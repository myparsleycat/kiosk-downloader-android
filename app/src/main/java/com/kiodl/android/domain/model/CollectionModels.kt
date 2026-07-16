package com.kiodl.android.domain.model

data class DownloadCollection(
    val shareId: String,
    val name: String,
    val expiresEpochSeconds: Long,
    val segmentSize: Long,
    val passwordProtected: Boolean,
    val provider: DownloadProvider,
    val tree: DirectoryNode,
) {
    val fileCount: Int
        get() = tree.flattenFiles().size

    val totalBytes: Long
        get() = tree.flattenFiles().sumOf(CollectionFile::size)
}

sealed interface CollectionNode {
    val id: String
    val name: String
}

data class DirectoryNode(
    override val id: String,
    override val name: String,
    val entries: List<CollectionNode>,
) : CollectionNode

sealed interface CollectionFile : CollectionNode {
    val size: Long
}

data class FileNode(
    override val id: String,
    override val name: String,
    override val size: Long,
    val zipEntry: ZipEntryMeta? = null,
) : CollectionFile

data class ZipEntryMeta(
    val path: String,
    val offset: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val compressionMethod: Int,
    val encrypted: Boolean,
    val archiveSize: Long,
    val crc32: Long,
)

data class ZipNode(
    override val id: String,
    override val name: String,
    override val size: Long,
    val entries: List<CollectionNode>? = null,
) : CollectionFile

data class LoadedCollection(
    val collection: DownloadCollection,
    val rootId: String,
    val accessToken: String,
    val sourceKeys: Map<String, String> = emptyMap(),
)

data class CollectionProbe(
    val passwordRequired: Boolean,
)

fun DirectoryNode.flattenFiles(): List<CollectionFile> = entries.flatMap { entry ->
    when (entry) {
        is DirectoryNode -> entry.flattenFiles()
        is CollectionFile -> listOf(entry)
    }
}

data class CollectionFilePath(val file: CollectionFile, val path: String)

fun DirectoryNode.flattenFilePaths(prefix: List<String> = emptyList()): List<CollectionFilePath> =
    entries.flatMap { entry ->
        when (entry) {
            is DirectoryNode -> entry.flattenFilePaths(prefix + entry.name)
            is FileNode -> listOf(CollectionFilePath(entry, (prefix + entry.name).joinToString("/")))
            is ZipNode -> {
                val archivePath = prefix + entry.name
                listOf(CollectionFilePath(entry, archivePath.joinToString("/"))) +
                    entry.entries.orEmpty().flatMap { child -> child.flattenFilePaths(archivePath) }
            }
        }
    }

private fun CollectionNode.flattenFilePaths(prefix: List<String>): List<CollectionFilePath> = when (this) {
    is DirectoryNode -> flattenFilePaths(prefix + name)
    is FileNode -> listOf(CollectionFilePath(this, (prefix + name).joinToString("/")))
    is ZipNode -> {
        val archivePath = prefix + name
        listOf(CollectionFilePath(this, archivePath.joinToString("/"))) +
            entries.orEmpty().flatMap { it.flattenFilePaths(archivePath) }
    }
}
