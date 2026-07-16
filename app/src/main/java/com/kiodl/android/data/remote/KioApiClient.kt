@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kiodl.android.data.remote

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.kiodl.android.domain.model.CollectionProbe
import com.kiodl.android.domain.model.DirectoryNode
import com.kiodl.android.domain.model.DownloadCollection
import com.kiodl.android.domain.model.DownloadProvider
import com.kiodl.android.domain.model.FileNode
import com.kiodl.android.domain.model.LoadedCollection
import com.kiodl.android.domain.model.ZipNode
import com.kiodl.android.transfer.zip.RemoteZipEntry
import com.kiodl.android.transfer.zip.ZipCentralDirectoryParser
import com.kiodl.android.domain.repository.CollectionPasswordRequiredException
import com.kiodl.android.domain.repository.CollectionInvalidPasswordException
import com.kiodl.android.domain.share.ShareIdCodec
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.ObjectTags
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val DEFAULT_API_BASE_URL = "https://api.kio.ac"
private const val PASSWORD_PROTECTOR = "password"
private const val INVALID_PASSWORD_CODE = "collection:invalid_protector_config"
private val CBOR_MEDIA_TYPE = "application/cbor".toMediaType()
private val RETRYABLE_STATUS_CODES = setOf(408, 413, 429, 500, 502, 503, 504, 524)
private val ZIP_FILE_PATTERN = Regex("\\.zip$", RegexOption.IGNORE_CASE)
private val directoryCborMapper = ObjectMapper(CBORFactory())

@OptIn(ExperimentalSerializationApi::class)
private val cbor = Cbor {
    ignoreUnknownKeys = true
    encodeDefaults = false
    alwaysUseByteString = true
    useDefiniteLengthEncoding = true
    encodeObjectTags = true
}

