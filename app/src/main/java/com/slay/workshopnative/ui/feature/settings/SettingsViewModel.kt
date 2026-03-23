package com.slay.workshopnative.ui.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.core.logging.MainActivityRuntimeTracker
import com.slay.workshopnative.core.logging.SupportBundleTextEntry
import com.slay.workshopnative.core.logging.SupportCdnExportSnapshot
import com.slay.workshopnative.core.logging.SupportDiagnosticsSnapshot
import com.slay.workshopnative.core.logging.SupportDiagnosticsStore
import com.slay.workshopnative.core.logging.SupportDownloadTaskSnapshot
import com.slay.workshopnative.core.logging.SupportSessionSnapshot
import com.slay.workshopnative.core.logging.SupportSettingsSnapshot
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.core.storage.DEFAULT_DOWNLOAD_FOLDER_NAME
import com.slay.workshopnative.core.storage.normalizeDownloadFolderName
import com.slay.workshopnative.core.util.buildAccountBindingHash
import com.slay.workshopnative.core.util.sanitizeRuntimeSourceAddress
import com.slay.workshopnative.core.util.toUserMessage
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.preferences.CdnPoolPreference
import com.slay.workshopnative.data.preferences.CdnTransportPreference
import com.slay.workshopnative.data.preferences.AppThemeMode
import com.slay.workshopnative.data.preferences.DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY
import com.slay.workshopnative.data.preferences.DEFAULT_AZURE_TRANSLATOR_ENDPOINT
import com.slay.workshopnative.data.preferences.DEFAULT_TRANSLATION_PROVIDER
import com.slay.workshopnative.data.preferences.DEFAULT_APP_THEME_MODE
import com.slay.workshopnative.data.preferences.DownloadPerformanceMode
import com.slay.workshopnative.data.local.DownloadTaskDao
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.repository.SteamRepository
import com.slay.workshopnative.data.repository.TranslationRepository
import com.slay.workshopnative.data.preferences.SavedSteamAccount
import com.slay.workshopnative.data.preferences.TranslationProvider
import com.slay.workshopnative.data.preferences.UserPreferences
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.preferences.displayLabel
import com.slay.workshopnative.data.preferences.isReady
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val autoCheckAppUpdates: Boolean = false,
    val autoCheckDownloadedModUpdatesOnLaunch: Boolean = false,
    val defaultGuestMode: Boolean = true,
    val downloadFolderName: String = DEFAULT_DOWNLOAD_FOLDER_NAME,
    val effectiveDownloadFolderName: String = DEFAULT_DOWNLOAD_FOLDER_NAME,
    val downloadTreeUri: String? = null,
    val downloadTreeLabel: String? = null,
    val downloadPerformanceMode: DownloadPerformanceMode = DownloadPerformanceMode.Auto,
    val downloadChunkConcurrency: Int = DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY,
    val primordialReducedMemoryProtectionEnabled: Boolean = false,
    val primordialAuthenticatedAggressiveCdnEnabled: Boolean = false,
    val preferAnonymousDownloads: Boolean = true,
    val allowAuthenticatedDownloadFallback: Boolean = true,
    val cdnTransportPreference: CdnTransportPreference = CdnTransportPreference.Auto,
    val cdnPoolPreference: CdnPoolPreference = CdnPoolPreference.Auto,
    val workshopPageSize: Int = WorkshopBrowseQuery.DEFAULT_PAGE_SIZE,
    val workshopAutoResolveVisibleItems: Boolean = false,
    val animatedWorkshopPreviewEnabled: Boolean = false,
    val terrariaArchivePostProcessorEnabled: Boolean = false,
    val terrariaArchiveKeepOriginal: Boolean = false,
    val wallpaperEnginePkgExtractEnabled: Boolean = false,
    val wallpaperEnginePkgKeepOriginal: Boolean = false,
    val wallpaperEngineTexConversionEnabled: Boolean = false,
    val wallpaperEngineKeepConvertedTexOriginal: Boolean = true,
    val translationProvider: TranslationProvider = DEFAULT_TRANSLATION_PROVIDER,
    val translationAzureEndpoint: String = DEFAULT_AZURE_TRANSLATOR_ENDPOINT,
    val translationAzureRegion: String = "",
    val translationAzureApiKey: String = "",
    val translationGoogleApiKey: String = "",
    val themeMode: AppThemeMode = DEFAULT_APP_THEME_MODE,
    val isTranslationConfigured: Boolean = DEFAULT_TRANSLATION_PROVIDER.isReady(
        azureEndpoint = DEFAULT_AZURE_TRANSLATOR_ENDPOINT,
        azureApiKey = "",
        googleApiKey = "",
    ),
    val isVerifyingTranslation: Boolean = false,
    val translationStatusSummary: String? = null,
    val supportLogs: SupportLogUiState = SupportLogUiState(),
    val maintenanceSummary: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesStore: UserPreferencesStore,
    private val steamRepository: SteamRepository,
    private val downloadsRepository: DownloadsRepository,
    private val translationRepository: TranslationRepository,
    private val downloadTaskDao: DownloadTaskDao,
    private val okHttpClient: OkHttpClient,
    private val supportDiagnosticsStore: SupportDiagnosticsStore,
    private val json: Json,
) : ViewModel() {
    private data class AvatarCacheEntry(
        val steamId64: Long,
        val avatarUrl: String?,
        val fetchedAtMs: Long,
    )

    private val avatarUrl = MutableStateFlow<String?>(null)
    private val maintenanceState = MutableStateFlow(MaintenanceUiState())
    private val translationStatusState = MutableStateFlow(TranslationStatusUiState())
    private val supportLogsState = MutableStateFlow(SupportLogUiState())
    private val preferences = preferencesStore.preferences
    private var avatarCacheEntry: AvatarCacheEntry? = null
    private val auxiliaryUiState = combine(
        maintenanceState,
        translationStatusState,
        supportLogsState,
    ) { maintenance, translationStatus, supportLogs ->
        Triple(maintenance, translationStatus, supportLogs)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences,
        steamRepository.sessionState,
        avatarUrl,
        auxiliaryUiState,
    ) { prefs, session, avatar, auxiliary ->
        val (maintenance, translationStatus, supportLogs) = auxiliary
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
            autoCheckDownloadedModUpdatesOnLaunch = prefs.autoCheckDownloadedModUpdatesOnLaunch,
            defaultGuestMode = prefs.defaultGuestMode,
            downloadFolderName = prefs.downloadFolderName,
            effectiveDownloadFolderName = effectiveFolderName,
            downloadTreeUri = prefs.downloadTreeUri,
            downloadTreeLabel = prefs.downloadTreeLabel,
            downloadPerformanceMode = prefs.downloadPerformanceMode,
            downloadChunkConcurrency = prefs.downloadChunkConcurrency,
            primordialReducedMemoryProtectionEnabled = prefs.primordialReducedMemoryProtectionEnabled,
            primordialAuthenticatedAggressiveCdnEnabled = prefs.primordialAuthenticatedAggressiveCdnEnabled,
            preferAnonymousDownloads = prefs.preferAnonymousDownloads,
            allowAuthenticatedDownloadFallback = prefs.allowAuthenticatedDownloadFallback,
            cdnTransportPreference = prefs.cdnTransportPreference,
            cdnPoolPreference = prefs.cdnPoolPreference,
            workshopPageSize = prefs.workshopPageSize,
            workshopAutoResolveVisibleItems = prefs.workshopAutoResolveVisibleItems,
            animatedWorkshopPreviewEnabled = prefs.animatedWorkshopPreviewEnabled,
            terrariaArchivePostProcessorEnabled = prefs.terrariaArchivePostProcessorEnabled,
            terrariaArchiveKeepOriginal = prefs.terrariaArchiveKeepOriginal,
            wallpaperEnginePkgExtractEnabled = prefs.wallpaperEnginePkgExtractEnabled,
            wallpaperEnginePkgKeepOriginal = prefs.wallpaperEnginePkgKeepOriginal,
            wallpaperEngineTexConversionEnabled = prefs.wallpaperEngineTexConversionEnabled,
            wallpaperEngineKeepConvertedTexOriginal = prefs.wallpaperEngineKeepConvertedTexOriginal,
            translationProvider = prefs.translationProvider,
            translationAzureEndpoint = prefs.translationAzureEndpoint,
            translationAzureRegion = prefs.translationAzureRegion,
            translationAzureApiKey = prefs.translationAzureApiKey,
            translationGoogleApiKey = prefs.translationGoogleApiKey,
            themeMode = prefs.themeMode,
            isTranslationConfigured = prefs.isTranslationConfigured,
            isVerifyingTranslation = translationStatus.isVerifying,
            translationStatusSummary = translationStatus.summary,
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

    fun saveDownloadPerformanceMode(mode: DownloadPerformanceMode) {
        viewModelScope.launch {
            preferencesStore.saveDownloadPerformanceMode(mode)
            maintenanceState.value = MaintenanceUiState(
                summary = when (mode) {
                    DownloadPerformanceMode.Auto ->
                        "已切到自动高性能下载。高内存设备会放宽并发，低内存设备仍会自动收紧。"
                    DownloadPerformanceMode.Compatibility ->
                        "已切到兼容模式。下载会更保守地限制并发和连接激进度。"
                    DownloadPerformanceMode.Primordial ->
                        "已切到洪荒模式。下载会使用更激进的并发与节点策略，可能更快，但更容易带来发热、波动或失败。"
                },
            )
        }
    }

    fun savePrimordialReducedMemoryProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.savePrimordialReducedMemoryProtectionEnabled(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启“降低内存保护强度”。仅对洪荒模式生效，会更激进地放宽并发收紧，速度可能更高，但也更容易带来发热、卡顿、掉登录、下载失败或闪退。"
                } else {
                    "已关闭“降低内存保护强度”。洪荒模式恢复为带保护的实验档。"
                },
            )
        }
    }

    fun savePrimordialAuthenticatedAggressiveCdnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.savePrimordialAuthenticatedAggressiveCdnEnabled(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启“已登录下载使用激进 CDN 策略”。洪荒模式下，账号下载也会使用更激进的首连、选路和并发路由策略，更容易提速，但也更容易遇到限流、掉登录、失败重试或会话波动。"
                } else {
                    "已关闭“已登录下载使用激进 CDN 策略”。洪荒模式下，只有匿名下载会默认使用更激进的 CDN 优化。"
                },
            )
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
                avatarCacheEntry = null
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

    fun saveAutoCheckDownloadedModUpdatesOnLaunch(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveAutoCheckDownloadedModUpdatesOnLaunch(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启“启动时检查已下载更新”。应用冷启动时会按公开工坊数据检查一次已下载条目更新，并在发现可更新条目时弹出选择窗口。"
                } else {
                    "已关闭“启动时检查已下载更新”。后续只会在你手动点“检查已下载更新”时检查。"
                },
            )
        }
    }

    fun saveAppThemeMode(themeMode: AppThemeMode) {
        viewModelScope.launch {
            preferencesStore.saveAppThemeMode(themeMode)
            maintenanceState.value = MaintenanceUiState(
                summary = "已切换到${themeMode.displayLabel()}主题。",
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
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启列表预读。当前可见工坊条目的下载方式会提前检测，请求量会略高。"
                } else {
                    "已关闭列表预读。工坊列表会先轻量加载，详情和下载方式改为点开后再检测。"
                },
            )
        }
    }

    fun saveAnimatedWorkshopPreviewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveAnimatedWorkshopPreviewEnabled(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启工坊动态预览。工坊列表会改用新的 GIF 预览渲染逻辑，Wallpaper Engine 这类条目的缩略图会动起来，但也会增加流量、内存和滚动负担。"
                } else {
                    "已关闭工坊动态预览。工坊列表已恢复为原来的静态缩略图逻辑。"
                },
            )
        }
    }

    fun saveTerrariaArchivePostProcessorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveTerrariaArchivePostProcessorEnabled(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启 Terraria 压缩导出。命中 Terraria 工坊下载时，会在下载完成后额外生成 _Post 压缩结果。"
                } else {
                    "已关闭 Terraria 压缩导出。下载流程已恢复为当前默认落盘逻辑。"
                },
            )
        }
    }

    fun saveTerrariaArchiveKeepOriginal(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveTerrariaArchiveKeepOriginal(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "Terraria 压缩导出将保留原目录，并额外生成 _Post 压缩结果。"
                } else {
                    "Terraria 压缩导出将只保留 _Post 压缩结果，不再额外保留原目录。"
                },
            )
        }
    }

    fun saveWallpaperEnginePkgExtractEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveWallpaperEnginePkgExtractEnabled(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启 Wallpaper Engine PKG 提取。命中 Wallpaper Engine 工坊下载且目录中包含 .pkg 时，会在下载完成后额外生成 _Post 提取结果。"
                } else {
                    "已关闭 Wallpaper Engine PKG 提取。Wallpaper Engine 工坊下载已恢复为当前默认落盘逻辑。"
                },
            )
        }
    }

    fun saveWallpaperEnginePkgKeepOriginal(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveWallpaperEnginePkgKeepOriginal(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "Wallpaper Engine PKG 提取将保留原目录，并额外生成 _Post 提取结果。"
                } else {
                    "Wallpaper Engine PKG 提取将只保留 _Post 提取结果，不再额外保留原目录。"
                },
            )
        }
    }

    fun saveWallpaperEngineTexConversionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveWallpaperEngineTexConversionEnabled(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "已开启 Wallpaper Engine TEX 转图片。命中可识别的 TEX 时，会额外尝试转换成 png、jpg 或 mp4。当前只覆盖最常见、最稳定的几类格式，不会生成 .mpkg。"
                } else {
                    "已关闭 Wallpaper Engine TEX 转图片。Wallpaper Engine 后处理将只提取 PKG，不再额外尝试转换 TEX。"
                },
            )
        }
    }

    fun saveWallpaperEngineKeepConvertedTexOriginal(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.saveWallpaperEngineKeepConvertedTexOriginal(enabled)
            maintenanceState.value = MaintenanceUiState(
                summary = if (enabled) {
                    "Wallpaper Engine TEX 转图片后将保留原始 TEX，避免因部分格式暂不支持而丢失原始资源。"
                } else {
                    "Wallpaper Engine TEX 转图片后会删除已成功转换的原始 TEX，只保留转换结果。"
                },
            )
        }
    }

    fun saveTranslationSettings(
        provider: TranslationProvider,
        azureEndpoint: String,
        azureRegion: String,
        azureApiKey: String,
        googleApiKey: String,
    ) {
        viewModelScope.launch {
            preferencesStore.saveTranslationProvider(provider)
            preferencesStore.saveTranslationAzureEndpoint(azureEndpoint)
            preferencesStore.saveTranslationAzureRegion(azureRegion)
            preferencesStore.saveTranslationAzureApiKey(azureApiKey)
            preferencesStore.saveTranslationGoogleApiKey(googleApiKey)
            translationStatusState.value = TranslationStatusUiState(
                summary = when (provider) {
                    TranslationProvider.Disabled -> "已关闭简介翻译功能。"
                    TranslationProvider.AzureTranslator -> "已保存 Azure Translator 配置。"
                    TranslationProvider.GoogleCloudTranslate -> "已保存 Google Cloud Translation 配置。"
                    TranslationProvider.GoogleWebTranslate -> "已启用 Google Web 实验翻译模式。"
                    TranslationProvider.MicrosoftEdgeTranslate -> "已启用 Microsoft Edge 实验翻译模式。"
                },
            )
        }
    }

    fun clearTranslationSettings() {
        viewModelScope.launch {
            preferencesStore.clearTranslationSettings()
            translationStatusState.value = TranslationStatusUiState(
                summary = "已清空翻译服务配置。",
            )
        }
    }

    fun verifyTranslationSettings() {
        viewModelScope.launch {
            translationStatusState.value = TranslationStatusUiState(
                isVerifying = true,
                summary = "正在测试当前翻译配置…",
            )
            translationRepository.translateToChinese(
                sourceText = "Hello Workshop Native",
                forceRefresh = true,
            )
                .onSuccess { result ->
                    translationStatusState.value = TranslationStatusUiState(
                        isVerifying = false,
                        summary = buildString {
                            append("翻译测试成功：")
                            append(result.provider.displayLabel())
                            result.detectedSourceLanguageLabel?.let { language ->
                                append("，识别原文为")
                                append(language)
                            }
                            append("。示例结果：")
                            append(result.translatedText.take(18))
                        },
                    )
                }
                .onFailure { error ->
                    translationStatusState.value = TranslationStatusUiState(
                        isVerifying = false,
                        summary = error.toUserMessage("翻译测试失败"),
                    )
                }
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
            avatarCacheEntry = null
            maintenanceState.value = MaintenanceUiState(
                summary = "已清除本地保存的登录状态、已保存账号和游戏库快照。",
            )
        }
    }

    fun exportSupportLogs(targetUri: Uri) {
        viewModelScope.launch {
            supportLogsState.value = supportLogsState.value.copy(isExporting = true)
            val result = runCatching {
                val exportedAtMs = System.currentTimeMillis()
                val prefs = preferencesStore.snapshot()
                val session = steamRepository.sessionState.value
                val tasks = withContext(Dispatchers.IO) { downloadTaskDao.getAll() }
                val diagnosticsSnapshot = supportDiagnosticsStore.snapshot()
                val extraEntries = buildSupportBundleEntries(
                    exportedAtMs = exportedAtMs,
                    prefs = prefs,
                    sessionStatus = session.status.name,
                    connectionRevision = session.connectionRevision,
                    hasAccount = session.account != null,
                    currentAccountBindingHash = buildAccountBindingHash(
                        accountName = session.account?.accountName ?: prefs.accountName,
                        steamId64 = session.account?.steamId64 ?: prefs.steamId64,
                    ),
                    hasRefreshToken = prefs.refreshToken.isNotBlank(),
                    savedAccountsCount = prefs.savedAccounts.size,
                    downloads = tasks,
                    diagnosticsSnapshot = diagnosticsSnapshot,
                )
                AppLog.exportSupportBundle(
                    targetUri = targetUri,
                    extraEntries = extraEntries,
                ).getOrThrow()
            }
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
        avatarCacheEntry
            ?.takeIf { cached ->
                cached.steamId64 == steamId64 &&
                    System.currentTimeMillis() - cached.fetchedAtMs <= AVATAR_CACHE_TTL_MS
            }
            ?.let { cached ->
                return cached.avatarUrl
            }
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
            }.onSuccess { resolvedUrl ->
                avatarCacheEntry = AvatarCacheEntry(
                    steamId64 = steamId64,
                    avatarUrl = resolvedUrl,
                    fetchedAtMs = System.currentTimeMillis(),
                )
            }.onFailure { error ->
                AppLog.w(LOG_TAG, "fetchAvatarUrl failed", error)
            }.getOrNull()
        }
    }

    private companion object {
        const val LOG_TAG = "SettingsViewModel"
        const val AVATAR_CACHE_TTL_MS = 12 * 60 * 60 * 1000L
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

    private fun buildSupportBundleEntries(
        exportedAtMs: Long,
        prefs: UserPreferences,
        sessionStatus: String,
        connectionRevision: Long,
        hasAccount: Boolean,
        currentAccountBindingHash: String?,
        hasRefreshToken: Boolean,
        savedAccountsCount: Int,
        downloads: List<DownloadTaskEntity>,
        diagnosticsSnapshot: SupportDiagnosticsSnapshot,
    ): List<SupportBundleTextEntry> {
        val downloadSnapshots = downloads
            .sortedByDescending(DownloadTaskEntity::createdAt)
            .map(::toSupportDownloadTaskSnapshot)
        val activityRuntimeSnapshot = MainActivityRuntimeTracker.snapshot()
        val sessionSnapshot = SupportSessionSnapshot(
            exportedAtMs = exportedAtMs,
            sessionStatus = sessionStatus,
            connectionRevision = connectionRevision,
            hasAccount = hasAccount,
            hasRefreshToken = hasRefreshToken,
            isGuestMode = sessionStatus != "Authenticated",
            savedAccountsCount = savedAccountsCount,
            currentAccountBindingHashPrefix = currentAccountBindingHash?.take(12),
            runtime = steamRepository.sessionRuntimeSnapshot(),
            activityRuntime = activityRuntimeSnapshot,
        )
        val settingsSnapshot = SupportSettingsSnapshot(
            exportedAtMs = exportedAtMs,
            isLoginFeatureEnabled = prefs.isLoginFeatureEnabled,
            isLoggedInDownloadEnabled = prefs.isLoggedInDownloadEnabled,
            isOwnedGamesDisplayEnabled = prefs.isOwnedGamesDisplayEnabled,
            isSubscriptionDisplayEnabled = prefs.isSubscriptionDisplayEnabled,
            autoCheckAppUpdates = prefs.autoCheckAppUpdates,
            autoCheckDownloadedModUpdatesOnLaunch = prefs.autoCheckDownloadedModUpdatesOnLaunch,
            defaultGuestMode = prefs.defaultGuestMode,
            preferAnonymousDownloads = prefs.preferAnonymousDownloads,
            allowAuthenticatedDownloadFallback = prefs.allowAuthenticatedDownloadFallback,
            cdnTransportPreference = prefs.cdnTransportPreference.name,
            cdnPoolPreference = prefs.cdnPoolPreference.name,
            downloadPerformanceMode = prefs.downloadPerformanceMode.name,
            requestedChunkConcurrency = prefs.downloadChunkConcurrency,
            primordialReducedMemoryProtectionEnabled = prefs.primordialReducedMemoryProtectionEnabled,
            primordialAuthenticatedAggressiveCdnEnabled = prefs.primordialAuthenticatedAggressiveCdnEnabled,
            workshopPageSize = prefs.workshopPageSize,
            workshopAutoResolveVisibleItems = prefs.workshopAutoResolveVisibleItems,
            animatedWorkshopPreviewEnabled = prefs.animatedWorkshopPreviewEnabled,
            terrariaArchivePostProcessorEnabled = prefs.terrariaArchivePostProcessorEnabled,
            terrariaArchiveKeepOriginal = prefs.terrariaArchiveKeepOriginal,
            wallpaperEnginePkgExtractEnabled = prefs.wallpaperEnginePkgExtractEnabled,
            wallpaperEnginePkgKeepOriginal = prefs.wallpaperEnginePkgKeepOriginal,
            wallpaperEngineTexConversionEnabled = prefs.wallpaperEngineTexConversionEnabled,
            wallpaperEngineKeepConvertedTexOriginal = prefs.wallpaperEngineKeepConvertedTexOriginal,
            hasCustomDownloadTree = !prefs.downloadTreeUri.isNullOrBlank(),
            hasCustomDownloadFolderName = prefs.downloadFolderName != DEFAULT_DOWNLOAD_FOLDER_NAME,
        )
        return listOf(
            SupportBundleTextEntry(
                name = "diagnostics/downloads_snapshot.json",
                payload = json.encodeToString(downloadSnapshots),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/download_timeline.jsonl",
                payload = diagnosticsSnapshot.downloadTimeline.toJsonLines(json),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/download_decisions.json",
                payload = json.encodeToString(diagnosticsSnapshot.downloadDecisions),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/cdn_summary.json",
                payload = json.encodeToString(
                    SupportCdnExportSnapshot(
                        tasks = diagnosticsSnapshot.cdnTaskSummaries,
                        hosts = diagnosticsSnapshot.cdnHostSnapshots,
                    ),
                ),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/performance_snapshot.json",
                payload = json.encodeToString(diagnosticsSnapshot.performanceSnapshots),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/session_snapshot.json",
                payload = json.encodeToString(sessionSnapshot),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/activity_snapshot.json",
                payload = json.encodeToString(activityRuntimeSnapshot),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/app_settings_snapshot.json",
                payload = json.encodeToString(settingsSnapshot),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/traffic_summary.json",
                payload = json.encodeToString(diagnosticsSnapshot.trafficCounters),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/search_summary.json",
                payload = json.encodeToString(diagnosticsSnapshot.searchSamples),
            ),
            SupportBundleTextEntry(
                name = "diagnostics/recovery_timeline.jsonl",
                payload = diagnosticsSnapshot.recoveryTimeline.toJsonLines(json),
            ),
        )
    }

    private fun toSupportDownloadTaskSnapshot(task: DownloadTaskEntity): SupportDownloadTaskSnapshot {
        val savedFileScheme = task.savedFileUri
            ?.let { uri -> runCatching { Uri.parse(uri).scheme }.getOrNull() }
        return SupportDownloadTaskSnapshot(
            taskId = task.taskId,
            publishedFileId = task.publishedFileId,
            appId = task.appId,
            title = task.title,
            status = task.status.name,
            downloadAuthMode = task.downloadAuthMode.name,
            boundAccountHashPrefix = task.boundAccountKeyHash?.take(12),
            progressPercent = task.progressPercent,
            bytesDownloaded = task.bytesDownloaded,
            totalBytes = task.totalBytes,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt,
            errorMessage = task.errorMessage,
            sourceAddress = exportSanitizedSourceAddress(task.sourceUrl),
            destinationType = task.exportDestinationType(),
            hasSavedFile = !task.savedFileUri.isNullOrBlank(),
            savedFileScheme = savedFileScheme,
            savedRelativePath = task.savedRelativePath,
            postProcessSummary = task.postProcessSummary,
            hasTargetTree = !task.targetTreeUri.isNullOrBlank(),
            storageRootKind = task.exportStorageRootKind(),
            runtimeRouteLabel = task.runtimeRouteLabel,
            runtimeTransportLabel = task.runtimeTransportLabel,
            runtimeEndpointLabel = task.runtimeEndpointLabel,
            runtimeSourceAddress = sanitizeRuntimeSourceAddress(task.runtimeSourceAddress),
            runtimeAttemptCount = task.runtimeAttemptCount,
            runtimeChunkConcurrency = task.runtimeChunkConcurrency,
            runtimeLastFailure = task.runtimeLastFailure,
        )
    }
}

