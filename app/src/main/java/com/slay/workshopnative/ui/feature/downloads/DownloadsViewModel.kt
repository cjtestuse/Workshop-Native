package com.slay.workshopnative.ui.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.repository.SteamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
    private val steamRepository: SteamRepository,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadTaskEntity>> = downloadsRepository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()
    private val _gameTitles = MutableStateFlow<Map<Int, String>>(emptyMap())
    val gameTitles = _gameTitles.asStateFlow()
    private val requestedGameTitles = linkedSetOf<Int>()

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
                .onFailure {
                    _messages.emit(it.message ?: "删除记录失败")
                }
        }
    }

    fun retry(taskId: String) {
        viewModelScope.launch {
            downloadsRepository.retry(taskId)
                .onSuccess {
                    _messages.emit("已重新加入下载队列")
                }
                .onFailure {
                    _messages.emit(it.message ?: "重试失败")
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
                .onFailure {
                    _messages.emit(it.message ?: "继续下载失败")
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

    fun prefetchGameTitles(appIds: Collection<Int>) {
        val targets = appIds
            .filter { it > 0 }
            .distinct()
            .filter { appId ->
                appId !in requestedGameTitles && !_gameTitles.value.containsKey(appId)
            }
        if (targets.isEmpty()) return

        requestedGameTitles += targets
        viewModelScope.launch {
            targets.forEach { appId ->
                steamRepository.loadGameDetails(appId)
                    .getOrNull()
                    ?.title
                    ?.takeIf(String::isNotBlank)
                    ?.let { title ->
                        _gameTitles.value = _gameTitles.value + (appId to title)
                    }
            }
        }
    }
}
