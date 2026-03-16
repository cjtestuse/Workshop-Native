package com.slay.workshopnative.ui

import com.slay.workshopnative.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.data.model.SteamSessionState
import com.slay.workshopnative.data.model.SessionStatus
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
    private val steamRepository: SteamRepository,
    private val preferencesStore: UserPreferencesStore,
    private val downloadsRepository: DownloadsRepository,
    private val appUpdateService: AppUpdateService,
) : ViewModel() {

    val sessionState: StateFlow<SteamSessionState> = steamRepository.sessionState
    private val _isBootstrapping = MutableStateFlow(true)
    val isBootstrapping: StateFlow<Boolean> = _isBootstrapping.asStateFlow()
    private val _guestMode = MutableStateFlow(false)
    val guestMode: StateFlow<Boolean> = _guestMode.asStateFlow()
    private val _appUpdateState = MutableStateFlow(AppUpdateUiState())
    val appUpdateState: StateFlow<AppUpdateUiState> = _appUpdateState.asStateFlow()
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()
    val savedAccounts: StateFlow<List<SavedSteamAccount>> = preferencesStore.preferences
        .map { it.savedAccounts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val prefs = preferencesStore.snapshot()
            runCatching {
                steamRepository.bootstrap()
            }
            val currentStatus = steamRepository.sessionState.value.status
            if (prefs.defaultGuestMode && currentStatus != SessionStatus.Authenticated) {
                _guestMode.value = true
            }
            if (currentStatus != SessionStatus.Authenticated) {
                steamRepository.prewarmAnonymousDownloadAccess()
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
            checkForAppUpdates(userInitiated = false)
        }
    }

    fun retrySessionRestore() {
        steamRepository.retryRestore()
    }

    fun onAppForegrounded() {
        steamRepository.onAppForegrounded()
        if (_guestMode.value || steamRepository.sessionState.value.status != SessionStatus.Authenticated) {
            steamRepository.prewarmAnonymousDownloadAccess()
        }
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
        steamRepository.prewarmAnonymousDownloadAccess()
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
            steamRepository.prewarmAnonymousDownloadAccess()
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

    private suspend fun checkForAppUpdates(userInitiated: Boolean) {
        val previousState = _appUpdateState.value
        if (previousState.isChecking) {
            if (userInitiated) {
                _userMessages.emit("正在检查更新")
            }
            return
        }

        _appUpdateState.value = previousState.copy(
            isChecking = true,
            summary = if (userInitiated) "正在检查 GitHub Release…" else previousState.summary,
        )

        when (val result = appUpdateService.checkForUpdates(BuildConfig.VERSION_NAME, AppUpdateSource.DEFAULT)) {
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
                if (userInitiated) {
                    _userMessages.emit(result.errorSummary)
                }
            }
        }
    }
}
