package com.slay.workshopnative.core.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val HTTP_URL_REGEX = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
private val STEAM_PROFILE_REGEX = Regex(
    """(https://steamcommunity\.com/profiles/)\d+""",
    RegexOption.IGNORE_CASE,
)

fun buildAccountBindingHash(
    accountName: String?,
    steamId64: Long?,
): String? {
    val normalized = when {
        steamId64 != null && steamId64 > 0L -> "steam:$steamId64"
        !accountName.isNullOrBlank() -> "name:${accountName.trim().lowercase()}"
        else -> return null
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(normalized.toByteArray(StandardCharsets.UTF_8))
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append("%02x".format(byte))
        }
    }
}

fun resolveAccountBindingHash(
    boundAccountKeyHash: String?,
    boundAccountName: String?,
    boundSteamId64: Long?,
): String? {
    return boundAccountKeyHash?.takeIf { it.isNotBlank() }
        ?: buildAccountBindingHash(
            accountName = boundAccountName,
            steamId64 = boundSteamId64,
        )
}

fun sanitizeUrlForLogging(rawValue: String): String {
    val httpUrl = rawValue.toHttpUrlOrNull()
    if (httpUrl != null) {
        val sanitized = httpUrl.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
        return STEAM_PROFILE_REGEX.replace(sanitized, "$1{steamid}")
    }
    return STEAM_PROFILE_REGEX.replace(rawValue, "$1{steamid}")
}

fun sanitizeOkHttpLogMessage(message: String): String {
    return HTTP_URL_REGEX.replace(message) { match ->
        val original = match.value
        val trimmed = original.trimEnd(')', ',', ';')
        val trailing = original.substring(trimmed.length)
        sanitizeUrlForLogging(trimmed) + trailing
    }
}

fun sanitizeRuntimeSourceAddress(sourceAddress: String?): String? {
    if (sourceAddress.isNullOrBlank()) return null
    if (sourceAddress.startsWith("steam://", ignoreCase = true)) {
        return sourceAddress
    }
    val httpUrl = sourceAddress.toHttpUrlOrNull() ?: return sanitizeUrlForLogging(sourceAddress)
    val defaultPort = when (httpUrl.scheme) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
    return buildString {
        append(httpUrl.scheme)
        append("://")
        append(httpUrl.host)
        if (httpUrl.port != defaultPort) {
            append(":")
            append(httpUrl.port)
        }
    }
}
