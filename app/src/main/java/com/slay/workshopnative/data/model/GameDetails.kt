package com.slay.workshopnative.data.model

data class GameDetails(
    val appId: Int,
    val title: String,
    val shortDescription: String,
    val about: String,
    val headerImageUrl: String?,
    val developers: List<String>,
    val publishers: List<String>,
    val genres: List<String>,
)
