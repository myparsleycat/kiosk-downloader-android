package com.kiodl.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KioChunkLayoutTest {
    @Test
    fun splitsFileIntoDesktopCompatibleSegments() {
        assertEquals(
            listOf(
                KioChunk(index = 0, offset = 0, size = 4),
                KioChunk(index = 1, offset = 4, size = 4),
                KioChunk(index = 2, offset = 8, size = 2),
            ),
            buildKioChunkLayout(fileSize = 10, segmentSize = 4),
        )
    }

    @Test
    fun returnsNoChunksForEmptyFile() {
        assertEquals(emptyList<KioChunk>(), buildKioChunkLayout(fileSize = 0, segmentSize = 4))
    }

    @Test
    fun rejectsInvalidSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            buildKioChunkLayout(fileSize = -1, segmentSize = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildKioChunkLayout(fileSize = 1, segmentSize = 0)
        }
    }
}

