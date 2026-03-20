package com.slay.workshopnative.core.logging

import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

@Singleton
class SupportDiagnosticsStore @Inject constructor() {
    private companion object {
        const val MAX_TIMELINE_EVENTS = 800
        const val MAX_SEARCH_SAMPLES = 200
    }

    private data class MutableCdnHostSnapshot(
        var manifestSuccessCount: Int = 0,
        var chunkSuccessCount: Int = 0,
        var failureCount: Int = 0,
        var cooldownHitCount: Int = 0,
        var lastFailureMessage: String? = null,
    )

    private data class MutableCdnTaskSummary(
        var timestampMs: Long,
        var accessLabel: String,
        var poolPreference: String,
        var transportSummary: String,
        var candidateServerCount: Int,
        var httpsServerCount: Int,
        var selectedRouteCount: Int,
        var manifestRouteHost: String? = null,
        var finalSuccessHost: String? = null,
        var lastFailureHost: String? = null,
    )

    private val lock = Any()
    private val downloadTimeline = mutableListOf<SupportStructuredEvent>()
    private val recoveryTimeline = mutableListOf<SupportStructuredEvent>()
    private val searchSamples = mutableListOf<SupportSearchSample>()
    private val trafficCounters = LinkedHashMap<Pair<String, String?>, Int>()
    private val downloadDecisions = LinkedHashMap<String, SupportDownloadDecisionSnapshot>()
    private val performanceSnapshots = LinkedHashMap<String, SupportPerformanceSnapshot>()
    private val cdnTaskSummaries = LinkedHashMap<String, MutableCdnTaskSummary>()
    private val cdnHostSnapshots = LinkedHashMap<String, MutableCdnHostSnapshot>()

    fun recordDownloadEvent(
        action: String,
        taskId: String? = null,
        fields: Map<String, String?> = emptyMap(),
    ) {
        synchronized(lock) {
            downloadTimeline += SupportStructuredEvent(
                timestampMs = System.currentTimeMillis(),
                category = "download",
                action = action,
                taskId = taskId,
                fields = sanitizeFields(fields),
            )
            trimList(downloadTimeline, MAX_TIMELINE_EVENTS)
        }
    }

    fun recordRecoveryEvent(
        action: String,
        fields: Map<String, String?> = emptyMap(),
    ) {
        synchronized(lock) {
            recoveryTimeline += SupportStructuredEvent(
                timestampMs = System.currentTimeMillis(),
                category = "recovery",
                action = action,
                fields = sanitizeFields(fields),
            )
            trimList(recoveryTimeline, MAX_TIMELINE_EVENTS)
        }
    }

    fun recordSearchSample(
        triggerSource: String,
        queryLength: Int,
        resultCount: Int? = null,
        succeeded: Boolean,
    ) {
        synchronized(lock) {
            searchSamples += SupportSearchSample(
                timestampMs = System.currentTimeMillis(),
                triggerSource = triggerSource,
                queryLength = queryLength.coerceAtLeast(0),
                resultCount = resultCount?.coerceAtLeast(0),
                succeeded = succeeded,
            )
            trimList(searchSamples, MAX_SEARCH_SAMPLES)
        }
    }

    fun recordDownloadDecision(snapshot: SupportDownloadDecisionSnapshot) {
        synchronized(lock) {
            downloadDecisions[snapshot.taskId] = snapshot
        }
    }

    fun recordPerformanceSnapshot(snapshot: SupportPerformanceSnapshot) {
        synchronized(lock) {
            performanceSnapshots[snapshot.taskId] = snapshot
        }
    }

    fun recordCdnTaskPlan(
        taskId: String,
        accessLabel: String,
        poolPreference: String,
        transportSummary: String,
        candidateServerCount: Int,
        httpsServerCount: Int,
        selectedRouteCount: Int,
    ) {
        synchronized(lock) {
            val existing = cdnTaskSummaries[taskId]
            if (existing == null) {
                cdnTaskSummaries[taskId] = MutableCdnTaskSummary(
                    timestampMs = System.currentTimeMillis(),
                    accessLabel = accessLabel,
                    poolPreference = poolPreference,
                    transportSummary = transportSummary,
                    candidateServerCount = candidateServerCount,
                    httpsServerCount = httpsServerCount,
                    selectedRouteCount = selectedRouteCount,
                )
            } else {
                existing.timestampMs = System.currentTimeMillis()
                existing.accessLabel = accessLabel
                existing.poolPreference = poolPreference
                existing.transportSummary = transportSummary
                existing.candidateServerCount = candidateServerCount
                existing.httpsServerCount = httpsServerCount
                existing.selectedRouteCount = selectedRouteCount
            }
        }
    }

    fun recordManifestRouteSuccess(taskId: String?, host: String?) {
        if (host.isNullOrBlank()) return
        synchronized(lock) {
            cdnTaskSummaries[taskId]?.manifestRouteHost = host
            val stats = cdnHostSnapshots.getOrPut(host) { MutableCdnHostSnapshot() }
            stats.manifestSuccessCount += 1
        }
    }

    fun recordChunkRouteSuccess(taskId: String?, host: String?) {
        if (host.isNullOrBlank()) return
        synchronized(lock) {
            cdnTaskSummaries[taskId]?.finalSuccessHost = host
            val stats = cdnHostSnapshots.getOrPut(host) { MutableCdnHostSnapshot() }
            stats.chunkSuccessCount += 1
        }
    }

