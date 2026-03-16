package com.slay.workshopnative.data.model

enum class SessionStatus {
    Idle,
    Connecting,
    Authenticating,
    AwaitingCode,
    Authenticated,
    Error,
}

data class SteamAccountSession(
    val accountName: String,
    val steamId64: Long,
)

data class SteamSessionState(
    val status: SessionStatus = SessionStatus.Idle,
    val account: SteamAccountSession? = null,
    val challenge: AuthChallenge? = null,
    val errorMessage: String? = null,
    val isRestoring: Boolean = false,
    val connectionRevision: Long = 0L,
)
