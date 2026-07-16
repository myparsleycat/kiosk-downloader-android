package com.kiodl.android.transfer.zip

interface RemoteRandomAccessReader {
    val size: Long
    suspend fun read(offset: Long, length: Int): ByteArray
}
