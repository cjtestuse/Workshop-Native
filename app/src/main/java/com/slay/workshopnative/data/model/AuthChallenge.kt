package com.slay.workshopnative.data.model

enum class AuthChallengeType {
    SteamGuard,
    Email,
}

data class AuthChallenge(
    val type: AuthChallengeType,
    val emailHint: String? = null,
    val previousCodeIncorrect: Boolean = false,
)
