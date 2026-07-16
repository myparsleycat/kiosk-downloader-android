package com.kiodl.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kiodl.android.domain.model.DownloadStatus
import com.kiodl.android.domain.model.FileDownloadStatus
import com.kiodl.android.domain.model.FileUploadStatus
import com.kiodl.android.domain.model.UploadStatus
import com.kiodl.android.ui.util.toLabel

@Composable
fun StatusBadge(
    status: DownloadStatus,
    modifier: Modifier = Modifier,
) = StatusBadge(status.toLabel(), statusColor(status), modifier)

@Composable
fun StatusBadge(
    status: UploadStatus,
    modifier: Modifier = Modifier,
) = StatusBadge(status.toLabel(), statusColor(status), modifier)

@Composable
fun StatusBadge(
    status: FileDownloadStatus,
    modifier: Modifier = Modifier,
) = StatusBadge(fileLabel(status), statusColor(status), modifier)

@Composable
fun StatusBadge(
    status: FileUploadStatus,
    modifier: Modifier = Modifier,
) = StatusBadge(fileLabel(status), statusColor(status), modifier)

@Composable
private fun StatusBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadStatus.ERROR -> MaterialTheme.colorScheme.error
    DownloadStatus.DOWNLOADING, DownloadStatus.INFLATING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun statusColor(status: UploadStatus): Color = when (status) {
    UploadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    UploadStatus.ERROR -> MaterialTheme.colorScheme.error
    UploadStatus.UPLOADING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun statusColor(status: FileDownloadStatus): Color = when (status) {
    FileDownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    FileDownloadStatus.ERROR -> MaterialTheme.colorScheme.error
    FileDownloadStatus.DOWNLOADING, FileDownloadStatus.INFLATING, FileDownloadStatus.PENDING ->
        MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun statusColor(status: FileUploadStatus): Color = when (status) {
    FileUploadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    FileUploadStatus.ERROR -> MaterialTheme.colorScheme.error
    FileUploadStatus.UPLOADING, FileUploadStatus.QUEUED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun fileLabel(status: FileDownloadStatus): String = when (status) {
    FileDownloadStatus.PENDING -> "대기"
    FileDownloadStatus.DOWNLOADING -> "다운로드"
    FileDownloadStatus.INFLATING -> "해제 중"
    FileDownloadStatus.PAUSED -> "정지"
    FileDownloadStatus.COMPLETED -> "완료"
    FileDownloadStatus.ERROR -> "오류"
}

private fun fileLabel(status: FileUploadStatus): String = when (status) {
    FileUploadStatus.QUEUED -> "대기"
    FileUploadStatus.UPLOADING -> "업로드"
    FileUploadStatus.PAUSED -> "정지"
    FileUploadStatus.COMPLETED -> "완료"
    FileUploadStatus.ERROR -> "오류"
}