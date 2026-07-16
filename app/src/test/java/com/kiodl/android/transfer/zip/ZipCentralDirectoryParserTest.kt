package com.kiodl.android.transfer.zip

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ZipCentralDirectoryParserTest {
    @Test
    fun parsesStoredAndDeflatedEntries() = runBlocking {
        val archive = zipArchive()
        val entries = ZipCentralDirectoryParser().parse(ByteArrayReader(archive))

        assertEquals(listOf("stored.txt", "folder/deflated.txt"), entries.map(RemoteZipEntry::path))
        assertEquals(0, entries[0].compressionMethod)
        assertEquals(8, entries[1].compressionMethod)
        assertFalse(entries.any(RemoteZipEntry::encrypted))
    }

    private fun zipArchive(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val stored = "stored".encodeToByteArray()
            zip.putNextEntry(ZipEntry("stored.txt").apply {
                method = ZipEntry.STORED
                size = stored.size.toLong()
                compressedSize = stored.size.toLong()
                crc = CRC32().apply { update(stored) }.value
            })
            zip.write(stored)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("folder/deflated.txt"))
            zip.write("deflated payload".encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}

private class ByteArrayReader(private val bytes: ByteArray) : RemoteRandomAccessReader {
    override val size = bytes.size.toLong()
    override suspend fun read(offset: Long, length: Int) =
        bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
}
