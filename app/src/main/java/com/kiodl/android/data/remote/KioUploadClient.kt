package com.kiodl.android.data.remote

import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.kiodl.android.domain.model.UploadDraft
import com.kiodl.android.domain.model.UploadSource
import com.kiodl.android.domain.share.ShareIdCodec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import android.net.Network
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okio.BufferedSink

const val UPLOAD_SEGMENT_SIZE = 16L * 1024 * 1024

class UploadSessionExpiredException(message: String) : Exception(message)

data class CreatedUpload(
    val collectionUuid: ByteArray,
    val uploadToken: String,
    val shareLink: String,
    val files: List<CreatedUploadFile>,
)

data class CreatedUploadFile(val source: UploadSource, val remoteId: ByteArray)

class KioUploadClient(
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: String = "https://api.kio.ac",
) {
    private val mapper = ObjectMapper(CBORFactory())

    fun boundTo(network: Network): KioUploadClient = KioUploadClient(
        httpClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            .build(),
        apiBaseUrl,
    )

    fun createCollection(draft: UploadDraft, turnstileToken: String): CreatedUpload {
        val sourceByPath = draft.sources.associateBy { it.path }
        require(sourceByPath.size == draft.sources.size) { "업로드 경로가 중복됩니다." }
        val root = buildTree(draft.sources)
        val protector = if (draft.password.isEmpty()) emptyList<Any>() else listOf(
            mapOf("type" to "password", "data" to Tagged(259, mapOf("password" to draft.password))),
        )
        val response = cborRequest(
            "POST",
            "/v0/collection/create",
            mapOf(
                "name" to draft.name.take(100),
                "description" to draft.description.take(2500),
                "protector" to protector,
                "root" to root,
                "segment_size" to UPLOAD_SEGMENT_SIZE,
                "eternal" to false,
                "expires" to Tagged(1, draft.expiresEpochMillis / 1000.0),
            ),
            mapOf("Kiosk-Upload-Preference" to "", "Request-Integrity-Token" to turnstileToken),
        )
        if (response.status != 200) throw IOException("collection/create 실패: HTTP ${response.status}")
        val body = response.body ?: throw IOException("collection/create 응답이 비어 있습니다.")
        val uuid = body["id"].asBytes()
        val token = body["token"] as? String ?: throw IOException("업로드 토큰이 없습니다.")
        val serverByPath = mutableMapOf<String, ByteArray>()
        indexServerTree(body["root"].asMap(), emptyList(), serverByPath)
        val files = sourceByPath.map { (path, source) ->
            CreatedUploadFile(source, serverByPath[path] ?: throw IOException("서버 파일 매핑 실패: $path"))
        }
        if (files.size != serverByPath.size) throw IOException("서버 응답 파일 수가 일치하지 않습니다.")
        return CreatedUpload(uuid, token, "https://kio.ac/c/${ShareIdCodec.encode(uuid)}", files)
    }

    fun requestSegment(remoteId: ByteArray, sequence: Int, bytes: ByteArray, uploadToken: String): SegmentTarget? {
        val response = cborRequest(
            "PUT",
            "/v0/collection/file/segment/upload",
            mapOf(
                "file_id" to remoteId,
                "hash" to MessageDigest.getInstance("SHA-256").digest(bytes),
                "segment_sequence" to sequence.toLong(),
            ),
            mapOf("Kiosk-Upload-Capability" to "edge", "Kiosk-UT" to uploadToken),
        )
        if (response.status != 200) {
            if (response.body?.get("code") == "collection:segment_hash_conflict") return null
            if (response.status == 401 || response.status == 403 || response.body?.get("code") == "collection:not_found") {
                throw UploadSessionExpiredException("업로드 세션이 만료되었거나 더 이상 존재하지 않습니다.")
            }
            throw IOException("segment/upload 실패: HTTP ${response.status}")
        }
        if (response.body?.get("exists") == true) return null
        val data = response.body?.get("data").asMap()
        return SegmentTarget(
            data["url"] as? String ?: throw IOException("edge URL이 없습니다."),
            data["token"] as? String ?: throw IOException("edge token이 없습니다."),
        )
    }

    fun uploadEdge(
        target: SegmentTarget,
        bytes: ByteArray,
        consumeBandwidth: ((Int) -> Unit)? = null,
        onUploaded: ((Int) -> Unit)? = null,
    ) {
        val request = Request.Builder()
            .url(target.url.trimEnd('/') + "/edge/v4/upload")
            .header("Content-Length", bytes.size.toString())
            .header("Kiosk-ESUT", target.token)
            .put(UploadRequestBody(bytes, consumeBandwidth, onUploaded))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("edge PUT 실패: HTTP ${response.code}")
        }
    }

    fun complete(uploadToken: String) {
        val response = cborRequest("POST", "/v0/collection/complete", null, mapOf("Kiosk-UT" to uploadToken))
        if (response.status != 200 && response.status != 204) {
            if (response.status == 401 || response.status == 403 || response.body?.get("code") == "collection:not_found") {
                throw UploadSessionExpiredException("업로드 세션이 만료되었거나 더 이상 존재하지 않습니다.")
            }
            throw IOException("collection/complete 실패: HTTP ${response.status}")
        }
    }

    private fun cborRequest(method: String, path: String, body: Any?, headers: Map<String, String>): UploadCborResponse {
        val requestBody = body?.let { CborWriter.encode(it).toRequestBody("application/cbor".toMediaType()) }
            ?: if (method == "POST" || method == "PUT") byteArrayOf().toRequestBody(null) else null
        val builder = Request.Builder().url(apiBaseUrl + path).method(method, requestBody)
            .header("Accept", "application/cbor")
        if (body != null) builder.header("Content-Type", "application/cbor")
        headers.forEach(builder::header)
        httpClient.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: byteArrayOf()
            @Suppress("UNCHECKED_CAST")
            return UploadCborResponse(
                response.code,
                if (bytes.isEmpty()) null else runCatching {
                    mapper.readValue(bytes, Map::class.java) as Map<String, Any?>
                }.getOrNull(),
            )
        }
    }

    private fun buildTree(sources: List<UploadSource>): Map<String, Any> {
        val root = MutableUploadDir("")
        sources.forEach { source ->
            val parts = source.path.split('/').filter(String::isNotEmpty)
            require(parts.isNotEmpty()) { "업로드 경로가 비어 있습니다." }
            var dir = root
            parts.dropLast(1).forEach { name -> dir = dir.children.getOrPut(name) { MutableUploadDir(name) } }
            dir.files += mapOf("id" to randomUuid(), "name" to parts.last(), "size" to source.size)
        }
        return root.toMap()
    }

    private fun indexServerTree(node: Map<String, Any?>, prefix: List<String>, out: MutableMap<String, ByteArray>) {
        (node["files"] as? List<*>)?.forEach { value ->
            val file = value.asMap()
            val name = file["name"] as? String ?: throw IOException("서버 파일 이름이 없습니다.")
            out[(prefix + name).joinToString("/")] = file["id"].asBytes()
        }
        (node["children"] as? List<*>)?.forEach { value ->
            val child = value.asMap()
            val name = child["name"] as? String ?: throw IOException("서버 폴더 이름이 없습니다.")
            indexServerTree(child, prefix + name, out)
        }
    }
}

