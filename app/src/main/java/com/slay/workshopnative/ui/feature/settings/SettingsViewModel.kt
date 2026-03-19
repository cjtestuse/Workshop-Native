package com.slay.workshopnative.ui.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.core.storage.DEFAULT_DOWNLOAD_FOLDER_NAME
import com.slay.workshopnative.core.storage.normalizeDownloadFolderName
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.preferences.CdnPoolPreference
import com.slay.workshopnative.data.preferences.CdnTransportPreference
import com.slay.workshopnative.data.preferences.DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.repository.SteamRepository
import com.slay.workshopnative.data.preferences.DOWNLOAD_CHUNK_CONCURRENCY_OPTIONS
import com.slay.workshopnative.data.preferences.SavedSteamAccount
import com.slay.workshopnative.data.preferences.UserPreferences
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class SupportLogUiState(
    val runtimeLogCount: Int = 0,
    val crashLogCount: Int = 0,
    val totalSizeLabel: String = "0 B",
    val summary: String = "当前还没有可导出的日志文件。",
    val latestCrashLabel: String? = null,
    val retentionSummary: String = AppLog.retentionPolicySummary(),
    val exportFileName: String = supportLogExportFileName(),
    val isExporting: Boolean = false,
) {
    val hasRuntimeLogs: Boolean
        get() = runtimeLogCount > 0

    val hasCrashLogs: Boolean
        get() = crashLogCount > 0

    val hasAnyLogs: Boolean
        get() = hasRuntimeLogs || hasCrashLogs
}

