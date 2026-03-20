package com.slay.workshopnative.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): DownloadTaskEntity?

    @Query("SELECT pauseRequested FROM download_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun isPauseRequested(taskId: String): Boolean?

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadTaskEntity>

    @Query(
        """
        DELETE FROM download_tasks
        WHERE status NOT IN ('Queued', 'Running', 'Paused')
        """,
    )
    suspend fun clearInactiveTasks(): Int

    @Query(
        """
        UPDATE download_tasks
        SET sourceUrl = CASE
                WHEN publishedFileId > 0 THEN 'steam://publishedfile/' || publishedFileId
                ELSE sourceUrl
            END,
            targetTreeUri = NULL,
            storageRootRef = NULL,
            boundAccountKeyHash = NULL,
            boundAccountName = NULL,
            boundSteamId64 = NULL,
            runtimeRouteLabel = NULL,
            runtimeTransportLabel = NULL,
            runtimeEndpointLabel = NULL,
            runtimeSourceAddress = NULL,
            runtimeAttemptCount = 0,
            runtimeChunkConcurrency = 0,
            runtimeLastFailure = NULL,
            updatedAt = :updatedAt
        WHERE status NOT IN ('Queued', 'Running', 'Paused')
        """,
    )
    suspend fun clearInactiveRuntimeDetails(updatedAt: Long): Int

    @Query(
        """
        SELECT * FROM download_tasks
        WHERE status IN ('Queued', 'Running')
        ORDER BY createdAt DESC
        """,
    )
    suspend fun getActiveTasks(): List<DownloadTaskEntity>

    @Query(
        """
        UPDATE download_tasks
        SET status = :status,
            pauseRequested = :pauseRequested,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE taskId = :taskId
        """,
    )
    suspend fun updateStatus(
        taskId: String,
        status: DownloadStatus,
        pauseRequested: Boolean,
        errorMessage: String?,
        updatedAt: Long,
    )

    @Query(
        """
        SELECT * FROM download_tasks
        WHERE publishedFileId = :publishedFileId
          AND status IN ('Queued', 'Running')
        ORDER BY createdAt DESC
        """,
    )
    suspend fun getActiveByPublishedFileId(publishedFileId: Long): List<DownloadTaskEntity>

    @Query(
        """
        SELECT * FROM download_tasks
        WHERE publishedFileId = :publishedFileId
          AND taskId != :keepTaskId
        ORDER BY createdAt DESC
        """,
    )
    suspend fun getByPublishedFileIdExcludingTask(
        publishedFileId: Long,
        keepTaskId: String,
    ): List<DownloadTaskEntity>

    @Upsert
    suspend fun upsert(entity: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE taskId = :taskId")
    suspend fun deleteById(taskId: String)

    @Query(
        """
        UPDATE download_tasks
        SET status = :status,
            pauseRequested = :pauseRequested,
            errorMessage = :errorMessage,
            progressPercent = :progressPercent,
            bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            updatedAt = :updatedAt
        WHERE taskId = :taskId
        """,
    )
    suspend fun updateProgress(
        taskId: String,
        status: DownloadStatus,
        pauseRequested: Boolean,
        errorMessage: String?,
        progressPercent: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE download_tasks
        SET status = CASE
                WHEN pauseRequested = 1 OR status = 'Paused' THEN 'Paused'
                ELSE 'Running'
            END,
            errorMessage = :errorMessage,
            progressPercent = :progressPercent,
            bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            updatedAt = :updatedAt
        WHERE taskId = :taskId
        """,
    )
    suspend fun updateRunningProgressPreservingPause(
        taskId: String,
        errorMessage: String?,
        progressPercent: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE download_tasks
        SET runtimeRouteLabel = :runtimeRouteLabel,
            runtimeTransportLabel = :runtimeTransportLabel,
            runtimeEndpointLabel = :runtimeEndpointLabel,
            runtimeSourceAddress = :runtimeSourceAddress,
            runtimeAttemptCount = :runtimeAttemptCount,
            runtimeChunkConcurrency = :runtimeChunkConcurrency,
            runtimeLastFailure = :runtimeLastFailure,
            updatedAt = :updatedAt
        WHERE taskId = :taskId
        """,
    )
    suspend fun updateRuntimeInfo(
        taskId: String,
        runtimeRouteLabel: String?,
        runtimeTransportLabel: String?,
        runtimeEndpointLabel: String?,
        runtimeSourceAddress: String?,
        runtimeAttemptCount: Int,
        runtimeChunkConcurrency: Int,
        runtimeLastFailure: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE download_tasks
        SET status = :status,
            pauseRequested = 0,
            sourceUrl = CASE
                WHEN publishedFileId > 0 THEN 'steam://publishedfile/' || publishedFileId
                ELSE sourceUrl
            END,
            targetTreeUri = NULL,
            boundAccountKeyHash = NULL,
            boundAccountName = NULL,
            boundSteamId64 = NULL,
            storageRootRef = NULL,
            runtimeEndpointLabel = NULL,
            runtimeSourceAddress = NULL,
            runtimeLastFailure = NULL,
            savedFileUri = :savedFileUri,
            errorMessage = :errorMessage,
            progressPercent = :progressPercent,
            bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            updatedAt = :updatedAt
        WHERE taskId = :taskId
        """,
    )
    suspend fun finish(
        taskId: String,
        status: DownloadStatus,
        savedFileUri: String?,
        errorMessage: String?,
        progressPercent: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        updatedAt: Long,
    )
}
