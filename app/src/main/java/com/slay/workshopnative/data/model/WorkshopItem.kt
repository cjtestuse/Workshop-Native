package com.slay.workshopnative.data.model

data class WorkshopItem(
    val publishedFileId: Long,
    val appId: Int,
    val title: String,
    val shortDescription: String,
    val description: String,
    val previewUrl: String?,
    val authorName: String = "",
    val detailUrl: String? = null,
    val fileUrl: String?,
    val fileName: String?,
    val fileSize: Long,
    val timeUpdated: Long,
    val subscriptions: Int,
    val creatorSteamId: Long,
    val contentManifestId: Long,
    val childPublishedFileIds: List<Long>,
    val tags: List<String>,
    val isSubscribed: Boolean = false,
    val isDownloadInfoResolved: Boolean = false,
) {
    val canDirectDownload: Boolean
        get() = !fileUrl.isNullOrBlank()

    val canSteamContentDownload: Boolean
        get() = contentManifestId > 0L

    val canDownload: Boolean
        get() = canDirectDownload || canSteamContentDownload

    val hasChildItems: Boolean
        get() = childPublishedFileIds.isNotEmpty()
}
