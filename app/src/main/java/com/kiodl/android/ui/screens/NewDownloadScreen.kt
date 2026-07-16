package com.kiodl.android.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kiodl.android.domain.model.CollectionNode
import com.kiodl.android.domain.model.DirectoryNode
import com.kiodl.android.domain.model.FileNode
import com.kiodl.android.domain.model.ZipNode
import com.kiodl.android.domain.model.flattenFilePaths
import com.kiodl.android.domain.share.ShareUrlParser
import com.kiodl.android.ui.CollectionLoadState
import com.kiodl.android.ui.util.formatBytes
import kotlinx.coroutines.launch

@Composable
internal fun NewDownloadPage(
    incomingUrl: String?,
    onIncomingUrlConsumed: () -> Unit,
    collectionState: CollectionLoadState,
    loadCollection: (String, String?) -> Unit,
    resetCollection: () -> Unit,
    loadZipEntries: (String) -> Unit,
    enqueue: suspend (String, String, Set<String>, Map<String, String>, Map<String, String>) -> Result<Unit>,
    lastDestinationUri: String,
    onQueued: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var destinationUri by rememberSaveable { mutableStateOf(lastDestinationUri) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val loadedFiles = (collectionState as? CollectionLoadState.Loaded)
        ?.value?.collection?.tree?.flattenFilePaths().orEmpty()
    var selectedPaths by remember((collectionState as? CollectionLoadState.Loaded)?.value?.collection?.shareId) {
        mutableStateOf<Set<String>>(loadedFiles.map { it.path }.toSet())
    }
    var zipPasswords by remember((collectionState as? CollectionLoadState.Loaded)?.value?.collection?.shareId) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var renames by remember((collectionState as? CollectionLoadState.Loaded)?.value?.collection?.shareId) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            url = ShareUrlParser.resolve(incomingUrl) ?: incomingUrl.trim()
            password = ""
            error = null
            resetCollection()
            onIncomingUrlConsumed()
        }
    }
    val queueDownload: () -> Unit = {
        scope.launch {
            enqueue(url, destinationUri, selectedPaths, zipPasswords, renames)
                .onSuccess { onQueued() }
                .onFailure { error = it.message ?: "다운로드를 추가하지 못했습니다." }
        }
        Unit
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { queueDownload() }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            destinationUri = uri.toString()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("공유 링크", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                error = null
                password = ""
                resetCollection()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("https://kio.ac/c/…") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
        )

        when (collectionState) {
            CollectionLoadState.Idle -> Button(
                onClick = { loadCollection(url, null) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("컬렉션 정보 불러오기")
            }

            CollectionLoadState.Loading -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.padding(8.dp))
                Text("컬렉션을 불러오는 중…")
            }

            is CollectionLoadState.PasswordRequired -> {
                if (collectionState.message != null) {
                    Text(
                        collectionState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("컬렉션 비밀번호") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = collectionState.message != null,
                )
                Button(
                    onClick = { loadCollection(url, password) },
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("비밀번호로 불러오기")
                }
            }

            is CollectionLoadState.Error -> {
                Text(
                    collectionState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { loadCollection(url, password.takeIf { it.isNotEmpty() }) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("다시 시도")
                }
            }

            is CollectionLoadState.Loaded -> LoadedCollectionContent(
                collectionState = collectionState,
                loadedFiles = loadedFiles,
                selectedPaths = selectedPaths,
                onSelectedPathsChange = { selectedPaths = it },
                renames = renames,
                onRenamesChange = { renames = it },
                zipPasswords = zipPasswords,
                onZipPasswordsChange = { zipPasswords = it },
                loadZipEntries = loadZipEntries,
                destinationUri = destinationUri,
                lastDestinationUri = lastDestinationUri,
                onPickFolder = { folderPicker.launch(lastDestinationUri.takeIf(String::isNotBlank)?.let(Uri::parse)) },
                onQueueDownload = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        queueDownload()
                    }
                },
            )
        }
    }
}

