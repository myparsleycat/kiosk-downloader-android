package com.kiodl.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kiodl.android.ui.screens.DownloadsPage
import com.kiodl.android.ui.screens.NewDownloadPage
import com.kiodl.android.ui.screens.SettingsPage
import com.kiodl.android.ui.screens.UploadsPage

private enum class Destination(
    val label: String,
    val icon: ImageVector,
) {
    DOWNLOADS("다운로드", Icons.Outlined.Download),
    NEW_DOWNLOAD("새 다운로드", Icons.Outlined.AddCircle),
    UPLOADS("업로드", Icons.Outlined.Upload),
    SETTINGS("설정", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KioskDownloaderApp(
    incomingUrl: String? = null,
    onIncomingUrlConsumed: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    var destination by rememberSaveable { mutableStateOf(Destination.DOWNLOADS) }
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val collectionState by viewModel.collectionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) destination = Destination.NEW_DOWNLOAD
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kiosk Downloader", fontWeight = FontWeight.SemiBold)
                        Text(
                            destination.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (destination) {
            Destination.DOWNLOADS -> DownloadsPage(
                downloads = downloads,
                lastDestinationUri = settings.lastDownloadDestinationUri,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onPauseFile = viewModel::pauseFile,
                onResumeFile = viewModel::resumeFile,
                onRemove = viewModel::remove,
                onExport = viewModel::exportKdx,
                onImport = viewModel::importKdx,
                modifier = Modifier.padding(padding),
            )
            Destination.NEW_DOWNLOAD -> NewDownloadPage(
                incomingUrl = incomingUrl,
                onIncomingUrlConsumed = onIncomingUrlConsumed,
                collectionState = collectionState,
                loadCollection = viewModel::loadCollection,
                resetCollection = viewModel::resetCollection,
                loadZipEntries = viewModel::loadZipEntries,
                enqueue = viewModel::enqueue,
                lastDestinationUri = settings.lastDownloadDestinationUri,
                onQueued = { destination = Destination.DOWNLOADS },
                modifier = Modifier.padding(padding),
            )
            Destination.UPLOADS -> UploadsPage(
                uploads = uploads,
                createUpload = viewModel::createUpload,
                onPause = viewModel::pauseUpload,
                onResume = viewModel::resumeUpload,
                onPauseFile = viewModel::pauseUploadFile,
                onResumeFile = viewModel::resumeUploadFile,
                onRemove = viewModel::removeUpload,
                modifier = Modifier.padding(padding),
            )
            Destination.SETTINGS -> SettingsPage(
                settings = settings,
                setSegmentPoolSize = viewModel::setSegmentPoolSize,
                setBandwidth = viewModel::setDownloadBandwidthMiBps,
                setUploadBandwidth = viewModel::setUploadBandwidthMiBps,
                setDownloadRetries = viewModel::setDownloadMaxRetries,
                setUploadRetries = viewModel::setUploadMaxRetries,
                setCreateCollectionSubfolder = viewModel::setCreateCollectionSubfolder,
                setAsciiFilenames = viewModel::setAsciiFilenames,
                setAutoTryCollectionPasswords = viewModel::setAutoTryCollectionPasswords,
                setCollectionPasswordList = viewModel::setCollectionPasswordList,
                setStreamWriteBatchBytes = viewModel::setStreamWriteBatchBytes,
                setInflateBufferBytes = viewModel::setInflateBufferBytes,
                setThemeMode = viewModel::setThemeMode,
                modifier = Modifier.padding(padding),
            )
        }
    }
}