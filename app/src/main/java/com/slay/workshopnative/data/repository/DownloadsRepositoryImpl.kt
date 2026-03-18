package com.slay.workshopnative.data.repository

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.core.storage.buildDownloadDestinationLabel
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class DownloadsRepositoryImpl @Inject constructor(
    private val workManager: WorkManager,
    private val downloadTaskDao: DownloadTaskDao,
    private val preferencesStore: UserPreferencesStore,
    private val steamRepository: SteamRepository,
) : DownloadsRepository {
    companion object {
        private const val LOG_TAG = "DownloadsRepository"
    }

    private data class DownloadBinding(
        val authMode: DownloadAuthMode,
        val boundAccountName: String?,
        val boundSteamId64: Long?,
    )

    override val downloads: Flow<List<DownloadTaskEntity>> = downloadTaskDao.observeAll()

    override suspend fun enqueue(item: WorkshopItem): Result<Unit> {
        AppLog.i(
            LOG_TAG,
            "enqueue requested publishedFileId=${item.publishedFileId} appId=${item.appId} canDownload=${item.canDownload}",
        )
        if (!item.canDownload && item.hasChildItems) {
            item.childPublishedFileIds
                .distinct()
                .filter { it > 0L }
                .forEach { publishedFileId ->
                    val childItem = steamRepository.resolveWorkshopItem(publishedFileId).getOrThrow()
                    enqueue(childItem).getOrThrow()
                }
            return Result.success(Unit)
        }

        if (!item.canDownload) {
            AppLog.w(
                LOG_TAG,
                "enqueue rejected because item is unavailable publishedFileId=${item.publishedFileId}",
            )
            val now = System.currentTimeMillis()
            downloadTaskDao.upsert(
                DownloadTaskEntity(
                    taskId = "unavailable-${item.publishedFileId}",
                    publishedFileId = item.publishedFileId,
                    appId = item.appId,
                    title = item.title,
                    previewUrl = item.previewUrl,
                    sourceUrl = item.fileUrl ?: "steam://publishedfile/${item.publishedFileId}",
                    fileName = item.fileName ?: sanitizeFileName(item.title),
                    downloadFolderName = null,
                    targetTreeUri = null,
                    storageRootRef = null,
                    destinationLabel = "不可下载",
                    downloadAuthMode = DownloadAuthMode.Anonymous,
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
                    totalBytes = item.fileSize,
                    savedFileUri = null,
                    errorMessage = "该条目既没有公开直链，也没有可用的 Steam 内容 manifest",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return Result.failure(IllegalStateException("该条目当前不可下载"))
        }

        cancelActiveDownloads(item.publishedFileId)

        val prefs = preferencesStore.snapshot()
        val taskId = UUID.randomUUID().toString()
        val fileName = sanitizeFileName(item.fileName ?: item.title, "workshop-${item.publishedFileId}")
        val downloadFolderName = prefs.downloadFolderName
        val session = steamRepository.sessionState.value
        val authMode = when {
            session.status != SessionStatus.Authenticated -> DownloadAuthMode.Anonymous
            prefs.preferAnonymousDownloads -> DownloadAuthMode.Auto
            else -> DownloadAuthMode.Authenticated
        }
        val boundAccountName = if (authMode == DownloadAuthMode.Anonymous) null else session.account?.accountName
        val boundSteamId64 = if (authMode == DownloadAuthMode.Anonymous) null else session.account?.steamId64
        val now = System.currentTimeMillis()

        downloadTaskDao.upsert(
            DownloadTaskEntity(
                taskId = taskId,
                publishedFileId = item.publishedFileId,
                appId = item.appId,
                title = item.title,
                previewUrl = item.previewUrl,
                sourceUrl = item.fileUrl ?: "steam://publishedfile/${item.publishedFileId}",
                fileName = fileName,
                downloadFolderName = downloadFolderName,
                targetTreeUri = prefs.downloadTreeUri,
                storageRootRef = null,
                destinationLabel = buildDownloadDestinationLabel(
                    treeLabel = prefs.downloadTreeLabel,
                    folderName = downloadFolderName,
                ),
                downloadAuthMode = authMode,
                boundAccountName = boundAccountName,
                boundSteamId64 = boundSteamId64,
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
                totalBytes = item.fileSize,
                savedFileUri = null,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
            ),
        )

        workManager.enqueueUniqueWork(
            taskId,
            ExistingWorkPolicy.REPLACE,
            buildWorkRequest(
                taskId = taskId,
                url = item.fileUrl,
                fileName = fileName,
                title = item.title,
                targetTreeUri = prefs.downloadTreeUri,
                downloadFolderName = downloadFolderName,
                appId = item.appId,
                publishedFileId = item.publishedFileId,
                contentManifestId = item.contentManifestId,
                downloadAuthMode = authMode,
                boundAccountName = boundAccountName,
                boundSteamId64 = boundSteamId64,
            ),
        )
        AppLog.i(
            LOG_TAG,
            "enqueue scheduled taskId=$taskId publishedFileId=${item.publishedFileId} authMode=$authMode",
        )
        return Result.success(Unit)
    }

    override suspend fun retry(taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val existing = downloadTaskDao.getById(taskId)
            ?: return@withContext Result.failure(IllegalArgumentException("下载任务不存在"))

        if (existing.publishedFileId <= 0L) {
            return@withContext Result.failure(IllegalStateException("当前任务没有可重试的 Workshop 条目标识"))
        }

        runCatching {
            val latestItem = steamRepository.resolveWorkshopItem(existing.publishedFileId).getOrThrow()
            requeueExistingTask(existing, latestItem)
        }
    }

    override suspend fun pause(taskId: String) = withContext(Dispatchers.IO) {
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
        val existing = downloadTaskDao.getById(taskId)
            ?: return@withContext Result.failure(IllegalArgumentException("下载任务不存在"))
        if (existing.status != DownloadStatus.Paused) {
            return@withContext Result.failure(IllegalStateException("当前任务不是暂停状态"))
        }
        if (existing.pauseRequested) {
            return@withContext Result.failure(IllegalStateException("正在暂停，请稍候"))
        }
        val latestItem = runCatching {
            steamRepository.resolveWorkshopItem(existing.publishedFileId).getOrThrow()
        }.getOrNull()

        downloadTaskDao.upsert(
            existing.copy(
                status = DownloadStatus.Queued,
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
                boundAccountName = existing.boundAccountName,
                boundSteamId64 = existing.boundSteamId64,
            ),
        )

        Result.success(Unit)
    }

    override suspend fun cancel(taskId: String) {
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
        downloadTaskDao.clearInactiveTasks()
    }

    override suspend fun clearInactiveDiagnostics(): Int = withContext(Dispatchers.IO) {
        downloadTaskDao.clearInactiveRuntimeDetails(updatedAt = System.currentTimeMillis())
    }

    override suspend fun rebindRetryableTasksToCurrentSession() = withContext(Dispatchers.IO) {
        val binding = currentAuthenticatedBinding() ?: return@withContext
        val now = System.currentTimeMillis()
        downloadTaskDao.getAll()
            .filter { task ->
                task.publishedFileId > 0L &&
                    task.status != DownloadStatus.Running &&
                    task.status != DownloadStatus.Success &&
                    (
                        task.downloadAuthMode != binding.authMode ||
                            task.boundSteamId64 != binding.boundSteamId64 ||
                            task.boundAccountName != binding.boundAccountName
                    )
            }
            .forEach { task ->
                downloadTaskDao.upsert(
                    task.copy(
                        downloadAuthMode = binding.authMode,
                        boundAccountName = binding.boundAccountName,
                        boundSteamId64 = binding.boundSteamId64,
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

    private suspend fun requeueExistingTask(
        existing: DownloadTaskEntity,
        latestItem: WorkshopItem,
    ) {
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
            authMode = existing.downloadAuthMode,
            boundAccountName = existing.boundAccountName,
            boundSteamId64 = existing.boundSteamId64,
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
                boundAccountName = binding.boundAccountName,
                boundSteamId64 = binding.boundSteamId64,
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
                boundAccountName = binding.boundAccountName,
                boundSteamId64 = binding.boundSteamId64,
            ),
        )
    }

    private suspend fun currentAuthenticatedBinding(): DownloadBinding? {
        val session = steamRepository.sessionState.value
        if (session.status != SessionStatus.Authenticated) return null
        val prefs = preferencesStore.snapshot()
        val authMode = if (prefs.preferAnonymousDownloads) {
            DownloadAuthMode.Auto
        } else {
            DownloadAuthMode.Authenticated
        }
        return DownloadBinding(
            authMode = authMode,
            boundAccountName = session.account?.accountName,
            boundSteamId64 = session.account?.steamId64,
        )
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
        boundAccountName: String?,
        boundSteamId64: Long?,
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
                .putString(WorkshopDownloadWorker.KEY_BOUND_ACCOUNT_NAME, boundAccountName)
                .putLong(WorkshopDownloadWorker.KEY_BOUND_STEAM_ID64, boundSteamId64 ?: 0L)
                .build(),
        )
        .addTag(WorkshopDownloadWorker.TAG_DOWNLOAD)
        .build()
}
