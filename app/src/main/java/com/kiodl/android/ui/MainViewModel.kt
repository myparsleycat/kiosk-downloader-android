package com.kiodl.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiodl.android.domain.model.DownloadItem
import com.kiodl.android.domain.model.LoadedCollection
import com.kiodl.android.domain.model.DirectoryNode
import com.kiodl.android.domain.model.CollectionNode
import com.kiodl.android.domain.model.ZipNode
import com.kiodl.android.data.settings.AppSettingsRepository
import com.kiodl.android.domain.model.ThemeMode
import com.kiodl.android.domain.repository.CollectionInvalidPasswordException
import com.kiodl.android.domain.repository.CollectionPasswordRequiredException
import com.kiodl.android.domain.repository.CollectionSourceRepository
import com.kiodl.android.domain.repository.DownloadRepository
import com.kiodl.android.domain.repository.UploadRepository
import com.kiodl.android.domain.model.UploadDraft
import com.kiodl.android.domain.model.UploadItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

sealed interface CollectionLoadState {
    data object Idle : CollectionLoadState
    data object Loading : CollectionLoadState
    data class PasswordRequired(val message: String? = null) : CollectionLoadState
    data class Loaded(val value: LoadedCollection) : CollectionLoadState
    data class Error(val message: String) : CollectionLoadState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val collectionSourceRepository: CollectionSourceRepository,
    private val settingsRepository: AppSettingsRepository,
    private val uploadRepository: UploadRepository,
) : ViewModel() {
    private val mutableCollectionState = MutableStateFlow<CollectionLoadState>(CollectionLoadState.Idle)
    private var loadedPassword: String? = null
    val collectionState = mutableCollectionState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>()
    val messages = mutableMessages.asSharedFlow()
    val settings = settingsRepository.settings

    init {
        viewModelScope.launch {
            downloadRepository.syncExpiredCollections()
            downloadRepository.cleanupOrphanPartFiles()
        }
    }

    fun setSegmentPoolSize(value: Int) = settingsRepository.setSegmentPoolSize(value)
    fun setDownloadBandwidthMiBps(value: Int) = settingsRepository.setDownloadBandwidthMiBps(value)
    fun setUploadBandwidthMiBps(value: Int) = settingsRepository.setUploadBandwidthMiBps(value)
    fun setDownloadMaxRetries(value: Int) = settingsRepository.setDownloadMaxRetries(value)
    fun setUploadMaxRetries(value: Int) = settingsRepository.setUploadMaxRetries(value)
    fun setCreateCollectionSubfolder(value: Boolean) = settingsRepository.setCreateCollectionSubfolder(value)
    fun setAsciiFilenames(value: Boolean) = settingsRepository.setAsciiFilenames(value)
    fun setAutoTryCollectionPasswords(value: Boolean) = settingsRepository.setAutoTryCollectionPasswords(value)
    fun setCollectionPasswordList(value: List<String>) = settingsRepository.setCollectionPasswordList(value)
    fun setStreamWriteBatchBytes(value: Int) = settingsRepository.setStreamWriteBatchBytes(value)
    fun setInflateBufferBytes(value: Int) = settingsRepository.setInflateBufferBytes(value)
    fun setThemeMode(value: ThemeMode) = settingsRepository.setThemeMode(value)

    val downloads: StateFlow<List<DownloadItem>> = downloadRepository.observeDownloads().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val uploads: StateFlow<List<UploadItem>> = uploadRepository.observeUploads().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    suspend fun createUpload(draft: UploadDraft, turnstileToken: String): Result<Unit> =
        runCatching { uploadRepository.create(draft, turnstileToken); Unit }
            .onSuccess { mutableMessages.emit("업로드를 시작했습니다.") }

    fun pauseUpload(id: String) { viewModelScope.launch { uploadRepository.pause(id) } }
    fun resumeUpload(id: String) { viewModelScope.launch { uploadRepository.resume(id) } }
    fun pauseUploadFile(id: String, fileId: String) {
        viewModelScope.launch { uploadRepository.pauseFile(id, fileId) }
    }
    fun resumeUploadFile(id: String, fileId: String) {
        viewModelScope.launch { uploadRepository.resumeFile(id, fileId) }
    }
    fun removeUpload(id: String) { viewModelScope.launch { uploadRepository.remove(id) } }

    fun loadCollection(url: String, password: String? = null) {
        viewModelScope.launch {
            mutableCollectionState.value = CollectionLoadState.Loading
            mutableCollectionState.value = try {
                collectionSourceRepository.load(url, password).let { loaded ->
                    loadedPassword = password
                    CollectionLoadState.Loaded(loaded)
                }
            } catch (_: CollectionPasswordRequiredException) {
                tryStoredCollectionPasswords(url)?.let { (loaded, matchedPassword) ->
                    loadedPassword = matchedPassword
                    CollectionLoadState.Loaded(loaded)
                } ?: CollectionLoadState.PasswordRequired()
            } catch (_: CollectionInvalidPasswordException) {
                CollectionLoadState.PasswordRequired("비밀번호가 올바르지 않습니다.")
            } catch (error: Exception) {
                CollectionLoadState.Error(error.message ?: "컬렉션 정보를 불러오지 못했습니다.")
            }
        }
    }

    fun resetCollection() {
        loadedPassword = null
        mutableCollectionState.value = CollectionLoadState.Idle
    }

    fun loadZipEntries(fileId: String) {
        val loaded = (collectionState.value as? CollectionLoadState.Loaded)?.value ?: return
        viewModelScope.launch {
            runCatching { collectionSourceRepository.listZipEntries(loaded, fileId) }
                .onSuccess { entries ->
                    mutableCollectionState.value = CollectionLoadState.Loaded(
                        loaded.copy(
                            collection = loaded.collection.copy(
                                tree = loaded.collection.tree.replaceZipEntries(fileId, entries),
                            ),
                        ),
                    )
                }
                .onFailure { mutableMessages.emit(it.message ?: "ZIP 목록을 불러오지 못했습니다.") }
        }
    }

    suspend fun enqueue(
        url: String,
        destinationUri: String,
        selectedPaths: Set<String>,
        zipPasswords: Map<String, String> = emptyMap(),
        renames: Map<String, String> = emptyMap(),
    ): Result<Unit> {
        val loaded = (collectionState.value as? CollectionLoadState.Loaded)?.value
            ?: return Result.failure(IllegalStateException("먼저 컬렉션 정보를 불러와 주세요."))
        runCatching {
            zipPasswords.filterValues(String::isNotEmpty).forEach { (fileId, zipPassword) ->
                collectionSourceRepository.verifyZipPassword(loaded, fileId, zipPassword)
            }
        }.onFailure { return Result.failure(it) }
        return downloadRepository.enqueue(
            url = url,
            destinationUri = destinationUri,
            loadedCollection = loaded,
            passwordPlain = loadedPassword,
            selectedPaths = selectedPaths,
            zipPasswords = zipPasswords,
            renames = renames,
        ).onSuccess {
            settingsRepository.setLastDownloadDestinationUri(destinationUri)
            resetCollection()
        }
    }

    fun pause(collectionId: String) {
        viewModelScope.launch { downloadRepository.pause(collectionId) }
    }

    fun resume(collectionId: String) {
        viewModelScope.launch { downloadRepository.resume(collectionId) }
    }

    private suspend fun tryStoredCollectionPasswords(url: String): Pair<LoadedCollection, String>? {
        val settings = settingsRepository.settings.value
        if (!settings.autoTryCollectionPasswords) return null
        val candidates = settings.collectionPasswordList
        if (candidates.isEmpty()) return null
        val winner = CompletableDeferred<Pair<LoadedCollection, String>?>()
        val remaining = AtomicInteger(candidates.size)
        candidates.forEach { candidate ->
            viewModelScope.launch {
                val loaded = runCatching { collectionSourceRepository.load(url, candidate) }.getOrNull()
                if (loaded != null) {
                    winner.complete(loaded to candidate)
                } else if (remaining.decrementAndGet() == 0) {
                    winner.complete(null)
                }
            }
        }
        return winner.await()
    }

    fun pauseFile(collectionId: String, fileId: String) {
        viewModelScope.launch { downloadRepository.pauseFile(collectionId, fileId) }
    }

    fun resumeFile(collectionId: String, fileId: String) {
        viewModelScope.launch { downloadRepository.resumeFile(collectionId, fileId) }
    }

    fun remove(collectionId: String) {
        viewModelScope.launch { downloadRepository.remove(collectionId) }
    }

    fun exportKdx(collectionId: String, outputUri: String) {
        viewModelScope.launch {
            downloadRepository.exportKdx(collectionId, outputUri).fold(
                onSuccess = { mutableMessages.emit("KDX 파일을 내보냈습니다.") },
                onFailure = { mutableMessages.emit(it.message ?: "KDX 파일을 내보내지 못했습니다.") },
            )
        }
    }

    fun importKdx(inputUri: String, destinationUri: String) {
        viewModelScope.launch {
            downloadRepository.importKdx(inputUri, destinationUri).fold(
                onSuccess = {
                    settingsRepository.setLastDownloadDestinationUri(destinationUri)
                    mutableMessages.emit("KDX 다운로드를 가져왔습니다.")
                },
                onFailure = { mutableMessages.emit(it.message ?: "KDX 파일을 가져오지 못했습니다.") },
            )
        }
    }
}

private fun DirectoryNode.replaceZipEntries(fileId: String, entries: List<CollectionNode>): DirectoryNode =
    copy(entries = this.entries.map { node ->
        when (node) {
            is DirectoryNode -> node.replaceZipEntries(fileId, entries)
            is ZipNode -> if (node.id == fileId) node.copy(entries = entries) else node
            else -> node
        }
    })
