package com.kiodl.android.transfer.zip

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

data class RemoteZipEntry(
    val path: String,
    val localHeaderOffset: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val compressionMethod: Int,
    val encrypted: Boolean,
    val crc32: Long,
)

class ZipCentralDirectoryParser {
    suspend fun parse(reader: RemoteRandomAccessReader): List<RemoteZipEntry> {
        require(reader.size >= EOCD_MIN_BYTES) { "Invalid ZIP file." }
        val tailLength = minOf(reader.size, MAX_EOCD_SEARCH_BYTES.toLong()).toInt()
        val tailOffset = reader.size - tailLength
        val tail = reader.read(tailOffset, tailLength).requireLength(tailLength)
        val eocdIndex = tail.findSignatureBackward(EOCD_SIGNATURE)
        if (eocdIndex < 0 || eocdIndex + EOCD_MIN_BYTES > tail.size) throw IOException("Invalid ZIP file.")
        val eocd = tail.littleEndian(eocdIndex)
        val disk = eocd.u16(4)
        val centralDisk = eocd.u16(6)
        val entriesOnDisk = eocd.u16(8)
        val entryCount32 = eocd.u16(10)
        val centralSize32 = eocd.u32(12)
        val centralOffset32 = eocd.u32(16)
        if (disk != 0 || centralDisk != 0 || entriesOnDisk != entryCount32) {
            throw IOException("Multi-disk ZIP files are not supported.")
        }

        val metadata = if (
            entryCount32 == U16_MAX || centralSize32 == U32_MAX || centralOffset32 == U32_MAX
        ) {
            parseZip64Metadata(reader, tail, tailOffset, eocdIndex)
        } else {
            CentralMetadata(entryCount32.toLong(), centralSize32, centralOffset32)
        }
        require(metadata.entryCount <= MAX_ENTRIES) { "ZIP has too many entries." }
        require(metadata.centralSize in 0..MAX_CENTRAL_DIRECTORY_BYTES) { "ZIP directory is too large." }
        requireRange(metadata.centralOffset, metadata.centralSize, reader.size)

        val central = reader.read(metadata.centralOffset, metadata.centralSize.toInt())
            .requireLength(metadata.centralSize.toInt())
        val entries = ArrayList<RemoteZipEntry>(metadata.entryCount.toInt())
        var cursor = 0
        repeat(metadata.entryCount.toInt()) {
            if (cursor + CENTRAL_FIXED_BYTES > central.size || central.u32(cursor) != CENTRAL_SIGNATURE) {
                throw IOException("Invalid ZIP central directory.")
            }
            val flags = central.u16(cursor + 8)
            val method = central.u16(cursor + 10)
            val crc32 = central.u32(cursor + 16)
            val compressed32 = central.u32(cursor + 20)
            val uncompressed32 = central.u32(cursor + 24)
            val nameLength = central.u16(cursor + 28)
            val extraLength = central.u16(cursor + 30)
            val commentLength = central.u16(cursor + 32)
            val diskStart = central.u16(cursor + 34)
            val localOffset32 = central.u32(cursor + 42)
            val end = cursor + CENTRAL_FIXED_BYTES + nameLength + extraLength + commentLength
            if (end > central.size) throw IOException("Invalid ZIP central directory.")
            val nameBytes = central.copyOfRange(cursor + CENTRAL_FIXED_BYTES, cursor + CENTRAL_FIXED_BYTES + nameLength)
            val extra = central.copyOfRange(
                cursor + CENTRAL_FIXED_BYTES + nameLength,
                cursor + CENTRAL_FIXED_BYTES + nameLength + extraLength,
            )
            val zip64 = parseZip64Extra(extra, uncompressed32, compressed32, localOffset32, diskStart)
            if (zip64.diskStart != 0L) throw IOException("Multi-disk ZIP files are not supported.")
            val path = decodePath(nameBytes, flags).sanitizeZipPath()
            if (path != null && !path.endsWith('/')) {
                entries += RemoteZipEntry(
                    path = path,
                    localHeaderOffset = zip64.localOffset,
                    compressedSize = zip64.compressedSize,
                    uncompressedSize = zip64.uncompressedSize,
                    compressionMethod = method,
                    encrypted = flags and 1 != 0,
                    crc32 = crc32,
                )
            }
            cursor = end
        }
        return entries
    }

