package com.slay.workshopnative.data.model

data class WorkshopGameEntry(
    val appId: Int,
    val name: String,
    val capsuleUrl: String?,
    val previewUrl: String?,
    val workshopItemCount: Int? = null,
)

data class WorkshopGamePage(
    val items: List<WorkshopGameEntry>,
    val page: Int,
    val totalCount: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)
