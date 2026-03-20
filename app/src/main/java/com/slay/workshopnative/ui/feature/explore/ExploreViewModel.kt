package com.slay.workshopnative.ui.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.core.logging.SupportDiagnosticsStore
import com.slay.workshopnative.core.util.textFingerprint
import com.slay.workshopnative.core.util.toUserMessage
import com.slay.workshopnative.data.model.FavoriteWorkshopGame
import com.slay.workshopnative.data.model.GameDetails
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.data.repository.SteamRepository
import com.slay.workshopnative.data.repository.TranslationRepository
import com.slay.workshopnative.data.repository.WorkshopFavoritesRepository
import com.slay.workshopnative.data.preferences.displayLabel
import com.slay.workshopnative.ui.InlineTranslationState
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
    val descriptionTranslationByAppId: Map<Int, InlineTranslationState> = emptyMap(),
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
    private val translationRepository: TranslationRepository,
    private val favoritesRepository: WorkshopFavoritesRepository,
    private val supportDiagnosticsStore: SupportDiagnosticsStore,
) : ViewModel() {
    private companion object {
        const val LOG_TAG = "ExploreViewModel"
        const val REMOTE_SEARCH_DEBOUNCE_MS = 650L
        const val MIN_REMOTE_SEARCH_LENGTH = 2
    }

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

        if (normalized.length < MIN_REMOTE_SEARCH_LENGTH) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    searchResults = emptyList(),
                    errorMessage = null,
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(REMOTE_SEARCH_DEBOUNCE_MS)
            runSearch(normalized, triggerSource = "input")
        }
    }

    fun refresh() {
        val state = _uiState.value
        if (state.isLoading) return
        val normalized = state.query.trim()
        if (normalized.isNotBlank()) {
            if (normalized.length < MIN_REMOTE_SEARCH_LENGTH) {
                _uiState.update { it.copy(isLoading = false, searchResults = emptyList(), errorMessage = null) }
                return
            }
            searchJob?.cancel()
            viewModelScope.launch { runSearch(normalized, triggerSource = "refresh") }
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

    fun translateGameDescription(
        appId: Int,
        sourceText: String,
        forceRefresh: Boolean = false,
    ) {
        val normalized = sourceText.trim()
        if (appId <= 0 || normalized.isBlank()) return
        val fingerprint = textFingerprint(normalized)
        val current = _uiState.value.descriptionTranslationByAppId[appId]
        val reusableState = current?.takeIf { it.sourceFingerprint == fingerprint }
        if (!forceRefresh &&
            current?.sourceFingerprint == fingerprint &&
            !current.translatedText.isNullOrBlank()
        ) {
            _uiState.update {
                it.copy(
                    descriptionTranslationByAppId = it.descriptionTranslationByAppId + (
                        appId to current.copy(showTranslated = true, errorMessage = null)
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    descriptionTranslationByAppId = it.descriptionTranslationByAppId + (
                        appId to InlineTranslationState(
                            sourceFingerprint = fingerprint,
                            translatedText = reusableState?.translatedText,
                            providerLabel = reusableState?.providerLabel,
                            sourceLanguageLabel = reusableState?.sourceLanguageLabel,
                            isTranslating = true,
                            showTranslated = false,
                            errorMessage = null,
                        )
                    ),
                )
            }
            translationRepository.translateToChinese(normalized, forceRefresh = forceRefresh)
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            descriptionTranslationByAppId = it.descriptionTranslationByAppId + (
                                appId to InlineTranslationState(
                                    sourceFingerprint = fingerprint,
                                    translatedText = result.translatedText,
                                    providerLabel = result.provider.displayLabel(),
                                    sourceLanguageLabel = result.detectedSourceLanguageLabel,
                                    isTranslating = false,
                                    showTranslated = true,
                                    errorMessage = null,
                                )
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                    descriptionTranslationByAppId = it.descriptionTranslationByAppId + (
                                appId to InlineTranslationState(
                                    sourceFingerprint = fingerprint,
                                    translatedText = reusableState?.translatedText,
                                    providerLabel = reusableState?.providerLabel,
                                    sourceLanguageLabel = reusableState?.sourceLanguageLabel,
                                    isTranslating = false,
                                    showTranslated = false,
                                    errorMessage = error.toUserMessage("翻译失败"),
                                )
                            ),
                        )
                    }
                }
        }
    }

    fun showOriginalGameDescription(appId: Int) {
        val current = _uiState.value.descriptionTranslationByAppId[appId] ?: return
        _uiState.update {
            it.copy(
                descriptionTranslationByAppId = it.descriptionTranslationByAppId + (
                    appId to current.copy(showTranslated = false, errorMessage = null)
                ),
            )
        }
    }

    fun showTranslatedGameDescription(appId: Int) {
        val current = _uiState.value.descriptionTranslationByAppId[appId] ?: return
        if (current.translatedText.isNullOrBlank()) return
        _uiState.update {
            it.copy(
                descriptionTranslationByAppId = it.descriptionTranslationByAppId + (
                    appId to current.copy(showTranslated = true, errorMessage = null)
                ),
            )
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
                    AppLog.w(LOG_TAG, "loadExplorePage failed page=$page", error)
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

    private suspend fun runSearch(
        normalized: String,
        triggerSource: String,
    ) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        steamRepository.searchWorkshopGames(normalized)
            .onSuccess { games ->
                supportDiagnosticsStore.recordSearchSample(
                    triggerSource = triggerSource,
                    queryLength = normalized.length,
                    resultCount = games.size,
                    succeeded = true,
                )
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
                AppLog.w(LOG_TAG, "runSearch failed queryLength=${normalized.length}", error)
                supportDiagnosticsStore.recordSearchSample(
                    triggerSource = triggerSource,
                    queryLength = normalized.length,
                    resultCount = null,
                    succeeded = false,
                )
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
