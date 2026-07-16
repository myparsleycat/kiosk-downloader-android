@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kiodl.android.transfer

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable

private val KDX_MAGIC = "KDX1".encodeToByteArray()
private val ZSTD_MAGIC = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())
private const val CHECKSUM_BYTES = 32
private const val HEADER_BYTES = 4 + CHECKSUM_BYTES
private const val MAX_COMPRESSED_BYTES = 16 * 1024 * 1024
private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024

private val kdxCbor = ObjectMapper(CBORFactory())

object KdxCodec {
    fun encodeTree(tree: KdxNode): ByteArray = kdxCbor.writeValueAsBytes(tree.toWire())

    fun decodeTree(raw: ByteArray): KdxNode = kdxCbor.readTree(raw).toKdxNode()

    fun encode(payload: KdxPayload): ByteArray {
        payload.requireValid()
        val body = Zstd.compress(kdxCbor.writeValueAsBytes(payload.toWire()))
        return frameKdxBody(body)
    }

    fun decode(raw: ByteArray): KdxPayload {
        if (raw.size > MAX_COMPRESSED_BYTES) throw IOException("Transfer file is too large.")
        val body = unwrapKdxBody(raw)
        val decoded = try {
            kdxCbor.readTree(decompressBounded(body)).toKdxPayload()
        } catch (error: Exception) {
            throw IOException("Invalid transfer file.", error)
        }
        return decoded.apply(KdxPayload::requireValid)
    }

    private fun decompressBounded(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZstdInputStream(ByteArrayInputStream(body)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_DECOMPRESSED_BYTES) {
                    throw IOException("Invalid transfer file.")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
}

internal fun frameKdxBody(body: ByteArray): ByteArray =
    KDX_MAGIC + MessageDigest.getInstance("SHA-256").digest(body) + body

// KDX1 = magic + SHA-256(body) + zstd body. Bare zstd is accepted for older desktop exports.
internal fun unwrapKdxBody(raw: ByteArray): ByteArray = when {
    raw.size >= HEADER_BYTES && raw.startsWith(KDX_MAGIC) -> {
        val body = raw.copyOfRange(HEADER_BYTES, raw.size)
        val expected = raw.copyOfRange(KDX_MAGIC.size, HEADER_BYTES)
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(body))) {
            throw IOException("Transfer file is corrupted.")
        }
        body
    }
    raw.startsWith(ZSTD_MAGIC) -> raw
    else -> throw IOException("Invalid transfer file.")
}

@Serializable
data class KdxPayload(
    val version: Int,
    val kind: String,
    val exportedAt: Long,
    val collection: KdxCollection,
    val files: List<KdxFile>,
) {
    fun requireValid() {
        require(version == 1 && kind == "kiosk-download-collection")
        require(exportedAt >= 0)
        collection.requireValid()
        require(files.isNotEmpty() && files.any(KdxFile::selected))
        files.forEach(KdxFile::requireValid)
    }
}

@Serializable
data class KdxCollection(
    val shareId: String,
    val sourceUrl: String,
    val passwordPlain: String? = null,
    val name: String,
    val rootId: String,
    val segmentSize: Long,
    val expires: Long,
    val tree: KdxNode,
    val asciiFilenames: Boolean,
    val provider: String,
) {
    fun requireValid() {
        require(shareId.isNotEmpty() && sourceUrl.isNotEmpty() && rootId.isNotEmpty())
        require(segmentSize > 0 && provider in setOf("kiosk", "transfer"))
        tree.requireValid("dir")
    }
}

@Serializable
data class KdxFile(
    val remoteId: String,
    val path: String,
    val name: String,
    val size: Long,
    val selected: Boolean,
    val status: String,
    val completedElsewhere: Boolean,
    val sourceKind: String,
    val zipEntryJson: String? = null,
    val sourceMetaJson: String? = null,
) {
    fun requireValid() {
        require(remoteId.isNotEmpty() && path.isNotEmpty() && size >= 0)
        require(status in setOf("completed", "pending"))
        require(sourceKind in setOf("file", "zip_entry"))
    }
}

@Serializable
data class KdxNode(
    val type: String,
    val id: String,
    val name: String,
    val size: Long? = null,
    val entries: List<KdxEntry>? = null,
    val zipEntry: KdxZipEntry? = null,
) {
    fun requireValid(expectedType: String = type) {
        require(type == expectedType && id.isNotEmpty())
        if (type == "file" || type == "zip") require(size != null && size >= 0)
        if (type == "dir") requireNotNull(entries)
        entries?.forEach { it.node.requireValid(it.kind) }
        zipEntry?.requireValid()
    }
}

@Serializable
data class KdxEntry(val kind: String, val node: KdxNode)

