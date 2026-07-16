package com.kiodl.android.data.remote

import com.kiodl.android.domain.model.CollectionProbe
import com.kiodl.android.domain.model.DirectoryNode
import com.kiodl.android.domain.model.DownloadCollection
import com.kiodl.android.domain.model.DownloadProvider
import com.kiodl.android.domain.model.FileNode
import com.kiodl.android.domain.model.LoadedCollection
import com.kiodl.android.domain.repository.CollectionInvalidPasswordException
import com.kiodl.android.domain.repository.CollectionPasswordRequiredException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TRANSFER_API = "https://bt7.api.mega.co.nz/cs"
const val TRANSFER_CHUNK_SIZE = 25L * 1024 * 1024
private val JSON_MEDIA_TYPE = "text/plain;charset=UTF-8".toMediaType()

class TransferItApiClient(
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: String = TRANSFER_API,
) {
    suspend fun probe(transferId: String): CollectionProbe =
        CollectionProbe(passwordRequired = xi(transferId).optInt("pw") == 1)

    suspend fun load(transferId: String, password: String?): LoadedCollection {
        val info = xi(transferId)
        val protected = info.optInt("pw") == 1
        val authPassword: String? = if (protected) {
            if (password.isNullOrBlank()) throw CollectionPasswordRequiredException()
            val derived = deriveTransferPassword(transferId, password)
            if (megaApi(JSONObject().put("a", "xv").put("xh", transferId).put("pw", derived)) != 1) {
                throw CollectionInvalidPasswordException()
            }
            derived
        } else {
            null
        }
        val response = megaApi(
            JSONObject().put("a", "f").put("c", 1).put("r", 1),
            buildMap {
                put("x", transferId)
                authPassword?.let { put("pw", it) }
            },
        ) as? JSONObject ?: throw IOException("Transfer file list failed.")
        val nodes = response.optJSONArray("f") ?: throw IOException("Transfer file list failed.")
        val tree = buildTree(nodes, info.optString("z").takeIf(String::isNotEmpty))
        val title = decodeBase64Url(info.optString("t")).toString(StandardCharsets.UTF_8)
            .replace("\u0000", "").trim().ifEmpty { tree.rootName.ifEmpty { transferId } }
        return LoadedCollection(
            collection = DownloadCollection(
                shareId = transferId,
                name = title,
                expiresEpochSeconds = 4_102_444_800,
                segmentSize = TRANSFER_CHUNK_SIZE,
                passwordProtected = protected,
                provider = DownloadProvider.TRANSFER,
                tree = tree.directory,
            ),
            rootId = transferId,
            accessToken = authPassword.orEmpty(),
            sourceKeys = tree.nodeKeys,
        )
    }

    suspend fun getDownloadUrl(transferId: String, nodeHandle: String, password: String?): String {
        val response = megaApi(
            JSONObject().put("a", "g").put("n", nodeHandle).put("g", 1).put("ssl", 2),
            buildMap {
                put("x", transferId)
                password?.let { put("pw", deriveTransferPassword(transferId, it)) }
            },
        ) as? JSONObject ?: throw IOException("Transfer download URL failed.")
        response.optInt("e").takeIf { it < 0 }?.let { throw IOException(megaError(it)) }
        return response.optString("g").takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: throw IOException("Transfer CDN URL missing.")
    }

    suspend fun downloadEncryptedRange(
        url: String,
        start: Long,
        size: Long,
        nodeKey: ByteArray,
        onBytes: suspend (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Range", "bytes=$start-${start + size - 1}").build()
        val call = httpClient.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
            if (response.code == 509) {
                throw TransferRateLimitException(parseRetryAfterMillis(response.header("Retry-After")))
            }
            if (response.code != 200 && response.code != 206) throw IOException("Transfer CDN HTTP ${response.code}")
            if (response.code == 206 && response.header("Content-Range")?.startsWith("bytes $start-") != true) {
                throw IOException("Transfer CDN returned an invalid Content-Range.")
            }
            val input = response.body?.byteStream() ?: throw IOException("Transfer CDN response is empty.")
            var skip = if (response.code == 200) start else 0L
            val scratch = ByteArray(64 * 1024)
            while (skip > 0) {
                val count = input.read(scratch, 0, minOf(skip, scratch.size.toLong()).toInt())
                if (count < 0) throw IOException("Transfer CDN response ended early.")
                skip -= count
            }
            val cipher = transferCipher(nodeKey, start)
            val byteOffset = (start % 16).toInt()
            if (byteOffset > 0) cipher.update(ByteArray(byteOffset))
            var remaining = size
            while (remaining > 0) {
                val count = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (count < 0) throw IOException("Transfer CDN response ended early.")
                cipher.update(scratch, 0, count)?.takeIf(ByteArray::isNotEmpty)?.let { onBytes(it) }
                remaining -= count
            }
            cipher.doFinal()?.takeIf(ByteArray::isNotEmpty)?.let { onBytes(it) }
            }
        } finally {
            cancellation?.dispose()
        }
    }

    fun decodeNodeKey(value: String): ByteArray = decodeBase64Url(value).also {
        require(it.size == 32) { "Invalid Transfer node key." }
    }

    private suspend fun xi(transferId: String) =
        megaApi(JSONObject().put("a", "xi").put("xh", transferId)) as? JSONObject
            ?: throw IOException("Transfer info failed.")

    private suspend fun megaApi(payload: JSONObject, query: Map<String, String> = emptyMap()): Any {
        val suffix = query.entries.joinToString("&", prefix = "?") { "${it.key}=${it.value}" }
        val request = Request.Builder()
            .url(apiBaseUrl + suffix)
            .post(JSONArray().put(payload).toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Origin", "https://transfer.it")
            .header("Referer", "https://transfer.it/")
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 509) {
                    throw TransferRateLimitException(parseRetryAfterMillis(response.header("Retry-After")))
                }
                if (!response.isSuccessful) throw IOException("Transfer API HTTP ${response.code}.")
                val array = JSONArray(response.body?.string() ?: throw IOException("Transfer API empty response."))
                val value = array.get(0)
                if (value is Number && value.toInt() < 0) throw IOException(megaError(value.toInt()))
                value
            }
        }
    }

    private fun buildTree(nodes: JSONArray, zipHandle: String?): TransferTree {
        data class Node(val id: String, val parent: String, val type: Int, val name: String, val size: Long)
        val parsed = mutableListOf<Node>()
        val keys = mutableMapOf<String, String>()
        for (index in 0 until nodes.length()) {
            val value = nodes.getJSONObject(index)
            if (value.getString("h") == zipHandle) continue
            val id = value.getString("h")
            val key = value.optString("k").takeIf(String::isNotEmpty)
            val keyBytes = key?.let(::decodeBase64Url)
            val name = if (keyBytes != null && value.has("a")) {
                decryptNodeName(value.getString("a"), keyBytes) ?: "unknown"
            } else {
                "unknown"
            }.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "unknown" }
            if (value.getInt("t") == 0 && keyBytes?.size == 32) keys[id] = encodeBase64Url(keyBytes)
            parsed += Node(id, value.optString("p"), value.getInt("t"), name, value.optLong("s"))
        }
        fun directory(node: Node): DirectoryNode = DirectoryNode(
            id = node.id,
            name = node.name,
            entries = parsed.filter { it.parent == node.id }.mapNotNull { child ->
                when (child.type) {
                    1 -> directory(child)
                    0 -> FileNode(child.id, child.name, child.size)
                    else -> null
                }
            },
        )
        val root = parsed.firstOrNull { it.parent.isEmpty() && it.type == 1 }
        val result = if (root != null) directory(root).copy(name = "") else DirectoryNode(
            id = "root", name = "",
            entries = parsed.filter { it.parent.isEmpty() }.mapNotNull { child ->
                when (child.type) {
                    1 -> directory(child)
                    0 -> FileNode(child.id, child.name, child.size)
                    else -> null
                }
            },
        )
        return TransferTree(result, keys, root?.name.orEmpty())
    }
}

