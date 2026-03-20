package com.slay.workshopnative.update

import com.slay.workshopnative.BuildConfig
import com.slay.workshopnative.core.logging.AppLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class AppUpdateService @Inject constructor(
    baseClient: OkHttpClient,
    private val json: Json,
) {
    private companion object {
        const val LOG_TAG = "AppUpdateService"
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 12L
        const val USER_AGENT = "WorkshopNative-Update"
    }

    private class HttpStatusException(
        val statusCode: Int,
        message: String,
    ) : IOException(message)

    private val client = baseClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkForUpdates(
        currentVersion: String,
        preferredSource: AppUpdateSource,
        validateReachability: Boolean = true,
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        AppLog.i(
            LOG_TAG,
            "checkForUpdates currentVersion=$currentVersion preferredSource=${preferredSource.name}",
        )
        if (!isConfigured()) {
            return@withContext AppUpdateCheckResult.Failure(
                errorSummary = "尚未配置更新仓库。",
            )
        }

        var lastErrorSummary = "无法连接 GitHub 官方更新源。"
        var successfulMetadataSource: AppUpdateSource? = null
        var releaseInfo: AppUpdateReleaseInfo? = null

        for (source in AppUpdateSource.metadataCandidates(preferredSource)) {
            try {
                val parsed = fetchLatestRelease(source)
                successfulMetadataSource = source
                releaseInfo = parsed
                break
            } catch (error: Throwable) {
                AppLog.w(LOG_TAG, "fetchLatestRelease failed source=${source.name}", error)
                lastErrorSummary = "${source.displayName}: ${summarizeError(error)}"
            }
        }

        val metadataSource = successfulMetadataSource
        val release = releaseInfo
        if (metadataSource == null || release == null) {
            AppLog.w(LOG_TAG, "checkForUpdates failed because metadata is unavailable: $lastErrorSummary")
            return@withContext AppUpdateCheckResult.Failure(
                errorSummary = lastErrorSummary,
            )
        }

        val hasUpdate = AppUpdateVersioning.isRemoteNewer(
            currentVersion = currentVersion,
            remoteVersionTag = release.normalizedVersion,
        )
        if (!hasUpdate) {
            AppLog.i(LOG_TAG, "checkForUpdates no update available remote=${release.rawTagName}")
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
            validateReachability = validateReachability,
        ) ?: return@withContext AppUpdateCheckResult.Failure(
            errorSummary = if (release.assets.isEmpty()) {
                "找到了新版本，但该 Release 还没有上传 APK 资产。"
            } else if (selectPreferredAsset(release.assets) == null) {
                "找到了新版本，但当前安装通道没有匹配的 APK 资产。"
            } else {
                "找到了新版本，但没有解析到可访问的 APK 下载地址。"
            },
            release = release,
            metadataSource = metadataSource,
        )

        AppLog.i(
            LOG_TAG,
            "checkForUpdates found update remote=${release.rawTagName} asset=${downloadResolution.assetName}",
        )

        AppUpdateCheckResult.Success(
            currentVersion = currentVersion,
            release = release,
            metadataSource = metadataSource,
            downloadResolution = downloadResolution,
            hasUpdate = true,
        )
    }

    private fun fetchLatestRelease(source: AppUpdateSource): AppUpdateReleaseInfo {
        val latestUrl = source.buildUrl(latestReleaseApiUrl())
        val releasesUrl = source.buildUrl(releasesApiUrl())

        val latestResult = runCatching {
            parseReleasePayload(
                requestText(latestUrl),
            ) ?: throw IOException("更新元数据格式无效。")
        }
        latestResult.getOrNull()?.let { return it }

        val latestError = latestResult.exceptionOrNull()
        val fallbackNeeded = latestError is HttpStatusException && latestError.statusCode == 404
        if (!fallbackNeeded) {
            throw latestError ?: IOException("无法读取最新 Release。")
        }

        val fallbackRelease = parseReleaseList(
            requestText(releasesUrl),
        )
        if (fallbackRelease != null) {
            return fallbackRelease
        }
        throw IOException("仓库还没有发布 GitHub Release。")
    }

    internal fun parseReleasePayload(responseText: String): AppUpdateReleaseInfo? {
        val payload = runCatching {
            json.decodeFromString(GithubReleasePayload.serializer(), responseText)
        }.getOrNull() ?: return null
        return payload.toReleaseInfo()
    }

    internal fun parseReleaseList(responseText: String): AppUpdateReleaseInfo? {
        val payloads = runCatching {
            json.decodeFromString(ListSerializer(GithubReleasePayload.serializer()), responseText)
        }.getOrNull() ?: return null
        val preferred = payloads.firstOrNull { !it.draft && !it.prerelease }
            ?: payloads.firstOrNull { !it.draft }
            ?: return null
        return preferred.toReleaseInfo()
    }

    private fun GithubReleasePayload.toReleaseInfo(): AppUpdateReleaseInfo? {
        val rawTagName = tagName.trim()
        if (rawTagName.isEmpty()) return null
        val releasePageUrl = htmlUrl.trim().ifEmpty { githubReleasesPageUrl() }
        val apkAssets = assets
            .mapNotNull { asset ->
                val assetName = asset.name.trim()
                val assetDownloadUrl = asset.browserDownloadUrl.trim()
                if (
                    assetName.isEmpty() ||
                    assetDownloadUrl.isEmpty() ||
                    !assetName.endsWith(".apk", ignoreCase = true)
                ) {
                    null
                } else {
                    AppUpdateAsset(
                        name = assetName,
                        downloadUrl = assetDownloadUrl,
                        sizeBytes = asset.size.coerceAtLeast(0L),
                    )
                }
            }
            .sortedWith(apkAssetComparator())
        return AppUpdateReleaseInfo(
            rawTagName = rawTagName,
            normalizedVersion = AppUpdateVersioning.normalizeVersionTag(rawTagName),
            publishedAtRaw = publishedAt?.trim()?.ifEmpty { null },
            publishedAtDisplayText = AppUpdateVersioning.formatPublishedAt(publishedAt),
            notesText = AppUpdateVersioning.normalizeReleaseNotesText(body),
            releasePageUrl = releasePageUrl,
            assets = apkAssets,
        )
    }

    internal fun resolveDownloadResolution(
        release: AppUpdateReleaseInfo,
        preferredSource: AppUpdateSource,
        metadataSource: AppUpdateSource,
        validateReachability: Boolean = true,
    ): AppUpdateDownloadResolution? {
        val asset = selectPreferredAsset(release.assets) ?: return null
        for (source in AppUpdateSource.downloadCandidates(preferredSource, metadataSource)) {
            val candidateUrl = source.buildUrl(asset.downloadUrl)
            if (!validateReachability || isDownloadCandidateReachable(candidateUrl)) {
                return AppUpdateDownloadResolution(
                    source = source,
                    resolvedUrl = candidateUrl,
                    assetName = asset.name,
                )
            }
        }
        return null
    }

    internal fun selectPreferredAsset(assets: List<AppUpdateAsset>): AppUpdateAsset? {
        return assets
            .filter(::isAssetCompatibleWithCurrentBuild)
            .minWithOrNull(apkAssetComparator())
    }

    private fun requestText(requestUrl: String): String {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "HTTP ${response.code}")
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

    private fun releasesApiUrl(): String {
        return "https://api.github.com/repos/${BuildConfig.UPDATE_GITHUB_OWNER}/${BuildConfig.UPDATE_GITHUB_REPO}/releases?per_page=10"
    }

    private fun githubReleasesPageUrl(): String {
        return "https://github.com/${BuildConfig.UPDATE_GITHUB_OWNER}/${BuildConfig.UPDATE_GITHUB_REPO}/releases"
    }

    private fun summarizeError(error: Throwable): String {
        if (error is HttpStatusException && error.statusCode == 404) {
            return "仓库还没有发布 GitHub Release。"
        }
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) message else error.javaClass.simpleName
    }

    private fun isConfigured(): Boolean {
        return BuildConfig.UPDATE_GITHUB_OWNER.isNotBlank() &&
            BuildConfig.UPDATE_GITHUB_REPO.isNotBlank()
    }

    private fun apkAssetComparator(): Comparator<AppUpdateAsset> {
        return compareBy<AppUpdateAsset>(
            ::debugBuildMatchPriority,
            ::releaseBuildMatchPriority,
            ::universalAssetPriority,
            { it.sizeBytes.takeIf { size -> size > 0L } ?: Long.MAX_VALUE },
            { it.name.length },
        )
    }

    private fun debugBuildMatchPriority(asset: AppUpdateAsset): Int {
        val normalized = asset.name.lowercase()
        return when {
            BuildConfig.DEBUG && "debug" in normalized -> 0
            BuildConfig.DEBUG -> 1
            else -> 0
        }
    }

    private fun releaseBuildMatchPriority(asset: AppUpdateAsset): Int {
        val normalized = asset.name.lowercase()
        return when {
            !BuildConfig.DEBUG && "debug" in normalized -> 2
            "release" in normalized -> 0
            else -> 1
        }
    }

    private fun isAssetCompatibleWithCurrentBuild(asset: AppUpdateAsset): Boolean {
        val normalized = asset.name.lowercase()
        val isDebugAsset = "debug" in normalized
        return if (BuildConfig.DEBUG) isDebugAsset else !isDebugAsset
    }

    private fun universalAssetPriority(asset: AppUpdateAsset): Int {
        val normalized = asset.name.lowercase()
        return when {
            "universal" in normalized || "all" in normalized -> 0
            "arm64" in normalized || "arm64-v8a" in normalized -> 1
            else -> 2
        }
    }

    @Serializable
    private data class GithubReleasePayload(
        @SerialName("tag_name")
        val tagName: String = "",
        @SerialName("html_url")
        val htmlUrl: String = "",
        @SerialName("published_at")
        val publishedAt: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String? = null,
        val assets: List<GithubReleaseAsset> = emptyList(),
    )

    @Serializable
    private data class GithubReleaseAsset(
        val name: String = "",
        val size: Long = 0L,
        @SerialName("browser_download_url")
        val browserDownloadUrl: String = "",
    )
}