    private suspend fun parseZip64Metadata(
        reader: RemoteRandomAccessReader,
        tail: ByteArray,
        tailOffset: Long,
        eocdIndex: Int,
    ): CentralMetadata {
        val locatorOffset = tailOffset + eocdIndex - ZIP64_LOCATOR_BYTES
        if (locatorOffset < 0) throw IOException("Invalid ZIP64 file.")
        val locator = reader.read(locatorOffset, ZIP64_LOCATOR_BYTES).requireLength(ZIP64_LOCATOR_BYTES)
        if (locator.u32(0) != ZIP64_LOCATOR_SIGNATURE || locator.u32(4) != 0L || locator.u32(16) != 1L) {
            throw IOException("Invalid ZIP64 locator.")
        }
        val recordOffset = locator.u64(8)
        val record = reader.read(recordOffset, ZIP64_EOCD_MIN_BYTES).requireLength(ZIP64_EOCD_MIN_BYTES)
        if (record.u32(0) != ZIP64_EOCD_SIGNATURE || record.u32(16) != 0L || record.u32(20) != 0L) {
            throw IOException("Invalid ZIP64 record.")
        }
        val entriesOnDisk = record.u64(24)
        val entryCount = record.u64(32)
        if (entriesOnDisk != entryCount) throw IOException("Multi-disk ZIP files are not supported.")
        return CentralMetadata(entryCount, record.u64(40), record.u64(48))
    }
}

private data class CentralMetadata(val entryCount: Long, val centralSize: Long, val centralOffset: Long)
private data class Zip64Values(
    val uncompressedSize: Long,
    val compressedSize: Long,
    val localOffset: Long,
    val diskStart: Long,
)

private fun parseZip64Extra(
    extra: ByteArray,
    uncompressed32: Long,
    compressed32: Long,
    localOffset32: Long,
    diskStart32: Int,
): Zip64Values {
    var cursor = 0
    var zip64: ByteArray? = null
    while (cursor + 4 <= extra.size) {
        val id = extra.u16(cursor)
        val length = extra.u16(cursor + 2)
        if (cursor + 4 + length > extra.size) throw IOException("Invalid ZIP extra field.")
        if (id == ZIP64_EXTRA_ID) zip64 = extra.copyOfRange(cursor + 4, cursor + 4 + length)
        cursor += 4 + length
    }
    var valueCursor = 0
    fun next64(): Long {
        val value = zip64 ?: throw IOException("Missing ZIP64 extra field.")
        if (valueCursor + 8 > value.size) throw IOException("Invalid ZIP64 extra field.")
        return value.u64(valueCursor).also { valueCursor += 8 }
    }
    fun next32(): Long {
        val value = zip64 ?: throw IOException("Missing ZIP64 extra field.")
        if (valueCursor + 4 > value.size) throw IOException("Invalid ZIP64 extra field.")
        return value.u32(valueCursor).also { valueCursor += 4 }
    }
    return Zip64Values(
        uncompressedSize = if (uncompressed32 == U32_MAX) next64() else uncompressed32,
        compressedSize = if (compressed32 == U32_MAX) next64() else compressed32,
        localOffset = if (localOffset32 == U32_MAX) next64() else localOffset32,
        diskStart = if (diskStart32 == U16_MAX) next32() else diskStart32.toLong(),
    )
}

private fun decodePath(bytes: ByteArray, flags: Int): String =
    bytes.toString(if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else Charset.forName("CP437"))

private fun String.sanitizeZipPath(): String? {
    val normalized = replace('\\', '/').trimStart('/')
    if (normalized.isEmpty() || '\u0000' in normalized || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
    val parts = normalized.split('/')
    if (parts.any { it == ".." }) return null
    return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        .takeIf(String::isNotEmpty)
}

private fun requireRange(offset: Long, length: Long, total: Long) {
    require(offset >= 0 && length >= 0 && offset <= total && length <= total - offset) { "Invalid ZIP range." }
}

private fun ByteArray.requireLength(expected: Int) = apply {
    if (size != expected) throw IOException("ZIP data ended unexpectedly.")
}

private fun ByteArray.findSignatureBackward(signature: Long): Int {
    for (index in size - 4 downTo 0) if (u32(index) == signature) return index
    return -1
}

private fun ByteArray.littleEndian(offset: Int) = copyOfRange(offset, size)
private fun ByteArray.u16(offset: Int): Int =
    ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
private fun ByteArray.u32(offset: Int): Long =
    ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
private fun ByteArray.u64(offset: Int): Long {
    val value = ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
    if (value < 0) throw IOException("ZIP64 value is too large.")
    return value
}

private const val EOCD_SIGNATURE = 0x06054b50L
private const val CENTRAL_SIGNATURE = 0x02014b50L
private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
private const val EOCD_MIN_BYTES = 22
private const val MAX_EOCD_SEARCH_BYTES = EOCD_MIN_BYTES + 65_535
private const val CENTRAL_FIXED_BYTES = 46
private const val ZIP64_LOCATOR_BYTES = 20
private const val ZIP64_EOCD_MIN_BYTES = 56
private const val ZIP64_EXTRA_ID = 0x0001
private const val UTF8_FLAG = 1 shl 11
private const val U16_MAX = 0xffff
private const val U32_MAX = 0xffff_ffffL
private const val MAX_ENTRIES = 100_000L
private const val MAX_CENTRAL_DIRECTORY_BYTES = 32L * 1024 * 1024
