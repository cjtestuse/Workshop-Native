package com.slay.workshopnative.core.util

import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

enum class DownloadFailureSource {
    Direct,
    SteamAnonymous,
    SteamAuthenticated,
    Storage,
    Unknown,
}

enum class DownloadFailureStage {
    DirectRequest,
    DirectResume,
    DirectRefresh,
    AnonymousContentAccess,
    AuthenticatedSessionRestore,
    AuthenticatedDepotLookup,
    AuthenticatedDepotKey,
    AuthenticatedManifestRequest,
    AuthenticatedCdnServers,
    CdnRouteSelection,
    CdnManifest,
    CdnChunk,
    ExportFile,
    Unknown,
}

enum class DownloadFailureReason {
    Http401,
    Http403,
    Http404,
    Http5xx,
    Timeout,
    DnsFailure,
    ConnectionFailed,
    NoCdnRoute,
    ResumeInvalid,
    StorageUnavailable,
    AccessDenied,
    Cancelled,
    Unknown,
}

data class DownloadFailureInfo(
    val source: DownloadFailureSource,
    val stage: DownloadFailureStage,
    val reason: DownloadFailureReason,
    val userMessage: String,
    val retryable: Boolean,
    val httpCode: Int? = null,
)

class DownloadFailureException(
    val info: DownloadFailureInfo,
    cause: Throwable? = null,
) : IllegalStateException(info.userMessage, cause)

class DownloadHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

fun Throwable.findDownloadFailureException(): DownloadFailureException? {
    return generateSequence(this) { it.cause }
        .filterIsInstance<DownloadFailureException>()
        .firstOrNull()
}

fun Throwable.findDownloadFailureInfo(): DownloadFailureInfo? {
    return findDownloadFailureException()?.info
}

fun Throwable.toDownloadFailureException(
    source: DownloadFailureSource,
    stage: DownloadFailureStage,
    userMessage: String,
    reasonOverride: DownloadFailureReason? = null,
    retryableOverride: Boolean? = null,
    httpCodeOverride: Int? = null,
): DownloadFailureException {
    if (this is DownloadFailureException &&
        reasonOverride == null &&
        retryableOverride == null &&
        httpCodeOverride == null
    ) {
        return this
    }

    val existingInfo = findDownloadFailureInfo()
    val httpCode = httpCodeOverride ?: existingInfo?.httpCode ?: inferDownloadHttpCode(this)
    val reason = reasonOverride ?: existingInfo?.reason ?: inferDownloadFailureReason(this, httpCode)
    val retryable = retryableOverride ?: existingInfo?.retryable ?: defaultRetryable(reason)
    return DownloadFailureException(
        info = DownloadFailureInfo(
            source = source,
            stage = stage,
            reason = reason,
            userMessage = userMessage,
            retryable = retryable,
            httpCode = httpCode,
        ),
        cause = this,
    )
}

fun Throwable.toDownloadFailureInfo(
    fallback: String,
    fallbackSource: DownloadFailureSource = DownloadFailureSource.Unknown,
    fallbackStage: DownloadFailureStage = DownloadFailureStage.Unknown,
): DownloadFailureInfo {
    findDownloadFailureInfo()?.let { return it }

    if (this is CancellationException) {
        return DownloadFailureInfo(
            source = fallbackSource,
            stage = fallbackStage,
            reason = DownloadFailureReason.Cancelled,
            userMessage = "操作已取消",
            retryable = false,
        )
    }

    val rawMessage = message?.trim().orEmpty()
    val httpCode = inferDownloadHttpCode(this)
    val reason = inferDownloadFailureReason(this, httpCode)
    return DownloadFailureInfo(
        source = fallbackSource,
        stage = fallbackStage,
        reason = reason,
        userMessage = defaultDownloadFailureMessage(
            fallback = fallback,
            rawMessage = rawMessage,
            reason = reason,
        ),
        retryable = defaultRetryable(reason),
        httpCode = httpCode,
    )
}

fun DownloadFailureInfo.toDiagnosticsFields(): Map<String, String?> {
    return mapOf(
        "failureSource" to source.name,
        "failureStage" to stage.name,
        "failureReason" to reason.name,
        "retryable" to retryable.toString(),
        "httpCode" to httpCode?.toString(),
    )
}

fun DownloadFailureInfo.toRuntimeLabel(): String {
    return buildString {
        append(stage.name)
        append(":")
        append(reason.name)
        httpCode?.let { code ->
            append(":HTTP")
            append(code)
        }
    }
}

fun Throwable.isStorageFailureLike(): Boolean {
    return generateSequence(this) { it.cause }.any { candidate ->
        if (candidate is FileNotFoundException || candidate is SecurityException) {
            return@any true
        }
        val rawMessage = candidate.message?.trim().orEmpty()
        rawMessage.contains("无法访问所选目录") ||
            rawMessage.contains("无法创建目标文件") ||
            rawMessage.contains("无法打开目标文件") ||
            rawMessage.contains("路径冲突") ||
            rawMessage.contains("permission denied", ignoreCase = true)
    }
}

fun Throwable.isNoCdnRouteFailureLike(): Boolean {
    return findDownloadFailureInfo()?.reason == DownloadFailureReason.NoCdnRoute ||
        generateSequence(this) { it.cause }.any { candidate ->
            val rawMessage = candidate.message?.trim().orEmpty()
            rawMessage.contains("没有可用的 Steam CDN 路由") ||
                rawMessage.contains("未找到可用的 Steam CDN 路由")
        }
}