@Composable
private fun LoadedCollectionContent(
    collectionState: CollectionLoadState.Loaded,
    loadedFiles: List<com.kiodl.android.domain.model.CollectionFilePath>,
    selectedPaths: Set<String>,
    onSelectedPathsChange: (Set<String>) -> Unit,
    renames: Map<String, String>,
    onRenamesChange: (Map<String, String>) -> Unit,
    zipPasswords: Map<String, String>,
    onZipPasswordsChange: (Map<String, String>) -> Unit,
    loadZipEntries: (String) -> Unit,
    destinationUri: String,
    lastDestinationUri: String,
    onPickFolder: () -> Unit,
    onQueueDownload: () -> Unit,
) {
    val collection = collectionState.value.collection
    var expandedPath by remember { mutableStateOf<String?>(null) }
    var editingRename by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(collection.name, fontWeight = FontWeight.SemiBold)
            Text(
                "파일 ${collection.fileCount}개 · ${formatBytes(collection.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("다운로드할 파일", style = MaterialTheme.typography.titleSmall)
        TextButton(
            onClick = {
                onSelectedPathsChange(
                    if (selectedPaths.size == loadedFiles.size) mutableSetOf()
                    else loadedFiles.mapTo(mutableSetOf()) { it.path },
                )
            },
        ) {
            Text(if (selectedPaths.size == loadedFiles.size) "전체 해제" else "전체 선택")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        loadedFiles.forEach { entry ->
            val fileName = entry.path.substringAfterLast('/')
            val renamed = renames[entry.path]
            val isExpanded = expandedPath == entry.path
            val isEditing = isExpanded && editingRename
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = entry.path in selectedPaths,
                        onCheckedChange = { checked ->
                            onSelectedPathsChange(
                                selectedPaths.toMutableSet().apply {
                                    if (checked) add(entry.path) else remove(entry.path)
                                },
                            )
                        },
                    )
                    Text(
                        renamed ?: fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { expandedPath = if (isExpanded) null else entry.path },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatBytes(entry.file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (renamed != null) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "이름 변경됨",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(18.dp),
                        )
                    }
                }
                if (isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (isEditing) {
                            OutlinedTextField(
                                value = renamed ?: fileName,
                                onValueChange = { value ->
                                    onRenamesChange(
                                        if (value.isBlank() || value == fileName) {
                                            renames - entry.path
                                        } else renames + (entry.path to value),
                                    )
                                },
                                label = { Text("저장 이름") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { editingRename = false }) {
                                        Icon(Icons.Outlined.Check, contentDescription = "이름 변경 완료")
                                    }
                                },
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "저장 이름: ${renamed ?: fileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (renamed != null) {
                                    Text(
                                        "← $fileName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                TextButton(onClick = { editingRename = true }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("이름 변경")
                                }
                            }
                        }
                        if (entry.file is ZipNode) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { loadZipEntries(entry.file.id) }) {
                                    Text(if (entry.file.entries == null) "ZIP 열기" else "새로고침")
                                }
                                if (entry.file.entries.orEmpty().any(CollectionNode::containsEncryptedZipEntry)) {
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = zipPasswords[entry.file.id].orEmpty(),
                                        onValueChange = { value ->
                                            onZipPasswordsChange(zipPasswords + (entry.file.id to value))
                                        },
                                        label = { Text("ZIP 비밀번호") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    OutlinedButton(
        onClick = onPickFolder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null)
        Spacer(Modifier.padding(4.dp))
        Text(if (destinationUri.isBlank()) "저장 폴더 선택" else "저장 폴더 선택됨")
    }
    Button(
        onClick = onQueueDownload,
        enabled = destinationUri.isNotBlank() && selectedPaths.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("다운로드에 추가")
    }
}

private fun CollectionNode.containsEncryptedZipEntry(): Boolean = when (this) {
    is DirectoryNode -> entries.any(CollectionNode::containsEncryptedZipEntry)
    is FileNode -> zipEntry?.encrypted == true
    is ZipNode -> entries.orEmpty().any(CollectionNode::containsEncryptedZipEntry)
}