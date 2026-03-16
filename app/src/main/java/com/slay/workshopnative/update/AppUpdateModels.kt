package com.slay.workshopnative.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.LinkedHashSet
import java.util.Locale

enum class AppUpdateSource(
    val id: String,
    val displayName: String,
    private val proxyPrefix: String?,
    val supportsMetadataCheck: Boolean,
    val supportsDownloadProxy: Boolean,
    val userSelectable: Boolean,
) {
    Official(
        id = "official",
        displayName = "GitHub",
        proxyPrefix = null,
        supportsMetadataCheck = true,
        supportsDownloadProxy = true,
        userSelectable = true,
    ),
    GhProxyVip(
        id = "ghproxy_vip",
        displayName = "ghproxy.vip",
        proxyPrefix = "https://ghproxy.vip/",
        supportsMetadataCheck = true,
        supportsDownloadProxy = true,
        userSelectable = true,
    ),
    GhLlkk(
        id = "gh_llkk",
        displayName = "gh.llkk.cc",
        proxyPrefix = "https://gh.llkk.cc/",
        supportsMetadataCheck = true,
        supportsDownloadProxy = true,
        userSelectable = true,
    ),
    GhProxyCom(
        id = "gh_proxy_com",
        displayName = "gh-proxy.com",
        proxyPrefix = "https://gh-proxy.com/",
        supportsMetadataCheck = false,
        supportsDownloadProxy = true,
        userSelectable = true,
    ),
    GhProxyNet(
        id = "ghproxy_net",
        displayName = "ghproxy.net",
        proxyPrefix = "https://ghproxy.net/",
        supportsMetadataCheck = false,
        supportsDownloadProxy = true,
        userSelectable = false,
    ),
    ;

    fun buildUrl(targetUrl: String): String = proxyPrefix?.plus(targetUrl) ?: targetUrl

    companion object {
        val DEFAULT: AppUpdateSource = Official

        fun fromPersistedValue(value: String?): AppUpdateSource? =
            entries.firstOrNull { it.id == value }

        fun normalizePreferredSource(value: String?): AppUpdateSource {
            val source = fromPersistedValue(value)
            return if (source != null && source.userSelectable) source else DEFAULT
        }

        fun userSelectableSources(): List<AppUpdateSource> =
            entries.filter { it.userSelectable }

        fun metadataCandidates(preferred: AppUpdateSource): List<AppUpdateSource> {
            val normalized = normalizePreferredSource(preferred.id)
            val ordered = LinkedHashSet<AppUpdateSource>()
            if (normalized.supportsMetadataCheck) {
                ordered += normalized
            }
            userSelectableSources()
                .filter { it != normalized && it.supportsMetadataCheck }
                .forEach(ordered::add)
            ordered += Official
            return ordered.toList()
        }

        fun downloadCandidates(
            preferred: AppUpdateSource,
            metadataSource: AppUpdateSource,
        ): List<AppUpdateSource> {
            val normalized = normalizePreferredSource(preferred.id)
            val ordered = LinkedHashSet<AppUpdateSource>()
            if (normalized.supportsDownloadProxy) ordered += normalized
            if (metadataSource.supportsDownloadProxy) ordered += metadataSource
            userSelectableSources()
                .filter { it != normalized && it.supportsDownloadProxy }
                .forEach(ordered::add)
            ordered += GhProxyNet
            ordered += Official
            return ordered.toList()
        }
    }
}

data class AppUpdateReleaseInfo(
    val rawTagName: String,
    val normalizedVersion: String,
    val publishedAtRaw: String?,
    val publishedAtDisplayText: String,
    val notesText: String,
    val releasePageUrl: String,
    val assets: List<AppUpdateAsset>,
)

data class AppUpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
)

data class AppUpdateDownloadResolution(
    val source: AppUpdateSource,
    val resolvedUrl: String,
    val assetName: String,
)

sealed interface AppUpdateCheckResult {
    data class Success(
        val currentVersion: String,
        val release: AppUpdateReleaseInfo,
        val metadataSource: AppUpdateSource,
        val downloadResolution: AppUpdateDownloadResolution?,
        val hasUpdate: Boolean,
    ) : AppUpdateCheckResult

    data class Failure(
        val errorSummary: String,
        val release: AppUpdateReleaseInfo? = null,
        val metadataSource: AppUpdateSource? = null,
    ) : AppUpdateCheckResult
}

object AppUpdateVersioning {
    private val releaseVersionPattern =
        Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-hotfix(\d+))?(?:[-+].*)?$""")
    private val publishedAtFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    fun normalizeVersionTag(value: String): String =
        value.trim().removePrefix("v").removePrefix("V")

    fun isRemoteNewer(currentVersion: String, remoteVersionTag: String): Boolean {
        val current = parseReleaseVersion(normalizeVersionTag(currentVersion))
        val remote = parseReleaseVersion(normalizeVersionTag(remoteVersionTag))
        if (current != null && remote != null) {
            return remote > current
        }
        return normalizeVersionTag(currentVersion) != normalizeVersionTag(remoteVersionTag)
    }

    fun formatPublishedAt(value: String?): String {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return ""
        return runCatching {
            Instant.parse(normalized)
                .atZone(ZoneId.systemDefault())
                .format(publishedAtFormatter)
        }.getOrElse { normalized }
    }

    fun normalizeReleaseNotesText(value: String?): String {
        return value
            .orEmpty()
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private data class ParsedReleaseVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val hotfix: Int,
    ) : Comparable<ParsedReleaseVersion> {
        override fun compareTo(other: ParsedReleaseVersion): Int {
            return compareValuesBy(this, other,
                ParsedReleaseVersion::major,
                ParsedReleaseVersion::minor,
                ParsedReleaseVersion::patch,
                ParsedReleaseVersion::hotfix,
            )
        }
    }

    private fun parseReleaseVersion(value: String): ParsedReleaseVersion? {
        val match = releaseVersionPattern.matchEntire(value) ?: return null
        return ParsedReleaseVersion(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toIntOrNull() ?: 0,
            patch = match.groupValues[3].toIntOrNull() ?: 0,
            hotfix = match.groupValues[4].toIntOrNull() ?: 0,
        )
    }
}