data class SegmentTarget(val url: String, val token: String)
private class UploadRequestBody(
    private val bytes: ByteArray,
    private val consumeBandwidth: ((Int) -> Unit)?,
    private val onUploaded: ((Int) -> Unit)?,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = bytes.size.toLong()
    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(64 * 1024, bytes.size - offset)
            consumeBandwidth?.invoke(count)
            sink.write(bytes, offset, count)
            onUploaded?.invoke(count)
            offset += count
        }
    }
}
private data class UploadCborResponse(val status: Int, val body: Map<String, Any?>?)
private data class Tagged(val tag: Long, val value: Any)

private class MutableUploadDir(private val name: String) {
    val files = mutableListOf<Map<String, Any>>()
    val children = linkedMapOf<String, MutableUploadDir>()
    fun toMap(): Map<String, Any> = mapOf(
        "id" to randomUuid(), "name" to name, "files" to files, "children" to children.values.map { it.toMap() },
    )
}

private fun randomUuid(): ByteArray = ByteArray(16).also {
    SecureRandom().nextBytes(it)
    it[6] = ((it[6].toInt() and 0x0f) or 0x40).toByte()
    it[8] = ((it[8].toInt() and 0x3f) or 0x80).toByte()
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?>
    ?: throw IOException("잘못된 CBOR map 응답입니다.")
private fun Any?.asBytes(): ByteArray = this as? ByteArray
    ?: throw IOException("잘못된 CBOR byte string 응답입니다.")

private object CborWriter {
    fun encode(value: Any): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xf6)
            false -> out.write(0xf4)
            true -> out.write(0xf5)
            is Tagged -> { head(out, 6, value.tag); write(out, value.value) }
            is ByteArray -> { head(out, 2, value.size.toLong()); out.write(value) }
            is String -> value.toByteArray().also { head(out, 3, it.size.toLong()); out.write(it) }
            is Int -> head(out, 0, value.toLong())
            is Long -> { require(value >= 0); head(out, 0, value) }
            is Double -> {
                out.write(0xfb)
                repeat(8) { shift -> out.write((value.toBits() ushr (56 - shift * 8)).toInt()) }
            }
            is List<*> -> { head(out, 4, value.size.toLong()); value.forEach { write(out, it) } }
            is Map<*, *> -> {
                head(out, 5, value.size.toLong())
                value.forEach { (key, item) -> write(out, key as String); write(out, item) }
            }
            else -> error("Unsupported CBOR value: ${value::class}")
        }
    }

    private fun head(out: ByteArrayOutputStream, major: Int, value: Long) {
        when {
            value < 24 -> out.write((major shl 5) or value.toInt())
            value <= 0xff -> { out.write((major shl 5) or 24); out.write(value.toInt()) }
            value <= 0xffff -> { out.write((major shl 5) or 25); out.write((value ushr 8).toInt()); out.write(value.toInt()) }
            value <= 0xffffffffL -> { out.write((major shl 5) or 26); repeat(4) { out.write((value ushr (24 - it * 8)).toInt()) } }
            else -> { out.write((major shl 5) or 27); repeat(8) { out.write((value ushr (56 - it * 8)).toInt()) } }
        }
    }
}
