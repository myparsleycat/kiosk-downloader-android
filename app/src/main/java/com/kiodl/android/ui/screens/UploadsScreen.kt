package com.kiodl.android.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.kiodl.android.domain.model.FileUploadStatus
import com.kiodl.android.domain.model.UploadDraft
import com.kiodl.android.domain.model.UploadItem
import com.kiodl.android.domain.model.UploadSource
import com.kiodl.android.domain.model.UploadStatus
import com.kiodl.android.ui.components.ProgressRow
import com.kiodl.android.ui.components.StatusBadge
import com.kiodl.android.ui.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadsPage(
    uploads: List<UploadItem>,
    createUpload: suspend (UploadDraft, String) -> Result<Unit>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onPauseFile: (String, String) -> Unit,
    onResumeFile: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var expiresAt by rememberSaveable { mutableStateOf(System.currentTimeMillis() + 7L * DAY_MILLIS) }
    var expiryTimeText by rememberSaveable { mutableStateOf(formatExpiryTime(expiresAt)) }
    var showExpiryPicker by rememberSaveable { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<UploadSource>>(emptyList()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showTurnstile by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        showTurnstile = true
    }

    val addUris: (List<Uri>) -> Unit = { uris ->
        scope.launch {
            val newSources = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.contentResolver.toUploadSource(uri, null)
                }
            }
            val merged = (sources + newSources).distinctBy(UploadSource::path)
            if (merged.size > 1_000) error = "하나의 컬렉션에는 최대 1,000개의 파일만 추가할 수 있습니다."
            sources = merged.take(1_000)
            if (name.isBlank() && newSources.isNotEmpty()) {
                name = newSources.first().path.substringBefore('/').take(100)
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addUris(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val root = DocumentFile.fromTreeUri(context, uri)
            val found = withContext(Dispatchers.IO) { root?.collectUploadSources().orEmpty() }
            val merged = (sources + found).distinctBy(UploadSource::path)
            if (merged.size > 1_000) error = "하나의 컬렉션에는 최대 1,000개의 파일만 추가할 수 있습니다."
            sources = merged.take(1_000)
            if (name.isBlank() && found.isNotEmpty()) {
                name = found.first().path.substringBefore('/').take(100)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("새 컬렉션", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(100); error = null },
                        label = { Text("컬렉션 이름") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(2500) },
                        label = { Text("설명 (선택)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("비밀번호 (선택)") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Text("만료 기간", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { showExpiryPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(expiresAt)))
                    }
                    OutlinedTextField(
                        value = expiryTimeText,
                        onValueChange = { value ->
                            expiryTimeText = value.take(8)
                            parseExpiryTime(value)?.let { time ->
                                expiresAt = mergeExpiryDateAndTime(expiresAt, time)
                            }
                        },
                        label = { Text("만료 시각 (HH:mm:ss)") },
                        supportingText = if (parseExpiryTime(expiryTimeText) == null) {
                            { Text("24시간 형식으로 입력해 주세요.") }
                        } else null,
                        isError = parseExpiryTime(expiryTimeText) == null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 7, 30).forEach { days ->
                            FilterChip(
                                selected = kotlin.math.abs(
                                    expiresAt - (System.currentTimeMillis() + days * DAY_MILLIS),
                                ) < DAY_MILLIS,
                                onClick = {
                                    expiresAt = System.currentTimeMillis() + days * DAY_MILLIS
                                    expiryTimeText = formatExpiryTime(expiresAt)
                                },
                                label = { Text("${days}일") },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                            Text("파일 추가")
                        }
                        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                            Text("폴더 추가")
                        }
                    }
                    Text(
                        "${sources.size}개 파일 · ${formatBytes(sources.sumOf { it.size })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sources.isNotEmpty()) {
                        OutlinedButton(onClick = { sources = emptyList() }) { Text("목록 지우기") }
                    }
                    sources.take(20).forEach { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = source.path,
                                onValueChange = { value ->
                                    val normalized = value.replace('\\', '/').trim('/')
                                    if (normalized.isNotEmpty() && sources.none { it !== source && it.path == normalized }) {
                                        sources = sources.map {
                                            if (it === source) it.copy(path = normalized, name = normalized.substringAfterLast('/')) else it
                                        }
                                    }
                                },
                                label = { Text("업로드 경로") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedButton(onClick = { sources = sources - source }) { Text("제거") }
                        }
                    }
                    if (sources.size > 20) Text("외 ${sources.size - 20}개", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            if (sources.sumOf { it.size } > 50L * 1024 * 1024 * 1024) {
                                error = "컬렉션 전체 크기는 50 GiB를 넘을 수 없습니다."
                                return@Button
                            }
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else showTurnstile = true
                        },
                        enabled = name.isNotBlank() && sources.isNotEmpty() &&
                            parseExpiryTime(expiryTimeText) != null && !starting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (starting) "업로드 준비 중…" else "업로드 시작") }
                }
            }
        }
        if (uploads.isNotEmpty()) item {
            Text("업로드 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(uploads, key = UploadItem::id) { upload ->
            UploadCard(
                upload = upload,
                onPause = onPause,
                onResume = onResume,
                onPauseFile = onPauseFile,
                onResumeFile = onResumeFile,
                onRemove = onRemove,
                onCopyLink = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Kio 공유 링크", upload.shareLink))
                },
            )
        }
    }

    if (showTurnstile) TurnstileDialog(
        onDismiss = { showTurnstile = false },
        onToken = { token ->
            showTurnstile = false
            starting = true
            scope.launch {
                createUpload(
                    UploadDraft(
                        name.trim(), description, password,
                        expiresAt,
                        sources,
                    ),
                    token,
                ).onSuccess {
                    name = ""; description = ""; password = ""; sources = emptyList()
                    expiresAt = System.currentTimeMillis() + 7L * DAY_MILLIS
                    expiryTimeText = formatExpiryTime(expiresAt)
                }.onFailure { error = it.message ?: "업로드를 시작하지 못했습니다." }
                starting = false
            }
        },
    )

    if (showExpiryPicker) {
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = expiresAt,
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showExpiryPicker = false },
            confirmButton = {
                Button(onClick = {
                    pickerState.selectedDateMillis?.let { selectedDate ->
                        expiresAt = mergeExpiryDateAndTime(
                            selectedDate,
                            parseExpiryTime(expiryTimeText)
                                ?: java.time.Instant.ofEpochMilli(expiresAt)
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalTime(),
                            selectedDateIsUtc = true,
                        )
                    }
                    showExpiryPicker = false
                }) { Text("확인") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExpiryPicker = false }) { Text("취소") }
            },
        ) {
            androidx.compose.material3.DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun UploadCard(
    upload: UploadItem,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onPauseFile: (String, String) -> Unit,
    onResumeFile: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onCopyLink: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(upload.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusBadge(upload.status)
            }
            ProgressRow(
                progress = upload.progress,
                done = upload.uploadedBytes,
                total = upload.totalBytes,
                speed = upload.speedBytesPerSecond,
                elapsed = upload.elapsedMillis,
                error = upload.error,
            )
            upload.files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(file.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        ProgressRow(
                            progress = file.progress,
                            done = file.uploadedBytes,
                            total = file.totalBytes,
                            speed = file.speedBytesPerSecond,
                            elapsed = null,
                            error = file.error,
                        )
                    }
                    when (file.status) {
                        FileUploadStatus.QUEUED, FileUploadStatus.UPLOADING ->
                            OutlinedButton(onClick = { onPauseFile(upload.id, file.id) }) { Text("정지") }
                        FileUploadStatus.PAUSED, FileUploadStatus.ERROR ->
                            OutlinedButton(onClick = { onResumeFile(upload.id, file.id) }) {
                                Text(if (file.status == FileUploadStatus.ERROR) "재시도" else "재개")
                            }
                        FileUploadStatus.COMPLETED -> Unit
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (upload.status == UploadStatus.UPLOADING || upload.status == UploadStatus.QUEUED) {
                    OutlinedButton(onClick = { onPause(upload.id) }) { Text("일시정지") }
                } else if (upload.status == UploadStatus.PAUSED || upload.status == UploadStatus.ERROR) {
                    Button(onClick = { onResume(upload.id) }) { Text("재개") }
                }
                if (upload.shareLink.isNotBlank()) OutlinedButton(onClick = onCopyLink) { Text("링크 복사") }
                OutlinedButton(onClick = { onRemove(upload.id) }) { Text("삭제") }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TurnstileDialog(onDismiss: () -> Unit, onToken: (String) -> Unit) {
    var turnstileError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("보안 확인") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        addJavascriptInterface(TurnstileBridge(onToken) { turnstileError = it }, "Android")
                        loadDataWithBaseURL(
                            "https://kio.ac/upload",
                            TURNSTILE_HTML,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }
                },
            )
            turnstileError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("취소") } },
    )
}

