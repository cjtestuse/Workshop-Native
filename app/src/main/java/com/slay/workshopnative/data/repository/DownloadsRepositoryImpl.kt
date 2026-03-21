package com.slay.workshopnative.data.repository

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.core.logging.SupportDiagnosticsStore
import com.slay.workshopnative.core.storage.buildDownloadDestinationLabel
import com.slay.workshopnative.core.util.buildAccountBindingHash
import com.slay.workshopnative.core.util.resolveAccountBindingHash
import com.slay.workshopnative.core.util.sanitizeFileName
import com.slay.workshopnative.data.local.DownloadAuthMode
import com.slay.workshopnative.data.local.DownloadStatus
import com.slay.workshopnative.data.local.DownloadTaskDao
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.model.WorkshopItem
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.worker.WorkshopDownloadWorker
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class DownloadsRepositoryImpl @Inject constructor(
    private val workManager: WorkManager,
    private val downloadTaskDao: DownloadTaskDao,
    private val preferencesStore: UserPreferencesStore,
    private val steamRepository: SteamRepository,
    private val supportDiagnosticsStore: SupportDiagnosticsStore,
) : DownloadsRepository {
    companion object {
        private const val LOG_TAG = "DownloadsRepository"
    }

    private data class DownloadBinding(
        val authMode: DownloadAuthMode,
        val boundAccountKeyHash: String?,
    )

    private val activeStatuses = setOf(
        DownloadStatus.Queued,
        DownloadStatus.Running,
        DownloadStatus.Paused,
    )
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val downloads: Flow<List<DownloadTaskEntity>> = downloadTaskDao.observeAll()

    init {
        repositoryScope.launch {
            sanitizeLegacyTaskBindings()
        }
    }

    override suspend fun enqueue(item: WorkshopItem): Result<Unit> {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "enqueue_requested",
            taskId = item.publishedFileId.takeIf { it > 0L }?.let { "pf:$it" },
            fields = mapOf(
                "publishedFileId" to item.publishedFileId.toString(),
                "appId" to item.appId.toString(),
                "canDownload" to item.canDownload.toString(),
            ),
        )
        AppLog.i(
            LOG_TAG,
            "enqueue requested publishedFileId=${item.publishedFileId} appId=${item.appId} canDownload=${item.canDownload}",
        )
        val preparedItem = prepareItemForDownload(item)

        if (!preparedItem.canDownload && preparedItem.hasChildItems) {
            preparedItem.childPublishedFileIds
                .distinct()
                .filter { it > 0L }
                .forEach { publishedFileId ->
                    val childItem = steamRepository.resolveWorkshopItemForDownload(publishedFileId).getOrThrow()
                    enqueue(childItem).getOrThrow()
                }
            return Result.success(Unit)
        }

        if (!preparedItem.canDownload) {
            supportDiagnosticsStore.recordDownloadEvent(
                action = "enqueue_unavailable",
                taskId = preparedItem.publishedFileId.takeIf { it > 0L }?.let { "pf:$it" },
                fields = mapOf(
                    "publishedFileId" to preparedItem.publishedFileId.toString(),
                    "reason" to "cannot_download",
                ),
            )
            AppLog.w(
                LOG_TAG,
                "enqueue rejected because item is unavailable publishedFileId=${preparedItem.publishedFileId}",
            )
            val now = System.currentTimeMillis()
            downloadTaskDao.upsert(
                DownloadTaskEntity(
                    taskId = "unavailable-${preparedItem.publishedFileId}",
                    publishedFileId = preparedItem.publishedFileId,
                    appId = preparedItem.appId,
                    title = preparedItem.title,
                    previewUrl = preparedItem.previewUrl,
                    sourceUrl = preparedItem.fileUrl ?: "steam://publishedfile/${preparedItem.publishedFileId}",
                    fileName = preparedItem.fileName ?: sanitizeFileName(preparedItem.title),
                    downloadFolderName = null,
                    targetTreeUri = null,
                    storageRootRef = null,
                    destinationLabel = "不可下载",
                    downloadAuthMode = DownloadAuthMode.Anonymous,
                    boundAccountKeyHash = null,
                    boundAccountName = null,
                    boundSteamId64 = null,
                    runtimeRouteLabel = null,
                    runtimeTransportLabel = null,
                    runtimeEndpointLabel = null,
                    runtimeSourceAddress = null,
                    runtimeAttemptCount = 0,
                    runtimeChunkConcurrency = 0,
                    runtimeLastFailure = null,
                    status = DownloadStatus.Unavailable,
                    pauseRequested = false,
                    progressPercent = 0,
                    bytesDownloaded = 0,
                    totalBytes = preparedItem.fileSize,
                    savedFileUri = null,
                    errorMessage = "该条目既没有公开直链，也没有可用的 Steam 内容 manifest",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return Result.failure(IllegalStateException("该条目当前不可下载"))
        }

        cancelActiveDownloads(preparedItem.publishedFileId)

        val prefs = preferencesStore.snapshot()
        val taskId = UUID.randomUUID().toString()
        val fileName = sanitizeFileName(
            preparedItem.fileName ?: preparedItem.title,
            "workshop-${preparedItem.publishedFileId}",
        )
        val downloadFolderName = prefs.downloadFolderName
        val session = steamRepository.sessionState.value
        val allowLoggedInDownload =
            prefs.isLoginFeatureEnabled &&
                prefs.isLoggedInDownloadEnabled &&
                steamRepository.isAuthenticatedDownloadReady()
        val authMode = when {
            !allowLoggedInDownload -> DownloadAuthMode.Anonymous
            prefs.preferAnonymousDownloads -> DownloadAuthMode.Auto
            else -> DownloadAuthMode.Authenticated
        }
        val boundAccountKeyHash = if (authMode == DownloadAuthMode.Anonymous) {
            null
        } else {
            buildAccountBindingHash(
                accountName = session.account?.accountName,
                steamId64 = session.account?.steamId64,
            )
        }
        val now = System.currentTimeMillis()

        downloadTaskDao.upsert(
            DownloadTaskEntity(
                taskId = taskId,
                publishedFileId = preparedItem.publishedFileId,
                appId = preparedItem.appId,
                title = preparedItem.title,
                previewUrl = preparedItem.previewUrl,
                sourceUrl = preparedItem.fileUrl ?: "steam://publishedfile/${preparedItem.publishedFileId}",
                fileName = fileName,
                downloadFolderName = downloadFolderName,
                targetTreeUri = prefs.downloadTreeUri,
                storageRootRef = null,
                destinationLabel = buildDownloadDestinationLabel(
                    treeLabel = prefs.downloadTreeLabel,
                    folderName = downloadFolderName,
                ),
                downloadAuthMode = authMode,
                boundAccountKeyHash = boundAccountKeyHash,
                boundAccountName = null,
                boundSteamId64 = null,
                runtimeRouteLabel = null,
                runtimeTransportLabel = null,
                runtimeEndpointLabel = null,
                runtimeSourceAddress = null,
                runtimeAttemptCount = 0,
                runtimeChunkConcurrency = 0,
                runtimeLastFailure = null,
                status = DownloadStatus.Queued,
                pauseRequested = false,
                progressPercent = 0,
                bytesDownloaded = 0,
                totalBytes = preparedItem.fileSize,
                savedFileUri = null,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        supportDiagnosticsStore.recordDownloadEvent(
            action = "enqueue_scheduled",
            taskId = taskId,
            fields = mapOf(
                "publishedFileId" to preparedItem.publishedFileId.toString(),
                "appId" to preparedItem.appId.toString(),
                "authMode" to authMode.name,
                "hasBindingHash" to (!boundAccountKeyHash.isNullOrBlank()).toString(),
            ),
        )

        workManager.enqueueUniqueWork(
            taskId,
            ExistingWorkPolicy.REPLACE,
            buildWorkRequest(
                taskId = taskId,
                url = preparedItem.fileUrl,
                fileName = fileName,
                title = preparedItem.title,
                targetTreeUri = prefs.downloadTreeUri,
                downloadFolderName = downloadFolderName,
                appId = preparedItem.appId,
                publishedFileId = preparedItem.publishedFileId,
                contentManifestId = preparedItem.contentManifestId,
                downloadAuthMode = authMode,
                boundAccountKeyHash = boundAccountKeyHash,
            ),
        )
        AppLog.i(
            LOG_TAG,
            "enqueue scheduled taskId=$taskId publishedFileId=${preparedItem.publishedFileId} authMode=$authMode",
        )
        return Result.success(Unit)
    }

    override suspend fun retry(taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "retry_requested",
            taskId = taskId,
        )
        val existing = downloadTaskDao.getById(taskId)
            ?: return@withContext Result.failure(IllegalArgumentException("下载任务不存在"))

        if (existing.publishedFileId <= 0L) {
            return@withContext Result.failure(IllegalStateException("当前任务没有可重试的 Workshop 条目标识"))
        }

        runCatching {
            val latestItem = steamRepository.resolveWorkshopItemForDownload(existing.publishedFileId).getOrThrow()
            requeueExistingTask(existing, latestItem)
        }
    }

    override suspend fun pause(taskId: String) = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "pause_requested",
            taskId = taskId,
        )
        val existing = downloadTaskDao.getById(taskId) ?: return@withContext
        if (existing.status != DownloadStatus.Queued && existing.status != DownloadStatus.Running) {
            return@withContext
        }
        val now = System.currentTimeMillis()
        if (existing.status == DownloadStatus.Queued) {
            workManager.cancelUniqueWork(taskId)
        }
        downloadTaskDao.upsert(
            existing.copy(
                status = DownloadStatus.Paused,
                pauseRequested = existing.status == DownloadStatus.Running,
                errorMessage = if (existing.status == DownloadStatus.Running) {
                    "正在暂停…"
                } else {
                    "已暂停"
                },
                updatedAt = now,
            ),
        )
    }

    override suspend fun resume(taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "resume_requested",
            taskId = taskId,
        )
        val existing = downloadTaskDao.getById(taskId)
            ?: return@withContext Result.failure(IllegalArgumentException("下载任务不存在"))
        if (existing.status != DownloadStatus.Paused) {
            return@withContext Result.failure(IllegalStateException("当前任务不是暂停状态"))
        }
        if (existing.pauseRequested) {
            return@withContext Result.failure(IllegalStateException("正在暂停，请稍候"))
        }
        val latestItem = runCatching {
            steamRepository.resolveWorkshopItemForDownload(existing.publishedFileId).getOrThrow()
        }.getOrNull()

        downloadTaskDao.upsert(
            existing.copy(
                status = DownloadStatus.Queued,
                boundAccountKeyHash = resolveAccountBindingHash(
                    existing.boundAccountKeyHash,
                    existing.boundAccountName,
                    existing.boundSteamId64,
                ),
                boundAccountName = null,
                boundSteamId64 = null,
                errorMessage = null,
                pauseRequested = false,
                runtimeRouteLabel = null,
                runtimeTransportLabel = null,
                runtimeEndpointLabel = null,
                runtimeSourceAddress = null,
                runtimeAttemptCount = 0,
                runtimeChunkConcurrency = 0,
                runtimeLastFailure = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        workManager.enqueueUniqueWork(
            taskId,
            ExistingWorkPolicy.REPLACE,
            buildWorkRequest(
                taskId = existing.taskId,
                url = latestItem?.fileUrl ?: existing.sourceUrl.takeUnless { it.startsWith("steam://") },
                fileName = existing.fileName,
                title = existing.title,
                targetTreeUri = existing.targetTreeUri,
                downloadFolderName = existing.downloadFolderName,
                appId = latestItem?.appId?.takeIf { it > 0 } ?: existing.appId,
                publishedFileId = existing.publishedFileId,
                contentManifestId = latestItem?.contentManifestId ?: 0L,
                downloadAuthMode = existing.downloadAuthMode,
                boundAccountKeyHash = resolveAccountBindingHash(
                    existing.boundAccountKeyHash,
                    existing.boundAccountName,
                    existing.boundSteamId64,
                ),
            ),
        )

        Result.success(Unit)
    }

    override suspend fun cancel(taskId: String) {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "cancel_requested",
            taskId = taskId,
        )
        AppLog.i(LOG_TAG, "cancel requested taskId=$taskId")
        workManager.cancelUniqueWork(taskId)
        val existing = downloadTaskDao.getById(taskId) ?: return
        downloadTaskDao.finish(
            taskId = taskId,
            status = DownloadStatus.Cancelled,
            savedFileUri = existing.savedFileUri,
            errorMessage = "已取消",
            progressPercent = existing.progressPercent,
            bytesDownloaded = existing.bytesDownloaded,
            totalBytes = existing.totalBytes,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun delete(taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "delete_requested",
            taskId = taskId,
        )
        val existing = downloadTaskDao.getById(taskId)
            ?: return@withContext Result.failure(IllegalArgumentException("下载任务不存在"))
        if (
            existing.status == DownloadStatus.Queued ||
            existing.status == DownloadStatus.Running ||
            existing.status == DownloadStatus.Paused
        ) {
            return@withContext Result.failure(IllegalStateException("进行中的任务请先暂停或取消"))
        }
        workManager.cancelUniqueWork(taskId)
        downloadTaskDao.deleteById(taskId)
        AppLog.i(LOG_TAG, "delete completed taskId=$taskId status=${existing.status}")
        Result.success(Unit)
    }

    override suspend fun clearInactiveHistory(): Int = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordDownloadEvent(action = "clear_inactive_history")
        downloadTaskDao.clearInactiveTasks()
    }

    override suspend fun clearInactiveDiagnostics(): Int = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordDownloadEvent(action = "clear_inactive_diagnostics")
        downloadTaskDao.clearInactiveRuntimeDetails(updatedAt = System.currentTimeMillis())
    }

    override suspend fun rebindRetryableTasksToCurrentSession() = withContext(Dispatchers.IO) {
        val binding = currentAuthenticatedBinding() ?: return@withContext
        supportDiagnosticsStore.recordDownloadEvent(
            action = "rebind_retryable_tasks",
            fields = mapOf(
                "authMode" to binding.authMode.name,
                "hasBindingHash" to (!binding.boundAccountKeyHash.isNullOrBlank()).toString(),
            ),
        )
        val now = System.currentTimeMillis()
        downloadTaskDao.getAll()
            .filter { task ->
                    task.publishedFileId > 0L &&
                    task.status != DownloadStatus.Running &&
                    task.status != DownloadStatus.Success &&
                    (
                        task.downloadAuthMode != binding.authMode ||
                            resolveAccountBindingHash(
                                task.boundAccountKeyHash,
                                task.boundAccountName,
                                task.boundSteamId64,
                            ) != binding.boundAccountKeyHash
                    )
            }
            .forEach { task ->
                downloadTaskDao.upsert(
                    task.copy(
                        downloadAuthMode = binding.authMode,
                        boundAccountKeyHash = binding.boundAccountKeyHash,
                        boundAccountName = null,
                        boundSteamId64 = null,
                        updatedAt = now,
                    ),
                )
            }
    }

    override suspend fun enforceAnonymousOnly(reason: String) = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "enforce_anonymous_only",
            fields = mapOf("reason" to reason),
        )
        val now = System.currentTimeMillis()
        downloadTaskDao.getAll().forEach { task ->
            if (task.downloadAuthMode == DownloadAuthMode.Anonymous &&
                task.boundAccountKeyHash.isNullOrBlank() &&
                task.boundAccountName.isNullOrBlank() &&
                task.boundSteamId64 == null
            ) {
                return@forEach
            }

            if (task.status in activeStatuses) {
                workManager.cancelUniqueWork(task.taskId)
            }

            downloadTaskDao.upsert(
                task.copy(
                    sourceUrl = if (task.publishedFileId > 0L) {
                        "steam://publishedfile/${task.publishedFileId}"
                    } else {
                        task.sourceUrl
                    },
                    targetTreeUri = if (task.status in activeStatuses) null else task.targetTreeUri,
                    storageRootRef = null,
                    downloadAuthMode = DownloadAuthMode.Anonymous,
                    boundAccountKeyHash = null,
                    boundAccountName = null,
                    boundSteamId64 = null,
                    runtimeRouteLabel = null,
                    runtimeTransportLabel = null,
                    runtimeEndpointLabel = null,
                    runtimeSourceAddress = null,
                    runtimeAttemptCount = 0,
                    runtimeChunkConcurrency = 0,
                    runtimeLastFailure = null,
                    status = if (task.status in activeStatuses) DownloadStatus.Cancelled else task.status,
                    pauseRequested = false,
                    errorMessage = if (task.status in activeStatuses) reason else task.errorMessage,
                    updatedAt = now,
                ),
            )
        }
    }

    override suspend fun reconcileActiveTasks() = withContext(Dispatchers.IO) {
        downloadTaskDao.getActiveTasks().forEach { task ->
            val infos = runCatching { workManager.getWorkInfosForUniqueWork(task.taskId).get() }
                .getOrDefault(emptyList())

            when {
                infos.isEmpty() -> markTaskInterrupted(task, "下载任务已丢失，请重新加入队列")
                infos.any { it.state == WorkInfo.State.RUNNING } -> Unit
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> {
                    if (task.status != DownloadStatus.Queued) {
                        downloadTaskDao.updateProgress(
                            taskId = task.taskId,
                            status = DownloadStatus.Queued,
                            pauseRequested = false,
                            errorMessage = null,
                            progressPercent = task.progressPercent,
                            bytesDownloaded = task.bytesDownloaded,
                            totalBytes = task.totalBytes,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                }
                infos.any { it.state == WorkInfo.State.CANCELLED } -> {
                    val latest = downloadTaskDao.getById(task.taskId) ?: task
                    val cancelledStatus = if (latest.status == DownloadStatus.Paused || latest.pauseRequested) {
                        DownloadStatus.Paused
                    } else {
                        DownloadStatus.Cancelled
                    }
                    downloadTaskDao.finish(
                        taskId = task.taskId,
                        status = cancelledStatus,
                        savedFileUri = latest.savedFileUri,
                        errorMessage = latest.errorMessage ?: if (cancelledStatus == DownloadStatus.Paused) "已暂停" else "已取消",
                        progressPercent = latest.progressPercent,
                        bytesDownloaded = latest.bytesDownloaded,
                        totalBytes = latest.totalBytes,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                infos.any { it.state == WorkInfo.State.FAILED } -> {
                    markTaskInterrupted(task, task.errorMessage ?: "下载失败，请重新加入队列")
                }
                infos.any { it.state == WorkInfo.State.SUCCEEDED } -> Unit
                else -> markTaskInterrupted(task, "下载状态异常，请重新加入队列")
            }
        }
    }

    private suspend fun cancelActiveDownloads(publishedFileId: Long) {
        downloadTaskDao.getActiveByPublishedFileId(publishedFileId).forEach { existing ->
            workManager.cancelUniqueWork(existing.taskId)
            downloadTaskDao.finish(
                taskId = existing.taskId,
                status = DownloadStatus.Cancelled,
                savedFileUri = existing.savedFileUri,
                errorMessage = "已被新的下载请求替换",
                progressPercent = existing.progressPercent,
                bytesDownloaded = existing.bytesDownloaded,
                totalBytes = existing.totalBytes,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun markTaskInterrupted(
        task: DownloadTaskEntity,
        message: String,
    ) {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "task_interrupted",
            taskId = task.taskId,
            fields = mapOf(
                "publishedFileId" to task.publishedFileId.toString(),
                "message" to message,
            ),
        )
        AppLog.w(
            LOG_TAG,
            "markTaskInterrupted taskId=${task.taskId} publishedFileId=${task.publishedFileId} message=$message",
        )
        downloadTaskDao.finish(
            taskId = task.taskId,
            status = DownloadStatus.Failed,
            savedFileUri = task.savedFileUri,
            errorMessage = message,
            progressPercent = task.progressPercent,
            bytesDownloaded = task.bytesDownloaded,
            totalBytes = task.totalBytes,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun sanitizeLegacyTaskBindings() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        downloadTaskDao.getAll().forEach { task ->
            val normalizedHash = when {
                task.downloadAuthMode == DownloadAuthMode.Anonymous -> null
                task.status !in activeStatuses -> null
                else -> resolveAccountBindingHash(
                    task.boundAccountKeyHash,
                    task.boundAccountName,
                    task.boundSteamId64,
                )
            }
            val alreadySanitized =
                task.boundAccountName.isNullOrBlank() &&
                    task.boundSteamId64 == null &&
                    task.boundAccountKeyHash == normalizedHash
            if (alreadySanitized) return@forEach
            downloadTaskDao.upsert(
                task.copy(
                    boundAccountKeyHash = normalizedHash,
                    boundAccountName = null,
                    boundSteamId64 = null,
                    updatedAt = now,
                ),
            )
        }
    }

    private suspend fun requeueExistingTask(
        existing: DownloadTaskEntity,
        latestItem: WorkshopItem,
    ) {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "requeue_existing_task",
            taskId = existing.taskId,
            fields = mapOf(
                "publishedFileId" to existing.publishedFileId.toString(),
                "latestCanDownload" to latestItem.canDownload.toString(),
            ),
        )
        removeDuplicateEntries(existing)

        if (!latestItem.canDownload) {
            downloadTaskDao.upsert(
                existing.copy(
                    title = latestItem.title,
                    previewUrl = latestItem.previewUrl,
                    sourceUrl = latestItem.fileUrl ?: "steam://publishedfile/${latestItem.publishedFileId}",
                    runtimeRouteLabel = null,
                    runtimeTransportLabel = null,
                    runtimeEndpointLabel = null,
                    runtimeSourceAddress = null,
                    runtimeAttemptCount = 0,
                    runtimeChunkConcurrency = 0,
                    runtimeLastFailure = null,
                    status = DownloadStatus.Unavailable,
                    pauseRequested = false,
                    errorMessage = "该条目当前不可下载",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            error("该条目当前不可下载")
        }

        val fileName = sanitizeFileName(
            latestItem.fileName ?: existing.fileName.ifBlank { latestItem.title },
            "workshop-${latestItem.publishedFileId}",
        )
        val binding = currentAuthenticatedBinding() ?: DownloadBinding(
            authMode = DownloadAuthMode.Anonymous,
            boundAccountKeyHash = null,
        )

        downloadTaskDao.upsert(
            existing.copy(
                publishedFileId = latestItem.publishedFileId,
                appId = latestItem.appId,
                title = latestItem.title,
                previewUrl = latestItem.previewUrl,
                sourceUrl = latestItem.fileUrl ?: "steam://publishedfile/${latestItem.publishedFileId}",
                fileName = fileName,
                totalBytes = latestItem.fileSize.takeIf { it > 0L } ?: existing.totalBytes,
                downloadAuthMode = binding.authMode,
                boundAccountKeyHash = binding.boundAccountKeyHash,
                boundAccountName = null,
                boundSteamId64 = null,
                runtimeRouteLabel = null,
                runtimeTransportLabel = null,
                runtimeEndpointLabel = null,
                runtimeSourceAddress = null,
                runtimeAttemptCount = 0,
                runtimeChunkConcurrency = 0,
                runtimeLastFailure = null,
                status = DownloadStatus.Queued,
                pauseRequested = false,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        workManager.enqueueUniqueWork(
            existing.taskId,
            ExistingWorkPolicy.REPLACE,
            buildWorkRequest(
                taskId = existing.taskId,
                url = latestItem.fileUrl,
                fileName = fileName,
                title = latestItem.title,
                targetTreeUri = existing.targetTreeUri,
                downloadFolderName = existing.downloadFolderName,
                appId = latestItem.appId,
                publishedFileId = latestItem.publishedFileId,
                contentManifestId = latestItem.contentManifestId,
                downloadAuthMode = binding.authMode,
                boundAccountKeyHash = binding.boundAccountKeyHash,
            ),
        )
    }

    private suspend fun currentAuthenticatedBinding(): DownloadBinding? {
        val session = steamRepository.sessionState.value
        if (session.status != SessionStatus.Authenticated) return null
        val prefs = preferencesStore.snapshot()
        if (!prefs.isLoginFeatureEnabled || !prefs.isLoggedInDownloadEnabled) return null
        if (!steamRepository.isAuthenticatedDownloadReady()) return null
        val authMode = if (prefs.preferAnonymousDownloads) {
            DownloadAuthMode.Auto
        } else {
            DownloadAuthMode.Authenticated
        }
        return DownloadBinding(
            authMode = authMode,
            boundAccountKeyHash = buildAccountBindingHash(
                accountName = session.account?.accountName,
                steamId64 = session.account?.steamId64,
            ),
        )
    }

    private suspend fun prepareItemForDownload(item: WorkshopItem): WorkshopItem {
        if (!item.needsDownloadPreparation()) return item
        supportDiagnosticsStore.recordDownloadEvent(
            action = "prepare_item_for_download",
            taskId = item.publishedFileId.takeIf { it > 0L }?.let { "pf:$it" },
            fields = mapOf("publishedFileId" to item.publishedFileId.toString()),
        )
        return runCatching {
            steamRepository.resolveWorkshopItemForDownload(item.publishedFileId).getOrThrow()
        }.getOrElse { item }
    }

    private fun WorkshopItem.needsDownloadPreparation(): Boolean {
        return publishedFileId > 0L &&
            (!isDownloadInfoResolved || !canDownload || hasChildItems)
    }

    private suspend fun removeDuplicateEntries(existing: DownloadTaskEntity) {
        downloadTaskDao.getByPublishedFileIdExcludingTask(
            publishedFileId = existing.publishedFileId,
            keepTaskId = existing.taskId,
        ).forEach { duplicate ->
            AppLog.i(
                LOG_TAG,
                "removeDuplicateEntries removing taskId=${duplicate.taskId} publishedFileId=${duplicate.publishedFileId}",
            )
            if (
                duplicate.status == DownloadStatus.Queued ||
                duplicate.status == DownloadStatus.Running ||
                duplicate.status == DownloadStatus.Paused
            ) {
                workManager.cancelUniqueWork(duplicate.taskId)
            }
            downloadTaskDao.deleteById(duplicate.taskId)
        }
    }

    private fun buildWorkRequest(
        taskId: String,
        url: String?,
        fileName: String,
        title: String,
        targetTreeUri: String?,
        downloadFolderName: String?,
        appId: Int,
        publishedFileId: Long,
        contentManifestId: Long,
        downloadAuthMode: DownloadAuthMode,
        boundAccountKeyHash: String?,
    ) = OneTimeWorkRequestBuilder<WorkshopDownloadWorker>()
        .setInputData(
            Data.Builder()
                .putString(WorkshopDownloadWorker.KEY_TASK_ID, taskId)
                .putString(WorkshopDownloadWorker.KEY_URL, url)
                .putString(WorkshopDownloadWorker.KEY_FILE_NAME, fileName)
                .putString(WorkshopDownloadWorker.KEY_TITLE, title)
                .putString(WorkshopDownloadWorker.KEY_TARGET_TREE_URI, targetTreeUri)
                .putString(WorkshopDownloadWorker.KEY_DOWNLOAD_FOLDER_NAME, downloadFolderName)
                .putInt(WorkshopDownloadWorker.KEY_APP_ID, appId)
                .putLong(WorkshopDownloadWorker.KEY_PUBLISHED_FILE_ID, publishedFileId)
                .putLong(WorkshopDownloadWorker.KEY_CONTENT_MANIFEST_ID, contentManifestId)
                .putString(WorkshopDownloadWorker.KEY_DOWNLOAD_AUTH_MODE, downloadAuthMode.name)
                .putString(WorkshopDownloadWorker.KEY_BOUND_ACCOUNT_KEY_HASH, boundAccountKeyHash)
                .build(),
        )
        .addTag(WorkshopDownloadWorker.TAG_DOWNLOAD)
        .build()
}
