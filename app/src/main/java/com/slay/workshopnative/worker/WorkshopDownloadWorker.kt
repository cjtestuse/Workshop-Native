package com.slay.workshopnative.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.slay.workshopnative.core.logging.AppLog as Log
import com.slay.workshopnative.core.storage.clearDownloadStaging
import com.slay.workshopnative.core.storage.copyLocalFileToUri
import com.slay.workshopnative.core.storage.createMediaStoreFileUri
import com.slay.workshopnative.core.storage.directDownloadStagingFile
import com.slay.workshopnative.core.storage.finalizeMediaStoreFile
import com.slay.workshopnative.core.util.DownloadPausedException
import com.slay.workshopnative.core.util.toUserMessage
import com.slay.workshopnative.data.local.DownloadAuthMode
import com.slay.workshopnative.data.local.DownloadStatus
import com.slay.workshopnative.data.local.DownloadTaskDao
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.model.WorkshopItem
import com.slay.workshopnative.data.remote.SteamSessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@HiltWorker
class WorkshopDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadTaskDao: DownloadTaskDao,
    private val okHttpClient: OkHttpClient,
    private val steamSessionManager: SteamSessionManager,
) : CoroutineWorker(appContext, workerParams) {

    private data class RuntimeInfoState(
        val routeLabel: String? = null,
        val transportLabel: String? = null,
        val endpointLabel: String? = null,
        val sourceAddress: String? = null,
        val attemptCount: Int = 0,
        val chunkConcurrency: Int = 0,
        val lastFailure: String? = null,
    )

    companion object {
        private const val LOG_TAG = "WorkshopDownloadWorker"
        const val TAG_DOWNLOAD = "workshop_download"
        const val CHANNEL_ID = "workshop_downloads"
        const val KEY_TASK_ID = "task_id"
        const val KEY_URL = "download_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_TITLE = "title"
        const val KEY_TARGET_TREE_URI = "target_tree_uri"
        const val KEY_DOWNLOAD_FOLDER_NAME = "download_folder_name"
        const val KEY_APP_ID = "app_id"
        const val KEY_PUBLISHED_FILE_ID = "published_file_id"
        const val KEY_CONTENT_MANIFEST_ID = "content_manifest_id"
        const val KEY_DOWNLOAD_AUTH_MODE = "download_auth_mode"
        const val KEY_BOUND_ACCOUNT_NAME = "bound_account_name"
        const val KEY_BOUND_STEAM_ID64 = "bound_steam_id64"
        private const val DIRECT_STREAM_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val PROGRESS_UPDATE_BYTES_STEP = 512L * 1024L
        private const val PROGRESS_UPDATE_PERCENT_STEP = 1
        private const val PAUSE_POLL_INTERVAL_MS = 250L
    }

    private var runtimeInfoState = RuntimeInfoState()
    private var lastForegroundProgressPercent = Int.MIN_VALUE
    private var lastForegroundPhaseMessage: String? = null
    private lateinit var pauseStatePoller: PauseStatePoller

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        ensureChannel()

        val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        val url = inputData.getString(KEY_URL).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "workshop-item"
        val title = inputData.getString(KEY_TITLE) ?: fileName
        val targetTreeUri = inputData.getString(KEY_TARGET_TREE_URI)
        val downloadFolderName = inputData.getString(KEY_DOWNLOAD_FOLDER_NAME)
        val appId = inputData.getInt(KEY_APP_ID, 0)
        val publishedFileId = inputData.getLong(KEY_PUBLISHED_FILE_ID, 0L)
        val contentManifestId = inputData.getLong(KEY_CONTENT_MANIFEST_ID, 0L)
        val downloadAuthMode = inputData.getString(KEY_DOWNLOAD_AUTH_MODE)
            ?.let(DownloadAuthMode::valueOf)
            ?: DownloadAuthMode.Anonymous
        val boundAccountName = inputData.getString(KEY_BOUND_ACCOUNT_NAME)
        val boundSteamId64 = inputData.getLong(KEY_BOUND_STEAM_ID64, 0L).takeIf { it > 0L }
        pauseStatePoller = PauseStatePoller(taskId)
        Log.i(
            LOG_TAG,
            "doWork start taskId=$taskId publishedFileId=$publishedFileId appId=$appId hasUrl=${url.isNotBlank()} manifest=$contentManifestId authMode=$downloadAuthMode",
        )

        val existing = downloadTaskDao.getById(taskId) ?: return@withContext Result.failure()
        runtimeInfoState = RuntimeInfoState(
            routeLabel = existing.runtimeRouteLabel,
            transportLabel = existing.runtimeTransportLabel,
            endpointLabel = existing.runtimeEndpointLabel,
            sourceAddress = existing.runtimeSourceAddress,
            attemptCount = existing.runtimeAttemptCount,
            chunkConcurrency = existing.runtimeChunkConcurrency,
            lastFailure = existing.runtimeLastFailure,
        )
        lastForegroundProgressPercent = existing.progressPercent
        lastForegroundPhaseMessage = existing.errorMessage
        downloadTaskDao.upsert(
            existing.copy(
                status = DownloadStatus.Running,
                pauseRequested = false,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        setForeground(createForegroundInfo(title, 0))

        return@withContext runCatching {
            val completed = runBestAvailableDownload(
                taskId = taskId,
                url = url,
                fileName = fileName,
                title = title,
                targetTreeUri = targetTreeUri,
                downloadFolderName = downloadFolderName,
                appId = appId,
                publishedFileId = publishedFileId,
                contentManifestId = contentManifestId,
                downloadAuthMode = downloadAuthMode,
                boundAccountName = boundAccountName,
                boundSteamId64 = boundSteamId64,
                existing = existing,
            )

            Log.i(
                LOG_TAG,
                "doWork success taskId=$taskId publishedFileId=$publishedFileId bytes=${completed.bytesDownloaded}/${completed.totalBytes}",
            )

            downloadTaskDao.finish(
                taskId = taskId,
                status = DownloadStatus.Success,
                savedFileUri = completed.savedUri,
                errorMessage = null,
                progressPercent = 100,
                bytesDownloaded = completed.bytesDownloaded,
                totalBytes = completed.totalBytes,
                updatedAt = System.currentTimeMillis(),
            )
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { throwable ->
                val latest = downloadTaskDao.getById(taskId) ?: existing
                val isPaused = throwable.isPauseSignal() || latest.pauseRequested
                if (isPaused) {
                    Log.i(LOG_TAG, "Download paused taskId=$taskId")
                } else {
                    Log.e(LOG_TAG, "Download failed taskId=$taskId", throwable)
                }
                val status = when {
                    isPaused -> DownloadStatus.Paused
                    isStopped -> DownloadStatus.Cancelled
                    else -> DownloadStatus.Failed
                }
                if (isPaused) {
                    downloadTaskDao.upsert(
                        latest.copy(
                            status = status,
                            pauseRequested = false,
                            errorMessage = "已暂停",
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    downloadTaskDao.finish(
                        taskId = taskId,
                        status = status,
                        savedFileUri = latest.savedFileUri,
                        errorMessage = throwable.toUserMessage("下载失败"),
                        progressPercent = latest.progressPercent,
                        bytesDownloaded = latest.bytesDownloaded,
                        totalBytes = latest.totalBytes,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                if (isPaused) {
                    Result.success()
                } else {
                    Result.failure()
                }
            },
        )
    }

    private suspend fun runBestAvailableDownload(
        taskId: String,
        url: String,
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
        existing: DownloadTaskEntity,
    ): CompletedDownload {
        if (contentManifestId > 0L && appId > 0) {
            val steamResult = runCatching {
                runSteamWorkshopDownload(
                    taskId = taskId,
                    appId = appId,
                    publishedFileId = publishedFileId,
                    contentManifestId = contentManifestId,
                    rootName = fileName,
                    title = title,
                    targetTreeUri = targetTreeUri,
                    downloadFolderName = downloadFolderName,
                    fallbackTotalBytes = existing.totalBytes,
                    existing = existing,
                    downloadAuthMode = downloadAuthMode,
                    boundAccountName = boundAccountName,
                    boundSteamId64 = boundSteamId64,
                )
            }
            if (steamResult.isSuccess) {
                return steamResult.getOrThrow()
            }

            val steamFailure = steamResult.exceptionOrNull()
            if (steamFailure.isPauseSignal() || url.isBlank()) {
                throw (steamFailure ?: IllegalStateException("Steam 内容下载失败"))
            }

            Log.w(
                LOG_TAG,
                "Steam content download failed, fallback to direct url taskId=$taskId publishedFileId=$publishedFileId",
                steamFailure,
            )
            val latest = downloadTaskDao.getById(taskId) ?: existing
            if (latest.storageRootRef != null) {
                downloadTaskDao.upsert(
                    latest.copy(
                        storageRootRef = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            return runDirectDownload(
                taskId = taskId,
                url = url,
                fileName = fileName,
                title = title,
                targetTreeUri = targetTreeUri,
                downloadFolderName = downloadFolderName,
                existing = existing,
            )
        }

        if (url.isNotBlank()) {
            return runDirectDownload(
                taskId = taskId,
                url = url,
                fileName = fileName,
                title = title,
                targetTreeUri = targetTreeUri,
                downloadFolderName = downloadFolderName,
                existing = existing,
            )
        }

        error("该条目没有可用的下载源")
    }

    private suspend fun runDirectDownload(
        taskId: String,
        url: String,
        fileName: String,
        title: String,
        targetTreeUri: String?,
        downloadFolderName: String?,
        existing: DownloadTaskEntity,
    ): CompletedDownload {
        val progressReporter = RunningProgressReporter(taskId = taskId, title = title)
        updateRuntimeInfo(
            taskId = taskId,
            routeLabel = "公开直链下载",
            transportLabel = "系统网络",
            endpointLabel = url.toHttpUrlOrNull()?.let(::formatEndpointLabel) ?: url,
            sourceAddress = url,
            attemptCount = 1,
            chunkConcurrency = 1,
            lastFailure = null,
        )
        val stagingFile = directDownloadStagingFile(
            context = applicationContext,
            taskId = taskId,
            fileName = fileName,
        )
        val resumedBytes = stagingFile
            .takeIf { it.exists() }
            ?.length()
            ?.coerceAtLeast(0L)
            ?: 0L
        val request = Request.Builder()
            .url(url)
            .apply {
                if (resumedBytes > 0L) {
                    header("Range", "bytes=$resumedBytes-")
                }
            }
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
            if (resumedBytes > 0L) {
                check(response.code == 206) { "当前下载源不支持断点续传" }
            }
            val body = response.body ?: error("下载响应为空")
            val totalBytes = if (resumedBytes > 0L) {
                (resumedBytes + body.contentLength().coerceAtLeast(0L)).coerceAtLeast(existing.totalBytes)
            } else {
                body.contentLength().coerceAtLeast(0L)
            }
            if (resumedBytes > 0L) {
                progressReporter.report(
                    bytesDownloaded = resumedBytes,
                    totalBytes = totalBytes,
                    force = true,
                )
            }
            var bytesDownloaded = resumedBytes
            try {
                stagingFile.parentFile?.mkdirs()
                BufferedOutputStream(
                    FileOutputStream(stagingFile, resumedBytes > 0L),
                    DIRECT_STREAM_BUFFER_SIZE,
                ).use { stream ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DIRECT_STREAM_BUFFER_SIZE)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            currentCoroutineContext().ensureActive()
                            if (read > 0) {
                                stream.write(buffer, 0, read)
                                bytesDownloaded += read
                                progressReporter.report(
                                    bytesDownloaded = bytesDownloaded,
                                    totalBytes = totalBytes,
                                )
                                if (pauseStatePoller.shouldPause()) {
                                    stream.flush()
                                    progressReporter.report(
                                        bytesDownloaded = bytesDownloaded,
                                        totalBytes = totalBytes,
                                        force = true,
                                    )
                                    throw DownloadPausedException()
                                }
                            }
                            read = input.read(buffer)
                        }
                    }
                }
                progressReporter.report(
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    force = true,
                )
                val savedUri = exportDirectStagingFile(
                    taskId = taskId,
                    title = title,
                    stagingFile = stagingFile,
                    fileName = fileName,
                    treeUri = targetTreeUri,
                    downloadFolderName = downloadFolderName,
                )
                clearDownloadStaging(applicationContext, taskId)
                return CompletedDownload(
                    savedUri = savedUri,
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                )
            } catch (throwable: Throwable) {
                progressReporter.report(
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    force = true,
                )
                throw throwable
            }
        }
    }

    private suspend fun exportDirectStagingFile(
        taskId: String,
        title: String,
        stagingFile: File,
        fileName: String,
        treeUri: String?,
        downloadFolderName: String?,
    ): String {
        check(stagingFile.exists()) { "本地暂存文件不存在" }
        val totalBytes = stagingFile.length().coerceAtLeast(0L)
        updateRunningProgress(
            taskId = taskId,
            title = title,
            bytesDownloaded = totalBytes,
            totalBytes = totalBytes,
            phaseMessage = "正在整理文件…",
        )
        return if (!treeUri.isNullOrBlank()) {
            val tree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri))
                ?: error("无法访问所选目录")
            val targetName = uniqueNameForTree(tree, fileName)
            val document = tree.createFile("application/octet-stream", targetName)
                ?: error("无法创建目标文件")
            try {
                copyLocalFileToUri(
                    context = applicationContext,
                    source = stagingFile,
                    targetUri = document.uri,
                    bufferSize = DIRECT_STREAM_BUFFER_SIZE,
                )
                document.uri.toString()
            } catch (throwable: Throwable) {
                document.delete()
                throw throwable
            }
        } else {
            val uri = createMediaStoreFileUri(
                context = applicationContext,
                folderName = downloadFolderName,
                rootName = null,
                relativePath = fileName,
                replaceExisting = false,
            )
            try {
                copyLocalFileToUri(
                    context = applicationContext,
                    source = stagingFile,
                    targetUri = uri,
                    bufferSize = DIRECT_STREAM_BUFFER_SIZE,
                )
                finalizeMediaStoreFile(applicationContext, uri)
                uri.toString()
            } catch (throwable: Throwable) {
                applicationContext.contentResolver.delete(uri, null, null)
                throw throwable
            }
        }
    }

    private suspend fun runSteamWorkshopDownload(
        taskId: String,
        appId: Int,
        publishedFileId: Long,
        contentManifestId: Long,
        rootName: String,
        title: String,
        targetTreeUri: String?,
        downloadFolderName: String?,
        fallbackTotalBytes: Long,
        existing: DownloadTaskEntity,
        downloadAuthMode: DownloadAuthMode,
        boundAccountName: String?,
        boundSteamId64: Long?,
    ): CompletedDownload {
        var bytesDownloaded = 0L
        var totalBytes = fallbackTotalBytes.coerceAtLeast(0L)
        val progressReporter = RunningProgressReporter(taskId = taskId, title = title)

        val savedUri = try {
            steamSessionManager.downloadWorkshopItem(
                item = WorkshopItem(
                    publishedFileId = publishedFileId,
                    appId = appId,
                    title = title,
                    shortDescription = "",
                    description = "",
                    previewUrl = null,
                    fileUrl = null,
                    fileName = rootName,
                    fileSize = fallbackTotalBytes,
                    timeUpdated = 0L,
                    subscriptions = 0,
                    creatorSteamId = 0L,
                    contentManifestId = contentManifestId,
                    childPublishedFileIds = emptyList(),
                    tags = emptyList(),
                ),
                stagingTaskId = taskId,
                targetTreeUri = targetTreeUri,
                downloadFolderName = downloadFolderName,
                rootName = rootName,
                existingRootRef = existing.storageRootRef,
                downloadAuthMode = downloadAuthMode,
                boundAccountName = boundAccountName,
                boundSteamId64 = boundSteamId64,
                onStorageRootResolved = { rootRef ->
                    val latest = downloadTaskDao.getById(taskId) ?: existing
                    if (latest.storageRootRef != rootRef) {
                        downloadTaskDao.upsert(
                            latest.copy(
                                storageRootRef = rootRef,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                shouldPause = {
                    pauseStatePoller.shouldPause()
                },
                onProgress = { downloaded, total ->
                    bytesDownloaded = downloaded
                    totalBytes = total.coerceAtLeast(totalBytes)
                    progressReporter.report(
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                    )
                },
                onPhaseChanged = { phaseMessage ->
                    updateRunningProgress(
                        taskId = taskId,
                        title = title,
                        bytesDownloaded = bytesDownloaded.coerceAtLeast(totalBytes),
                        totalBytes = totalBytes.coerceAtLeast(0L),
                        phaseMessage = phaseMessage,
                    )
                },
                onRuntimeInfoChanged = { routeLabel, transportLabel, endpointLabel, sourceAddress, attemptCount, chunkConcurrency, lastFailure ->
                    updateRuntimeInfo(
                        taskId = taskId,
                        routeLabel = routeLabel,
                        transportLabel = transportLabel,
                        endpointLabel = endpointLabel,
                        sourceAddress = sourceAddress,
                        attemptCount = attemptCount,
                        chunkConcurrency = chunkConcurrency,
                        lastFailure = lastFailure,
                    )
                },
            ).also {
                progressReporter.report(
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    force = true,
                )
            }
        } catch (throwable: Throwable) {
            progressReporter.report(
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                force = true,
            )
            throw throwable
        }

        return CompletedDownload(
            savedUri = savedUri,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
        ).also {
            clearDownloadStaging(applicationContext, taskId)
        }
    }

    private suspend fun updateRunningProgress(
        taskId: String,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        phaseMessage: String? = null,
    ) {
        val progressPercent = calculateProgressPercent(bytesDownloaded, totalBytes)
        downloadTaskDao.updateRunningProgressPreservingPause(
            taskId = taskId,
            errorMessage = phaseMessage,
            progressPercent = progressPercent,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            updatedAt = System.currentTimeMillis(),
        )
        if (
            progressPercent != lastForegroundProgressPercent ||
            phaseMessage != lastForegroundPhaseMessage
        ) {
            setForeground(createForegroundInfo(title, progressPercent, phaseMessage))
            lastForegroundProgressPercent = progressPercent
            lastForegroundPhaseMessage = phaseMessage
        }
    }

    private suspend fun updateRuntimeInfo(
        taskId: String,
        routeLabel: String?,
        transportLabel: String?,
        endpointLabel: String?,
        sourceAddress: String?,
        attemptCount: Int?,
        chunkConcurrency: Int?,
        lastFailure: String?,
    ) {
        runtimeInfoState = runtimeInfoState.copy(
            routeLabel = routeLabel ?: runtimeInfoState.routeLabel,
            transportLabel = transportLabel ?: runtimeInfoState.transportLabel,
            endpointLabel = endpointLabel ?: runtimeInfoState.endpointLabel,
            sourceAddress = sourceAddress ?: runtimeInfoState.sourceAddress,
            attemptCount = attemptCount ?: runtimeInfoState.attemptCount,
            chunkConcurrency = chunkConcurrency ?: runtimeInfoState.chunkConcurrency,
            lastFailure = lastFailure,
        )
        downloadTaskDao.updateRuntimeInfo(
            taskId = taskId,
            runtimeRouteLabel = runtimeInfoState.routeLabel,
            runtimeTransportLabel = runtimeInfoState.transportLabel,
            runtimeEndpointLabel = runtimeInfoState.endpointLabel,
            runtimeSourceAddress = runtimeInfoState.sourceAddress,
            runtimeAttemptCount = runtimeInfoState.attemptCount,
            runtimeChunkConcurrency = runtimeInfoState.chunkConcurrency,
            runtimeLastFailure = runtimeInfoState.lastFailure,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun calculateProgressPercent(
        bytesDownloaded: Long,
        totalBytes: Long,
    ): Int {
        return if (totalBytes > 0L) {
            val rawPercent = ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
            when {
                bytesDownloaded <= 0L -> 0
                bytesDownloaded >= totalBytes -> 99
                rawPercent <= 0 -> 1
                else -> rawPercent
            }
        } else {
            0
        }
    }

    private fun Throwable?.isPauseSignal(): Boolean {
        return generateSequence(this) { it.cause }.any { it is DownloadPausedException }
    }

    private fun uniqueNameForTree(parent: DocumentFile, fileName: String): String {
        if (parent.findFile(fileName) == null) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot >= 0) fileName.substring(0, dot) else fileName
        val extension = if (dot >= 0) fileName.substring(dot) else ""
        var index = 1
        while (parent.findFile("$base ($index)$extension") != null) {
            index++
        }
        return "$base ($index)$extension"
    }

    private fun formatEndpointLabel(httpUrl: okhttp3.HttpUrl): String {
        val defaultPort = when (httpUrl.scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        return buildString {
            append(httpUrl.scheme)
            append("://")
            append(httpUrl.host)
            if (httpUrl.port != defaultPort) {
                append(":")
                append(httpUrl.port)
            }
        }
    }

    private fun createForegroundInfo(
        title: String,
        progressPercent: Int,
        phaseMessage: String? = null,
    ): ForegroundInfo {
        val isFinalizing = phaseMessage?.startsWith("正在整理文件") == true
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(phaseMessage ?: "下载中 $progressPercent%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progressPercent, isFinalizing || progressPercent == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                title.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(title.hashCode(), notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Workshop Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "创意工坊下载进度"
        }
        manager.createNotificationChannel(channel)
    }

    private data class CompletedDownload(
        val savedUri: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    )

    private inner class RunningProgressReporter(
        private val taskId: String,
        private val title: String,
    ) {
        private var lastFlushedBytes = Long.MIN_VALUE
        private var lastFlushedTotal = Long.MIN_VALUE
        private var lastFlushedPercent = Int.MIN_VALUE
        private var lastFlushedAtMs = 0L

        suspend fun report(
            bytesDownloaded: Long,
            totalBytes: Long,
            force: Boolean = false,
        ) {
            val progressPercent = calculateProgressPercent(bytesDownloaded, totalBytes)
            val now = System.currentTimeMillis()
            val bytesDelta = if (lastFlushedBytes == Long.MIN_VALUE) {
                Long.MAX_VALUE
            } else {
                (bytesDownloaded - lastFlushedBytes).coerceAtLeast(0L)
            }
            val percentDelta = if (lastFlushedPercent == Int.MIN_VALUE) {
                Int.MAX_VALUE
            } else {
                (progressPercent - lastFlushedPercent).coerceAtLeast(0)
            }
            val totalChanged = totalBytes != lastFlushedTotal
            val intervalElapsed = now - lastFlushedAtMs >= PROGRESS_UPDATE_INTERVAL_MS

            if (!force &&
                !totalChanged &&
                bytesDelta < PROGRESS_UPDATE_BYTES_STEP &&
                percentDelta < PROGRESS_UPDATE_PERCENT_STEP &&
                !intervalElapsed
            ) {
                return
            }

            updateRunningProgress(
                taskId = taskId,
                title = title,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                phaseMessage = null,
            )
            lastFlushedBytes = bytesDownloaded
            lastFlushedTotal = totalBytes
            lastFlushedPercent = progressPercent
            lastFlushedAtMs = now
        }
    }

    private inner class PauseStatePoller(
        private val taskId: String,
    ) {
        private var lastCheckedAtMs = Long.MIN_VALUE
        private var lastKnownPauseRequested = false

        suspend fun shouldPause(forceRefresh: Boolean = false): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (
                !forceRefresh &&
                lastCheckedAtMs != Long.MIN_VALUE &&
                now - lastCheckedAtMs < PAUSE_POLL_INTERVAL_MS
            ) {
                return lastKnownPauseRequested
            }
            lastKnownPauseRequested = downloadTaskDao.isPauseRequested(taskId) == true
            lastCheckedAtMs = now
            return lastKnownPauseRequested
        }
    }
}
