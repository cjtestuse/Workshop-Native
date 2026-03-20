package com.slay.workshopnative.ui.feature.downloads

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slay.workshopnative.core.logging.AppLog as Log
import com.slay.workshopnative.core.storage.MEDIASTORE_DOWNLOADS_URI_STRING
import com.slay.workshopnative.core.util.formatBytes
import com.slay.workshopnative.core.util.sanitizeRuntimeSourceAddress
import com.slay.workshopnative.data.local.DownloadAuthMode
import com.slay.workshopnative.data.local.DownloadStatus
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.ui.components.ArtworkThumbnail
import com.slay.workshopnative.ui.components.steamCapsuleUrl
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DOWNLOADS_SCREEN_LOG_TAG = "DownloadsScreen"

private enum class DownloadsTab(val label: String) {
    Active("进行中"),
    Paused("暂停"),
    Attention("待处理"),
    Completed("完成"),
}

private data class DownloadsTabState(
    val tab: DownloadsTab,
    val count: Int,
    val tasks: List<DownloadTaskEntity>,
    val emptyTitle: String,
    val emptyMessage: String,
)

private data class DownloadGameGroup(
    val key: String,
    val appId: Int,
    val gameTitle: String,
    val tasks: List<DownloadTaskEntity>,
    val summary: String,
    val firstQueuedAt: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    paddingValues: PaddingValues,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTabName by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedTask = downloads.firstOrNull { it.taskId == selectedTaskId }
    val previewTask = downloads.firstOrNull { it.taskId == previewTaskId }
    val hasInactiveHistory = downloads.any {
        it.status != DownloadStatus.Queued &&
            it.status != DownloadStatus.Running &&
            it.status != DownloadStatus.Paused
    }
    val runningCount = downloads.count { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running }
    val pausedCount = downloads.count { it.status == DownloadStatus.Paused }
    val successCount = downloads.count { it.status == DownloadStatus.Success }
    val attentionCount = downloads.count {
        it.status == DownloadStatus.Failed ||
            it.status == DownloadStatus.Cancelled ||
            it.status == DownloadStatus.Unavailable
    }
    val activeDownloads = downloads.filter { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running }
    val pausedDownloads = downloads.filter { it.status == DownloadStatus.Paused }
    val attentionDownloads = downloads.filter {
        it.status == DownloadStatus.Failed ||
            it.status == DownloadStatus.Cancelled ||
            it.status == DownloadStatus.Unavailable
    }
    val completedDownloads = downloads.filter { it.status == DownloadStatus.Success }
    val tabStates = listOf(
        DownloadsTabState(
            tab = DownloadsTab.Active,
            count = runningCount,
            tasks = activeDownloads,
            emptyTitle = "当前没有进行中的任务",
            emptyMessage = "新的下载加入队列后，会先显示在这里。",
        ),
        DownloadsTabState(
            tab = DownloadsTab.Paused,
            count = pausedCount,
            tasks = pausedDownloads,
            emptyTitle = "当前没有已暂停任务",
            emptyMessage = "需要临时停下的任务，会保留在这里继续下载。",
        ),
        DownloadsTabState(
            tab = DownloadsTab.Attention,
            count = attentionCount,
            tasks = attentionDownloads,
            emptyTitle = "当前没有待处理任务",
            emptyMessage = "失败、已取消或暂不可下载的条目会集中放在这里。",
        ),
        DownloadsTabState(
            tab = DownloadsTab.Completed,
            count = successCount,
            tasks = completedDownloads,
            emptyTitle = "当前没有已完成任务",
            emptyMessage = "下载完成后，可以在这里直接打开目录。",
        ),
    )
    val selectedTab = DownloadsTab.entries.firstOrNull { it.name == selectedTabName }
        ?: tabStates.firstOrNull { it.count > 0 }?.tab
        ?: DownloadsTab.Active
    val selectedTabState = tabStates.first { it.tab == selectedTab }
    val groupedDownloads = remember(selectedTabState.tasks) {
        buildDownloadGroups(selectedTabState.tasks)
    }
    val defaultExpandedForTab = selectedTab.expandsGroupsByDefault()
    var expandedGroupOverrides by remember(selectedTabState.tab) {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item {
                DownloadsControlPanel(
                    tabs = tabStates,
                    selectedTab = selectedTab,
                    onSelectTab = { tab -> selectedTabName = tab.name },
                    hasInactiveHistory = hasInactiveHistory,
                    onRefresh = viewModel::refresh,
                    onClearHistory = viewModel::clearInactiveHistory,
                )
            }

            if (downloads.isEmpty()) {
                item {
                    DownloadsEmptyStateCard(
                        title = "当前还没有下载任务",
                        message = "去工坊列表点开条目，详情里直接加入下载队列。",
                    )
                }
            } else {
                if (groupedDownloads.isEmpty()) {
                    item {
                        DownloadsEmptyStateCard(
                            title = selectedTabState.emptyTitle,
                            message = selectedTabState.emptyMessage,
                        )
                    }
                } else {
                    items(groupedDownloads, key = { it.key }) { group ->
                        val expanded = expandedGroupOverrides[group.key] ?: defaultExpandedForTab
                        DownloadGameGroupCard(
                            group = group,
                            collapsed = !expanded,
                            onToggleCollapse = {
                                val nextExpanded = !expanded
                                expandedGroupOverrides = if (nextExpanded == defaultExpandedForTab) {
                                    expandedGroupOverrides - group.key
                                } else {
                                    expandedGroupOverrides + (group.key to nextExpanded)
                                }
                            },
                        ) { task ->
                            DownloadListItem(
                                task = task,
                                onClick = { selectedTaskId = task.taskId },
                                compact = true,
                                onPause = if (
                                    task.status == DownloadStatus.Queued ||
                                    task.status == DownloadStatus.Running
                                ) {
                                    {
                                        selectedTabName = DownloadsTab.Paused.name
                                        viewModel.pause(task.taskId)
                                    }
                                } else {
                                    null
                                },
                                onResume = if (task.status == DownloadStatus.Paused) {
                                    {
                                        selectedTabName = DownloadsTab.Active.name
                                        viewModel.resume(task.taskId)
                                    }
                                } else {
                                    null
                                },
                                onRetry = when (task.status) {
                                    DownloadStatus.Failed -> ({
                                        selectedTabName = DownloadsTab.Active.name
                                        viewModel.retry(task.taskId)
                                    })
                                    DownloadStatus.Cancelled -> task.retryActionOrNull {
                                        selectedTabName = DownloadsTab.Active.name
                                        viewModel.retry(task.taskId)
                                    }
                                    else -> null
                                },
                                onCancel = if (
                                    task.status == DownloadStatus.Queued ||
                                    task.status == DownloadStatus.Running ||
                                    task.status == DownloadStatus.Paused ||
                                    task.status == DownloadStatus.Failed
                                ) {
                                    {
                                        selectedTabName = DownloadsTab.Attention.name
                                        viewModel.cancel(task.taskId)
                                    }
                                } else {
                                    null
                                },
                                onOpenDirectory = if (task.status == DownloadStatus.Success) {
                                    {
                                        selectedTabName = DownloadsTab.Completed.name
                                        when {
                                            task.canPreviewDirectoryInApp() -> previewTaskId = task.taskId
                                            else -> {
                                                val message = openDirectoryLocation(context, task)
                                                if (message != null) {
                                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    null
                                },
                                onDelete = if (task.canDeleteIndividually()) {
                                    { viewModel.delete(task.taskId) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    selectedTask?.let { task ->
        ModalBottomSheet(onDismissRequest = { selectedTaskId = null }) {
            DownloadTaskSheet(task = task)
        }
    }

    previewTask?.let { task ->
        ModalBottomSheet(onDismissRequest = { previewTaskId = null }) {
            DownloadResultSheet(task = task)
        }
    }
}

@Composable
private fun DownloadGameGroupCard(
    group: DownloadGameGroup,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    itemContent: @Composable (DownloadTaskEntity) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.84f),
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleCollapse)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkThumbnail(
                    imageUrl = group.appId.takeIf { it > 0 }?.let(::steamCapsuleUrl),
                    alternateImageUrl = null,
                    fallbackText = group.gameTitle,
                    modifier = Modifier
                        .width(84.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = group.gameTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = group.summary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.tasks.size.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            imageVector = if (collapsed) {
                                Icons.Rounded.KeyboardArrowDown
                            } else {
                                Icons.Rounded.KeyboardArrowUp
                            },
                            contentDescription = null,
                        )
                    }
                }
            }

            if (!collapsed) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.tasks.forEach { task ->
                        itemContent(task)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadListItem(
    task: DownloadTaskEntity,
    onClick: () -> Unit,
    compact: Boolean = false,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onOpenDirectory: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val cardTone = task.cardTone()
    val showProgressBar = when (task.status) {
        DownloadStatus.Queued,
        DownloadStatus.Running,
        DownloadStatus.Paused,
        DownloadStatus.Failed,
        -> true
        DownloadStatus.Success,
        DownloadStatus.Cancelled,
        DownloadStatus.Unavailable,
        -> false
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        color = cardTone.surface,
        shadowElevation = if (compact) 0.dp else 4.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, cardTone.border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(
                            cardTone.start,
                            cardTone.end,
                        ),
                    ),
                    shape = RoundedCornerShape(24.dp),
                )
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (compact) 10.dp else 11.dp,
                        vertical = if (compact) 9.dp else 10.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadArtwork(
                    task = task,
                    width = if (compact) 78.dp else 92.dp,
                    height = if (compact) 50.dp else 58.dp,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = task.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        StatusBadge(task = task, compact = compact)
                    }

                    if (showProgressBar) {
                        if (task.isConnectingToSource() || task.isFinalizingDownload()) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = {
                                    when {
                                        task.totalBytes > 0L -> task.progressPercent / 100f
                                        else -> 0f
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (compact) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = task.progressLabel(),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            DownloadAuthBadge(task = task, compact = true)
                        }
                    } else {
                        Text(
                            text = task.progressLabel(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DownloadAuthBadge(task = task)
                    }

                    DownloadRowActions(
                        task = task,
                        onPause = onPause,
                        onResume = onResume,
                        onRetry = onRetry,
                        onCancel = onCancel,
                        onOpenDirectory = onOpenDirectory,
                        onDelete = onDelete,
                    )

                    task.supportingMessage()?.let { message ->
                        Text(
                            text = message,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (task.status == DownloadStatus.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRowActions(
    task: DownloadTaskEntity,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onOpenDirectory: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val actions = buildList {
        when (task.status) {
            DownloadStatus.Queued,
            DownloadStatus.Running,
            -> {
                onPause?.let { add("暂停" to it) }
                onCancel?.let { add("取消" to it) }
            }

            DownloadStatus.Paused -> {
                if (!task.pauseRequested) {
                    onResume?.let { add("继续" to it) }
                }
                onCancel?.let { add("取消" to it) }
            }

            DownloadStatus.Failed -> {
                onRetry?.let { add("重试" to it) }
                onCancel?.let { add("取消" to it) }
            }

            DownloadStatus.Success -> {
                onOpenDirectory?.let { add(task.directoryActionCompactLabel() to it) }
            }

            DownloadStatus.Cancelled -> {
                onRetry?.let { add("重下" to it) }
            }

            DownloadStatus.Unavailable -> Unit
        }
    }

    if (actions.isEmpty() && onDelete == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { (label, action) ->
            ActionPillButton(
                modifier = if (onDelete == null && actions.size > 1) {
                    Modifier.weight(1f)
                } else {
                    Modifier.widthIn(min = 88.dp)
                },
                onClick = action,
                icon = null,
                label = label,
                compact = true,
            )
        }
        onDelete?.let { deleteAction ->
            IconActionSurface(
                onClick = deleteAction,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "删除这条记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                compact = true,
            )
        }
    }
}

@Composable
private fun DownloadTaskSheet(
    task: DownloadTaskEntity,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DownloadArtwork(
            task = task,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            width = null,
            height = 172.dp,
        )

        Text(
            text = task.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(22.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(task = task)
                Text(
                    text = task.progressLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "任务摘要",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryDetailCard(
                        modifier = Modifier.weight(1f),
                        label = "下载身份",
                        value = task.downloadAuthSummary(),
                    )
                    SummaryDetailCard(
                        modifier = Modifier.weight(1f),
                        label = "任务绑定",
                        value = task.boundAccountSummary(),
                    )
                }
                DetailBlock(
                    label = "下载位置",
                    value = task.destinationLabel,
                )
                DetailBlock(
                    label = "结果路径",
                    value = task.savedFileUri ?: task.targetTreeUri ?: "当前还没有落盘结果",
                )
                task.supportingMessage()?.let { message ->
                    DetailBlock(label = "错误信息", value = message)
                }
            }
        }

        if (task.hasRuntimeDetails()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "本次下载链路",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryDetailCard(
                            modifier = Modifier.weight(1f),
                            label = "本次链路",
                            value = task.runtimeRouteLabel ?: task.downloadAuthSummary(),
                        )
                        SummaryDetailCard(
                            modifier = Modifier.weight(1f),
                            label = "尝试次数",
                            value = if (task.runtimeAttemptCount > 0) {
                                "${task.runtimeAttemptCount} 次"
                            } else {
                                "未记录"
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryDetailCard(
                            modifier = Modifier.weight(1f),
                            label = "当前并发",
                            value = if (task.runtimeChunkConcurrency > 0) {
                                "${task.runtimeChunkConcurrency} 路"
                            } else {
                                "未记录"
                            },
                        )
                        SummaryDetailCard(
                            modifier = Modifier.weight(1f),
                            label = "网络路径",
                            value = task.runtimeTransportLabel ?: "未记录",
                        )
                    }
                    task.runtimeEndpointLabel?.let { endpoint ->
                        DetailBlock(label = "节点地址", value = endpoint)
                    }
                    DetailBlock(
                        label = "源地址",
                        value = sanitizeRuntimeSourceAddress(task.runtimeSourceAddress)
                            ?: task.sourceUrl
                                .takeUnless { it.startsWith("steam://publishedfile/") }
                                ?.let(::sanitizeRuntimeSourceAddress)
                            ?: "已脱敏",
                    )
                    task.runtimeLastFailure?.takeIf { it.isNotBlank() }?.let { failure ->
                        DetailBlock(label = "最后失败", value = failure)
                    }
                }
            }
        }

        if (task.status != DownloadStatus.Cancelled && task.status != DownloadStatus.Unavailable) {
            if (task.isConnectingToSource() || task.isFinalizingDownload()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = {
                        when {
                            task.totalBytes > 0L -> task.progressPercent / 100f
                            else -> 0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (task.status == DownloadStatus.Paused && task.pauseRequested) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = "正在暂停，当前分块处理完后会停下。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun DownloadsControlPanel(
    tabs: List<DownloadsTabState>,
    selectedTab: DownloadsTab,
    onSelectTab: (DownloadsTab) -> Unit,
    hasInactiveHistory: Boolean,
    onRefresh: () -> Unit,
    onClearHistory: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.82f),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "下载中心",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconActionSurface(
                        onClick = onRefresh,
                        icon = { Icon(Icons.Rounded.Refresh, contentDescription = "刷新下载状态") },
                    )
                    if (hasInactiveHistory) {
                        IconActionSurface(
                            onClick = onClearHistory,
                            icon = { Icon(Icons.Rounded.CleaningServices, contentDescription = "清理历史") },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEach { tabState ->
                    DownloadTabChip(
                        modifier = Modifier.weight(1f),
                        state = tabState,
                        selected = selectedTab == tabState.tab,
                        onClick = { onSelectTab(tabState.tab) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsEmptyStateCard(
    title: String,
    message: String,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.86f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadTabChip(
    modifier: Modifier = Modifier,
    state: DownloadsTabState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = state.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun IconActionSurface(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    compact: Boolean = false,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(if (compact) 16.dp else 18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 8.dp else 10.dp,
            ),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

@Composable
private fun ActionPillButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)?,
    label: String,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 8.dp else 10.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.invoke()
            Text(
                text = label,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DownloadArtwork(
    task: DownloadTaskEntity,
    modifier: Modifier = Modifier,
    width: Dp? = 112.dp,
    height: Dp = 72.dp,
) {
    ArtworkThumbnail(
        imageUrl = task.previewUrl,
        alternateImageUrl = steamCapsuleUrl(task.appId),
        fallbackText = task.title,
        modifier = modifier.then(
            if (width == null) {
                Modifier.height(height)
            } else {
                Modifier
                    .width(width)
                    .height(height)
            },
        ),
        shape = RoundedCornerShape(22.dp),
    )
}

@Composable
private fun SummaryDetailCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailBlock(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(18.dp),
        ) {
            SelectionContainer {
                Text(
                    text = value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    task: DownloadTaskEntity,
    compact: Boolean = false,
) {
    val status = task.status
    val pauseRequested = task.pauseRequested
    val containerColor = when (status) {
        DownloadStatus.Success -> MaterialTheme.colorScheme.secondaryContainer
        DownloadStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        DownloadStatus.Paused -> MaterialTheme.colorScheme.surfaceVariant
        DownloadStatus.Cancelled -> MaterialTheme.colorScheme.surfaceContainerHigh
        DownloadStatus.Unavailable -> MaterialTheme.colorScheme.surfaceContainerHighest
        DownloadStatus.Queued -> MaterialTheme.colorScheme.tertiaryContainer
        DownloadStatus.Running -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when (status) {
        DownloadStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
        DownloadStatus.Success -> MaterialTheme.colorScheme.onSecondaryContainer
        DownloadStatus.Queued -> MaterialTheme.colorScheme.onTertiaryContainer
        DownloadStatus.Running -> MaterialTheme.colorScheme.onPrimaryContainer
        DownloadStatus.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = task.statusLabel(),
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 9.dp,
                vertical = if (compact) 4.dp else 5.dp,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

@Composable
private fun DownloadAuthBadge(
    task: DownloadTaskEntity,
    compact: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Text(
            text = task.downloadAuthCompactLabel(),
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 9.dp,
                vertical = if (compact) 4.dp else 5.dp,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadResultSheet(task: DownloadTaskEntity) {
    val previewState by produceState<PreviewState>(
        initialValue = PreviewState.Loading,
        key1 = task.taskId,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { buildPreviewState(task) }
                .fold(
                    onSuccess = PreviewState::Ready,
                    onFailure = { PreviewState.Error(it.message ?: "读取下载结果失败") },
                )
        }
    }

    Column(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = task.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "旧任务结果位于应用内部目录，这里提供目录预览。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val state = previewState) {
            PreviewState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is PreviewState.Error -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        text = state.message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            is PreviewState.Ready -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.entries.isEmpty()) {
                            Text(
                                text = "当前目录为空。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.entries.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = if (entry.isDirectory) "[目录] ${entry.name}" else entry.name,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = entry.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openDirectoryLocation(
    context: Context,
    task: DownloadTaskEntity,
): String? {
    val publicDirectoryUri = resolvePublicDownloadsDirectoryUri(context, task)
    if (publicDirectoryUri != null) {
        openDirectoryUri(context, publicDirectoryUri)?.let { return it }
        return null
    }

    if (task.isInSystemDownloads()) {
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(downloadsIntent)
            null
        }.getOrElse {
            Log.w(DOWNLOADS_SCREEN_LOG_TAG, "openDirectoryLocation failed action=${downloadsIntent.action}", it)
            "无法打开系统下载目录"
        }
    }

    val candidateUris = buildList {
        task.targetTreeUri?.let { add(Uri.parse(it)) }
        task.savedFileUri
            ?.takeUnless { task.isInSystemDownloads() || it.startsWith("file:") }
            ?.let { add(Uri.parse(it)) }
    }.distinct()

    candidateUris.forEach { uri ->
        val message = openDirectoryUri(context, uri)
        if (message == null) return null
    }

    return "无法打开下载目录"
}

private fun openDirectoryUri(
    context: Context,
    uri: Uri,
): String? {
    val intents = buildList {
        add(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        add(
            Intent(Intent.ACTION_VIEW).apply {
                setData(uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        add(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, toInitialTreeUri(uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    var lastError: Throwable? = null
    intents.forEach { intent ->
        val result = runCatching {
            context.startActivity(intent)
        }
        if (result.isSuccess) return null
        lastError = result.exceptionOrNull()
        Log.w(
            DOWNLOADS_SCREEN_LOG_TAG,
            "openDirectoryUri failed action=${intent.action}",
            lastError,
        )
    }

    return lastError?.message ?: "无法打开下载目录"
}

private fun resolvePublicDownloadsDirectoryUri(
    context: Context,
    task: DownloadTaskEntity,
): Uri? {
    if (task.targetTreeUri != null) return null

    val folderName = task.publicDownloadsFolderName() ?: return null

    task.savedFileUri
        ?.takeIf { it.matches(SPECIFIC_MEDIA_DOWNLOAD_URI_REGEX) }
        ?.let { savedUri ->
            val relativePath = queryRelativePath(context, Uri.parse(savedUri))
            if (!relativePath.isNullOrBlank()) {
                return buildExternalDownloadsDocumentUri(relativePath)
            }
        }

    if (!task.isInSystemDownloads()) return null

    val rootRelativePath = queryMediaStoreRootRelativePath(
        context = context,
        folderName = folderName,
        taskRootName = task.fileName,
    ) ?: return buildExternalDownloadsDocumentUri(
        "${Environment.DIRECTORY_DOWNLOADS}/$folderName/",
    )

    return buildExternalDownloadsDocumentUri(rootRelativePath)
}

private fun queryRelativePath(
    context: Context,
    uri: Uri,
): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
            } else {
                null
            }
        }
    }.getOrNull()
}

private fun toInitialTreeUri(uri: Uri): Uri {
    if (uri.authority != "com.android.externalstorage.documents") return uri
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return uri
    return DocumentsContract.buildTreeDocumentUri(uri.authority, documentId)
}

private val SPECIFIC_MEDIA_DOWNLOAD_URI_REGEX = Regex("""content://media/external/downloads/\d+""")

private fun queryMediaStoreRootRelativePath(
    context: Context,
    folderName: String,
    taskRootName: String,
): String? {
    val basePrefix = "${Environment.DIRECTORY_DOWNLOADS}/$folderName/"
    val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("$basePrefix$taskRootName%")

    return context.contentResolver.query(
        Uri.parse(MEDIASTORE_DOWNLOADS_URI_STRING),
        projection,
        selection,
        args,
        null,
    )?.use { cursor ->
        val results = linkedSetOf<String>()
        val relativePathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            val relativePath = cursor.getString(relativePathIndex).orEmpty()
            if (!relativePath.startsWith(basePrefix)) continue
            val suffix = relativePath.removePrefix(basePrefix)
            val rootSegment = suffix.substringBefore('/').ifBlank { return@use null }
            results += "$basePrefix$rootSegment/"
        }
        results.minByOrNull { it.length }
    }
}

private fun buildExternalDownloadsDocumentUri(
    relativePath: String,
): Uri {
    val normalized = relativePath.trimEnd('/')
    return DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:$normalized",
    )
}

private fun buildPreviewState(task: DownloadTaskEntity): PreviewContent {
    val savedFileUri = task.savedFileUri ?: error("当前任务没有保存结果")
    check(savedFileUri.startsWith("file:")) { "当前结果不支持应用内目录预览" }
    val savedFile = File(URI(savedFileUri))
    check(savedFile.exists()) { "本地下载结果不存在，可能已被删除" }

    return if (savedFile.isDirectory) {
        PreviewContent(
            title = "目录内容",
            subtitle = savedFile.absolutePath,
            entries = savedFile
                .listFiles()
                .orEmpty()
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                .take(80)
                .map { file ->
                    PreviewEntry(
                        name = file.name,
                        detail = if (file.isDirectory) {
                            "${file.listFiles()?.size ?: 0} 项"
                        } else {
                            formatBytes(file.length())
                        },
                        isDirectory = file.isDirectory,
                    )
                },
        )
    } else {
        PreviewContent(
            title = "已下载文件",
            subtitle = savedFile.parentFile?.absolutePath ?: savedFile.absolutePath,
            entries = listOf(
                PreviewEntry(
                    name = savedFile.name,
                    detail = formatBytes(savedFile.length()),
                    isDirectory = false,
                ),
            ),
        )
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState

    data class Ready(val title: String, val subtitle: String, val entries: List<PreviewEntry>) : PreviewState {
        constructor(content: PreviewContent) : this(content.title, content.subtitle, content.entries)
    }

    data class Error(val message: String) : PreviewState
}

private data class PreviewContent(
    val title: String,
    val subtitle: String,
    val entries: List<PreviewEntry>,
)

private data class PreviewEntry(
    val name: String,
    val detail: String,
    val isDirectory: Boolean,
)

private fun DownloadTaskEntity.statusLabel(): String {
    return if (isFinalizingDownload()) {
        "整理中"
    } else {
        status.label(pauseRequested)
    }
}

private fun DownloadStatus.label(pauseRequested: Boolean = false): String {
    return when (this) {
        DownloadStatus.Queued -> "已排队"
        DownloadStatus.Running -> "下载中"
        DownloadStatus.Paused -> if (pauseRequested) "暂停中" else "已暂停"
        DownloadStatus.Success -> "已完成"
        DownloadStatus.Failed -> "失败"
        DownloadStatus.Cancelled -> "已取消"
        DownloadStatus.Unavailable -> "不可下载"
    }
}

private fun DownloadTaskEntity.isConnectingToSource(): Boolean {
    return status == DownloadStatus.Running && progressPercent == 0 && bytesDownloaded == 0L
}

private fun DownloadTaskEntity.isFinalizingDownload(): Boolean {
    return status == DownloadStatus.Running &&
        totalBytes > 0L &&
        bytesDownloaded >= totalBytes
}

private fun DownloadTaskEntity.progressLabel(): String {
    return if (isConnectingToSource()) {
        "正在连接 CDN…"
    } else if (isFinalizingDownload()) {
        "正在整理文件…"
    } else if (status == DownloadStatus.Paused && pauseRequested) {
        if (totalBytes > 0L) {
            "正在暂停… ${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
        } else {
            "正在暂停…"
        }
    } else if (status == DownloadStatus.Paused) {
        if (totalBytes > 0L) {
            "已暂停 · ${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
        } else {
            "已暂停"
        }
    } else if (status == DownloadStatus.Running && progressPercent == 0 && bytesDownloaded > 0L) {
        "已开始下载 · ${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
    } else {
        "${progressPercent}% · ${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
    }
}

private fun DownloadTaskEntity.directoryActionCompactLabel(): String {
    return if (canPreviewDirectoryInApp()) "结果" else "目录"
}

private fun DownloadTaskEntity.downloadAuthCompactLabel(): String {
    return when (downloadAuthMode) {
        DownloadAuthMode.Anonymous -> "匿名下载"
        DownloadAuthMode.Auto -> "匿名优先"
        DownloadAuthMode.Authenticated -> "账号下载"
    }
}

private fun DownloadTaskEntity.downloadAuthSummary(): String {
    return when (downloadAuthMode) {
        DownloadAuthMode.Anonymous -> "匿名下载"
        DownloadAuthMode.Auto -> "匿名优先，必要时回退到当前已登录账号"
        DownloadAuthMode.Authenticated -> "账号下载（当前已登录账号）"
    }
}

private fun DownloadTaskEntity.canDeleteIndividually(): Boolean {
    return status != DownloadStatus.Queued &&
        status != DownloadStatus.Running &&
        status != DownloadStatus.Paused
}

private fun DownloadTaskEntity.boundAccountSummary(): String {
    return when {
        status !in setOf(DownloadStatus.Queued, DownloadStatus.Running, DownloadStatus.Paused) &&
            downloadAuthMode != DownloadAuthMode.Anonymous -> "已脱敏"
        downloadAuthMode == DownloadAuthMode.Anonymous -> "访客"
        else -> "当前账号"
    }
}

private fun DownloadTaskEntity.hasRuntimeDetails(): Boolean {
    return (sourceUrl.isNotBlank() && !sourceUrl.startsWith("steam://publishedfile/")) ||
        !runtimeRouteLabel.isNullOrBlank() ||
        !runtimeTransportLabel.isNullOrBlank() ||
        !runtimeEndpointLabel.isNullOrBlank() ||
        !runtimeSourceAddress.isNullOrBlank() ||
        runtimeAttemptCount > 0 ||
        runtimeChunkConcurrency > 0 ||
        !runtimeLastFailure.isNullOrBlank()
}

private data class DownloadCardTone(
    val surface: Color,
    val border: Color,
    val start: Color,
    val end: Color,
)

private fun DownloadTaskEntity.cardTone(): DownloadCardTone {
    return when (status) {
        DownloadStatus.Success -> DownloadCardTone(
            surface = Color(0xFFF7FBF4),
            border = Color(0xFFD8E7D2),
            start = Color(0xFFFFFFFF),
            end = Color(0xFFEAF5E4),
        )
        DownloadStatus.Failed,
        DownloadStatus.Cancelled,
        DownloadStatus.Unavailable,
        -> DownloadCardTone(
            surface = Color(0xFFFFF8F4),
            border = Color(0xFFF0D7CB),
            start = Color(0xFFFFFFFF),
            end = Color(0xFFFBEADF),
        )
        DownloadStatus.Paused -> DownloadCardTone(
            surface = Color(0xFFFBFAF6),
            border = Color(0xFFE6E0D4),
            start = Color(0xFFFFFFFF),
            end = Color(0xFFF4F0E8),
        )
        DownloadStatus.Queued,
        DownloadStatus.Running,
        -> DownloadCardTone(
            surface = Color(0xFFFFFBF7),
            border = Color.White.copy(alpha = 0.44f),
            start = Color.White.copy(alpha = 0.95f),
            end = Color(0xFFF8EFE5).copy(alpha = 0.9f),
        )
    }
}

private fun DownloadTaskEntity.supportingMessage(): String? {
    val message = errorMessage?.trim().orEmpty()
    if (message.isBlank()) return null

    if (message == status.label(pauseRequested)) return null
    if (status == DownloadStatus.Paused && (message == "正在暂停…" || message == "已暂停")) return null
    if (status == DownloadStatus.Cancelled && message == "已取消") return null

    return message
}

private fun DownloadTaskEntity.isInSystemDownloads(): Boolean {
    return savedFileUri == MEDIASTORE_DOWNLOADS_URI_STRING ||
        savedFileUri?.startsWith("content://media/external/downloads") == true
}

private fun DownloadTaskEntity.publicDownloadsFolderName(): String? {
    val prefixes = listOf("系统下载/", "手机下载/")
    return destinationLabel
        .takeIf { label -> prefixes.any(label::startsWith) }
        ?.let { label ->
            prefixes.firstOrNull(label::startsWith)?.let(label::removePrefix) ?: label
        }
        ?.substringBefore('/')
        ?.ifBlank { null }
}

private fun DownloadTaskEntity.canPreviewDirectoryInApp(): Boolean {
    return savedFileUri?.startsWith("file:") == true
}

private fun DownloadTaskEntity.canOpenDirectory(): Boolean {
    return targetTreeUri != null ||
        !savedFileUri.isNullOrBlank() ||
        isInSystemDownloads() ||
        canPreviewDirectoryInApp()
}

private fun DownloadTaskEntity.directoryActionLabel(): String {
    return if (canPreviewDirectoryInApp()) "查看目录" else "打开目录"
}

private fun DownloadTaskEntity.canRetryFromHistory(): Boolean {
    return status == DownloadStatus.Cancelled && publishedFileId > 0L
}

private fun DownloadTaskEntity.retryActionOrNull(
    action: () -> Unit,
): (() -> Unit)? {
    return if (canRetryFromHistory()) action else null
}

private fun buildDownloadGroups(
    tasks: List<DownloadTaskEntity>,
): List<DownloadGameGroup> {
    return tasks
        .groupBy { task ->
            if (task.appId > 0) {
                "app:${task.appId}"
            } else {
                "task:${task.taskId}"
            }
        }
        .map { (key, groupTasks) ->
            val sortedTasks = groupTasks.sortedBy(DownloadTaskEntity::createdAt)
            val appId = groupTasks.firstOrNull()?.appId ?: 0
            val gameTitle = when {
                appId > 0 -> "Steam App $appId"
                else -> groupTasks.firstOrNull()?.title ?: "未分类游戏"
            }
            DownloadGameGroup(
                key = key,
                appId = appId,
                gameTitle = gameTitle,
                tasks = sortedTasks,
                summary = buildGameGroupSummary(sortedTasks),
                firstQueuedAt = sortedTasks.minOfOrNull(DownloadTaskEntity::createdAt) ?: 0L,
            )
        }
        .sortedWith(
            compareBy<DownloadGameGroup> { it.firstQueuedAt },
        )
}

private fun DownloadsTab.expandsGroupsByDefault(): Boolean {
    return this == DownloadsTab.Active || this == DownloadsTab.Attention
}

private fun buildGameGroupSummary(tasks: List<DownloadTaskEntity>): String {
    val counts = linkedMapOf<String, Int>()
    tasks.forEach { task ->
        val key = task.groupSummaryLabel()
        counts[key] = (counts[key] ?: 0) + 1
    }
    val statusSummary = counts.entries.joinToString(" · ") { (label, count) -> "$label $count" }
    return "${tasks.size} 个条目 · $statusSummary"
}

private fun DownloadTaskEntity.groupSummaryLabel(): String {
    return when {
        isFinalizingDownload() -> "整理中"
        status == DownloadStatus.Running -> "下载中"
        status == DownloadStatus.Queued -> "排队中"
        status == DownloadStatus.Paused && pauseRequested -> "暂停中"
        status == DownloadStatus.Paused -> "已暂停"
        status == DownloadStatus.Success -> "已完成"
        status == DownloadStatus.Failed -> "失败"
        status == DownloadStatus.Cancelled -> "已取消"
        status == DownloadStatus.Unavailable -> "不可下载"
        else -> status.label(pauseRequested)
    }
}

private fun DownloadTaskEntity.statusSortPriority(): Int {
    return when {
        isFinalizingDownload() -> 0
        status == DownloadStatus.Running -> 1
        status == DownloadStatus.Queued -> 2
        status == DownloadStatus.Paused && pauseRequested -> 3
        status == DownloadStatus.Paused -> 4
        status == DownloadStatus.Failed -> 5
        status == DownloadStatus.Cancelled -> 6
        status == DownloadStatus.Unavailable -> 7
        status == DownloadStatus.Success -> 8
        else -> 9
    }
}

private fun DownloadTaskEntity.isPrimaryAttentionStatus(): Boolean {
    return when {
        isFinalizingDownload() -> true
        status == DownloadStatus.Running -> true
        status == DownloadStatus.Queued -> true
        status == DownloadStatus.Failed -> true
        else -> false
    }
}
