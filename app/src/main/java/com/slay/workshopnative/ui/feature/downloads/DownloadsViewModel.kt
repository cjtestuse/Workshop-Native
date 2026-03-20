package com.slay.workshopnative.ui.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.repository.DownloadsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
) : ViewModel() {
    private companion object {
        const val LOG_TAG = "DownloadsViewModel"
    }

    val downloads: StateFlow<List<DownloadTaskEntity>> = downloadsRepository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

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
}