class KioApiClient(
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {
    private val directoryRequests = Semaphore(8)

    suspend fun probe(shareId: String): CollectionProbe {
        val response = post<CollectionGetRequest, CollectionGetResponse>(
            path = "/v0/collection/get",
            body = CollectionGetRequest(ShareIdCodec.decode(shareId)),
        )
        if (response.status == 200 && response.body?.token != null) {
            return CollectionProbe(passwordRequired = false)
        }
        if (response.status != 418) {
            throw IOException("collection/get failed: HTTP ${response.status}")
        }
        if (response.body?.meta?.type == PASSWORD_PROTECTOR) {
            return CollectionProbe(passwordRequired = true)
        }
        throw IOException("Collection requires an unsupported protector.")
    }

    suspend fun load(shareId: String, password: String? = null): LoadedCollection {
        val unlocked = unlock(shareId, password)
        val tree = buildDirectory(unlocked.root, "", unlocked.token)
        return LoadedCollection(
            collection = DownloadCollection(
                shareId = shareId,
                name = unlocked.name,
                expiresEpochSeconds = unlocked.expires,
                segmentSize = unlocked.segmentSize,
                passwordProtected = unlocked.passwordProtected,
                provider = DownloadProvider.KIOSK,
                tree = tree,
            ),
            rootId = unlocked.root.toHex(),
            accessToken = unlocked.token,
        )
    }

    suspend fun refreshToken(shareId: String, password: String?): RefreshedCollectionToken =
        unlock(shareId, password).let { unlocked ->
            RefreshedCollectionToken(unlocked.token, unlocked.expires)
        }

    suspend fun getSegments(remoteFileId: String, accessToken: String): List<KioSegment> {
        val response = post<FileGetsRequest, FileGetsResponse>(
            path = "/v0/collection/file/gets",
            body = FileGetsRequest(listOf(remoteFileId.hexToBytes())),
            headers = mapOf(
                "Kiosk-CAT" to accessToken,
                "Kiosk-Download-Capability" to "cdn, edge",
            ),
        )
        val segments = response.body?.files?.firstOrNull()?.segments.orEmpty()
        if (response.status != 200 || segments.isEmpty()) {
            throw IOException("file/gets failed: HTTP ${response.status}")
        }
        return segments.map { segment ->
            when (segment.type) {
                "cdn" -> KioSegment.Cdn(
                    segment.data.url ?: throw IOException("cdn segment is missing url."),
                )

                "edge" -> KioSegment.Edge(
                    baseUrl = segment.data.url
                        ?: throw IOException("edge segment is missing url/token."),
                    token = segment.data.token
                        ?: throw IOException("edge segment is missing url/token."),
                )

                else -> throw IOException("Unknown segment type: ${segment.type}")
            }
        }
    }

    suspend fun listZipEntries(
        remoteFileId: String,
        archiveSize: Long,
        segmentSize: Long,
        accessToken: String,
    ): List<RemoteZipEntry> = ZipCentralDirectoryParser().parse(
        KioSegmentedReader(
            size = archiveSize,
            segmentSize = segmentSize,
            segments = getSegments(remoteFileId, accessToken),
            apiClient = this,
        ),
    )

    suspend fun segmentedReader(
        remoteFileId: String,
        archiveSize: Long,
        segmentSize: Long,
        accessToken: String,
    ): KioSegmentedReader = KioSegmentedReader(
        size = archiveSize,
        segmentSize = segmentSize,
        segments = getSegments(remoteFileId, accessToken),
        apiClient = this,
    )

    suspend fun streamSegment(
        segment: KioSegment,
        localStart: Long,
        expectedBytes: Long,
        onBytes: suspend (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(localStart >= 0 && expectedBytes >= 0)
        if (expectedBytes == 0L) return@withContext
        val request = Request.Builder()
            .url(segment.url)
            .apply {
                if (segment is KioSegment.Edge) header("Kiosk-SAT", segment.token)
                if (localStart > 0) header("Range", "bytes=$localStart-")
            }
            .build()
        val call = httpClient.newCall(request)
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (response.code != 200 && response.code != 206) {
                    throw IOException("Segment HTTP ${response.code}")
                }
                val input = response.body?.byteStream()
                    ?: throw IOException("Segment response has no body.")
                var remainingSkip = if (localStart > 0 && response.code == 200) localStart else 0
                val buffer = ByteArray(64 * 1024)
                while (remainingSkip > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remainingSkip).toInt())
                    if (read < 0) throw IOException("Segment ended while skipping resumed bytes.")
                    remainingSkip -= read
                }

                var remaining = expectedBytes
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) {
                        throw IOException(
                            "Segment returned ${expectedBytes - remaining}B, expected ${expectedBytes}B.",
                        )
                    }
                    onBytes(buffer.copyOf(read))
                    remaining -= read
                }
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private suspend fun unlock(shareId: String, password: String?): UnlockedCollection {
        val uuid = ShareIdCodec.decode(shareId)
        val first = post<CollectionGetRequest, CollectionGetResponse>(
            path = "/v0/collection/get",
            body = CollectionGetRequest(uuid),
        )
        if (first.status == 200 && first.body?.token != null) {
            return first.body.requireUnlocked(passwordProtected = false)
        }
        if (first.status != 418) {
            throw IOException("collection/get failed: HTTP ${first.status}")
        }
        if (password.isNullOrEmpty()) {
            if (first.body?.meta?.type == PASSWORD_PROTECTOR) {
                throw CollectionPasswordRequiredException()
            }
            throw IOException("Collection requires an unsupported protector.")
        }
        if (first.body?.meta?.type != PASSWORD_PROTECTOR) {
            throw IOException("Unsupported collection protector: ${first.body?.meta?.type}")
        }

        val second = post<CollectionGetRequest, CollectionGetResponse>(
            path = "/v0/collection/get",
            body = CollectionGetRequest(
                uuid = uuid,
                protector = listOf(
                    Protector(type = PASSWORD_PROTECTOR, data = PasswordData(password)),
                ),
            ),
        )
        if (second.status == 200 && second.body?.token != null) {
            return second.body.requireUnlocked(passwordProtected = true)
        }
        if (second.body?.code == INVALID_PASSWORD_CODE) {
            throw CollectionInvalidPasswordException()
        }
        throw IOException("collection/get failed: HTTP ${second.status}")
    }

    private suspend fun buildDirectory(
        id: ByteArray,
        name: String,
        token: String,
    ): DirectoryNode = coroutineScope {
        val response = directoryRequests.withPermit {
            post<DirectoryGetRequest, DirectoryGetResponse>(
                path = "/v0/collection/directory/get",
                body = DirectoryGetRequest(id),
                headers = mapOf("Kiosk-CAT" to token),
            )
        }
        if (response.status != 200) {
            throw IOException("directory/get failed for \"$name\": HTTP ${response.status}")
        }
        val body = try {
            decodeDirectoryResponse(response.rawBody)
        } catch (error: Exception) {
            throw IOException(
                "directory/get returned invalid CBOR for \"$name\": ${error.message}",
                error,
            )
        }

        val directories = body.children.map { child ->
            async { buildDirectory(child.id, child.name, token) }
        }.awaitAll()
        val files = body.files.map { file ->
            if (ZIP_FILE_PATTERN.containsMatchIn(file.name)) {
                ZipNode(
                    id = file.id.toHex(),
                    name = file.name,
                    size = file.size,
                )
            } else {
                FileNode(
                    id = file.id.toHex(),
                    name = file.name,
                    size = file.size,
                )
            }
        }
        DirectoryNode(
            id = id.toHex(),
            name = name,
            entries = directories + files,
        )
    }

    private suspend inline fun <reified RequestBody : Any, reified ResponseBody : Any> post(
        path: String,
        body: RequestBody,
        headers: Map<String, String> = emptyMap(),
    ): CborResponse<ResponseBody> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$apiBaseUrl$path")
            .post(cbor.encodeToByteArray(body).toRequestBody(CBOR_MEDIA_TYPE))
            .header("Accept", "application/cbor")
            .apply { headers.forEach(::header) }
            .build()

        repeat(3) { attempt ->
            val result = httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.bytes() ?: byteArrayOf()
                CborResponse(
                    status = response.code,
                    body = rawBody.takeIf(ByteArray::isNotEmpty)?.let { raw ->
                        runCatching { cbor.decodeFromByteArray<ResponseBody>(raw) }.getOrNull()
                    },
                    rawBody = rawBody,
                )
            }
            if (result.status !in RETRYABLE_STATUS_CODES || attempt == 2) {
                return@withContext result
            }
            delay(300L * (attempt + 1))
        }
        error("unreachable")
    }
}

