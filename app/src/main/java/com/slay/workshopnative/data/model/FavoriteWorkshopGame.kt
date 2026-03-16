package com.slay.workshopnative.data.model

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteWorkshopGame(
    val appId: Int,
    val name: String,
    val capsuleUrl: String? = null,
    val previewUrl: String? = null,
    val workshopItemCount: Int? = null,
    val addedAtMs: Long = 0L,
    val lastOpenedAtMs: Long = 0L,
) {
    fun toWorkshopGameEntry(): WorkshopGameEntry {
        return WorkshopGameEntry(
            appId = appId,
            name = name,
            capsuleUrl = capsuleUrl,
            previewUrl = previewUrl,
            workshopItemCount = workshopItemCount,
        )
    }
}
