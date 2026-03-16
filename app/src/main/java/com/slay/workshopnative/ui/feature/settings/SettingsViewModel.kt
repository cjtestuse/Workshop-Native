package com.slay.workshopnative.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import javax.inject.Inject

data class SettingsUiState(
    val accountName: String = "",
    val avatarUrl: String? = null,
    val savedAccounts: List<SavedSteamAccount> = emptyList(),
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
    private val preferences = preferencesStore.preferences

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences,
        steamRepository.sessionState,
        avatarUrl,
        maintenanceState,
    ) { prefs, session, avatar, maintenance ->
        val accountName = session.account?.accountName
            ?.takeIf(String::isNotBlank)
            ?: prefs.accountName
        val effectiveFolderName = normalizeDownloadFolderName(prefs.downloadFolderName)

        SettingsUiState(
            accountName = accountName,
            avatarUrl = avatar,
            savedAccounts = prefs.savedAccounts,
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
            }.getOrNull()
        }
    }

    private companion object {
        val AVATAR_REGEX = Regex("<avatarFull><!\\[CDATA\\[(.*?)]]></avatarFull>")
    }
}

private data class MaintenanceUiState(
    val summary: String? = null,
)
