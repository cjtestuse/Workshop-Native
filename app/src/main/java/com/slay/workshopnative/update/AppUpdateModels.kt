package com.slay.workshopnative.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AppUpdateSource(
    val id: String,
    val displayName: String,
    private val proxyPrefix: String?,
) {
    Official(
        id = "official",
        displayName = "GitHub 官方",
        proxyPrefix = null,
    ),
    ;

    fun buildUrl(targetUrl: String): String = proxyPrefix?.plus(targetUrl) ?: targetUrl

    companion object {
        val DEFAULT: AppUpdateSource = Official

        fun fromPersistedValue(value: String?): AppUpdateSource? =
            entries.firstOrNull { it.id == value }

        fun normalizePreferredSource(value: String?): AppUpdateSource =
            fromPersistedValue(value) ?: DEFAULT

        fun userSelectableSources(): List<AppUpdateSource> =
            listOf(Official)

        fun metadataCandidates(preferred: AppUpdateSource): List<AppUpdateSource> =
            listOf(Official)

        fun downloadCandidates(
            preferred: AppUpdateSource,
            metadataSource: AppUpdateSource,
        ): List<AppUpdateSource> = listOf(Official)
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
