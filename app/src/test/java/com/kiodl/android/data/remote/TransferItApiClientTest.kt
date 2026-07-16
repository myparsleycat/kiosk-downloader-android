package com.kiodl.android.data.remote

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONArray

class TransferItApiClientTest {
    @Test
    fun probesPasswordRequirementWithXiCommand() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[{\"pw\":1}]"))
            val client = TransferItApiClient(OkHttpClient(), server.url("cs").toString())

            assertEquals(true, client.probe("abcdefghijkl").passwordRequired)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            val command = JSONArray(request.body.readUtf8()).getJSONObject(0)
            assertEquals("xi", command.getString("a"))
            assertEquals("abcdefghijkl", command.getString("xh"))
        }
    }

    @Test
    fun decryptsPartialCdnResponseAtAbsoluteOffset() = runBlocking {
        MockWebServer().use { server ->
            val encrypted = hex("11ac39c2c553f1b1f10245f1a068f6db36976d74")
            server.enqueue(
                MockResponse().setResponseCode(206)
                    .addHeader("Content-Range", "bytes 25-44/100")
                    .setBody(Buffer().write(encrypted)),
            )
            val output = ByteArrayOutputStream()
            TransferItApiClient(OkHttpClient()).downloadEncryptedRange(
                server.url("file").toString(), 25, encrypted.size.toLong(), ByteArray(32) { it.toByte() },
                output::write,
            )

            assertArrayEquals("hello transfer chunk".encodeToByteArray(), output.toByteArray())
            assertEquals("bytes=25-44", server.takeRequest().getHeader("Range"))
        }
    }
}

private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
