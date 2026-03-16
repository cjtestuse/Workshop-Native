package com.slay.workshopnative.ui.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.core.util.toUserMessage
import com.slay.workshopnative.data.model.FavoriteWorkshopGame
import com.slay.workshopnative.data.model.GameDetails
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.data.repository.SteamRepository
import com.slay.workshopnative.data.repository.WorkshopFavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExploreUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val browsePage: Int = 1,
    val totalCount: Int = 0,
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
    val popularGames: List<WorkshopGameEntry> = emptyList(),
    val searchResults: List<WorkshopGameEntry> = emptyList(),
    val favoriteGames: List<FavoriteWorkshopGame> = emptyList(),
    val gameDetailsByAppId: Map<Int, GameDetails> = emptyMap(),
    val loadingDetailsAppId: Int? = null,
    val errorMessage: String? = null,
) {
    val isSearching: Boolean
        get() = query.trim().isNotBlank()

    val visibleGames: List<WorkshopGameEntry>
        get() = if (isSearching) searchResults else popularGames
}

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val steamRepository: SteamRepository,
    private val favoritesRepository: WorkshopFavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeFavorites()
        loadExplorePage(1)
    }

    fun toggleFavorite(game: WorkshopGameEntry) {
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(game)
        }
    }

    fun markWorkshopOpened(appId: Int) {
        viewModelScope.launch {
            favoritesRepository.markOpened(appId)
        }
    }

    fun setQuery(value: String) {
        _uiState.update {
            it.copy(
                query = value,
                errorMessage = null,
                searchResults = if (value.isBlank()) emptyList() else it.searchResults,
            )
        }
        searchJob?.cancel()

        val normalized = value.trim()
        if (normalized.isBlank()) {
            if (_uiState.value.popularGames.isEmpty()) {
                loadExplorePage(_uiState.value.browsePage)
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(280)
            runSearch(normalized)
        }
    }

    fun refresh() {
        val state = _uiState.value
        if (state.isLoading) return
        val normalized = state.query.trim()
        if (normalized.isNotBlank()) {
            searchJob?.cancel()
            viewModelScope.launch { runSearch(normalized) }
        } else {
            loadExplorePage(state.browsePage)
        }
    }

    fun goToPreviousPage() {
        val state = _uiState.value
        if (state.isSearching || state.isLoading || !state.hasPreviousPage) return
        loadExplorePage(state.browsePage - 1)
    }

    fun goToNextPage() {
        val state = _uiState.value
        if (state.isSearching || state.isLoading || !state.hasNextPage) return
        loadExplorePage(state.browsePage + 1)
    }

    fun loadGameDetails(appId: Int) {
        if (appId <= 0) return
        val current = _uiState.value
        if (current.gameDetailsByAppId.containsKey(appId) || current.loadingDetailsAppId == appId) return

        viewModelScope.launch {
            _uiState.update { it.copy(loadingDetailsAppId = appId) }
            steamRepository.loadGameDetails(appId)
                .onSuccess { details ->
                    _uiState.update {
                        it.copy(
                            gameDetailsByAppId = it.gameDetailsByAppId + (appId to details),
                            loadingDetailsAppId = it.loadingDetailsAppId.takeUnless { loadingAppId -> loadingAppId == appId },
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            loadingDetailsAppId = it.loadingDetailsAppId.takeUnless { loadingAppId -> loadingAppId == appId },
                        )
                    }
                }
        }
    }

    private fun loadExplorePage(page: Int) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    browsePage = page.coerceAtLeast(1),
                )
            }
            steamRepository.loadWorkshopExplorePage(page)
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            browsePage = result.page,
                            totalCount = result.totalCount,
                            hasPreviousPage = result.hasPrevious,
                            hasNextPage = result.hasNext,
                            popularGames = result.items,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            popularGames = emptyList(),
                            errorMessage = error.toUserMessage("读取热门工坊游戏失败"),
                        )
                    }
                }
        }
    }

    private suspend fun runSearch(normalized: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        steamRepository.searchWorkshopGames(normalized)
            .onSuccess { games ->
                if (_uiState.value.query.trim() == normalized) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            searchResults = games,
                            errorMessage = null,
                        )
                    }
                }
            }
            .onFailure { error ->
                if (_uiState.value.query.trim() == normalized) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            searchResults = emptyList(),
                            errorMessage = error.toUserMessage("搜索工坊游戏失败"),
                        )
                    }
                }
            }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoritesRepository.favorites.collect { favorites ->
                _uiState.update { it.copy(favoriteGames = favorites) }
            }
        }
    }
}
