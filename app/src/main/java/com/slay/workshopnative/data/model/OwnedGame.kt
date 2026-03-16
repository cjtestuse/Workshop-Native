package com.slay.workshopnative.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class OwnershipSource {
    Owned,
    FamilyShared,
}

@Serializable
data class OwnedGame(
    val appId: Int,
    val name: String,
    val iconHash: String,
    val playtimeForeverMinutes: Int,
    val lastPlayedEpochSeconds: Int,
    val ownershipSource: OwnershipSource = OwnershipSource.Owned,
    val lenderSteamId64: Long? = null,
) {
    val capsuleUrl: String
        get() = "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$appId/capsule_616x353.jpg"

    val iconUrl: String
        get() = if (iconHash.isBlank()) {
            ""
        } else {
            "https://media.steampowered.com/steamcommunity/public/images/apps/$appId/$iconHash.jpg"
        }

    val sourceLabel: String
        get() = when (ownershipSource) {
            OwnershipSource.Owned -> "已购买"
            OwnershipSource.FamilyShared -> "家庭共享"
        }

    val isFamilyShared: Boolean
        get() = ownershipSource == OwnershipSource.FamilyShared
}
