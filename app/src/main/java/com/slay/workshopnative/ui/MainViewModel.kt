package com.slay.workshopnative.ui

import com.slay.workshopnative.BuildConfig
import com.slay.workshopnative.core.logging.AppLog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.data.model.SteamSessionState
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.preferences.AppThemeMode
import com.slay.workshopnative.data.preferences.DEFAULT_APP_THEME_MODE
import com.slay.workshopnative.data.preferences.SavedSteamAccount
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.repository.SteamRepository
import com.slay.workshopnative.update.AppUpdateCheckResult
import com.slay.workshopnative.update.AppUpdateDownloadResolution
import com.slay.workshopnative.update.AppUpdateReleaseInfo
import com.slay.workshopnative.update.AppUpdateService
import com.slay.workshopnative.update.AppUpdateSource
import com.slay.workshopnative.update.AppUpdateVersioning
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val isChecking: Boolean = false,
    val summary: String? = null,
    val release: AppUpdateReleaseInfo? = null,
    val downloadResolution: AppUpdateDownloadResolution? = null,
    val hasUpdateAvailable: Boolean = false,
    val metadataSource: AppUpdateSource? = null,
    val lastCheckedAtMillis: Long? = null,
    val lastCheckSucceeded: Boolean? = null,
    val showUpdateDialog: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val steamRepository: SteamRepository,
    private val preferencesStore: UserPreferencesStore,
    private val downloadsRepository: DownloadsRepository,
    private val appUpdateService: AppUpdateService,
) : ViewModel() {
    private companion object {
        const val LOG_TAG = "MainViewModel"
        const val DEFAULT_ROOT_TAB_ROUTE = "explore"
        const val DEFAULT_WORKSHOP_MODE = "Browse"
        const val KEY_ROOT_TAB_ROUTE = "root_tab_route"
        const val KEY_ACTIVE_WORKSHOP_APP_ID = "active_workshop_app_id"
        const val KEY_ACTIVE_WORKSHOP_APP_NAME = "active_workshop_app_name"
        const val KEY_ACTIVE_WORKSHOP_MODE = "active_workshop_mode"
    }

    val sessionState: StateFlow<SteamSessionState> = steamRepository.sessionState
    private val _isBootstrapping = MutableStateFlow(true)
    val isBootstrapping: StateFlow<Boolean> = _isBootstrapping.asStateFlow()
    private val _guestMode = MutableStateFlow(false)
    val guestMode: StateFlow<Boolean> = _guestMode.asStateFlow()
    private val _appUpdateState = MutableStateFlow(AppUpdateUiState())
    val appUpdateState: StateFlow<AppUpdateUiState> = _appUpdateState.asStateFlow()
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()
    val currentRootTabRoute: StateFlow<String> =
        savedStateHandle.getStateFlow(KEY_ROOT_TAB_ROUTE, DEFAULT_ROOT_TAB_ROUTE)
    val activeWorkshopAppId: StateFlow<Int?> =
        savedStateHandle.getStateFlow(KEY_ACTIVE_WORKSHOP_APP_ID, null)
    val activeWorkshopAppName: StateFlow<String> =
        savedStateHandle.getStateFlow(KEY_ACTIVE_WORKSHOP_APP_NAME, "")
    val activeWorkshopMode: StateFlow<String> =
        savedStateHandle.getStateFlow(KEY_ACTIVE_WORKSHOP_MODE, DEFAULT_WORKSHOP_MODE)
    val hasAcknowledgedDisclaimer: StateFlow<Boolean?> = preferencesStore.preferences
        .map { it.hasAcknowledgedDisclaimer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasAcknowledgedUsageBoundary: StateFlow<Boolean?> = preferencesStore.preferences
        .map { it.hasAcknowledgedUsageBoundary }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val savedAccounts: StateFlow<List<SavedSteamAccount>> = preferencesStore.preferences
        .map { it.savedAccounts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val isLoginFeatureEnabled: StateFlow<Boolean> = preferencesStore.preferences
        .map { it.isLoginFeatureEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val showLibraryTab: StateFlow<Boolean> = preferencesStore.preferences
        .map { prefs -> prefs.isLoginFeatureEnabled && prefs.isOwnedGamesDisplayEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val themeMode: StateFlow<AppThemeMode> = preferencesStore.preferences
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_APP_THEME_MODE)

    init {
        viewModelScope.launch {
            val prefs = preferencesStore.snapshot()
            runCatching {
                steamRepository.bootstrap()
            }.onFailure { error ->
                AppLog.w(LOG_TAG, "bootstrap failed", error)
            }
            val currentStatus = steamRepository.sessionState.value.status
            if ((!prefs.isLoginFeatureEnabled || prefs.defaultGuestMode) && currentStatus != SessionStatus.Authenticated) {
                _guestMode.value = true
            }
            _isBootstrapping.value = false
        }
        viewModelScope.launch {
            steamRepository.sessionState.collectLatest { state ->
                if (state.status == SessionStatus.Authenticated) {
                    _guestMode.value = false
                }
            }
        }
        viewModelScope.launch {
            preferencesStore.preferences
                .map { prefs -> prefs.isLoginFeatureEnabled to prefs.isLoggedInDownloadEnabled }
                .distinctUntilChanged()
                .collectLatest { (loginEnabled, loggedInDownloadEnabled) ->
                    if (!loginEnabled && steamRepository.sessionState.value.status == SessionStatus.Authenticated) {
                        steamRepository.logout()
                    }
                    if (!loginEnabled || !loggedInDownloadEnabled) {
                        _guestMode.value = true
                        downloadsRepository.enforceAnonymousOnly(
                            if (!loginEnabled) {
                                "已关闭登录功能，涉及账号的下载任务已停止"
                            } else {
                                "已关闭“登录后下载”，涉及账号的下载任务已停止"
                            },
                        )
                    }
                }
        }
        viewModelScope.launch {
            steamRepository.sessionState
                .map { state -> state.status to (state.account?.steamId64 ?: 0L) }
                .distinctUntilChanged()
                .collectLatest { (status, _) ->
                    if (status == SessionStatus.Authenticated) {
                        downloadsRepository.rebindRetryableTasksToCurrentSession()
                    }
                }
        }
        viewModelScope.launch {
            val prefs = preferencesStore.snapshot()
            if (prefs.autoCheckAppUpdates) {
                checkForAppUpdates(userInitiated = false)
            } else {
                AppLog.i(LOG_TAG, "skip auto app update check because it is disabled")
            }
        }
    }

    fun retrySessionRestore() {
        steamRepository.retryRestore()
    }

    fun onAppForegrounded() {
        steamRepository.onAppForegrounded()
    }

    fun onAppBackgrounded() {
        steamRepository.onAppBackgrounded()
    }

    fun navigateRootTab(route: String) {
        savedStateHandle[KEY_ROOT_TAB_ROUTE] = route
    }

    fun openWorkshop(appId: Int, appName: String, launchMode: String) {
        savedStateHandle[KEY_ACTIVE_WORKSHOP_APP_ID] = appId
        savedStateHandle[KEY_ACTIVE_WORKSHOP_APP_NAME] = appName
        savedStateHandle[KEY_ACTIVE_WORKSHOP_MODE] = launchMode
    }

    fun closeWorkshop() {
        savedStateHandle[KEY_ACTIVE_WORKSHOP_APP_ID] = null
        savedStateHandle[KEY_ACTIVE_WORKSHOP_APP_NAME] = ""
        savedStateHandle[KEY_ACTIVE_WORKSHOP_MODE] = DEFAULT_WORKSHOP_MODE
    }

    fun login(username: String, password: String, rememberSession: Boolean) {
        _guestMode.value = false
        steamRepository.login(username, password, rememberSession)
    }

    fun submitAuthCode(code: String) {
        steamRepository.submitAuthCode(code)
    }

    fun enterGuestMode() {
        _guestMode.value = true
    }

    fun leaveGuestMode() {
        _guestMode.value = false
    }

    fun switchSavedAccount(accountKey: String) {
        _guestMode.value = false
        viewModelScope.launch {
            steamRepository.switchSavedAccount(accountKey)
        }
    }

    fun logout() {
        _guestMode.value = true
        viewModelScope.launch {
            steamRepository.logout()
        }
    }

    fun checkForAppUpdates() {
        viewModelScope.launch {
            checkForAppUpdates(userInitiated = true)
        }
    }

    fun dismissUpdateDialog() {
        _appUpdateState.value = _appUpdateState.value.copy(showUpdateDialog = false)
    }

    fun acknowledgeDisclaimer() {
        viewModelScope.launch {
            preferencesStore.saveDisclaimerAcknowledged()
        }
    }

    fun acknowledgeUsageBoundary() {
        viewModelScope.launch {
            preferencesStore.saveUsageBoundaryAcknowledged()
        }
    }

    private suspend fun checkForAppUpdates(userInitiated: Boolean) {
        val previousState = _appUpdateState.value
        if (previousState.isChecking) {
            AppLog.i(LOG_TAG, "app update check ignored because one is already running")
            if (userInitiated) {
                _userMessages.emit("正在检查更新")
            }
            return
        }

        AppLog.i(LOG_TAG, "starting app update check userInitiated=$userInitiated")

        _appUpdateState.value = previousState.copy(
            isChecking = true,
            summary = if (userInitiated) "正在检查 GitHub Release…" else previousState.summary,
        )

        when (val result = appUpdateService.checkForUpdates(
            currentVersion = BuildConfig.VERSION_NAME,
            preferredSource = AppUpdateSource.DEFAULT,
            validateReachability = userInitiated,
        )) {
            is AppUpdateCheckResult.Success -> {
                val now = System.currentTimeMillis()
                _appUpdateState.value = _appUpdateState.value.copy(
                    isChecking = false,
                    summary = if (result.hasUpdate) {
                        "发现新版本 ${result.release.rawTagName}"
                    } else {
                        "当前已是最新版本"
                    },
                    release = result.release,
                    downloadResolution = result.downloadResolution,
                    hasUpdateAvailable = result.hasUpdate,
                    metadataSource = result.metadataSource,
                    lastCheckedAtMillis = now,
                    lastCheckSucceeded = true,
                    showUpdateDialog = result.hasUpdate,
                )
                AppLog.i(
                    LOG_TAG,
                    "app update check succeeded hasUpdate=${result.hasUpdate} release=${result.release.rawTagName}",
                )
                if (userInitiated && !result.hasUpdate) {
                    _userMessages.emit("已经是最新版本 ${AppUpdateVersioning.normalizeVersionTag(result.currentVersion)}")
                }
            }

            is AppUpdateCheckResult.Failure -> {
                _appUpdateState.value = _appUpdateState.value.copy(
                    isChecking = false,
                    summary = result.errorSummary,
                    release = result.release,
                    downloadResolution = null,
                    hasUpdateAvailable = false,
                    metadataSource = result.metadataSource,
                    lastCheckedAtMillis = System.currentTimeMillis(),
                    lastCheckSucceeded = false,
                    showUpdateDialog = false,
                )
                AppLog.w(LOG_TAG, "app update check failed summary=${result.errorSummary}")
                if (userInitiated) {
                    _userMessages.emit(result.errorSummary)
                }
            }
        }
    }
}
