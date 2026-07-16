package com.kiodl.android.transfer

import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KdxCodecTest {
    @Test
    fun decodesDesktopCborXFloat64Numbers() {
        val payload = KdxCodec.decode(Base64.getDecoder().decode(DESKTOP_FIXTURE_BASE64))

        assertEquals(1_700_000_000_000, payload.exportedAt)
        assertEquals(5_368_709_120, payload.files.single().size)
        assertEquals(" plain ", payload.collection.passwordPlain)
    }

    @Test
    fun roundTripsCompletePayload() {
        val payload = KdxPayload(
            version = 1,
            kind = "kiosk-download-collection",
            exportedAt = 1_700_000_000_000,
            collection = KdxCollection(
                shareId = "share", sourceUrl = "https://kio.ac/c/share", passwordPlain = " plain ",
                name = "Example", rootId = "root", segmentSize = 1024, expires = 2_000_000_000,
                tree = KdxNode(
                    "dir", "root", "",
                    entries = listOf(KdxEntry("file", KdxNode("file", "file", "file.bin", 12))),
                ),
                asciiFilenames = false, provider = "kiosk",
            ),
            files = listOf(KdxFile("file", "file.bin", "file.bin", 12, true, "pending", false, "file")),
        )

        assertEquals(payload, KdxCodec.decode(KdxCodec.encode(payload)))
    }

    @Test
    fun framesBodyWithKdxHeaderAndRoundTripsIt() {
        val body = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 1, 2, 3)
        val encoded = frameKdxBody(body)

        assertArrayEquals("KDX1".encodeToByteArray(), encoded.copyOfRange(0, 4))
        assertArrayEquals(body, unwrapKdxBody(encoded))
    }

    @Test(expected = IOException::class)
    fun rejectsCorruptedBody() {
        val encoded = frameKdxBody(byteArrayOf(1, 2, 3))
        encoded[encoded.lastIndex] = (encoded.last() + 1).toByte()
        unwrapKdxBody(encoded)
    }
}

private const val DESKTOP_FIXTURE_BASE64 =
    "S0RYMWoO3SkSE22heONm/XRraGD0ppCIDVbg6iCddpCSSLfEKLUv/WD2AK0KAAaVRDOAN0kHoOlHBmYAgNOEyACOgWaJrJWk2FKSTZokuyXZf3iQMWhIefQBLgMdnNwjnfhHtDs3ADgANQBXO6GMv8c3KbwY0BNByAGDCRGcAhcNce/V5jySiq3OmCqHS8qy0XNYWHFhOJe4nEIq8p5iBUBPAxoB/MXmfKEFxiWooPPMOi93j1ZdiGOdSXkNrWElJpbgVhvm1JgGo+rhrDHJeaNBgFu3wnDYLgiHFLpNmfAZGkBUnBJNIC4uQb5EiYevTQzPpNO8M4QcAPQ84ON2fjVAicVTbxV9C+j5tw9BCBapHJH6CNIilSMqttPRUMIORCbfK82ZOe6talfi5dviiU7NEAn3VmdnRenUHm9lvgrDvw8ZAEFH1RoR3nYUGhu4GRgAQ67Ia8OACLtCq1NSC8GJ7jmQVUDNC7DW4bb7KLRwrLCo2AjVxCoBgNSRlUYDAaso"
