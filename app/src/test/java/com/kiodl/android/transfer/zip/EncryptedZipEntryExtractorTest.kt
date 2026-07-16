package com.kiodl.android.transfer.zip

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedZipEntryExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun extractsAesEntryFromOnlyItsRemoteRange() = extractRange(EncryptionMethod.AES)

    @Test fun extractsZipCryptoEntryFromOnlyItsRemoteRange() = extractRange(EncryptionMethod.ZIP_STANDARD)

    private fun extractRange(encryptionMethod: EncryptionMethod) {
        val payload = ByteArray(128 * 1024) { (it * 31).toByte() }
        val source = temporary.newFile("payload.bin").apply { writeBytes(payload) }
        val archive = File(temporary.root, "archive-${encryptionMethod.name}.zip")
        val password = "correct-password"
        ZipFile(archive, password.toCharArray()).addFile(source, ZipParameters().apply {
            isEncryptFiles = true
            this.encryptionMethod = encryptionMethod
        })
        val header = ZipFile(archive).fileHeaders.single()
        val raw = archive.readBytes()
        val offset = header.offsetLocalHeader.toInt()
        val local = ByteBuffer.wrap(raw, offset, 30).order(ByteOrder.LITTLE_ENDIAN)
        require(local.int == 0x04034b50)
        local.position(offset + 26)
        val nameLength = local.short.toInt() and 0xffff
        val extraLength = local.short.toInt() and 0xffff
        val rangeSize = minOf(
            30L + nameLength + extraLength + header.compressedSize + 24L,
            raw.size.toLong() - offset,
        ).toInt()
        val rangeFile = temporary.newFile("entry-range.bin").apply {
            writeBytes(raw.copyOfRange(offset, offset + rangeSize))
        }
        val output = temporary.newFile("output.bin")

        EncryptedZipEntryExtractor.extract(
            rangeFile, output, password, header.fileName, header.compressedSize,
            header.uncompressedSize, header.crc,
        )

        assertArrayEquals(payload, output.readBytes())
    }
}