    fun recordCdnFailure(
        taskId: String?,
        host: String?,
        message: String?,
    ) {
        if (host.isNullOrBlank()) return
        synchronized(lock) {
            cdnTaskSummaries[taskId]?.lastFailureHost = host
            val stats = cdnHostSnapshots.getOrPut(host) { MutableCdnHostSnapshot() }
            stats.failureCount += 1
            stats.lastFailureMessage = message?.trim()?.take(240)
        }
    }

    fun recordCdnCooldownHit(host: String?) {
        if (host.isNullOrBlank()) return
        synchronized(lock) {
            val stats = cdnHostSnapshots.getOrPut(host) { MutableCdnHostSnapshot() }
            stats.cooldownHitCount += 1
        }
    }

    fun incrementCounter(
        category: String,
        host: String? = null,
        amount: Int = 1,
    ) {
        if (category.isBlank() || amount == 0) return
        synchronized(lock) {
            val key = category.trim() to host?.trim()?.ifBlank { null }
            trafficCounters[key] = (trafficCounters[key] ?: 0) + amount
        }
    }

    fun recordHttpRequest(request: Request) {
        val httpUrl = request.url
        val category = httpCategoryFor(httpUrl.toString())
        incrementCounter(category = category, host = httpUrl.host)
    }

    fun recordHttpFailure(request: Request) {
        val httpUrl = request.url
        val category = httpCategoryFor(httpUrl.toString()) + "_failure"
        incrementCounter(category = category, host = httpUrl.host)
    }

    fun snapshot(): SupportDiagnosticsSnapshot = synchronized(lock) {
        SupportDiagnosticsSnapshot(
            downloadTimeline = downloadTimeline.toList(),
            recoveryTimeline = recoveryTimeline.toList(),
            searchSamples = searchSamples.toList(),
            trafficCounters = trafficCounters.entries
                .sortedWith(compareBy<Map.Entry<Pair<String, String?>, Int>>({ it.key.first }, { it.key.second ?: "" }))
                .map { entry ->
                    SupportTrafficCounter(
                        category = entry.key.first,
                        host = entry.key.second,
                        count = entry.value,
                    )
                },
            downloadDecisions = downloadDecisions.values.sortedBy { it.timestampMs },
            performanceSnapshots = performanceSnapshots.values.sortedBy { it.timestampMs },
            cdnTaskSummaries = cdnTaskSummaries.entries
                .sortedBy { it.value.timestampMs }
                .map { (taskId, value) ->
                    SupportCdnTaskSummary(
                        taskId = taskId,
                        timestampMs = value.timestampMs,
                        accessLabel = value.accessLabel,
                        poolPreference = value.poolPreference,
                        transportSummary = value.transportSummary,
                        candidateServerCount = value.candidateServerCount,
                        httpsServerCount = value.httpsServerCount,
                        selectedRouteCount = value.selectedRouteCount,
                        manifestRouteHost = value.manifestRouteHost,
                        finalSuccessHost = value.finalSuccessHost,
                        lastFailureHost = value.lastFailureHost,
                    )
                },
            cdnHostSnapshots = cdnHostSnapshots.entries
                .sortedBy { it.key }
                .map { (host, value) ->
                    SupportCdnHostSnapshot(
                        host = host,
                        manifestSuccessCount = value.manifestSuccessCount,
                        chunkSuccessCount = value.chunkSuccessCount,
                        failureCount = value.failureCount,
                        cooldownHitCount = value.cooldownHitCount,
                        lastFailureMessage = value.lastFailureMessage,
                    )
                },
        )
    }

    private fun httpCategoryFor(rawUrl: String): String {
        val httpUrl = rawUrl.toHttpUrlOrNull() ?: return "http_other"
        val host = httpUrl.host.lowercase()
        val path = httpUrl.encodedPath.lowercase()
        return when {
            host == "steamcommunity.com" && path == "/sharedfiles/ajaxgetworkshops/" -> "explore_page_http"
            host == "steamcommunity.com" && path == "/workshop/ajaxfindworkshops/" -> "explore_search_http"
            host == "steamcommunity.com" && path == "/workshop/browse/" -> "workshop_browse_http"
            host == "steamcommunity.com" && path == "/sharedfiles/filedetails/" -> "workshop_detail_page_http"
            host == "steamcommunity.com" && path.startsWith("/profiles/") -> "settings_avatar_http"
            host == "store.steampowered.com" && path == "/api/appdetails" -> "store_appdetails_http"
            host == "api.steampowered.com" && path.contains("getpublishedfiledetails") -> "published_file_details_http"
            host == "api.github.com" || host == "github.com" -> "app_update_http"
            else -> "http_other"
        }
    }

    private fun sanitizeFields(fields: Map<String, String?>): Map<String, String> {
        return fields.mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value?.trim()
            if (normalizedKey.isEmpty() || normalizedValue.isNullOrEmpty()) {
                null
            } else {
                normalizedKey to normalizedValue.take(320)
            }
        }.toMap()
    }

    private fun <T> trimList(list: MutableList<T>, maxSize: Int) {
        val overflow = list.size - maxSize
        if (overflow > 0) {
            repeat(overflow) {
                list.removeAt(0)
            }
        }
    }
}
