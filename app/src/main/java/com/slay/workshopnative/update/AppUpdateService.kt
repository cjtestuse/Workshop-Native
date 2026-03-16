package com.slay.workshopnative.update

import com.slay.workshopnative.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class AppUpdateService @Inject constructor(
    baseClient: OkHttpClient,
    private val json: Json,
) {
    private val client = baseClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkForUpdates(
        currentVersion: String,
        preferredSource: AppUpdateSource,
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext AppUpdateCheckResult.Failure(
                errorSummary = "尚未配置更新仓库。请先把 BuildConfig 里的 xxxxx 替换成真实 GitHub 仓库。",
            )
        }

        var lastErrorSummary = "无法连接任何更新源。"
        var successfulMetadataSource: AppUpdateSource? = null
        var releaseInfo: AppUpdateReleaseInfo? = null

        for (source in AppUpdateSource.metadataCandidates(preferredSource)) {
            val requestUrl = source.buildUrl(latestReleaseApiUrl())
            try {
                val responseText = requestText(requestUrl)
                val parsed = parseLatestRelease(responseText)
                    ?: throw IOException("更新元数据格式无效。")
                successfulMetadataSource = source
                releaseInfo = parsed
                break
            } catch (error: Throwable) {
                lastErrorSummary = "${source.displayName}: ${summarizeError(error)}"
            }
        }

        val metadataSource = successfulMetadataSource
        val release = releaseInfo
        if (metadataSource == null || release == null) {
            return@withContext AppUpdateCheckResult.Failure(
                errorSummary = lastErrorSummary,
            )
        }

        val hasUpdate = AppUpdateVersioning.isRemoteNewer(
            currentVersion = currentVersion,
            remoteVersionTag = release.normalizedVersion,
        )
        if (!hasUpdate) {
            return@withContext AppUpdateCheckResult.Success(
                currentVersion = currentVersion,
                release = release,
                metadataSource = metadataSource,
                downloadResolution = null,
                hasUpdate = false,
            )
        }

        val downloadResolution = resolveDownloadResolution(
            release = release,
            preferredSource = preferredSource,
            metadataSource = metadataSource,
        ) ?: return@withContext AppUpdateCheckResult.Failure(
            errorSummary = "找到了新版本，但没有解析到可访问的 APK 下载地址。",
            release = release,
            metadataSource = metadataSource,
        )

        AppUpdateCheckResult.Success(
            currentVersion = currentVersion,
            release = release,
            metadataSource = metadataSource,
            downloadResolution = downloadResolution,
            hasUpdate = true,
        )
    }

    internal fun parseLatestRelease(responseText: String): AppUpdateReleaseInfo? {
        val payload = runCatching {
            json.decodeFromString(GithubReleasePayload.serializer(), responseText)
        }.getOrNull() ?: return null
        val rawTagName = payload.tagName.trim()
        if (rawTagName.isEmpty()) return null
        val asset = payload.assets.firstOrNull { it.name.trim().endsWith(".apk", ignoreCase = true) } ?: return null
        val assetName = asset.name.trim()
        val assetDownloadUrl = asset.browserDownloadUrl.trim()
        if (assetName.isEmpty() || assetDownloadUrl.isEmpty()) return null
        return AppUpdateReleaseInfo(
            rawTagName = rawTagName,
            normalizedVersion = AppUpdateVersioning.normalizeVersionTag(rawTagName),
            publishedAtRaw = payload.publishedAt?.trim()?.ifEmpty { null },
            publishedAtDisplayText = AppUpdateVersioning.formatPublishedAt(payload.publishedAt),
            notesText = AppUpdateVersioning.normalizeReleaseNotesText(payload.body),
            assetName = assetName,
            assetDownloadUrl = assetDownloadUrl,
        )
    }

    internal fun resolveDownloadResolution(
        release: AppUpdateReleaseInfo,
        preferredSource: AppUpdateSource,
        metadataSource: AppUpdateSource,
    ): AppUpdateDownloadResolution? {
        for (source in AppUpdateSource.downloadCandidates(preferredSource, metadataSource)) {
            val candidateUrl = source.buildUrl(release.assetDownloadUrl)
            if (isDownloadCandidateReachable(candidateUrl)) {
                return AppUpdateDownloadResolution(
                    source = source,
                    resolvedUrl = candidateUrl,
                )
            }
        }
        return null
    }

    private fun requestText(requestUrl: String): String {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun isDownloadCandidateReachable(requestUrl: String): Boolean {
        return requestProbe(requestUrl, head = true) || requestRangeProbe(requestUrl)
    }

    private fun requestProbe(requestUrl: String, head: Boolean): Boolean {
        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", USER_AGENT)
        val request = if (head) requestBuilder.head().build() else requestBuilder.get().build()
        return runCatching {
            client.newCall(request).execute().use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }

    private fun requestRangeProbe(requestUrl: String): Boolean {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 206
            }
        }.getOrDefault(false)
    }

    private fun latestReleaseApiUrl(): String {
        return "https://api.github.com/repos/${BuildConfig.UPDATE_GITHUB_OWNER}/${BuildConfig.UPDATE_GITHUB_REPO}/releases/latest"
    }

    private fun summarizeError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) message else error.javaClass.simpleName
    }

    private fun isConfigured(): Boolean {
        return BuildConfig.UPDATE_GITHUB_OWNER != "xxxxx" &&
            BuildConfig.UPDATE_GITHUB_REPO != "xxxxx" &&
            BuildConfig.UPDATE_GITHUB_OWNER.isNotBlank() &&
            BuildConfig.UPDATE_GITHUB_REPO.isNotBlank()
    }

    @Serializable
    private data class GithubReleasePayload(
        @SerialName("tag_name")
        val tagName: String = "",
        @SerialName("published_at")
        val publishedAt: String? = null,
        val body: String? = null,
        val assets: List<GithubReleaseAsset> = emptyList(),
    )

    @Serializable
    private data class GithubReleaseAsset(
        val name: String = "",
        @SerialName("browser_download_url")
        val browserDownloadUrl: String = "",
    )

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 12L
        const val USER_AGENT = "WorkshopNative-Update"
    }
}
