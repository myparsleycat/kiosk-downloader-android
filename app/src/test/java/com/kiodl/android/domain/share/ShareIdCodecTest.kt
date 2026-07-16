package com.kiodl.android.domain.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareIdCodecTest {
    @Test
    fun preservesDesktopBitOrder() {
        assertArrayEquals(
            "15354911f4380db32809721805310801".hexToBytes(),
            ShareIdCodec.decode("abcdefghijklmnopqrstuv"),
        )
        val bytes = "15354911f4380db32809721805310801".hexToBytes()
        assertArrayEquals(bytes, ShareIdCodec.decode(ShareIdCodec.encode(bytes)))
        org.junit.Assert.assertEquals("abcdefghijklmnopqrstuv", ShareIdCodec.encode(bytes))
        assertArrayEquals(
            "9aa6699aa6699aa6699aa6699aa6699a".hexToBytes(),
            ShareIdCodec.decode("AAAAAAAAAAAAAAAAAAAAAA"),
        )
    }

    @Test
    fun rejectsInvalidLengthAndAlphabet() {
        assertThrows(IllegalArgumentException::class.java) { ShareIdCodec.decode("short") }
        assertThrows(IllegalArgumentException::class.java) {
            ShareIdCodec.decode("!bcdefghijklmnopqrstuv")
        }
    }
}

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
