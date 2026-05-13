package com.slay.workshopnative.core.util

import kotlinx.coroutines.CancellationException

fun Throwable.toUserMessage(fallback: String): String {
    findDownloadFailureInfo()?.let { return it.userMessage }
    val rawMessage = message?.trim().orEmpty()
    if (this is CancellationException) return "操作已取消"
    if (rawMessage.isBlank()) return fallback

    return when {
        rawMessage.contains("must be connected", ignoreCase = true) -> {
            "Steam 连接已断开，请重试恢复或重新登录"
        }

        rawMessage.contains("timed out", ignoreCase = true) ||
            rawMessage.contains("timeout", ignoreCase = true) -> {
            "Steam 响应超时，请稍后重试"
        }

        rawMessage.contains("unable to resolve host", ignoreCase = true) ||
            rawMessage.contains("unknown host", ignoreCase = true) -> {
            "网络不可用或域名解析失败"
        }

        rawMessage.contains("failed to connect", ignoreCase = true) ||
            rawMessage.contains("connection refused", ignoreCase = true) -> {
            "网络连接失败，请检查当前网络、代理或 VPN 设置"
        }

        else -> sanitizeMessageForDisplay(rawMessage).ifBlank { fallback }
    }
}
