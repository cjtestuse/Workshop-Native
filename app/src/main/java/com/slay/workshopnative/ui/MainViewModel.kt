package com.slay.workshopnative.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.data.model.SteamSessionState
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.preferences.SavedSteamAccount
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.repository.SteamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val steamRepository: SteamRepository,
    private val preferencesStore: UserPreferencesStore,
    private val downloadsRepository: DownloadsRepository,
) : ViewModel() {

    val sessionState: StateFlow<SteamSessionState> = steamRepository.sessionState
    private val _isBootstrapping = MutableStateFlow(true)
    val isBootstrapping: StateFlow<Boolean> = _isBootstrapping.asStateFlow()
    private val _guestMode = MutableStateFlow(false)
    val guestMode: StateFlow<Boolean> = _guestMode.asStateFlow()
    val savedAccounts: StateFlow<List<SavedSteamAccount>> = preferencesStore.preferences
        .map { it.savedAccounts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val prefs = preferencesStore.snapshot()
            runCatching {
                steamRepository.bootstrap()
            }
            if (prefs.defaultGuestMode && steamRepository.sessionState.value.status != SessionStatus.Authenticated) {
                _guestMode.value = true
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
    }

    fun retrySessionRestore() {
        steamRepository.retryRestore()
    }

    fun onAppForegrounded() {
        steamRepository.onAppForegrounded()
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
}
