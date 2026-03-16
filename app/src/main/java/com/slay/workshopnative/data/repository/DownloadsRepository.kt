package com.slay.workshopnative.data.repository

import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.model.WorkshopItem
import kotlinx.coroutines.flow.Flow

interface DownloadsRepository {
    val downloads: Flow<List<DownloadTaskEntity>>

    suspend fun enqueue(item: WorkshopItem): Result<Unit>

    suspend fun retry(taskId: String): Result<Unit>

    suspend fun pause(taskId: String)

    suspend fun resume(taskId: String): Result<Unit>

    suspend fun cancel(taskId: String)

    suspend fun delete(taskId: String): Result<Unit>

    suspend fun clearInactiveHistory(): Int

    suspend fun clearInactiveDiagnostics(): Int

    suspend fun reconcileActiveTasks()

    suspend fun rebindRetryableTasksToCurrentSession()
}
