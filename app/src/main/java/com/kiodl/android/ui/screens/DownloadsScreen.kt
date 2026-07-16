package com.kiodl.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiodl.android.domain.model.DownloadItem
import com.kiodl.android.domain.model.DownloadStatus
import com.kiodl.android.domain.model.FileDownloadStatus
import com.kiodl.android.ui.components.ConfirmRemoveDialog
import com.kiodl.android.ui.components.EmptyState
import com.kiodl.android.ui.components.ProgressRow
import com.kiodl.android.ui.components.StatusBadge

@Composable
internal fun DownloadsPage(
    downloads: List<DownloadItem>,
    lastDestinationUri: String,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onPauseFile: (String, String) -> Unit,
    onResumeFile: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onExport: (String, String) -> Unit,
    onImport: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<DownloadItem?>(null) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val id = pendingExportId
        if (uri != null && id != null) onExport(id, uri.toString())
        pendingExportId = null
    }
    val importDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val source = pendingImportUri
        if (uri != null && source != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onImport(source, uri.toString())
        }
        pendingImportUri = null
    }
    val importDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pendingImportUri = uri.toString()
            importDestination.launch(lastDestinationUri.takeIf(String::isNotBlank)?.let(Uri::parse))
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(
                onClick = { importDocument.launch(arrayOf("application/octet-stream", "application/zstd", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null)
                Text("KDX 가져오기")
            }
        }
        if (downloads.isEmpty()) {
            item {
                EmptyState(
                    title = "아직 다운로드가 없습니다",
                    description = "새 다운로드를 추가하거나 KDX 파일을 가져오세요.",
                )
            }
        }
        items(downloads, key = DownloadItem::id) { item ->
            DownloadCard(
                item = item,
                onPause = onPause,
                onResume = onResume,
                onPauseFile = onPauseFile,
                onResumeFile = onResumeFile,
                onRemove = { pendingRemoval = item },
                onExport = {
                    pendingExportId = item.id
                    exportDocument.launch("${item.name}.kdx")
                },
            )
        }
    }

    pendingRemoval?.let { item ->
        ConfirmRemoveDialog(
            message = "${item.name}의 진행 기록을 삭제할까요? 이미 저장된 완성 파일은 유지됩니다.",
            onConfirm = { onRemove(item.id) },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onPauseFile: (String, String) -> Unit,
    onResumeFile: (String, String) -> Unit,
    onRemove: () -> Unit,
    onExport: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        item.provider.name.lowercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                StatusBadge(item.status)
            }
            ProgressRow(
                progress = item.progress,
                done = item.transferredBytes,
                total = item.totalBytes,
                speed = item.speedBytesPerSecond,
                elapsed = item.elapsedMillis,
                error = item.error,
            )
            item.files.filter { it.selected }.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(file.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        ProgressRow(
                            progress = file.progress,
                            done = file.downloadedBytes,
                            total = file.totalBytes,
                            speed = file.speedBytesPerSecond,
                            elapsed = null,
                            error = file.error,
                        )
                    }
                    when {
                        file.status in setOf(
                            FileDownloadStatus.DOWNLOADING,
                            FileDownloadStatus.INFLATING,
                            FileDownloadStatus.PENDING,
                        ) -> OutlinedButton(
                            onClick = { onPauseFile(item.id, file.id) },
                        ) { Text("정지") }
                        file.status == FileDownloadStatus.PAUSED || file.status == FileDownloadStatus.ERROR ->
                            OutlinedButton(onClick = { onResumeFile(item.id, file.id) }) {
                                Text(if (file.status == FileDownloadStatus.ERROR) "재시도" else "재개")
                            }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                when (item.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.INFLATING -> OutlinedButton(
                        onClick = { onPause(item.id) },
                    ) {
                        Icon(Icons.Outlined.Pause, contentDescription = null)
                        Text("일시정지")
                    }
                    DownloadStatus.PAUSED, DownloadStatus.QUEUED, DownloadStatus.ERROR -> Button(
                        onClick = { onResume(item.id) },
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text(if (item.status == DownloadStatus.ERROR) "재시도" else "시작")
                    }
                    else -> Unit
                }
                OutlinedButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Text("삭제")
                }
                OutlinedButton(onClick = onExport) {
                    Text("KDX")
                }
            }
        }
    }
}