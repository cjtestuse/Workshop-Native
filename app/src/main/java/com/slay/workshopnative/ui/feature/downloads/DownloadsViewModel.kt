package com.slay.workshopnative.ui.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.repository.DownloadedItemUpdateCandidate
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.repository.SteamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val DOWNLOADED_MOD_UPDATES_STARTUP_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

data class DownloadUpdatesDialogState(
    val candidates: List<DownloadedItemUpdateCandidate> = emptyList(),
    val selectedPublishedFileIds: Set<Long> = emptySet(),
    val failedCount: Int = 0,
    val isUpdating: Boolean = false,
) {
    val isVisible: Boolean
        get() = candidates.isNotEmpty()
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
    private val steamRepository: SteamRepository,
    private val preferencesStore: UserPreferencesStore,
) : ViewModel() {
    private companion object {
        const val LOG_TAG = "DownloadsViewModel"
        val PUBLISHED_FILE_ID_QUERY_REGEX = Regex("""[?&]id=(\d+)""")
        val PUBLISHED_FILE_ID_STEAM_URI_REGEX = Regex("""steam://publishedfile/(\d+)""")
    }

    val downloads: StateFlow<List<DownloadTaskEntity>> = downloadsRepository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isCreatingTask = MutableStateFlow(false)
    val isCreatingTask = _isCreatingTask.asStateFlow()

    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates = _isCheckingUpdates.asStateFlow()

    private val _isSimulatingUpdate = MutableStateFlow(false)
    val isSimulatingUpdate = _isSimulatingUpdate.asStateFlow()

    private val _downloadUpdatesDialogState = MutableStateFlow(DownloadUpdatesDialogState())
    val downloadUpdatesDialogState = _downloadUpdatesDialogState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    private val _taskCreated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val taskCreated = _taskCreated.asSharedFlow()

    private var startupUpdateCheckAttempted = false

    init {
        viewModelScope.launch {
            downloadsRepository.reconcileActiveTasks()
        }
    }

    fun cancel(taskId: String) {
        viewModelScope.launch {
            downloadsRepository.cancel(taskId)
        }
    }

    fun delete(taskId: String) {
        viewModelScope.launch {
            downloadsRepository.delete(taskId)
                .onSuccess {
                    _messages.emit("已删除这条下载记录")
                }
                .onFailure { error ->
                    AppLog.w(LOG_TAG, "delete failed taskId=$taskId", error)
                    _messages.emit(error.message ?: "删除记录失败")
                }
        }
    }

    fun retry(taskId: String) {
        viewModelScope.launch {
            downloadsRepository.retry(taskId)
                .onSuccess {
                    _messages.emit("已重新加入下载队列")
                }
                .onFailure { error ->
                    AppLog.w(LOG_TAG, "retry failed taskId=$taskId", error)
                    _messages.emit(error.message ?: "重试失败")
                }
        }
    }

    fun pause(taskId: String) {
        viewModelScope.launch {
            downloadsRepository.pause(taskId)
            _messages.emit("已暂停下载")
        }
    }

    fun resume(taskId: String) {
        viewModelScope.launch {
            downloadsRepository.resume(taskId)
                .onSuccess {
                    _messages.emit("已继续下载")
                }
                .onFailure { error ->
                    AppLog.w(LOG_TAG, "resume failed taskId=$taskId", error)
                    _messages.emit(error.message ?: "继续下载失败")
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            downloadsRepository.reconcileActiveTasks()
            _messages.emit("已刷新下载状态")
        }
    }

    fun clearInactiveHistory() {
        viewModelScope.launch {
            val deleted = downloadsRepository.clearInactiveHistory()
            _messages.emit(
                if (deleted > 0) {
                    "已清理 $deleted 条历史记录"
                } else {
                    "没有可清理的历史记录"
                },
            )
        }
    }

    fun checkDownloadedItemsForUpdates() {
        runDownloadedItemUpdateCheck(userInitiated = true)
    }

    fun maybeAutoCheckDownloadedItemsForUpdatesOnStartup() {
        viewModelScope.launch {
            if (startupUpdateCheckAttempted) return@launch
            startupUpdateCheckAttempted = true
            val prefs = preferencesStore.snapshot()
            if (!prefs.autoCheckDownloadedModUpdatesOnLaunch) return@launch
            val now = System.currentTimeMillis()
            if (
                prefs.lastDownloadedModUpdatesLaunchCheckAtMs > 0L &&
                now - prefs.lastDownloadedModUpdatesLaunchCheckAtMs < DOWNLOADED_MOD_UPDATES_STARTUP_CHECK_INTERVAL_MS
            ) {
                return@launch
            }
            preferencesStore.saveLastDownloadedModUpdatesLaunchCheckAt(now)
            runDownloadedItemUpdateCheck(userInitiated = false)
        }
    }

    fun simulateUpdateAvailable() {
        viewModelScope.launch {
            if (_isSimulatingUpdate.value || _isCheckingUpdates.value) return@launch
            _isSimulatingUpdate.value = true
            downloadsRepository.simulateUpdateAvailableForDownloadedItem()
                .onSuccess { title ->
                    _messages.emit("已为《$title》写入更旧的本地更新基线，现在点“检查已下载更新”就能看到可更新效果")
                }
                .onFailure { error ->
                    AppLog.w(LOG_TAG, "simulateUpdateAvailable failed", error)
                    _messages.emit(error.message ?: "模拟更新失败")
                }
            _isSimulatingUpdate.value = false
        }
    }

    fun dismissDownloadUpdatesDialog() {
        _downloadUpdatesDialogState.value = DownloadUpdatesDialogState()
    }

    fun toggleDownloadUpdateSelection(publishedFileId: Long) {
        val current = _downloadUpdatesDialogState.value
        if (!current.isVisible || current.isUpdating) return
        val nextSelection = if (publishedFileId in current.selectedPublishedFileIds) {
            current.selectedPublishedFileIds - publishedFileId
        } else {
            current.selectedPublishedFileIds + publishedFileId
        }
        _downloadUpdatesDialogState.value = current.copy(selectedPublishedFileIds = nextSelection)
    }

    fun enqueueAllDownloadUpdates() {
        enqueueDownloadUpdates(_downloadUpdatesDialogState.value.candidates.map { it.publishedFileId })
    }

    fun enqueueSelectedDownloadUpdates() {
        enqueueDownloadUpdates(_downloadUpdatesDialogState.value.selectedPublishedFileIds.toList())
    }

    fun enqueueByPublishedFileId(rawInput: String) {
        viewModelScope.launch {
            if (_isCreatingTask.value) return@launch

            val publishedFileId = parsePublishedFileId(rawInput)
            if (publishedFileId == null) {
                _messages.emit("请输入有效的 publishedFileId 或工坊链接")
                return@launch
            }

            _isCreatingTask.value = true
            runCatching {
                val item = steamRepository.resolveWorkshopItemForDownload(publishedFileId).getOrThrow()
                downloadsRepository.enqueue(item).getOrThrow()
                item
            }.onSuccess { item ->
                _taskCreated.tryEmit(Unit)
                _messages.emit("已加入下载队列：${item.title}")
            }.onFailure { error ->
                AppLog.w(
                    LOG_TAG,
                    "enqueueByPublishedFileId failed publishedFileId=$publishedFileId",
                    error,
                )
                _messages.emit(error.message ?: "新建下载任务失败")
            }
            _isCreatingTask.value = false
        }
    }

    private fun parsePublishedFileId(rawInput: String): Long? {
        val normalized = rawInput.trim()
        if (normalized.isBlank()) return null

        normalized.toLongOrNull()?.takeIf { it > 0L }?.let { return it }

        val queryMatch = PUBLISHED_FILE_ID_QUERY_REGEX.find(normalized)
        if (queryMatch != null) {
            return queryMatch.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
        }

        val steamMatch = PUBLISHED_FILE_ID_STEAM_URI_REGEX.find(normalized)
        if (steamMatch != null) {
            return steamMatch.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
        }

        return null
    }

    private fun runDownloadedItemUpdateCheck(userInitiated: Boolean) {
        viewModelScope.launch {
            if (_isCheckingUpdates.value) return@launch
            _isCheckingUpdates.value = true
            downloadsRepository.checkDownloadedItemsForUpdates()
                .onSuccess { result ->
                    val summary = result.summary
                    val candidates = result.updateCandidates
                    if (candidates.isNotEmpty()) {
                        _downloadUpdatesDialogState.value = DownloadUpdatesDialogState(
                            candidates = candidates,
                            selectedPublishedFileIds = candidates.map { it.publishedFileId }.toSet(),
                            failedCount = summary.failedCount,
                            isUpdating = false,
                        )
                    } else if (userInitiated) {
                        val message = when {
                            summary.requestedCount == 0 -> "当前没有可检查更新的已完成任务"
                            summary.failedCount > 0 && summary.checkedCount == 0 ->
                                "公开更新检查失败：${summary.failedCount} 项暂时无法读取"
                            summary.failedCount > 0 ->
                                "已检查 ${summary.checkedCount} 项，${summary.failedCount} 项无法公开检查"
                            else ->
                                "已检查 ${summary.checkedCount} 项，当前都已是最新"
                        }
                        _messages.emit(message)
                    }
                }
                .onFailure { error ->
                    AppLog.w(LOG_TAG, "checkDownloadedItemsForUpdates failed", error)
                    if (userInitiated) {
                        _messages.emit(error.message ?: "公开更新检查失败")
                    }
                }
            _isCheckingUpdates.value = false
        }
    }

    private fun enqueueDownloadUpdates(publishedFileIds: List<Long>) {
        viewModelScope.launch {
            val uniqueIds = publishedFileIds.distinct().filter { it > 0L }
            if (uniqueIds.isEmpty()) {
                _messages.emit("请先选择要更新的条目")
                return@launch
            }
            val currentDialog = _downloadUpdatesDialogState.value
            if (!currentDialog.isVisible || currentDialog.isUpdating) return@launch
            _downloadUpdatesDialogState.value = currentDialog.copy(isUpdating = true)

            var successCount = 0
            var failedCount = 0
            uniqueIds.forEach { publishedFileId ->
                runCatching {
                    val item = steamRepository.resolveWorkshopItemForDownload(publishedFileId).getOrThrow()
                    downloadsRepository.enqueue(item).getOrThrow()
                }.onSuccess {
                    successCount += 1
                }.onFailure { error ->
                    failedCount += 1
                    AppLog.w(
                        LOG_TAG,
                        "enqueueDownloadUpdates failed publishedFileId=$publishedFileId",
                        error,
                    )
                }
            }

            _downloadUpdatesDialogState.value = DownloadUpdatesDialogState()
            val message = when {
                successCount > 0 && failedCount > 0 ->
                    "已加入 $successCount 项更新任务，另有 $failedCount 项更新失败"
                successCount > 0 ->
                    "已加入 $successCount 项更新任务"
                else ->
                    "没有成功加入任何更新任务"
            }
            _messages.emit(message)
        }
    }
}