private class TurnstileBridge(
    private val onToken: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    @JavascriptInterface
    fun token(value: String) { Handler(Looper.getMainLooper()).post { onToken(value) } }
    @JavascriptInterface
    fun error(value: String) { Handler(Looper.getMainLooper()).post { onError(value) } }
}

private const val TURNSTILE_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body><div id="widget"></div><script>
let retries=0, widgetId=null;
function reportError(message){Android.error(message)}
function renderWidget(){widgetId=turnstile.render('#widget',{
 sitekey:'0x4AAAAAABCKdPyYZ6jgsDdR',
 callback:function(t){Android.token(t)},
 'expired-callback':function(){if(widgetId!==null)turnstile.reset(widgetId)},
 'error-callback':function(code){
   if(retries++<3&&widgetId!==null){setTimeout(function(){turnstile.reset(widgetId)},1500+retries*500)}
   else reportError('보안 확인을 완료하지 못했습니다: '+code)
 }
})}
</script><script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" onload="renderWidget()" onerror="reportError('Turnstile을 불러오지 못했습니다.')"></script>
</body></html>
"""

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private fun formatExpiryTime(epochMillis: Long): String = java.time.Instant.ofEpochMilli(epochMillis)
    .atZone(java.time.ZoneId.systemDefault())
    .toLocalTime()
    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

private fun parseExpiryTime(value: String): java.time.LocalTime? = runCatching {
    java.time.LocalTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
}.getOrNull()

private fun mergeExpiryDateAndTime(
    epochMillis: Long,
    time: java.time.LocalTime,
    selectedDateIsUtc: Boolean = false,
): Long {
    val date = if (selectedDateIsUtc) {
        java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    } else {
        java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }
    val now = System.currentTimeMillis()
    return date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        .coerceIn(now, now + 30L * DAY_MILLIS)
}

private fun ContentResolver.toUploadSource(uri: Uri, prefix: String?): UploadSource? {
    val cursor: Cursor = runCatching {
        query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )
    }.getOrNull() ?: query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val name = it.getString(0) ?: return null
        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
        val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else 0
        val modifiedIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val modified = if (modifiedIndex >= 0 && !it.isNull(modifiedIndex)) it.getLong(modifiedIndex) else 0
        return UploadSource(uri.toString(), listOfNotNull(prefix, name).joinToString("/"), name, size, modified)
    }
}

private fun DocumentFile.collectUploadSources(prefix: String = name.orEmpty()): List<UploadSource> {
    if (isFile) {
        val fileName = name ?: return emptyList()
        return listOf(UploadSource(uri.toString(), prefix, fileName, length(), lastModified()))
    }
    return listFiles().flatMap { child ->
        child.collectUploadSources(listOf(prefix, child.name.orEmpty()).filter(String::isNotEmpty).joinToString("/"))
    }
}