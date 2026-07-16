package com.kiodl.android.ui.util

import com.kiodl.android.domain.model.DownloadStatus
import com.kiodl.android.domain.model.UploadStatus

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    return "%.1f %s".format(value, units[unitIndex])
}

fun buildProgressText(
    done: Long,
    total: Long,
    speedBytesPerSecond: Long?,
    elapsedMillis: Long?,
): String = buildList {
    add("${formatBytes(done)} / ${formatBytes(total)}")
    speedBytesPerSecond?.takeIf { it > 0 }?.let { add("${formatBytes(it)}/s") }
    elapsedMillis?.let { add(formatDuration(it)) }
}.joinToString(" · ")

fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

val STREAM_WRITE_OPTIONS = intArrayOf(
    256 * 1024, 512 * 1024, 1024 * 1024, 2 * 1024 * 1024,
    4 * 1024 * 1024, 8 * 1024 * 1024,
)
val INFLATE_BUFFER_OPTIONS = intArrayOf(
    1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024,
    8 * 1024 * 1024, 16 * 1024 * 1024,
)

fun previousOption(options: IntArray, current: Int): Int =
    options.lastOrNull { it < current } ?: options.first()

fun nextOption(options: IntArray, current: Int): Int =
    options.firstOrNull { it > current } ?: options.last()

fun DownloadStatus.toLabel(): String = when (this) {
    DownloadStatus.QUEUED -> "대기 중"
    DownloadStatus.DOWNLOADING -> "다운로드 중"
    DownloadStatus.INFLATING -> "압축 해제 중"
    DownloadStatus.PAUSED -> "일시정지"
    DownloadStatus.COMPLETED -> "완료"
    DownloadStatus.ERROR -> "오류"
    DownloadStatus.EXPIRED -> "만료됨"
    DownloadStatus.DELETING -> "삭제 중"
}

fun UploadStatus.toLabel(): String = when (this) {
    UploadStatus.QUEUED -> "대기 중"
    UploadStatus.UPLOADING -> "업로드 중"
    UploadStatus.PAUSED -> "일시정지"
    UploadStatus.COMPLETED -> "완료"
    UploadStatus.ERROR -> "오류"
    UploadStatus.EXPIRED -> "만료"
}