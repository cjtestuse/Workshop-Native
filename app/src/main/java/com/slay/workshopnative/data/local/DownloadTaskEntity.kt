package com.slay.workshopnative.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadStatus {
    Queued,
    Running,
    Paused,
    Success,
    Failed,
    Cancelled,
    Unavailable,
}

enum class DownloadAuthMode {
    Anonymous,
    Auto,
    Authenticated,
}

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val taskId: String,
    val publishedFileId: Long,
    val appId: Int,
    val title: String,
    val previewUrl: String?,
    val sourceUrl: String,
    val fileName: String,
    val downloadFolderName: String?,
    val targetTreeUri: String?,
    val storageRootRef: String?,
    val destinationLabel: String,
    val downloadAuthMode: DownloadAuthMode,
    val boundAccountKeyHash: String?,
    val boundAccountName: String?,
    val boundSteamId64: Long?,
    val runtimeRouteLabel: String?,
    val runtimeTransportLabel: String?,
    val runtimeEndpointLabel: String?,
    val runtimeSourceAddress: String?,
    val runtimeAttemptCount: Int,
    val runtimeChunkConcurrency: Int,
    val runtimeLastFailure: String?,
    val status: DownloadStatus,
    val pauseRequested: Boolean,
    val progressPercent: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val savedFileUri: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
