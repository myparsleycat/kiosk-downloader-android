package com.kiodl.android.transfer

data class KioChunk(
    val index: Int,
    val offset: Long,
    val size: Long,
)

fun buildKioChunkLayout(fileSize: Long, segmentSize: Long): List<KioChunk> {
    require(fileSize >= 0) { "File size cannot be negative." }
    require(segmentSize > 0) { "Segment size must be positive." }
    if (fileSize == 0L) return emptyList()
    val chunkCount = ((fileSize - 1) / segmentSize) + 1
    require(chunkCount <= Int.MAX_VALUE) { "File has too many segments." }
    return List(chunkCount.toInt()) { index ->
        val offset = index * segmentSize
        KioChunk(
            index = index,
            offset = offset,
            size = minOf(segmentSize, fileSize - offset),
        )
    }
}