class TransferRateLimitException(val retryAfterMillis: Long?) : IOException(
    "Transfer bandwidth quota exceeded.",
)

private fun parseRetryAfterMillis(value: String?): Long? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    trimmed.toLongOrNull()?.let { return (it * 1_000L).coerceAtLeast(0) }
    return runCatching {
        (ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
            System.currentTimeMillis()).coerceAtLeast(0)
    }.getOrNull()
}

private data class TransferTree(
    val directory: DirectoryNode,
    val nodeKeys: Map<String, String>,
    val rootName: String,
)

internal fun deriveTransferPassword(transferId: String, password: String): String {
    val decoded = decodeBase64Url(transferId)
    val tail = decoded.copyOfRange(decoded.size - 6, decoded.size)
    val salt = ByteArray(18) { tail[it % tail.size] }
    val spec: KeySpec = PBEKeySpec(password.trim().toCharArray(), salt, 100_000, 256)
    return encodeBase64Url(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
}

private fun decryptNodeName(attribute: String, key: ByteArray): String? = runCatching {
    val padded = key.copyOf(32)
    val aesKey = ByteArray(16) { (padded[it].toInt() xor padded[it + 16].toInt()).toByte() }
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
    val plain = cipher.doFinal(decodeBase64Url(attribute)).toString(StandardCharsets.UTF_8)
        .replace("\u0000", "")
    if (!plain.startsWith("MEGA")) null else JSONObject(plain.substring(4)).optString("n").takeIf(String::isNotEmpty)
}.getOrNull()

internal fun transferCipher(key: ByteArray, start: Long): Cipher {
    require(key.size == 32)
    val aesKey = ByteArray(16) { (key[it].toInt() xor key[it + 16].toInt()).toByte() }
    val iv = ByteArray(16)
    key.copyInto(iv, endIndex = 24, destinationOffset = 0, startIndex = 16)
    var carry = start / 16
    for (index in iv.lastIndex downTo 0) {
        val sum = (iv[index].toInt() and 0xff) + (carry and 0xff).toInt()
        iv[index] = sum.toByte()
        carry = (carry ushr 8) + (sum ushr 8)
    }
    return Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
    }
}

private fun decodeBase64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(
    value.replace(",", "") + "=".repeat((4 - value.replace(",", "").length % 4) % 4),
)
private fun encodeBase64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

private fun megaError(code: Int) = when (code) {
    -8 -> "Transfer has expired."
    -9 -> "Transfer not found."
    -14 -> "Invalid password."
    -17 -> "Transfer bandwidth quota exceeded."
    else -> "Transfer API error ($code)."
}
