package com.slay.workshopnative.ui.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.core.util.toUserMessage
import com.slay.workshopnative.data.model.FavoriteWorkshopGame
import com.slay.workshopnative.data.model.GameDetails
import com.slay.workshopnative.data.model.OwnedGame
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.repository.SteamRepository
import com.slay.workshopnative.data.repository.WorkshopFavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val isLoginFeatureEnabled: Boolean = false,
    val isOwnedGamesDisplayEnabled: Boolean = false,
    val isSubscriptionDisplayEnabled: Boolean = false,
    val games: List<OwnedGame> = emptyList(),
    val query: String = "",
    val errorMessage: String? = null,
    val gameDetailsByAppId: Map<Int, GameDetails> = emptyMap(),
    val loadingDetailsAppId: Int? = null,
    val favoriteGames: List<FavoriteWorkshopGame> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val steamRepository: SteamRepository,
    private val favoritesRepository: WorkshopFavoritesRepository,
    private val preferencesStore: UserPreferencesStore,
) : ViewModel() {
    private companion object {
        const val LOG_TAG = "LibraryViewModel"
    }

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private var isLoginFeatureEnabled = false
    private var isOwnedGamesDisplayEnabled = false
    private var isSubscriptionDisplayEnabled = false
    private var currentSessionStatus = SessionStatus.Idle

    init {
        preloadSnapshot()
        observeFavorites()
        observePreferences()
        observeSession()
    }

    fun toggleFavorite(appId: Int, name: String, capsuleUrl: String?, previewUrl: String?) {
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(
                com.slay.workshopnative.data.model.WorkshopGameEntry(
                    appId = appId,
                    name = name,
                    capsuleUrl = capsuleUrl,
                    previewUrl = previewUrl,
                ),
            )
        }
    }

    fun markWorkshopOpened(appId: Int) {
        viewModelScope.launch {
            favoritesRepository.markOpened(appId)
        }
    }

    fun refresh() = refresh(forceRefresh = true)

    fun refresh(forceRefresh: Boolean) {
        if (!canDisplayOwnedGames()) {
            clearLibraryContent()
            return
        }
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            steamRepository.loadOwnedGames(forceRefresh = forceRefresh)
                .onSuccess { games ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            hasLoadedOnce = true,
                            games = games,
                        )
                    }
                }
                .onFailure { error ->
                    AppLog.w(LOG_TAG, "refresh failed forceRefresh=$forceRefresh", error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            hasLoadedOnce = true,
                            errorMessage = error.toUserMessage("读取游戏库失败"),
                        )
                    }
                }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun loadGameDetails(appId: Int) {
        if (!canDisplayOwnedGames()) return
        if (_uiState.value.gameDetailsByAppId.containsKey(appId)) return
        if (_uiState.value.loadingDetailsAppId == appId) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingDetailsAppId = appId) }
            steamRepository.loadGameDetails(appId)
                .onSuccess { details ->
                    _uiState.update {
                        it.copy(
                            loadingDetailsAppId = null,
                            gameDetailsByAppId = it.gameDetailsByAppId + (appId to details),
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state -> state.copy(loadingDetailsAppId = null) }
                }
        }
    }

    private fun preloadSnapshot() {
        viewModelScope.launch {
            if (!canDisplayOwnedGames()) return@launch
            steamRepository.loadOwnedGamesSnapshot()
                .onSuccess { games ->
                    if (games.isEmpty()) return@onSuccess
                    _uiState.update { state ->
                        if (state.games.isNotEmpty()) {
                            state
                        } else {
                            state.copy(
                                hasLoadedOnce = true,
                                games = games,
                            )
                        }
                    }
                }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesStore.preferences.collectLatest { prefs ->
                val wasLibraryVisible = canDisplayOwnedGames()
                isLoginFeatureEnabled = prefs.isLoginFeatureEnabled
                isOwnedGamesDisplayEnabled = prefs.isOwnedGamesDisplayEnabled
                isSubscriptionDisplayEnabled = prefs.isSubscriptionDisplayEnabled
                val isLibraryVisible = canDisplayOwnedGames()
                _uiState.update {
                    it.copy(
                        isLoginFeatureEnabled = isLoginFeatureEnabled,
                        isOwnedGamesDisplayEnabled = isOwnedGamesDisplayEnabled,
                        isSubscriptionDisplayEnabled = canShowSubscriptions(),
                    )
                }
                if (!isLibraryVisible) {
                    clearLibraryContent()
                    return@collectLatest
                }
                if (!wasLibraryVisible) {
                    preloadSnapshot()
                    if (currentSessionStatus == SessionStatus.Authenticated) {
                        refresh(forceRefresh = false)
                    }
                }
            }
        }
    }

    private fun observeSession() {
        viewModelScope.launch {
            var lastConnectionRevision = -1L
            steamRepository.sessionState.collectLatest { session ->
                currentSessionStatus = session.status
                val isAuthenticated = session.status == SessionStatus.Authenticated
                val currentRevision = session.connectionRevision
                if (isAuthenticated && canDisplayOwnedGames() && currentRevision != lastConnectionRevision) {
                    lastConnectionRevision = currentRevision
                    refresh(forceRefresh = false)
                }
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoritesRepository.favorites.collectLatest { favorites ->
                _uiState.update { it.copy(favoriteGames = favorites) }
            }
        }
    }

    private fun clearLibraryContent() {
        _uiState.update {
            it.copy(
                isLoading = false,
                hasLoadedOnce = false,
                games = emptyList(),
                errorMessage = null,
                gameDetailsByAppId = emptyMap(),
                loadingDetailsAppId = null,
                isLoginFeatureEnabled = isLoginFeatureEnabled,
                isOwnedGamesDisplayEnabled = isOwnedGamesDisplayEnabled,
                isSubscriptionDisplayEnabled = canShowSubscriptions(),
            )
        }
    }

    private fun canDisplayOwnedGames(): Boolean {
        return isLoginFeatureEnabled && isOwnedGamesDisplayEnabled
    }

    private fun canShowSubscriptions(): Boolean {
        return canDisplayOwnedGames() && isSubscriptionDisplayEnabled
    }
}
