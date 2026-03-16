package com.slay.workshopnative.data.preferences

import kotlinx.serialization.Serializable

@Serializable
data class PersistedActiveSteamSession(
    val accountName: String = "",
    val clientId: Long = 0L,
    val steamId64: Long = 0L,
)

@Serializable
data class PersistedSavedSteamAccount(
    val accountName: String,
    val clientId: Long,
    val steamId64: Long,
    val rememberSession: Boolean,
    val lastUsedAtMs: Long,
)
