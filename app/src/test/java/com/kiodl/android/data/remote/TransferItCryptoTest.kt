package com.kiodl.android.data.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferItCryptoTest {
    @Test
    fun passwordDerivationMatchesDesktop() {
        assertEquals(
            "A5HIyCo-vWytmcndKNP0jd6aFhwTv0cEXo40-KgRPC4",
            deriveTransferPassword("abcdefghijkl", "päss"),
        )
    }

    @Test
    fun ctrDecryptionAtUnalignedOffsetMatchesDesktop() {
        val cipher = transferCipher(ByteArray(32) { it.toByte() }, 25)
        cipher.update(ByteArray(9))
        val decrypted = cipher.update(hex("11ac39c2c553f1b1f10245f1a068f6db36976d74")) + cipher.doFinal()

        assertArrayEquals("hello transfer chunk".encodeToByteArray(), decrypted)
    }
}

private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