private data class CborResponse<T>(
    val status: Int,
    val body: T?,
    val rawBody: ByteArray,
)

private data class UnlockedCollection(
    val name: String,
    val token: String,
    val root: ByteArray,
    val segmentSize: Long,
    val expires: Long,
    val passwordProtected: Boolean,
)

private fun CollectionGetResponse.requireUnlocked(passwordProtected: Boolean): UnlockedCollection {
    val resolvedName = name ?: throw IOException("Invalid collection/get response.")
    val resolvedToken = token ?: throw IOException("Invalid collection/get response.")
    val resolvedRoot = root ?: throw IOException("Invalid collection/get response.")
    val resolvedSegmentSize = segmentSize ?: throw IOException("Invalid collection/get response.")
    val resolvedExpires = expires ?: throw IOException("Invalid collection/get response.")
    if (resolvedRoot.size != 16 || resolvedSegmentSize <= 0) {
        throw IOException("Invalid collection/get response.")
    }
    return UnlockedCollection(
        name = resolvedName,
        token = resolvedToken,
        root = resolvedRoot,
        segmentSize = resolvedSegmentSize,
        expires = resolvedExpires,
        passwordProtected = passwordProtected,
    )
}

private fun ByteArray.toHex() = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "Invalid hexadecimal file id."
    }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

sealed interface KioSegment {
    val url: String

    data class Cdn(override val url: String) : KioSegment

