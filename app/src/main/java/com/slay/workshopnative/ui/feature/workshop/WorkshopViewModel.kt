package com.slay.workshopnative.ui.feature.workshop

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.core.util.textFingerprint
import com.slay.workshopnative.core.util.toUserMessage
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.model.WorkshopBrowsePage
import com.slay.workshopnative.data.model.WorkshopBrowsePeriodOption
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.model.WorkshopBrowseSectionOption
import com.slay.workshopnative.data.model.WorkshopBrowseSortOption
import com.slay.workshopnative.data.model.WorkshopBrowseTagGroup
import com.slay.workshopnative.data.model.WorkshopItem
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.preferences.displayLabel
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.repository.SteamRepository
import com.slay.workshopnative.data.repository.TranslationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.slay.workshopnative.ui.InlineTranslationState

data class WorkshopUiState(
    val appId: Int = 0,
    val appName: String = "",
    val launchMode: WorkshopLaunchMode = WorkshopLaunchMode.Browse,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val isResolvingSelection: Boolean = false,
    val items: List<WorkshopItem> = emptyList(),
    val query: WorkshopBrowseQuery = WorkshopBrowseQuery(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val sectionOptions: List<WorkshopBrowseSectionOption> = emptyList(),
    val sortOptions: List<WorkshopBrowseSortOption> = emptyList(),
    val periodOptions: List<WorkshopBrowsePeriodOption> = emptyList(),
    val tagGroups: List<WorkshopBrowseTagGroup> = emptyList(),
    val supportsIncompatibleFilter: Boolean = false,
    val autoResolveDownloadInfo: Boolean = false,
    val isLoginFeatureEnabled: Boolean = false,
    val isLoggedInDownloadEnabled: Boolean = false,
    val isSubscriptionDisplayEnabled: Boolean = false,
    val canOpenSubscriptions: Boolean = false,
    val showSubscriptionState: Boolean = false,
    val downloadIdentityLabel: String = "匿名下载",
    val downloadIdentityDescription: String = "当前未登录，将直接按匿名方式尝试公开下载。",
    val selectedItem: WorkshopItem? = null,
    val descriptionTranslationByPublishedFileId: Map<Long, InlineTranslationState> = emptyMap(),
    val downloadTasksByPublishedFileId: Map<Long, DownloadTaskEntity> = emptyMap(),
    val queueingPublishedFileId: Long? = null,
    val errorMessage: String? = null,
    val actionMessage: String? = null,
)

@HiltViewModel
class WorkshopViewModel @Inject constructor(
    private val steamRepository: SteamRepository,
    private val downloadsRepository: DownloadsRepository,
    private val translationRepository: TranslationRepository,
    private val preferencesStore: UserPreferencesStore,
) : ViewModel() {
    private companion object {
        const val LOG_TAG = "WorkshopViewModel"
    }

    private val _uiState = MutableStateFlow(WorkshopUiState())
    val uiState: StateFlow<WorkshopUiState> = _uiState.asStateFlow()

    private val resolvedItemsCache = mutableMapOf<Long, WorkshopItem>()
    private val metadataRequestsInFlight = mutableSetOf<Long>()
    private var workshopPageSize = WorkshopBrowseQuery.DEFAULT_PAGE_SIZE
    private var autoResolveDownloadInfo = false
    private var isLoginFeatureEnabled = false
    private var isLoggedInDownloadEnabled = false
    private var isSubscriptionDisplayEnabled = false
    private var preferAnonymousDownloads = true
    private var allowAuthenticatedFallback = true
    private var currentSessionStatus = SessionStatus.Idle
    private var currentAccountName = ""

    init {
        viewModelScope.launch {
            downloadsRepository.reconcileActiveTasks()
        }
        observePreferences()
        observeSession()
        observeDownloads()
    }

    fun bindApp(
        appId: Int,
        appName: String,
        launchMode: WorkshopLaunchMode,
        noticeMessage: String? = null,
    ) {
        val decodedAppName = Uri.decode(appName)
        val effectiveLaunchMode = requestedLaunchMode(launchMode)
        val blockedMessage = if (effectiveLaunchMode != launchMode) {
            subscriptionsUnavailableMessage()
        } else {
            noticeMessage
        }
        val currentState = _uiState.value
        if (
            currentState.appId == appId &&
            currentState.appName == decodedAppName &&
            currentState.launchMode == effectiveLaunchMode
        ) {
            if (!blockedMessage.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        errorMessage = null,
                        actionMessage = blockedMessage,
                    )
                }
            }
            return
        }

        metadataRequestsInFlight.clear()
        resolvedItemsCache.clear()
        _uiState.update {
            it.copy(
                appId = appId,
                appName = decodedAppName,
                launchMode = effectiveLaunchMode,
                isRefreshing = false,
                isLoadingMore = false,
                hasLoadedOnce = false,
                isResolvingSelection = false,
                items = emptyList(),
                query = WorkshopBrowseQuery(
                    sectionKey = if (effectiveLaunchMode == WorkshopLaunchMode.Subscriptions) {
                        WorkshopBrowseQuery.SECTION_MY_SUBSCRIPTIONS
                    } else {
                        WorkshopBrowseQuery.SECTION_ITEMS
                    },
                    pageSize = workshopPageSize,
                ),
                totalCount = 0,
                hasMore = false,
                sectionOptions = if (effectiveLaunchMode == WorkshopLaunchMode.Subscriptions) {
                    listOf(
                        WorkshopBrowseSectionOption(
                            key = WorkshopBrowseQuery.SECTION_MY_SUBSCRIPTIONS,
                            label = "我的订阅",
                        ),
                    )
                } else {
                    emptyList()
                },
                sortOptions = emptyList(),
                periodOptions = emptyList(),
                tagGroups = emptyList(),
                supportsIncompatibleFilter = false,
                autoResolveDownloadInfo = autoResolveDownloadInfo,
                isLoginFeatureEnabled = isLoginFeatureEnabled,
                isLoggedInDownloadEnabled = isLoggedInDownloadEnabled,
                isSubscriptionDisplayEnabled = isSubscriptionDisplayEnabled,
                canOpenSubscriptions = canUseSubscriptionFeatures(),
                showSubscriptionState = shouldShowSubscriptionState(effectiveLaunchMode),
                selectedItem = null,
                queueingPublishedFileId = null,
                errorMessage = null,
                actionMessage = blockedMessage,
            )
        }

        refresh(forceRefresh = false)
    }

    fun openSubscriptionsMode() {
        val state = _uiState.value
        if (state.appId <= 0) return
        bindApp(
            appId = state.appId,
            appName = state.appName,
            launchMode = WorkshopLaunchMode.Subscriptions,
        )
    }

    fun switchToBrowseMode() {
        val state = _uiState.value
        if (state.appId <= 0 || state.launchMode == WorkshopLaunchMode.Browse) return
        bindApp(
            appId = state.appId,
            appName = state.appName,
            launchMode = WorkshopLaunchMode.Browse,
        )
    }

    fun refresh(forceRefresh: Boolean = true) {
        loadPage(
            targetPage = _uiState.value.query.page.coerceAtLeast(1),
            showRefresh = true,
            forceRefresh = forceRefresh,
        )
    }

    fun goToPage(page: Int) {
        val state = _uiState.value
        if (page < 1 || page == state.query.page || state.isRefreshing || state.isLoadingMore) return
        if (page > 1 && !state.hasMore && page > state.query.page) return

        loadPage(
            targetPage = page,
            showRefresh = false,
            forceRefresh = false,
        )
    }

    fun applyQuery(query: WorkshopBrowseQuery) {
        val normalizedQuery = query.copy(page = 1)
        _uiState.update {
            it.copy(
                query = normalizedQuery,
                errorMessage = null,
                actionMessage = null,
            )
        }
        loadPage(
            targetPage = normalizedQuery.page,
            showRefresh = true,
            forceRefresh = false,
        )
    }

    fun prefetchItemMetadata(publishedFileIds: Collection<Long>) {
        val state = _uiState.value
        if (!state.autoResolveDownloadInfo) return
        val targets = publishedFileIds
            .mapNotNull { publishedFileId ->
                state.items.firstOrNull { it.publishedFileId == publishedFileId }
            }
            .filter { !it.isDownloadInfoResolved }
            .map(WorkshopItem::publishedFileId)
            .filter { resolvedItemsCache[it] == null }
            .distinct()
            .filter { metadataRequestsInFlight.add(it) }
        if (targets.isEmpty()) return

        resolveMetadataForTargets(
            publishedFileIds = targets,
            appId = state.appId,
            query = state.query,
        )
    }

    fun openItemDetails(item: WorkshopItem) {
        val cached = resolvedItemsCache[item.publishedFileId]
        if (cached != null) {
            _uiState.update {
                it.copy(
                    selectedItem = cached.mergeBrowseState(item),
                    isResolvingSelection = false,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                selectedItem = item,
                isResolvingSelection = true,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            steamRepository.resolveWorkshopItem(item.publishedFileId)
                .onSuccess { resolved ->
                    val merged = resolved.mergeBrowseState(item)
                    resolvedItemsCache[item.publishedFileId] = merged
                    _uiState.update {
                        it.copy(
                            items = it.items.replaceItem(merged),
                            selectedItem = merged,
                            isResolvingSelection = false,
                        )
                    }
                }
                .onFailure { error ->
                    AppLog.w(
                        LOG_TAG,
                        "openItemDetails failed publishedFileId=${item.publishedFileId}",
                        error,
                    )
                    _uiState.update {
                        it.copy(
                            isResolvingSelection = false,
                            errorMessage = error.toUserMessage("读取条目详情失败"),
                        )
                    }
                }
        }
    }

    fun dismissItemDetails() {
        _uiState.update { it.copy(selectedItem = null, isResolvingSelection = false) }
    }

    fun translateItemDescription(
        publishedFileId: Long,
        sourceText: String,
        forceRefresh: Boolean = false,
    ) {
        val normalized = sourceText.trim()
        if (publishedFileId <= 0L || normalized.isBlank()) return
        val fingerprint = textFingerprint(normalized)
        val current = _uiState.value.descriptionTranslationByPublishedFileId[publishedFileId]
        val reusableState = current?.takeIf { it.sourceFingerprint == fingerprint }
        if (!forceRefresh &&
            current?.sourceFingerprint == fingerprint &&
            !current.translatedText.isNullOrBlank()
        ) {
            _uiState.update {
                it.copy(
                    descriptionTranslationByPublishedFileId = it.descriptionTranslationByPublishedFileId + (
                        publishedFileId to current.copy(showTranslated = true, errorMessage = null)
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    descriptionTranslationByPublishedFileId = it.descriptionTranslationByPublishedFileId + (
                        publishedFileId to InlineTranslationState(
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
                            descriptionTranslationByPublishedFileId = it.descriptionTranslationByPublishedFileId + (
                                publishedFileId to InlineTranslationState(
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
                    descriptionTranslationByPublishedFileId = it.descriptionTranslationByPublishedFileId + (
                                publishedFileId to InlineTranslationState(
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

    fun showOriginalItemDescription(publishedFileId: Long) {
        val current = _uiState.value.descriptionTranslationByPublishedFileId[publishedFileId] ?: return
        _uiState.update {
            it.copy(
                descriptionTranslationByPublishedFileId = it.descriptionTranslationByPublishedFileId + (
                    publishedFileId to current.copy(showTranslated = false, errorMessage = null)
                ),
            )
        }
    }

    fun showTranslatedItemDescription(publishedFileId: Long) {
        val current = _uiState.value.descriptionTranslationByPublishedFileId[publishedFileId] ?: return
        if (current.translatedText.isNullOrBlank()) return
        _uiState.update {
            it.copy(
                descriptionTranslationByPublishedFileId = it.descriptionTranslationByPublishedFileId + (
                    publishedFileId to current.copy(showTranslated = true, errorMessage = null)
                ),
            )
        }
    }

    fun enqueueDownload(item: WorkshopItem) {
        viewModelScope.launch {
            val targetItem = resolvedItemsCache[item.publishedFileId] ?: item
            _uiState.update {
                it.copy(
                    queueingPublishedFileId = targetItem.publishedFileId,
                    errorMessage = null,
                    actionMessage = null,
                )
            }

            downloadsRepository.enqueue(targetItem)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            actionMessage = when {
                                downloadIdentityLabel().isNotBlank() && targetItem.hasChildItems && !targetItem.canDownload ->
                                    "已按${downloadIdentityLabel()}加入合集子项下载队列：${targetItem.title}"
                                downloadIdentityLabel().isNotBlank() && targetItem.canSteamContentDownload && !targetItem.canDirectDownload ->
                                    "已按${downloadIdentityLabel()}加入 Steam 内容下载队列：${targetItem.title}"
                                downloadIdentityLabel().isNotBlank() ->
                                    "已按${downloadIdentityLabel()}加入下载队列：${targetItem.title}"
                                targetItem.hasChildItems && !targetItem.canDownload -> "已将合集子项加入下载队列：${targetItem.title}"
                                targetItem.canSteamContentDownload && !targetItem.canDirectDownload -> "已加入 Steam 内容下载队列：${targetItem.title}"
                                else -> "已加入下载队列：${targetItem.title}"
                            },
                        )
                    }
                }
                .onFailure { error ->
                    AppLog.w(
                        LOG_TAG,
                        "enqueueDownload failed publishedFileId=${targetItem.publishedFileId}",
                        error,
                    )
                    _uiState.update {
                        it.copy(errorMessage = error.toUserMessage("无法开始下载"))
                    }
                }

            _uiState.update { current ->
                current.copy(
                    queueingPublishedFileId = current.queueingPublishedFileId
                        ?.takeUnless { it == targetItem.publishedFileId },
                )
            }
        }
    }

    fun consumeErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun consumeActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    private fun loadPage(
        targetPage: Int,
        showRefresh: Boolean,
        forceRefresh: Boolean,
    ) {
        val state = _uiState.value
        val appId = state.appId
        if (appId <= 0) return

        val request = state.query.copy(page = targetPage)

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = showRefresh,
                    isLoadingMore = !showRefresh,
                    errorMessage = null,
                    actionMessage = null,
                )
            }

            val pageResult = when (state.launchMode) {
                WorkshopLaunchMode.Browse ->
                    steamRepository.loadWorkshopBrowsePage(appId, request, forceRefresh)
                WorkshopLaunchMode.Subscriptions ->
                    steamRepository.loadSubscribedWorkshopPage(
                        appId = appId,
                        page = request.page,
                        pageSize = request.pageSize,
                        forceRefresh = forceRefresh,
                    )
            }
            pageResult
                .onSuccess { page ->
                    applyLoadedPage(page = page, request = request)
                }
                .onFailure { error ->
                    AppLog.w(
                        LOG_TAG,
                        "loadPage failed appId=$appId page=${request.page}",
                        error,
                    )
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            isLoadingMore = false,
                            hasLoadedOnce = true,
                            errorMessage = error.toUserMessage("读取创意工坊失败"),
                        )
                    }
                }
        }
    }

    private fun applyLoadedPage(
        page: WorkshopBrowsePage,
        request: WorkshopBrowseQuery,
    ) {
        val mergedItems = page.items.map { item ->
            resolvedItemsCache[item.publishedFileId]?.mergeBrowseState(item) ?: item
        }
        _uiState.update { state ->
            state.copy(
                isRefreshing = false,
                isLoadingMore = false,
                hasLoadedOnce = true,
                items = mergedItems,
                query = request,
                totalCount = page.totalCount,
                hasMore = page.hasMore,
                sectionOptions = page.sectionOptions,
                sortOptions = page.sortOptions,
                periodOptions = page.periodOptions,
                tagGroups = page.tagGroups,
                supportsIncompatibleFilter = page.supportsIncompatibleFilter,
            )
        }
        if (_uiState.value.autoResolveDownloadInfo) {
            prefetchItemMetadata(mergedItems.take(6).map(WorkshopItem::publishedFileId))
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesStore.preferences.collectLatest { prefs ->
                val nextPageSize = prefs.workshopPageSize
                val nextAutoResolve = prefs.workshopAutoResolveVisibleItems
                isLoginFeatureEnabled = prefs.isLoginFeatureEnabled
                isLoggedInDownloadEnabled = prefs.isLoggedInDownloadEnabled
                isSubscriptionDisplayEnabled = prefs.isSubscriptionDisplayEnabled
                preferAnonymousDownloads = prefs.preferAnonymousDownloads
                allowAuthenticatedFallback = prefs.allowAuthenticatedDownloadFallback
                val current = _uiState.value
                val pageSizeChanged = workshopPageSize != nextPageSize
                val autoResolveChanged = autoResolveDownloadInfo != nextAutoResolve
                val subscriptionModeBlocked = current.launchMode == WorkshopLaunchMode.Subscriptions &&
                    !canUseSubscriptionFeatures()
                if (!pageSizeChanged && !autoResolveChanged) {
                    _uiState.update {
                        it.copy(
                            isLoginFeatureEnabled = isLoginFeatureEnabled,
                            isLoggedInDownloadEnabled = isLoggedInDownloadEnabled,
                            isSubscriptionDisplayEnabled = isSubscriptionDisplayEnabled,
                            canOpenSubscriptions = canUseSubscriptionFeatures(),
                            showSubscriptionState = shouldShowSubscriptionState(it.launchMode),
                            downloadIdentityLabel = downloadIdentityLabel(),
                            downloadIdentityDescription = downloadIdentityDescription(),
                        )
                    }
                    if (subscriptionModeBlocked && current.appId > 0) {
                        bindApp(
                            appId = current.appId,
                            appName = current.appName,
                            launchMode = WorkshopLaunchMode.Browse,
                            noticeMessage = subscriptionsUnavailableMessage(),
                        )
                    }
                    return@collectLatest
                }

                workshopPageSize = nextPageSize
                autoResolveDownloadInfo = nextAutoResolve

                _uiState.update { state ->
                    state.copy(
                        query = if (state.query.pageSize != nextPageSize) {
                            state.query.copy(page = 1, pageSize = nextPageSize)
                        } else {
                            state.query
                        },
                        autoResolveDownloadInfo = nextAutoResolve,
                        isLoginFeatureEnabled = isLoginFeatureEnabled,
                        isLoggedInDownloadEnabled = isLoggedInDownloadEnabled,
                        isSubscriptionDisplayEnabled = isSubscriptionDisplayEnabled,
                        canOpenSubscriptions = canUseSubscriptionFeatures(),
                        showSubscriptionState = shouldShowSubscriptionState(state.launchMode),
                        downloadIdentityLabel = downloadIdentityLabel(),
                        downloadIdentityDescription = downloadIdentityDescription(),
                    )
                }

                if (subscriptionModeBlocked && current.appId > 0) {
                    bindApp(
                        appId = current.appId,
                        appName = current.appName,
                        launchMode = WorkshopLaunchMode.Browse,
                        noticeMessage = subscriptionsUnavailableMessage(),
                    )
                    return@collectLatest
                }

                if (pageSizeChanged && current.appId > 0 && current.hasLoadedOnce) {
                    loadPage(
                        targetPage = 1,
                        showRefresh = true,
                        forceRefresh = false,
                    )
                } else if (nextAutoResolve && current.items.isNotEmpty()) {
                    prefetchItemMetadata(current.items.take(6).map(WorkshopItem::publishedFileId))
                }
            }
        }
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            downloadsRepository.downloads.collectLatest { downloads ->
                _uiState.update {
                    it.copy(
                        downloadTasksByPublishedFileId = downloads
                            .groupBy(DownloadTaskEntity::publishedFileId)
                            .mapValues { entry ->
                                entry.value.maxByOrNull(DownloadTaskEntity::updatedAt)
                                    ?: entry.value.first()
                            },
                    )
                }
            }
        }
    }

    private fun observeSession() {
        viewModelScope.launch {
            var lastConnectionRevision = -1L
            steamRepository.sessionState.collectLatest { session ->
                currentSessionStatus = session.status
                currentAccountName = session.account?.accountName.orEmpty()
                _uiState.update {
                    it.copy(
                        canOpenSubscriptions = canUseSubscriptionFeatures(),
                        showSubscriptionState = shouldShowSubscriptionState(it.launchMode),
                        downloadIdentityLabel = downloadIdentityLabel(),
                        downloadIdentityDescription = downloadIdentityDescription(),
                    )
                }
                val currentState = _uiState.value
                if (
                    currentState.appId > 0 &&
                    currentState.launchMode == WorkshopLaunchMode.Subscriptions &&
                    !canUseSubscriptionFeatures()
                ) {
                    bindApp(
                        appId = currentState.appId,
                        appName = currentState.appName,
                        launchMode = WorkshopLaunchMode.Browse,
                        noticeMessage = subscriptionsUnavailableMessage(),
                    )
                    return@collectLatest
                }
                val isAuthenticated = session.status == SessionStatus.Authenticated
                val currentRevision = session.connectionRevision
                if (isAuthenticated && currentRevision != lastConnectionRevision && _uiState.value.appId > 0) {
                    lastConnectionRevision = currentRevision
                    refresh(forceRefresh = false)
                }
            }
        }
    }

    private fun WorkshopItem.mergeBrowseState(browseItem: WorkshopItem): WorkshopItem {
        return copy(
            title = preferWorkshopTitle(title, browseItem.title),
            shortDescription = preferLongerWorkshopText(shortDescription, browseItem.shortDescription),
            description = preferLongerWorkshopText(description, browseItem.description),
            previewUrl = browseItem.previewUrl ?: previewUrl,
            authorName = authorName.ifBlank { browseItem.authorName },
            detailUrl = detailUrl ?: browseItem.detailUrl,
            isSubscribed = isSubscribed || browseItem.isSubscribed,
            isDownloadInfoResolved = isDownloadInfoResolved || browseItem.isDownloadInfoResolved,
        )
    }

    private fun preferLongerWorkshopText(
        primary: String,
        fallback: String,
    ): String {
        return when {
            primary.isBlank() -> fallback
            fallback.isBlank() -> primary
            fallback.length > primary.length -> fallback
            else -> primary
        }
    }

    private fun preferWorkshopTitle(
        primary: String,
        fallback: String,
    ): String {
        if (primary.isBlank() || primary.startsWith("Workshop #")) {
            return fallback.ifBlank { primary }
        }
        if (fallback.isBlank()) return primary
        return if (primary.isLikelyInternalWorkshopTitle() && fallback.containsCjkCharacters()) {
            fallback
        } else {
            primary
        }
    }

    private fun String.isLikelyInternalWorkshopTitle(): Boolean {
        return isNotBlank() &&
            all { it.code in 32..126 } &&
            any { it.isLetter() } &&
            none { it.isWhitespace() }
    }

    private fun String.containsCjkCharacters(): Boolean {
        return any { character ->
            Character.UnicodeScript.of(character.code) in setOf(
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
            )
        }
    }

    private fun resolveMetadataForTargets(
        publishedFileIds: List<Long>,
        appId: Int,
        query: WorkshopBrowseQuery,
    ) {
        viewModelScope.launch {
            steamRepository.resolveWorkshopItems(publishedFileIds)
                .onSuccess { resolvedItems ->
                    val browseItemsById = _uiState.value.items.associateBy(WorkshopItem::publishedFileId)
                    val mergedById = resolvedItems.associate { resolved ->
                        val merged = browseItemsById[resolved.publishedFileId]
                            ?.let { browseItem -> resolved.mergeBrowseState(browseItem) }
                            ?: resolved
                        resolvedItemsCache[merged.publishedFileId] = merged
                        merged.publishedFileId to merged
                    }
                    val unavailableIds = publishedFileIds
                        .filterNot { it in mergedById.keys }
                        .associateWith { publishedFileId ->
                            browseItemsById[publishedFileId]?.copy(isDownloadInfoResolved = true)
                        }

                    _uiState.update { state ->
                        if (state.appId != appId || state.query != query) return@update state
                        state.copy(
                            items = state.items.map { current ->
                                mergedById[current.publishedFileId]?.mergeBrowseState(current)
                                    ?: unavailableIds[current.publishedFileId]
                                    ?: current
                            },
                            selectedItem = state.selectedItem?.let { selected ->
                                mergedById[selected.publishedFileId]?.mergeBrowseState(selected) ?: selected
                            },
                        )
                    }
                }
                .onFailure {
                    publishedFileIds.forEach(metadataRequestsInFlight::remove)
                }
            publishedFileIds.forEach(metadataRequestsInFlight::remove)
        }
    }

    private fun List<WorkshopItem>.replaceItem(updated: WorkshopItem): List<WorkshopItem> {
        return map { current ->
            if (current.publishedFileId == updated.publishedFileId) {
                updated.mergeBrowseState(current)
            } else {
                current
            }
        }
    }

    private fun downloadIdentityLabel(): String {
        return when {
            !isLoginFeatureEnabled -> "匿名下载"
            !isLoggedInDownloadEnabled -> "匿名下载"
            currentSessionStatus != SessionStatus.Authenticated -> "匿名下载"
            preferAnonymousDownloads -> "匿名优先"
            else -> "账号下载"
        }
    }

    private fun downloadIdentityDescription(): String {
        return when {
            !isLoginFeatureEnabled ->
                "已在设置中关闭登录功能，当前只允许匿名下载公开内容。"
            !isLoggedInDownloadEnabled ->
                "已在设置中关闭“登录后下载”，即使账号已登录也只会匿名下载公开内容。"
            currentSessionStatus != SessionStatus.Authenticated ->
                "当前未登录，将直接按匿名方式尝试公开下载。"
            preferAnonymousDownloads && allowAuthenticatedFallback ->
                "会优先尝试匿名方式；若匿名不可用或条目需要账号，再切回当前 Steam 账号。"
            preferAnonymousDownloads ->
                "会优先尝试匿名方式；若匿名不可用，不会自动切换到当前 Steam 账号。"
            else ->
                "当前会直接使用已登录账号进行下载。"
        }
    }

    private fun requestedLaunchMode(launchMode: WorkshopLaunchMode): WorkshopLaunchMode {
        return if (launchMode == WorkshopLaunchMode.Subscriptions && !canUseSubscriptionFeatures()) {
            WorkshopLaunchMode.Browse
        } else {
            launchMode
        }
    }

    private fun canUseSubscriptionFeatures(): Boolean {
        return isLoginFeatureEnabled &&
            isSubscriptionDisplayEnabled &&
            currentSessionStatus == SessionStatus.Authenticated
    }

    private fun shouldShowSubscriptionState(launchMode: WorkshopLaunchMode): Boolean {
        return launchMode == WorkshopLaunchMode.Subscriptions && canUseSubscriptionFeatures()
    }

    private fun subscriptionsUnavailableMessage(): String {
        return when {
            !isLoginFeatureEnabled -> "已在设置中关闭登录功能，当前无法读取我的订阅。"
            !isSubscriptionDisplayEnabled -> "已在设置中关闭“用户已订阅展示”，当前改为浏览全部工坊。"
            currentSessionStatus != SessionStatus.Authenticated -> "当前未登录，无法读取我的订阅。"
            else -> "当前无法读取我的订阅。"
        }
    }
}