data class SettingsUiState(
    val accountName: String = "",
    val avatarUrl: String? = null,
    val savedAccounts: List<SavedSteamAccount> = emptyList(),
    val isLoginFeatureEnabled: Boolean = false,
    val isLoggedInDownloadEnabled: Boolean = false,
    val isOwnedGamesDisplayEnabled: Boolean = false,
    val isSubscriptionDisplayEnabled: Boolean = false,
    val autoCheckAppUpdates: Boolean = true,
    val defaultGuestMode: Boolean = true,
    val downloadFolderName: String = DEFAULT_DOWNLOAD_FOLDER_NAME,
    val effectiveDownloadFolderName: String = DEFAULT_DOWNLOAD_FOLDER_NAME,
    val downloadTreeUri: String? = null,
    val downloadTreeLabel: String? = null,
    val downloadChunkConcurrency: Int = DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY,
    val preferAnonymousDownloads: Boolean = true,
    val allowAuthenticatedDownloadFallback: Boolean = true,
    val cdnTransportPreference: CdnTransportPreference = CdnTransportPreference.Auto,
    val cdnPoolPreference: CdnPoolPreference = CdnPoolPreference.Auto,
    val workshopPageSize: Int = WorkshopBrowseQuery.DEFAULT_PAGE_SIZE,
    val workshopAutoResolveVisibleItems: Boolean = true,
    val supportLogs: SupportLogUiState = SupportLogUiState(),
    val maintenanceSummary: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesStore: UserPreferencesStore,
    private val steamRepository: SteamRepository,
    private val downloadsRepository: DownloadsRepository,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val avatarUrl = MutableStateFlow<String?>(null)
    private val maintenanceState = MutableStateFlow(MaintenanceUiState())
    private val supportLogsState = MutableStateFlow(SupportLogUiState())
    private val preferences = preferencesStore.preferences

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences,
        steamRepository.sessionState,
        avatarUrl,
        maintenanceState,
        supportLogsState,
    ) { prefs, session, avatar, maintenance, supportLogs ->
        val accountName = session.account?.accountName
            ?.takeIf(String::isNotBlank)
            ?: prefs.accountName
        val effectiveFolderName = normalizeDownloadFolderName(prefs.downloadFolderName)

        SettingsUiState(
            accountName = accountName,
            avatarUrl = avatar,
            savedAccounts = prefs.savedAccounts,
            isLoginFeatureEnabled = prefs.isLoginFeatureEnabled,
            isLoggedInDownloadEnabled = prefs.isLoggedInDownloadEnabled,
            isOwnedGamesDisplayEnabled = prefs.isOwnedGamesDisplayEnabled,
            isSubscriptionDisplayEnabled = prefs.isSubscriptionDisplayEnabled,
            autoCheckAppUpdates = prefs.autoCheckAppUpdates,
            defaultGuestMode = prefs.defaultGuestMode,
            downloadFolderName = prefs.downloadFolderName,
            effectiveDownloadFolderName = effectiveFolderName,
            downloadTreeUri = prefs.downloadTreeUri,
            downloadTreeLabel = prefs.downloadTreeLabel,
            downloadChunkConcurrency = prefs.downloadChunkConcurrency,
            preferAnonymousDownloads = prefs.preferAnonymousDownloads,
            allowAuthenticatedDownloadFallback = prefs.allowAuthenticatedDownloadFallback,
            cdnTransportPreference = prefs.cdnTransportPreference,
            cdnPoolPreference = prefs.cdnPoolPreference,
            workshopPageSize = prefs.workshopPageSize,
            workshopAutoResolveVisibleItems = prefs.workshopAutoResolveVisibleItems,
            supportLogs = supportLogs,
            maintenanceSummary = maintenance.summary,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(),
    )

    init {
        viewModelScope.launch {
            combine(
                preferences,
                steamRepository.sessionState,
            ) { prefs: UserPreferences, session ->
                session.account?.steamId64
                    ?.takeIf { it > 0L }
                    ?: prefs.steamId64
            }
                .distinctUntilChanged()
                .collect { steamId64 ->
                    avatarUrl.value = fetchAvatarUrl(steamId64)
                }
        }
        refreshSupportLogs()
    }

    fun saveDownloadTree(uri: String, label: String) {
        viewModelScope.launch {
            preferencesStore.saveDownloadTree(uri, label)
        }
    }

    fun saveDownloadFolderName(folderName: String) {
        viewModelScope.launch {
            preferencesStore.saveDownloadFolderName(folderName)
        }
    }

    fun restoreDefaultDownloadLocation() {
        viewModelScope.launch {
            preferencesStore.clearDownloadTree()
            preferencesStore.saveDownloadFolderName(DEFAULT_DOWNLOAD_FOLDER_NAME)
        }
    }

    fun saveDownloadChunkConcurrency(concurrency: Int) {
        viewModelScope.launch {
            preferencesStore.saveDownloadChunkConcurrency(concurrency)
        }
    }

    fun saveDefaultGuestMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveDefaultGuestMode(enabled)
        }
    }

    fun saveLoginFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveLoginFeatureEnabled(enabled)
            if (!enabled) {
                downloadsRepository.enforceAnonymousOnly("已关闭登录功能，涉及账号的下载任务已停止")
                steamRepository.logout()
                avatarUrl.value = null
            }
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启登录功能。后续是否显示已购、订阅和登录后下载，仍需单独打开。"
                } else {
                    "已关闭登录功能。当前只保留匿名能力，登录恢复、已保存账号和账号型下载已收起。"
                },
            )
        }
    }

    fun saveLoggedInDownloadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveLoggedInDownloadEnabled(enabled)
            if (!enabled) {
                downloadsRepository.enforceAnonymousOnly("已关闭“登录后下载”，涉及账号的下载任务已停止")
            }
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启“登录后下载”。只有需要账号下载的场景才会使用当前登录态。"
                } else {
                    "已关闭“登录后下载”。后续下载只会走匿名能力。"
                },
            )
        }
    }

    fun saveOwnedGamesDisplayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveOwnedGamesDisplayEnabled(enabled)
            if (!enabled) {
                steamRepository.clearOwnedGamesCache()
            }
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启“用户已购买标识展示”。"
                } else {
                    "已关闭“用户已购买标识展示”，并清除了本地已购缓存。"
                },
            )
        }
    }

    fun saveSubscriptionDisplayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveSubscriptionDisplayEnabled(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启“用户已订阅展示”。"
                } else {
                    "已关闭“用户已订阅展示”。"
                },
            )
        }
    }

    fun saveAutoCheckAppUpdates(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveAutoCheckAppUpdates(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启启动时自动检查更新。"
                } else {
                    "已关闭启动时自动检查更新，仍可手动检查。"
                },
            )
        }
    }

    fun removeSavedAccount(accountKey: String) {
        viewModelScope.launch {
            preferencesStore.removeSavedAccount(accountKey)
        }
    }

    fun savePreferAnonymousDownloads(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.savePreferAnonymousDownloads(enabled)
        }
    }

    fun saveAllowAuthenticatedDownloadFallback(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveAllowAuthenticatedDownloadFallback(enabled)
        }
    }

    fun saveCdnTransportPreference(preference: CdnTransportPreference) {
        viewModelScope.launch {
            preferencesStore.saveCdnTransportPreference(preference)
        }
    }

    fun saveCdnPoolPreference(preference: CdnPoolPreference) {
        viewModelScope.launch {
            preferencesStore.saveCdnPoolPreference(preference)
        }
    }

    fun saveWorkshopPageSize(pageSize: Int) {
        viewModelScope.launch {
            preferencesStore.saveWorkshopPageSize(pageSize)
        }
    }

    fun saveWorkshopAutoResolveVisibleItems(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveWorkshopAutoResolveVisibleItems(enabled)
        }
    }

    fun clearOwnedGamesCache() {
        viewModelScope.launch {
            steamRepository.clearOwnedGamesCache()
            maintenanceState.value = MaintenanceUiState(
                summary = "已清除游戏库缓存，下次进入“我的内容”会重新读取 Steam。",
            )
        }
    }

    fun clearInactiveDownloadDiagnostics() {
        viewModelScope.launch {
            val updated = downloadsRepository.clearInactiveDiagnostics()
            maintenanceState.value = MaintenanceUiState(
                summary = if (updated > 0) {
                    "已清除 $updated 条下载记录的诊断信息。"
                } else {
                    "没有可清除的下载诊断信息。"
                },
            )
        }
    }

    fun clearInactiveDownloadHistory() {
        viewModelScope.launch {
            val deleted = downloadsRepository.clearInactiveHistory()
            maintenanceState.value = MaintenanceUiState(
                summary = if (deleted > 0) {
                    "已删除 $deleted 条已完成或失败的下载记录。"
                } else {
                    "没有可删除的下载历史。"
                },
            )
        }
    }

    fun clearFavoriteWorkshopGames() {
        viewModelScope.launch {
            preferencesStore.clearFavoriteWorkshopGames()
            maintenanceState.value = MaintenanceUiState(
                summary = "已清除收藏的工坊游戏列表。",
            )
        }
    }

    fun clearAllAccountData() {
        viewModelScope.launch {
            steamRepository.logout()
            preferencesStore.clearAllAccountData()
            avatarUrl.value = null
            maintenanceState.value = MaintenanceUiState(
                summary = "已清除本地保存的登录状态、已保存账号和游戏库快照。",
            )
        }
    }

    fun exportSupportLogs(targetUri: Uri) {
        viewModelScope.launch {
            supportLogsState.value = supportLogsState.value.copy(isExporting = true)
            val result = AppLog.exportToUri(targetUri)
            supportLogsState.value = supportLogsState.value.copy(isExporting = false)
            maintenanceState.value = MaintenanceUiState(
                summary = result.fold(
                    onSuccess = { exported ->
                        "已导出日志包 ${exported.fileName}，共 ${exported.exportedEntryCount} 个文件，大小 ${formatBytes(exported.totalBytes)}。"
                    },
                    onFailure = { error ->
                        "日志导出失败：${error.message ?: "无法写入目标文件"}"
                    },
                ),
            )
            refreshSupportLogs()
        }
    }

    fun clearRuntimeLogs() {
        viewModelScope.launch {
            val deleted = AppLog.clearRuntimeLogs()
            maintenanceState.value = MaintenanceUiState(
                summary = if (deleted.deletedFileCount > 0) {
                    "已清除 ${deleted.deletedFileCount} 份运行日志，释放 ${formatBytes(deleted.reclaimedBytes)}。"
                } else {
                    "当前没有可清除的运行日志。"
                },
            )
            refreshSupportLogs()
        }
    }

    fun clearCrashLogs() {
        viewModelScope.launch {
            val deleted = AppLog.clearCrashLogs()
            maintenanceState.value = MaintenanceUiState(
                summary = if (deleted.deletedFileCount > 0) {
                    "已清除 ${deleted.deletedFileCount} 份崩溃日志，释放 ${formatBytes(deleted.reclaimedBytes)}。"
                } else {
                    "当前没有可清除的崩溃日志。"
                },
            )
            refreshSupportLogs()
        }
    }

    fun clearAllSupportLogs() {
        viewModelScope.launch {
            val deleted = AppLog.clearAllLogs()
            maintenanceState.value = MaintenanceUiState(
                summary = if (deleted.deletedFileCount > 0) {
                    "已清除全部日志文件，共 ${deleted.deletedFileCount} 份，释放 ${formatBytes(deleted.reclaimedBytes)}。"
                } else {
                    "当前没有可清除的日志文件。"
                },
            )
            refreshSupportLogs()
        }
    }

    private suspend fun fetchAvatarUrl(steamId64: Long): String? {
        if (steamId64 <= 0L) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://steamcommunity.com/profiles/$steamId64?xml=1")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val payload = response.body?.string().orEmpty()
                    AVATAR_REGEX.find(payload)?.groupValues?.getOrNull(1)
                }
            }.onFailure { error ->
                AppLog.w(LOG_TAG, "fetchAvatarUrl failed steamId64=$steamId64", error)
            }.getOrNull()
        }
    }

    private companion object {
        const val LOG_TAG = "SettingsViewModel"
        val AVATAR_REGEX = Regex("<avatarFull><!\\[CDATA\\[(.*?)]]></avatarFull>")
        val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
            .withZone(ZoneId.systemDefault())
    }

    private fun refreshSupportLogs() {
        viewModelScope.launch {
            val current = supportLogsState.value
            supportLogsState.value = withContext(Dispatchers.IO) {
                buildSupportLogState(isExporting = current.isExporting)
            }
        }
    }

    private fun buildSupportLogState(isExporting: Boolean): SupportLogUiState {
        val summary = AppLog.summary()
        val summaryText = when {
            summary.hasLogs -> buildString {
                append("当前已记录 ${summary.runtimeLogCount} 份运行日志")
                if (summary.crashLogCount > 0) {
                    append("、${summary.crashLogCount} 份崩溃日志")
                }
                append("，共 ${formatBytes(summary.totalBytes)}。")
            }
            else -> "当前还没有可导出的日志文件。"
        }
        return SupportLogUiState(
            runtimeLogCount = summary.runtimeLogCount,
            crashLogCount = summary.crashLogCount,
            totalSizeLabel = formatBytes(summary.totalBytes),
            summary = summaryText,
            latestCrashLabel = summary.latestCrashAtMillis?.let { millis ->
                "最近一次崩溃日志生成于 ${DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(millis))}"
            },
            retentionSummary = AppLog.retentionPolicySummary(),
            exportFileName = supportLogExportFileName(),
            isExporting = isExporting,
        )
    }
}

private data class MaintenanceUiState(
    val summary: String? = null,
)

private fun supportLogExportFileName(nowMillis: Long = System.currentTimeMillis()): String {
    return "workshop-native-logs-" + DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(nowMillis)) + ".zip"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
