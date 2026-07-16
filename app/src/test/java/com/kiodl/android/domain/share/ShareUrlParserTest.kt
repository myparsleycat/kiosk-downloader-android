package com.kiodl.android.domain.share

import com.kiodl.android.domain.model.DownloadProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ShareUrlParserTest {
    @Test
    fun parsesKioskUrls() {
        val parsed = ShareUrlParser.parse("https://kio.ac/c/abcdefghijklmnopqrstuv")

        assertEquals(DownloadProvider.KIOSK, parsed?.provider)
        assertEquals("abcdefghijklmnopqrstuv", parsed?.id)
    }

    @Test
    fun parsesTransferUrls() {
        val parsed = ShareUrlParser.parse("https://www.transfer.it/t/Abc_123-xyZ9/files")

        assertEquals(DownloadProvider.TRANSFER, parsed?.provider)
        assertEquals("Abc_123-xyZ9", parsed?.id)
    }

    @Test
    fun rejectsUnknownOrMalformedUrls() {
        assertNull(ShareUrlParser.parse("https://example.com/c/abcdefghijklmnopqrstuv"))
        assertNull(ShareUrlParser.parse("https://kio.ac/c/too-short"))
        assertNull(ShareUrlParser.parse("not a url"))
    }

    @Test
    fun decodesNestedBase64ShareUrls() {
        val url = "https://kio.ac/c/abcdefghijklmnopqrstuv"
        val once = Base64.getEncoder().encodeToString(url.encodeToByteArray())
        val twice = Base64.getEncoder().encodeToString(once.encodeToByteArray())

        assertEquals(url, ShareUrlParser.resolve(twice))
        assertEquals(DownloadProvider.KIOSK, ShareUrlParser.parse(twice)?.provider)
    }
}