@Serializable
data class KdxZipEntry(
    val path: String,
    val offset: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val compressionMethod: Int,
    val encrypted: Boolean,
) {
    fun requireValid() {
        require(path.isNotEmpty() && offset >= 0 && compressedSize >= 0 && uncompressedSize >= 0)
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun KdxPayload.toWire() = kdxCbor.createObjectNode().apply {
    put("version", version.toDouble())
    put("kind", kind)
    put("exportedAt", exportedAt.toDouble())
    set<ObjectNode>("collection", collection.toWire())
    putArray("files").also { array -> files.forEach { array.add(it.toWire()) } }
}

private fun KdxCollection.toWire() = kdxCbor.createObjectNode().apply {
    put("shareId", shareId)
    put("sourceUrl", sourceUrl)
    if (passwordPlain == null) putNull("passwordPlain") else put("passwordPlain", passwordPlain)
    put("name", name)
    put("rootId", rootId)
    put("segmentSize", segmentSize.toDouble())
    put("expires", expires.toDouble())
    set<ObjectNode>("tree", tree.toWire())
    put("asciiFilenames", asciiFilenames)
    put("provider", provider)
}

private fun KdxFile.toWire() = kdxCbor.createObjectNode().apply {
    put("remoteId", remoteId)
    put("path", path)
    put("name", name)
    put("size", size.toDouble())
    put("selected", selected)
    put("status", status)
    put("completedElsewhere", completedElsewhere)
    put("sourceKind", sourceKind)
    if (zipEntryJson == null) putNull("zipEntryJson") else put("zipEntryJson", zipEntryJson)
    if (sourceMetaJson == null) putNull("sourceMetaJson") else put("sourceMetaJson", sourceMetaJson)
}

private fun KdxNode.toWire(): ObjectNode = kdxCbor.createObjectNode().apply {
    put("type", type)
    put("id", id)
    put("name", name)
    size?.let { put("size", it.toDouble()) }
    if (entries == null) {
        if (type == "zip") putNull("entries")
    } else {
        putArray("entries").also { array ->
            entries.forEach { entry ->
                array.add(kdxCbor.createObjectNode().apply {
                    put("kind", entry.kind)
                    set<JsonNode>("node", entry.node.toWire())
                })
            }
        }
    }
    zipEntry?.let { value ->
        set<ObjectNode>("zipEntry", kdxCbor.createObjectNode().apply {
            put("path", value.path)
            put("offset", value.offset.toDouble())
            put("compressedSize", value.compressedSize.toDouble())
            put("uncompressedSize", value.uncompressedSize.toDouble())
            put("compressionMethod", value.compressionMethod.toDouble())
            put("encrypted", value.encrypted)
        })
    }
}

private fun JsonNode.toKdxPayload() = KdxPayload(
    version = exactInt("version"),
    kind = requiredText("kind"),
    exportedAt = exactLong("exportedAt"),
    collection = requiredObject("collection").toKdxCollection(),
    files = requiredArray("files").map(JsonNode::toKdxFile),
)

private fun JsonNode.toKdxCollection() = KdxCollection(
    shareId = requiredText("shareId"),
    sourceUrl = requiredText("sourceUrl"),
    passwordPlain = optionalText("passwordPlain"),
    name = requiredText("name"),
    rootId = requiredText("rootId"),
    segmentSize = exactLong("segmentSize"),
    expires = exactLong("expires"),
    tree = requiredObject("tree").toKdxNode(),
    asciiFilenames = requiredBoolean("asciiFilenames"),
    provider = requiredText("provider"),
)

private fun JsonNode.toKdxFile() = KdxFile(
    remoteId = requiredText("remoteId"),
    path = requiredText("path"),
    name = requiredText("name"),
    size = exactLong("size"),
    selected = requiredBoolean("selected"),
    status = requiredText("status"),
    completedElsewhere = requiredBoolean("completedElsewhere"),
    sourceKind = requiredText("sourceKind"),
    zipEntryJson = optionalText("zipEntryJson"),
    sourceMetaJson = optionalText("sourceMetaJson"),
)

private fun JsonNode.toKdxNode(): KdxNode {
    val nodeType = requiredText("type")
    return KdxNode(
        type = nodeType,
        id = requiredText("id"),
        name = requiredText("name"),
        size = get("size")?.takeUnless(JsonNode::isNull)?.exactLong(),
        entries = get("entries")?.takeUnless(JsonNode::isNull)?.also { require(it.isArray) }?.map { entry ->
            KdxEntry(entry.requiredText("kind"), entry.requiredObject("node").toKdxNode())
        },
        zipEntry = get("zipEntry")?.takeUnless(JsonNode::isNull)?.let { value ->
            KdxZipEntry(
                path = value.requiredText("path"),
                offset = value.exactLong("offset"),
                compressedSize = value.exactLong("compressedSize"),
                uncompressedSize = value.exactLong("uncompressedSize"),
                compressionMethod = value.exactInt("compressionMethod"),
                encrypted = value.requiredBoolean("encrypted"),
            )
        },
    )
}

private fun JsonNode.requiredText(name: String): String =
    requireNotNull(get(name)).also { require(it.isTextual) }.textValue()

private fun JsonNode.optionalText(name: String): String? =
    get(name)?.takeUnless(JsonNode::isNull)?.also { require(it.isTextual) }?.textValue()

private fun JsonNode.requiredBoolean(name: String): Boolean =
    requireNotNull(get(name)).also { require(it.isBoolean) }.booleanValue()

private fun JsonNode.requiredObject(name: String): JsonNode =
    requireNotNull(get(name)).also { require(it.isObject) }

private fun JsonNode.requiredArray(name: String): JsonNode =
    requireNotNull(get(name)).also { require(it.isArray) }

private fun JsonNode.exactLong(name: String): Long = requireNotNull(get(name)).exactLong()

private fun JsonNode.exactInt(name: String): Int {
    val value = exactLong(name)
    require(value in Int.MIN_VALUE..Int.MAX_VALUE)
    return value.toInt()
}

private fun JsonNode.exactLong(): Long {
    require(isNumber)
    if (isIntegralNumber) {
        require(canConvertToLong())
        return longValue()
    }
    val value = doubleValue()
    require(value.isFinite() && value % 1.0 == 0.0 && kotlin.math.abs(value) <= 9_007_199_254_740_991.0)
    return value.toLong()
}