private fun inferDownloadHttpCode(throwable: Throwable): Int? {
    return generateSequence(throwable) { it.cause }
        .filterIsInstance<DownloadHttpException>()
        .firstOrNull()
        ?.statusCode
}

private fun inferDownloadFailureReason(
    throwable: Throwable,
    httpCode: Int?,
): DownloadFailureReason {
    httpCode?.let { code ->
        return when {
            code == 401 -> DownloadFailureReason.Http401
            code == 403 -> DownloadFailureReason.Http403
            code == 404 -> DownloadFailureReason.Http404
            code >= 500 -> DownloadFailureReason.Http5xx
            else -> DownloadFailureReason.Unknown
        }
    }

    if (throwable is CancellationException) {
        return DownloadFailureReason.Cancelled
    }

    if (throwable.isStorageFailureLike()) {
        return DownloadFailureReason.StorageUnavailable
    }

    return generateSequence(throwable) { it.cause }
        .mapNotNull { candidate ->
            when (candidate) {
                is SocketTimeoutException,
                is InterruptedIOException,
                -> DownloadFailureReason.Timeout
                is UnknownHostException -> DownloadFailureReason.DnsFailure
                is ConnectException,
                is NoRouteToHostException,
                -> DownloadFailureReason.ConnectionFailed
                else -> classifyDownloadFailureReasonFromMessage(candidate.message)
            }
        }
        .firstOrNull()
        ?: DownloadFailureReason.Unknown
}

private fun classifyDownloadFailureReasonFromMessage(rawMessage: String?): DownloadFailureReason? {
    val normalized = rawMessage?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return when {
        normalized.contains("timed out", ignoreCase = true) ||
            normalized.contains("timeout", ignoreCase = true) -> {
            DownloadFailureReason.Timeout
        }

        normalized.contains("unable to resolve host", ignoreCase = true) ||
            normalized.contains("unknown host", ignoreCase = true) -> {
            DownloadFailureReason.DnsFailure
        }

        normalized.contains("failed to connect", ignoreCase = true) ||
            normalized.contains("connection refused", ignoreCase = true) ||
            normalized.contains("connection reset", ignoreCase = true) ||
            normalized.contains("broken pipe", ignoreCase = true) ||
            normalized.contains("network is unreachable", ignoreCase = true) -> {
            DownloadFailureReason.ConnectionFailed
        }

        normalized.contains("没有可用的 Steam CDN 路由") ||
            normalized.contains("未找到可用的 Steam CDN 路由") -> {
            DownloadFailureReason.NoCdnRoute
        }

        normalized.contains("不支持断点续传") ||
            normalized.contains("下载进度已失效") -> {
            DownloadFailureReason.ResumeInvalid
        }

        normalized.contains("accessdenied", ignoreCase = true) ||
            normalized.contains("绑定的是其他 Steam 账号") ||
            normalized.contains("只允许匿名下载") -> {
            DownloadFailureReason.AccessDenied
        }

        else -> null
    }
}

private fun defaultRetryable(reason: DownloadFailureReason): Boolean {
    return when (reason) {
        DownloadFailureReason.Http401,
        DownloadFailureReason.Http403,
        DownloadFailureReason.Http404,
        DownloadFailureReason.AccessDenied,
        DownloadFailureReason.Cancelled,
        -> false

        DownloadFailureReason.Http5xx,
        DownloadFailureReason.Timeout,
        DownloadFailureReason.DnsFailure,
        DownloadFailureReason.ConnectionFailed,
        DownloadFailureReason.NoCdnRoute,
        DownloadFailureReason.ResumeInvalid,
        DownloadFailureReason.StorageUnavailable,
        DownloadFailureReason.Unknown,
        -> true
    }
}

private fun defaultDownloadFailureMessage(
    fallback: String,
    rawMessage: String,
    reason: DownloadFailureReason,
): String {
    return when (reason) {
        DownloadFailureReason.Http401,
        DownloadFailureReason.Http403,
        -> "下载请求被拒绝，请稍后重试"

        DownloadFailureReason.Http404 -> "下载资源不存在或已失效"
        DownloadFailureReason.Http5xx -> "下载服务暂时不可用，请稍后重试"
        DownloadFailureReason.Timeout -> "下载超时，请稍后重试"
        DownloadFailureReason.DnsFailure -> "网络不可用或域名解析失败"
        DownloadFailureReason.ConnectionFailed -> "网络连接失败，请检查当前网络、代理或 VPN 设置"
        DownloadFailureReason.NoCdnRoute -> "当前没有可用的 Steam CDN 路由，请稍后重试"
        DownloadFailureReason.ResumeInvalid -> "下载进度已失效，请重新开始下载"
        DownloadFailureReason.StorageUnavailable -> "文件导出失败，请检查存储目录权限和可用空间"
        DownloadFailureReason.AccessDenied -> sanitizeMessageForDisplay(rawMessage).ifBlank { fallback }
        DownloadFailureReason.Cancelled -> "操作已取消"
        DownloadFailureReason.Unknown -> sanitizeMessageForDisplay(rawMessage).ifBlank { fallback }
    }
}
