package com.slay.workshopnative.core.logging

import kotlinx.serialization.Serializable

@Serializable
data class SupportBundleTextEntry(
    val name: String,
    val payload: String,
)

@Serializable
data class SupportStructuredEvent(
    val timestampMs: Long,
    val category: String,
    val action: String,
    val taskId: String? = null,
    val fields: Map<String, String> = emptyMap(),
)

@Serializable
data class SupportTrafficCounter(
    val category: String,
    val host: String? = null,
    val count: Int,
)

@Serializable
data class SupportDownloadDecisionSnapshot(
    val taskId: String,
    val timestampMs: Long,
    val publishedFileId: Long,
    val appId: Int,
    val downloadAuthMode: String,
    val anonymousFirst: Boolean,
    val canUseAuthenticatedPath: Boolean,
    val allowAuthenticatedFallback: Boolean,
    val sessionStatus: String,
    val isLoginFeatureEnabled: Boolean,
    val isLoggedInDownloadEnabled: Boolean,
    val preferAnonymousDownloads: Boolean,
    val allowAuthenticatedDownloadFallbackSetting: Boolean,
    val boundAccountHashMatched: Boolean,
)

@Serializable
data class SupportPerformanceSnapshot(
    val taskId: String,
    val timestampMs: Long,
    val mode: String,
    val requestedChunkConcurrency: Int,
    val effectiveChunkConcurrency: Int,
    val maxRoutesPerTransport: Int,
    val maxActiveRoutes: Int,
    val dispatcherMaxRequests: Int,
    val dispatcherMaxRequestsPerHost: Int,
    val heapBudgetMb: Int,
    val availableHeapMb: Int,
    val isLowRamDevice: Boolean,
)

@Serializable
data class SupportCdnHostSnapshot(
    val host: String,
    val manifestSuccessCount: Int,
    val chunkSuccessCount: Int,
    val failureCount: Int,
    val cooldownHitCount: Int,
    val lastFailureMessage: String? = null,
)

@Serializable
data class SupportCdnTaskSummary(
    val taskId: String,
    val timestampMs: Long,
    val accessLabel: String,
    val poolPreference: String,
    val transportSummary: String,
    val candidateServerCount: Int,
    val httpsServerCount: Int,
    val selectedRouteCount: Int,
    val manifestRouteHost: String? = null,
    val finalSuccessHost: String? = null,
    val lastFailureHost: String? = null,
)

@Serializable
data class SupportSearchSample(
    val timestampMs: Long,
    val triggerSource: String,
    val queryLength: Int,
    val resultCount: Int? = null,
    val succeeded: Boolean,
)

@Serializable
data class SupportDiagnosticsSnapshot(
    val downloadTimeline: List<SupportStructuredEvent> = emptyList(),
    val recoveryTimeline: List<SupportStructuredEvent> = emptyList(),
    val searchSamples: List<SupportSearchSample> = emptyList(),
    val trafficCounters: List<SupportTrafficCounter> = emptyList(),
    val downloadDecisions: List<SupportDownloadDecisionSnapshot> = emptyList(),
    val performanceSnapshots: List<SupportPerformanceSnapshot> = emptyList(),
    val cdnTaskSummaries: List<SupportCdnTaskSummary> = emptyList(),
    val cdnHostSnapshots: List<SupportCdnHostSnapshot> = emptyList(),
)

@Serializable
data class SupportCdnExportSnapshot(
    val tasks: List<SupportCdnTaskSummary> = emptyList(),
    val hosts: List<SupportCdnHostSnapshot> = emptyList(),
)

@Serializable
data class SupportDownloadTaskSnapshot(
    val taskId: String,
    val publishedFileId: Long,
    val appId: Int,
    val title: String,
    val status: String,
    val downloadAuthMode: String,
    val boundAccountHashPrefix: String? = null,
    val progressPercent: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null,
    val sourceAddress: String? = null,
    val destinationType: String,
    val hasSavedFile: Boolean,
    val savedFileScheme: String? = null,
    val hasTargetTree: Boolean,
    val storageRootKind: String,
    val runtimeRouteLabel: String? = null,
    val runtimeTransportLabel: String? = null,
    val runtimeEndpointLabel: String? = null,
    val runtimeSourceAddress: String? = null,
    val runtimeAttemptCount: Int,
    val runtimeChunkConcurrency: Int,
    val runtimeLastFailure: String? = null,
)

@Serializable
data class SupportSessionSnapshot(
    val exportedAtMs: Long,
    val sessionStatus: String,
    val connectionRevision: Long,
    val hasAccount: Boolean,
    val hasRefreshToken: Boolean,
    val isGuestMode: Boolean,
    val savedAccountsCount: Int,
    val currentAccountBindingHashPrefix: String? = null,
)

@Serializable
data class SupportSettingsSnapshot(
    val exportedAtMs: Long,
    val isLoginFeatureEnabled: Boolean,
    val isLoggedInDownloadEnabled: Boolean,
    val isOwnedGamesDisplayEnabled: Boolean,
    val isSubscriptionDisplayEnabled: Boolean,
    val autoCheckAppUpdates: Boolean,
    val defaultGuestMode: Boolean,
    val preferAnonymousDownloads: Boolean,
    val allowAuthenticatedDownloadFallback: Boolean,
    val cdnTransportPreference: String,
    val cdnPoolPreference: String,
    val downloadPerformanceMode: String,
    val requestedChunkConcurrency: Int,
    val workshopPageSize: Int,
    val workshopAutoResolveVisibleItems: Boolean,
    val hasCustomDownloadTree: Boolean,
    val hasCustomDownloadFolderName: Boolean,
)
