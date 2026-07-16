package com.kiodl.android.data.remote

import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.kiodl.android.domain.model.UploadDraft
import com.kiodl.android.domain.model.UploadSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows

class KioUploadClientTest {
    private val server = MockWebServer()
    private val mapper = ObjectMapper(CBORFactory())

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test
    fun createsUploadsSegmentsAndCompletesWithDesktopHeaders() {
        val collectionId = ByteArray(16) { it.toByte() }
        val remoteId = ByteArray(16) { (it + 16).toByte() }
        server.enqueue(cborResponse(mapOf(
            "id" to collectionId,
            "token" to "upload-token",
            "root" to mapOf(
                "id" to ByteArray(16), "name" to "", "children" to emptyList<Any>(),
                "files" to listOf(mapOf("id" to remoteId, "name" to "hello.txt", "size" to 3L)),
            ),
        )))
        server.enqueue(cborResponse(mapOf("exists" to true)))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = KioUploadClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        val created = client.createCollection(
            UploadDraft(
                "테스트", "설명", "plain-password", 2_000_000_000_000,
                listOf(UploadSource("content://hello", "hello.txt", "hello.txt", 3, 0)),
            ),
            "turnstile-token",
        )
        assertArrayEquals(collectionId, created.collectionUuid)
        assertArrayEquals(remoteId, created.files.single().remoteId)

        val createRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v0/collection/create", createRequest.path)
        assertEquals("turnstile-token", createRequest.getHeader("Request-Integrity-Token"))
        @Suppress("UNCHECKED_CAST")
        val createBody = mapper.readValue(createRequest.body.readByteArray(), Map::class.java) as Map<String, Any?>
        assertEquals("테스트", createBody["name"])
        assertEquals(UPLOAD_SEGMENT_SIZE, (createBody["segment_size"] as Number).toLong())

        assertNull(client.requestSegment(remoteId, 0, byteArrayOf(1, 2, 3), "upload-token"))
        val segmentRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("edge", segmentRequest.getHeader("Kiosk-Upload-Capability"))
        assertEquals("upload-token", segmentRequest.getHeader("Kiosk-UT"))

        client.complete("upload-token")
        val completeRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", completeRequest.method)
        assertEquals("/v0/collection/complete", completeRequest.path)
    }

    @Test
    fun mapsMissingOrUnauthorizedUploadSessionToExpired() {
        server.enqueue(cborResponse(mapOf("code" to "collection:not_found")).setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403))
        val client = KioUploadClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))

        assertThrows(UploadSessionExpiredException::class.java) {
            client.requestSegment(ByteArray(16), 0, byteArrayOf(1), "expired-token")
        }
        assertThrows(UploadSessionExpiredException::class.java) {
            client.complete("expired-token")
        }
    }

    @Test
    fun reportsUploadedBytesAfterWritingEachEdgeBlock() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = KioUploadClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        val payload = ByteArray(150_000) { (it and 0xff).toByte() }
        val bandwidthBytes = AtomicInteger()
        val uploadedBytes = AtomicInteger()

        client.uploadEdge(
            SegmentTarget(server.url("/").toString().trimEnd('/'), "edge-token"),
            payload,
            consumeBandwidth = { bandwidthBytes.addAndGet(it) },
            onUploaded = { uploadedBytes.addAndGet(it) },
        )

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/edge/v4/upload", request.path)
        assertArrayEquals(payload, request.body.readByteArray())
        assertEquals(payload.size, bandwidthBytes.get())
        assertEquals(payload.size, uploadedBytes.get())
    }

    private fun cborResponse(value: Any) = MockResponse()
        .setHeader("Content-Type", "application/cbor")
        .setBody(Buffer().write(mapper.writeValueAsBytes(value)))
}