private data class MaintenanceUiState(
    val summary: String? = null,
)

private data class TranslationStatusUiState(
    val isVerifying: Boolean = false,
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

private fun List<com.slay.workshopnative.core.logging.SupportStructuredEvent>.toJsonLines(json: Json): String {
    return joinToString(separator = "\n") { item ->
        json.encodeToString(item)
    }
}

private fun exportSanitizedSourceAddress(sourceUrl: String?): String? {
    if (sourceUrl.isNullOrBlank()) return null
    return sanitizeRuntimeSourceAddress(sourceUrl)
}

private fun DownloadTaskEntity.exportDestinationType(): String {
    return when {
        !targetTreeUri.isNullOrBlank() -> "TreeUri"
        savedFileUri == null -> "Pending"
        savedFileUri == com.slay.workshopnative.core.storage.MEDIASTORE_DOWNLOADS_URI_STRING ||
            savedFileUri.startsWith("content://media/external/downloads") -> "MediaStore"
        savedFileUri.startsWith("file:") -> "LocalFile"
        else -> "ContentUri"
    }
}

private fun DownloadTaskEntity.exportStorageRootKind(): String {
    return when {
        storageRootRef.isNullOrBlank() -> "none"
        storageRootRef.startsWith("tree:") -> "tree"
        storageRootRef.startsWith("downloads:") -> "downloads"
        storageRootRef.startsWith("local:") -> "local"
        else -> "other"
    }
}
