package com.kiodl.android.domain.share

// Desktop-compatible share-id: 16-byte UUID packed as 22 chars of custom base64url-ish alphabet.
// Bits are filled LSB-first within each char and chars are written right-to-left — not standard base64.
object ShareIdCodec {
    private const val SHARE_ID_LENGTH = 22
    private const val UUID_BYTE_LENGTH = 16
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"

    fun decode(shareId: String): ByteArray {
        require(shareId.length == SHARE_ID_LENGTH) {
            "Invalid share id length ${shareId.length} (expected $SHARE_ID_LENGTH)."
        }

        val bytes = ByteArray(UUID_BYTE_LENGTH)
        var bitPosition = 0
        var bytePosition = 0

        for (sourceIndex in shareId.lastIndex downTo 0) {
            val character = shareId[sourceIndex]
            val value = ALPHABET.indexOf(character)
            require(value >= 0) { "Invalid character \"$character\" in share id." }

            for (characterBit in 0 until 6) {
                if (bytePosition >= UUID_BYTE_LENGTH) break
                val bit = (value shr characterBit) and 1
                bytes[bytePosition] =
                    (bytes[bytePosition].toInt() or (bit shl bitPosition)).toByte()
                bitPosition += 1
                if (bitPosition == 8) {
                    bitPosition = 0
                    bytePosition += 1
                }
            }
        }

        return bytes
    }

    fun encode(bytes: ByteArray): String {
        require(bytes.size == UUID_BYTE_LENGTH) { "A collection UUID must contain 16 bytes." }
        val result = CharArray(SHARE_ID_LENGTH)
        var bitPosition = 0
        var bytePosition = 0
        for (targetIndex in result.lastIndex downTo 0) {
            var value = 0
            for (characterBit in 0 until 6) {
                if (bytePosition < bytes.size) {
                    value = value or (((bytes[bytePosition].toInt() ushr bitPosition) and 1) shl characterBit)
                    bitPosition += 1
                    if (bitPosition == 8) {
                        bitPosition = 0
                        bytePosition += 1
                    }
                }
            }
            result[targetIndex] = ALPHABET[value]
        }
        return result.concatToString()
    }
}
