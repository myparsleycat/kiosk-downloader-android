package com.kiodl.android.domain.share

import com.kiodl.android.domain.model.DownloadProvider
import com.kiodl.android.domain.model.ParsedDownloadUrl
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

object ShareUrlParser {
    private const val KIOSK_HOST = "kio.ac"
    private const val KIOSK_PREFIX = "/c/"
    private const val KIOSK_ID_LENGTH = 22
    private const val TRANSFER_HOST = "transfer.it"
    private const val TRANSFER_PREFIX = "/t/"
    private const val TRANSFER_ID_LENGTH = 12
    private val validId = Regex("^[A-Za-z0-9_-]+$")

    fun parse(value: String): ParsedDownloadUrl? {
        val uri = runCatching { URI(resolve(value) ?: value.trim()) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.") ?: return null
        val path = uri.path ?: return null

        return when (host) {
            KIOSK_HOST -> extract(path, KIOSK_PREFIX, KIOSK_ID_LENGTH)?.let {
                ParsedDownloadUrl(DownloadProvider.KIOSK, it)
            }

            TRANSFER_HOST -> extract(path, TRANSFER_PREFIX, TRANSFER_ID_LENGTH)?.let {
                ParsedDownloadUrl(DownloadProvider.TRANSFER, it)
            }

            else -> null
        }
    }

    fun resolve(value: String): String? {
        var current = value.trim()
        if (parseDirect(current) != null) return current
        repeat(5) {
            val decoded = runCatching {
                Base64.getDecoder().decode(current).toString(StandardCharsets.UTF_8).trim()
            }.getOrNull() ?: return null
            if (decoded.isEmpty() || decoded == current) return null
            if (parseDirect(decoded) != null) return decoded
            current = decoded
        }
        return null
    }

    private fun parseDirect(value: String): ParsedDownloadUrl? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.") ?: return null
        val path = uri.path ?: return null
        return when (host) {
            KIOSK_HOST -> extract(path, KIOSK_PREFIX, KIOSK_ID_LENGTH)?.let {
                ParsedDownloadUrl(DownloadProvider.KIOSK, it)
            }
            TRANSFER_HOST -> extract(path, TRANSFER_PREFIX, TRANSFER_ID_LENGTH)?.let {
                ParsedDownloadUrl(DownloadProvider.TRANSFER, it)
            }
            else -> null
        }
    }

    private fun extract(path: String, prefix: String, length: Int): String? {
        if (!path.startsWith(prefix)) return null
        val id = path.removePrefix(prefix).substringBefore('/')
        return id.takeIf { it.length == length && validId.matches(it) }
    }
}

