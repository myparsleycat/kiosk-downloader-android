package com.kiodl.android.data.remote

import com.kiodl.android.domain.model.FileNode
import com.kiodl.android.domain.model.DirectoryNode
import com.kiodl.android.domain.model.ZipNode
import com.kiodl.android.domain.repository.CollectionInvalidPasswordException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KioApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: KioApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = KioApiClient(
            httpClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
            apiBaseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loadsDesktopCborFixtureAndBuildsFileTree() = runBlocking {
        server.enqueue(cborResponse(PUBLIC_COLLECTION_RESPONSE))
        server.enqueue(cborResponse(ROOT_DIRECTORY_RESPONSE))

        val loaded = client.load("abcdefghijklmnopqrstuv")

        assertEquals("Demo", loaded.collection.name)
        assertEquals(2, loaded.collection.fileCount)
        assertEquals(6_912, loaded.collection.totalBytes)
        assertFalse(loaded.collection.passwordProtected)
        assertTrue(loaded.collection.tree.entries[0] is FileNode)
        assertTrue(loaded.collection.tree.entries[1] is ZipNode)

        val collectionRequest = server.takeRequest()
        assertEquals("/v0/collection/get", collectionRequest.path)
        assertEquals("application/cbor", collectionRequest.getHeader("Content-Type"))
        assertEquals(
            "a164757569645015354911f4380db32809721805310801",
            collectionRequest.body.readByteArray().toHex(),
        )

        val directoryRequest = server.takeRequest()
        assertEquals("/v0/collection/directory/get", directoryRequest.path)
        assertEquals("cat-token", directoryRequest.getHeader("Kiosk-CAT"))
    }

    @Test
    fun preservesPasswordTextWhenUnlocking() = runBlocking {
        server.enqueue(cborResponse(PASSWORD_REQUIRED_RESPONSE, status = 418))
        server.enqueue(cborResponse(PUBLIC_COLLECTION_RESPONSE))
        server.enqueue(cborResponse(ROOT_DIRECTORY_RESPONSE))

        val password = "  keep spaces  "
        val loaded = client.load("abcdefghijklmnopqrstuv", password)

        assertTrue(loaded.collection.passwordProtected)
        server.takeRequest()
        val unlockBody = server.takeRequest().body.readByteArray()
        assertEquals(
            "a264757569645015354911f4380db328097218053108016970726f746563746f7281" +
                "a264747970656870617373776f72646464617461d90103a16870617373776f7264" +
                "6f20206b656570207370616365732020",
            unlockBody.toHex(),
        )
    }

    @Test
    fun reportsPasswordChallengeFromHttp418() = runBlocking {
        server.enqueue(cborResponse(PASSWORD_REQUIRED_RESPONSE, status = 418))

        assertTrue(client.probe("abcdefghijklmnopqrstuv").passwordRequired)
    }

    @Test
    fun placesChildDirectoriesBeforeFiles() = runBlocking {
        server.enqueue(cborResponse(PUBLIC_COLLECTION_RESPONSE))
        server.enqueue(cborResponse(DIRECTORY_WITH_CHILD_RESPONSE))
        server.enqueue(cborResponse(CHILD_DIRECTORY_RESPONSE))

        val entries = client.load("abcdefghijklmnopqrstuv").collection.tree.entries

        assertTrue(entries.first() is DirectoryNode)
        assertEquals("folder", entries.first().name)
        assertTrue(entries.last() is FileNode)
    }

    @Test
    fun acceptsDesktopFloat64SizesAndNullableDirectoryLists() = runBlocking {
        server.enqueue(cborResponse(PUBLIC_COLLECTION_RESPONSE))
        server.enqueue(cborResponse(LARGE_FILE_NULL_CHILDREN_RESPONSE))

        val collection = client.load("abcdefghijklmnopqrstuv").collection

        assertEquals(1, collection.fileCount)
        assertEquals(4_294_967_296L, collection.totalBytes)
    }

    @Test
    fun mapsInvalidPasswordSentinel() {
        server.enqueue(cborResponse(PASSWORD_REQUIRED_RESPONSE, status = 418))
        server.enqueue(cborResponse(INVALID_PASSWORD_RESPONSE, status = 418))

        assertThrows(CollectionInvalidPasswordException::class.java) {
            runBlocking { client.load("abcdefghijklmnopqrstuv", "wrong") }
        }
    }

    @Test
    fun decodesTaggedSegmentMapsAndSendsCapabilityHeaders() = runBlocking {
        server.enqueue(cborResponse(FILE_SEGMENTS_RESPONSE))

        val segments = client.getSegments("000102030405060708090a0b0c0d0e0f", "cat-token")

        assertEquals(KioSegment.Cdn("https://cdn.test/segment"), segments[0])
        assertEquals(
            KioSegment.Edge("https://edge.test", "sat-token"),
            segments[1],
        )
        val request = server.takeRequest()
        assertEquals("cat-token", request.getHeader("Kiosk-CAT"))
        assertEquals("cdn, edge", request.getHeader("Kiosk-Download-Capability"))
    }

    @Test
    fun skipsPrefixWhenServerIgnoresRange() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))))
        val received = mutableListOf<Byte>()

        client.streamSegment(
            segment = KioSegment.Cdn(server.url("/segment").toString()),
            localStart = 3,
            expectedBytes = 4,
        ) { received += it.toList() }

        assertEquals(listOf<Byte>(3, 4, 5, 6), received)
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
    }

    private fun cborResponse(hex: String, status: Int = 200) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/cbor")
        .setBody(Buffer().write(hex.hexToBytes()))

    private companion object {
        const val PUBLIC_COLLECTION_RESPONSE =
            "b90005646e616d656444656d6f65746f6b656e696361742d746f6b656e64726f6f7450" +
                "fffefdfcfbfaf9f8f7f6f5f4f3f2f1f06c7365676d656e745f73697a651a00040000" +
                "67657870697265731a77359400"
        const val ROOT_DIRECTORY_RESPONSE =
            "b900026566696c657382b9000362696450000102030405060708090a0b0c0d0e0f646e" +
                "616d656970686f746f2e6a70676473697a651904d2b9000362696450fffefdfcfbfaf9" +
                "f8f7f6f5f4f3f2f1f0646e616d656b617263686976652e7a69706473697a6519162e" +
                "686368696c6472656e80"
        const val PASSWORD_REQUIRED_RESPONSE =
            "b90001646d657461b9000164747970656870617373776f7264"
        const val DIRECTORY_WITH_CHILD_RESPONSE =
            "b900026566696c657381b9000362696450000102030405060708090a0b0c0d0e0f646e" +
                "616d6568726f6f742e7478746473697a6501686368696c6472656e81b9000262696450" +
                "fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0646e616d6566666f6c646572"
        const val CHILD_DIRECTORY_RESPONSE =
            "b900026566696c657381b9000362696450000102030405060708090a0b0c0d0e0f646e" +
                "616d65696368696c642e7478746473697a6502686368696c6472656e80"
        const val LARGE_FILE_NULL_CHILDREN_RESPONSE =
            "b900026566696c657381b900036269645000000000000000000000000000000000646e" +
                "616d6561786473697a65fb41f0000000000000686368696c6472656ef6"
        const val INVALID_PASSWORD_RESPONSE =
            "b9000164636f64657823636f6c6c656374696f6e3a696e76616c69645f70726f7465" +
                "63746f725f636f6e666967"
        const val FILE_SEGMENTS_RESPONSE =
            "b900016566696c657381b90001687365676d656e747382b9000264747970656363646e" +
                "6464617461d90103a16375726c781868747470733a2f2f63646e2e746573742f736567" +
                "6d656e74b90002647479706564656467656464617461d90103a26375726c7168747470" +
                "733a2f2f656467652e7465737465746f6b656e697361742d746f6b656e"
    }
}

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
