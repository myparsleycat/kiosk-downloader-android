package com.kiodl.android.transfer.zip

import java.io.File
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.model.FileHeader

internal object EncryptedZipEntryExtractor {
    fun extract(
        entryRangeFile: File,
        outputFile: File,
        password: String,
        entryPath: String,
        compressedSize: Long,
        uncompressedSize: Long,
        crc32: Long,
        bufferSize: Int = 8 * 1024 * 1024,
        ensureActive: () -> Unit = {},
    ) {
        outputFile.outputStream().buffered().use { output ->
            entryRangeFile.inputStream().buffered().use { raw ->
                ZipInputStream(raw, password.toCharArray()).use { input ->
                    val centralHeader = FileHeader().apply {
                        this.compressedSize = compressedSize
                        this.uncompressedSize = uncompressedSize
                        crc = crc32
                        fileName = entryPath
                        isDirectory = false
                    }
                    requireNotNull(input.getNextEntry(centralHeader, true)) {
                        "ZIP 항목을 찾을 수 없습니다: $entryPath"
                    }
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }
}
