package com.kiodl.android.data.remote

import com.kiodl.android.transfer.zip.RemoteRandomAccessReader
import java.io.ByteArrayOutputStream
import java.io.IOException

class KioSegmentedReader(
    override val size: Long,
    private val segmentSize: Long,
    private val segments: List<KioSegment>,
    private val apiClient: KioApiClient,
    private val withSegmentPermit: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) : RemoteRandomAccessReader {
    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= size && length.toLong() <= size - offset)
        val output = ByteArrayOutputStream(length)
        var position = offset
        var remaining = length.toLong()
        while (remaining > 0) {
            val segmentIndex = (position / segmentSize).toInt()
            val localStart = position % segmentSize
            val count = minOf(remaining, segmentSize - localStart)
            val segment = segments.getOrNull(segmentIndex)
                ?: throw IOException("ZIP segment is missing: $segmentIndex")
            withSegmentPermit {
                apiClient.streamSegment(segment, localStart, count, output::write)
            }
            position += count
            remaining -= count
        }
        return output.toByteArray()
    }
}