    data class Edge(
        val baseUrl: String,
        val token: String,
    ) : KioSegment {
        override val url = "${baseUrl.trimEnd('/')}/edge/v4/download"
    }
}

@Serializable
private data class CollectionGetRequest(
    @ByteString val uuid: ByteArray,
    val protector: List<Protector>? = null,
)

@Serializable
private data class Protector(
    val type: String,
    val data: PasswordData,
)

@Serializable
@ObjectTags(259uL)
private data class PasswordData(
    val password: String,
)

@Serializable
private data class CollectionGetResponse(
    val name: String? = null,
    val token: String? = null,
    @ByteString val root: ByteArray? = null,
    @SerialName("segment_size") val segmentSize: Long? = null,
    val expires: Long? = null,
    val meta: ProtectorMeta? = null,
    val code: String? = null,
)

@Serializable
private data class ProtectorMeta(
    val type: String? = null,
)

@Serializable
private data class DirectoryGetRequest(
    @ByteString val id: ByteArray,
)

@Serializable
private data class DirectoryGetResponse(
    val files: List<RemoteFile> = emptyList(),
    val children: List<RemoteDirectory> = emptyList(),
)

@Serializable
private data class RemoteFile(
    @ByteString val id: ByteArray,
    val name: String,
    val size: Long,
)

@Serializable
private data class RemoteDirectory(
    @ByteString val id: ByteArray,
    val name: String,
)

@Suppress("UNCHECKED_CAST")
private fun decodeDirectoryResponse(raw: ByteArray): DirectoryGetResponse {
    require(raw.isNotEmpty()) { "empty response body" }
    val root = directoryCborMapper.readValue(raw, Map::class.java) as? Map<Any?, Any?>
        ?: throw IOException("top-level value is not a map")
    val files = (root["files"] as? List<*>).orEmpty().mapIndexed { index, value ->
        val file = value as? Map<Any?, Any?>
            ?: throw IOException("files[$index] is not a map")
        RemoteFile(
            id = file["id"].asDirectoryId("files[$index].id"),
            name = file["name"] as? String
                ?: throw IOException("files[$index].name is not a string"),
            size = file["size"].asDirectorySize("files[$index].size"),
        )
    }
    val children = (root["children"] as? List<*>).orEmpty().mapIndexed { index, value ->
        val child = value as? Map<Any?, Any?>
            ?: throw IOException("children[$index] is not a map")
        RemoteDirectory(
            id = child["id"].asDirectoryId("children[$index].id"),
            name = child["name"] as? String
                ?: throw IOException("children[$index].name is not a string"),
        )
    }
    return DirectoryGetResponse(files = files, children = children)
}

data class RefreshedCollectionToken(
    val token: String,
    val expiresEpochSeconds: Long,
)

private fun Any?.asDirectoryId(field: String): ByteArray = (this as? ByteArray)
    ?.takeIf { it.size == 16 }
    ?: throw IOException("$field is not a 16-byte id")

private fun Any?.asDirectorySize(field: String): Long {
    val number = this as? Number ?: throw IOException("$field is not a number")
    if (number is Byte || number is Short || number is Int || number is Long) {
        return number.toLong().takeIf { it >= 0 }
            ?: throw IOException("$field is negative")
    }
    val value = number.toDouble()
    if (!value.isFinite() || value < 0.0 || value > Long.MAX_VALUE.toDouble() || value % 1.0 != 0.0) {
        throw IOException("$field is not a non-negative integer")
    }
    return value.toLong()
}

@Serializable
private data class FileGetsRequest(
    val ids: List<ByteArray>,
)

@Serializable
private data class FileGetsResponse(
    val files: List<RemoteFileSegments> = emptyList(),
)

@Serializable
private data class RemoteFileSegments(
    val segments: List<RemoteSegment> = emptyList(),
)

@Serializable
private data class RemoteSegment(
    val type: String,
    val data: SegmentData,
)

@Serializable
private data class SegmentData(
    val url: String? = null,
    val token: String? = null,
)
