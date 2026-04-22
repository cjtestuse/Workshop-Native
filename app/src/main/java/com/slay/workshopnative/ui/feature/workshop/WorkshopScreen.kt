package com.slay.workshopnative.ui.feature.workshop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slay.workshopnative.core.util.formatBytes
import com.slay.workshopnative.core.util.formatEpochSeconds
import com.slay.workshopnative.core.util.openUrlWithChooser
import com.slay.workshopnative.core.util.textFingerprint
import com.slay.workshopnative.data.local.DownloadStatus
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.model.WorkshopBrowsePeriodOption
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.model.WorkshopBrowseSectionOption
import com.slay.workshopnative.data.model.WorkshopBrowseSortOption
import com.slay.workshopnative.data.model.WorkshopBrowseTagGroup
import com.slay.workshopnative.data.model.WorkshopBrowseTagGroupSelectionMode
import com.slay.workshopnative.data.model.WorkshopBrowseTagOption
import com.slay.workshopnative.data.model.WorkshopDateRangeFilter
import com.slay.workshopnative.data.model.WorkshopItem
import com.slay.workshopnative.ui.components.AnimatedWorkshopThumbnail
import com.slay.workshopnative.ui.components.ArtworkThumbnail
import com.slay.workshopnative.ui.components.ExpandableBodyText
import com.slay.workshopnative.ui.components.TranslatableDescriptionCard
import com.slay.workshopnative.ui.components.WorkshopNativeModalBottomSheet
import com.slay.workshopnative.ui.theme.LocalWorkshopDarkTheme
import com.slay.workshopnative.ui.theme.workshopAdaptiveBorderColor
import com.slay.workshopnative.ui.theme.workshopAdaptiveGradientBrush
import com.slay.workshopnative.ui.theme.workshopAdaptiveOverlayColor
import com.slay.workshopnative.ui.theme.workshopAdaptiveSurfaceColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopScreen(
    appId: Int,
    appName: String,
    launchMode: WorkshopLaunchMode,
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: WorkshopViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSourcePicker by rememberSaveable { mutableStateOf(false) }
    var showBrowseSectionPicker by rememberSaveable { mutableStateOf(false) }
    var showBrowseQuickFilterPicker by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable(appId, launchMode.name) { mutableStateOf("") }
    var isSearchFieldFocused by rememberSaveable(appId, launchMode.name) { mutableStateOf(false) }
    var suppressExitBackAfterSearch by rememberSaveable(appId, launchMode.name) { mutableStateOf(false) }
    val isBrowseMode = state.launchMode == WorkshopLaunchMode.Browse
    val isAccountQueryMode = state.launchMode == WorkshopLaunchMode.AccountQuery
    val isSubscriptionMode = state.launchMode == WorkshopLaunchMode.Subscriptions
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(state.query.searchText) {
        if (searchText != state.query.searchText) {
            searchText = state.query.searchText
        }
    }

    LaunchedEffect(suppressExitBackAfterSearch) {
        if (!suppressExitBackAfterSearch) return@LaunchedEffect
        delay(650)
        suppressExitBackAfterSearch = false
    }

    LaunchedEffect(state.launchMode) {
        if (!isBrowseMode) {
            showFilters = false
            showBrowseSectionPicker = false
            showBrowseQuickFilterPicker = false
        }
    }

    BackHandler(enabled = !isSubscriptionMode && (isSearchFieldFocused || imeVisible || suppressExitBackAfterSearch)) {
        if (isSearchFieldFocused || imeVisible) {
            focusManager.clearFocus(force = true)
        }
    }

    LaunchedEffect(appId, appName, launchMode) {
        viewModel.bindApp(appId, appName, launchMode)
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeErrorMessage()
    }

    LaunchedEffect(state.actionMessage) {
        val message = state.actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeActionMessage()
    }

    LaunchedEffect(state.query.page) {
        if (state.items.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(listState, state.items, state.autoResolveDownloadInfo) {
        if (!state.autoResolveDownloadInfo) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { info -> state.items.getOrNull(info.index)?.publishedFileId }
                .distinct()
        }
            .distinctUntilChanged()
            .collect { visibleIds ->
                if (visibleIds.isNotEmpty()) {
                    viewModel.prefetchItemMetadata(visibleIds)
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkshopTopBarModern(
                appName = state.appName,
                totalCount = state.totalCount,
                hasLoadedOnce = state.hasLoadedOnce,
                launchMode = state.launchMode,
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onOpenSourcePicker = { showSourcePicker = true },
                onOpenFilters = { showFilters = true },
                onOpenBrowseSectionPicker = { showBrowseSectionPicker = true },
                onOpenBrowseQuickFilterPicker = { showBrowseQuickFilterPicker = true },
                canOpenAccountQuery = state.canOpenAccountQuery,
                onOpenAccountQuery = viewModel::openAccountQueryMode,
                canOpenSubscriptions = state.canOpenSubscriptions,
                onOpenSubscriptions = viewModel::openSubscriptionsMode,
                onSwitchToBrowse = viewModel::switchToBrowseMode,
                isRefreshing = state.isRefreshing,
                query = state.query,
                sectionOptions = state.sectionOptions,
                sortOptions = state.sortOptions,
                periodOptions = state.periodOptions,
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                onSearchFocusChanged = { isSearchFieldFocused = it },
                onSubmitSearch = {
                    suppressExitBackAfterSearch = true
                    val normalized = searchText.trim()
                    if (normalized != state.query.searchText) {
                        viewModel.applyQuery(state.query.copy(searchText = normalized, page = 1))
                    }
                },
                onClearSearch = {
                    searchText = ""
                    focusManager.clearFocus(force = true)
                    if (state.query.searchText.isNotBlank()) {
                        viewModel.applyQuery(state.query.copy(searchText = "", page = 1))
                    }
                },
            )

            if (state.items.isEmpty() && (state.isRefreshing || !state.hasLoadedOnce)) {
                WorkshopLoadingCard(
                    modifier = Modifier.weight(1f),
                    appName = state.appName,
                    launchMode = state.launchMode,
                    autoResolveDownloadInfo = state.autoResolveDownloadInfo,
                )
            } else if (state.items.isEmpty()) {
                if (isSubscriptionMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "你还没有订阅这个游戏的创意工坊内容。",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "这里不会修改你的订阅状态，只会读取当前账号已经订阅的条目。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = viewModel::switchToBrowseMode,
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text("浏览全部工坊")
                            }
                        }
                    }
                } else if (isAccountQueryMode && state.query.searchText.isBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "输入关键词后，使用当前 Steam 账号查询账号可见的工坊条目。",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "这类结果可能不会出现在公开工坊页面里，适合查找只在登录视角下可见的条目。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (isAccountQueryMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "当前账号可见范围内没有匹配条目。",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "可以尝试更换关键词，或切回公开工坊继续浏览。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.canOpenSubscriptions) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = viewModel::switchToBrowseMode,
                                        shape = RoundedCornerShape(18.dp),
                                    ) {
                                        Text("公开工坊")
                                    }
                                    OutlinedButton(
                                        onClick = viewModel::openSubscriptionsMode,
                                        shape = RoundedCornerShape(18.dp),
                                    ) {
                                        Text("我的订阅")
                                    }
                                }
                            } else {
                                Button(
                                    onClick = viewModel::switchToBrowseMode,
                                    shape = RoundedCornerShape(18.dp),
                                ) {
                                    Text("公开工坊")
                                }
                            }
                        }
                    }
                } else if (!state.inlineStatusMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = state.inlineStatusMessage.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "可以返回探索页切换其他游戏，或稍后刷新再试。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(
                            text = "当前筛选条件下没有可显示的创意工坊条目。",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(
                        items = state.items,
                        key = WorkshopItem::publishedFileId,
                    ) { item ->
                        WorkshopListItem(
                            item = item,
                            showSubscriptionState = state.showSubscriptionState,
                            autoResolveDownloadInfo = state.autoResolveDownloadInfo,
                            animatedPreviewEnabled = state.animatedWorkshopPreviewEnabled,
                            latestTask = state.downloadTasksByPublishedFileId[item.publishedFileId],
                            onClick = { viewModel.openItemDetails(item) },
                        )
                    }

                    if (shouldShowPagination(state.query.page, state.totalCount, state.hasMore, state.query.pageSize)) {
                        item {
                            PaginationCard(
                                currentPage = state.query.page,
                                totalPages = workshopPageCount(state.totalCount, state.query.pageSize),
                                totalCount = state.totalCount,
                                pageSize = state.query.pageSize,
                                isLoading = state.isLoadingMore,
                                hasPrevious = state.query.page > 1,
                                hasNext = state.hasMore,
                                onPrevious = { viewModel.goToPage(state.query.page - 1) },
                                onNext = { viewModel.goToPage(state.query.page + 1) },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    if (isBrowseMode && showFilters) {
        FilterSheet(
            currentQuery = state.query,
            tagGroups = state.tagGroups,
            supportsIncompatibleFilter = state.supportsIncompatibleFilter,
            onDismiss = { showFilters = false },
            onApply = {
                showFilters = false
                viewModel.applyQuery(it)
            },
        )
    }

    val browseSectionPickerOptions = remember(state.sectionOptions, state.query.sectionKey) {
        state.sectionOptions
            .takeIf(List<WorkshopBrowseSectionOption>::isNotEmpty)
            ?.map { it.key to it.label }
            ?: fallbackSectionOptions(state.query.sectionKey)
    }
    val browseSortPickerOptions = remember(state.sortOptions, state.query.sortKey) {
        state.sortOptions
            .takeIf(List<WorkshopBrowseSortOption>::isNotEmpty)
            ?.map { it.key to it.label }
            ?: fallbackSortOptions(state.query.sortKey)
    }
    val browsePeriodPickerOptions = remember(state.periodOptions, state.query.periodDays) {
        state.periodOptions
            .takeIf(List<WorkshopBrowsePeriodOption>::isNotEmpty)
            ?.map { it.days.toString() to it.label }
            ?: fallbackPeriodOptions(state.query.periodDays)
    }

    val sourcePickerOptions = remember(state.canOpenAccountQuery) {
        buildList {
            add(WorkshopLaunchMode.Browse.name to launchModeLabel(WorkshopLaunchMode.Browse))
            if (state.canOpenAccountQuery) {
                add(WorkshopLaunchMode.AccountQuery.name to launchModeLabel(WorkshopLaunchMode.AccountQuery))
            }
        }
    }

    if (showSourcePicker) {
        QuickChoiceSheet(
            title = "查询来源",
            subtitle = "公开工坊只能看到公开可见结果；账号可见会走当前 Steam 登录态查询。",
            options = sourcePickerOptions,
            selectedKey = state.launchMode.name,
            onDismiss = { showSourcePicker = false },
            onSelect = { selectedKey ->
                showSourcePicker = false
                when (WorkshopLaunchMode.entries.firstOrNull { it.name == selectedKey } ?: state.launchMode) {
                    WorkshopLaunchMode.Browse -> viewModel.switchToBrowseMode()
                    WorkshopLaunchMode.AccountQuery -> viewModel.openAccountQueryMode()
                    WorkshopLaunchMode.Subscriptions -> viewModel.openSubscriptionsMode()
                }
            },
        )
    }

    if (isBrowseMode && showBrowseSectionPicker) {
        QuickChoiceSheet(
            title = "浏览",
            subtitle = "切换当前公开工坊浏览范围。",
            options = browseSectionPickerOptions,
            selectedKey = state.query.sectionKey,
            onDismiss = { showBrowseSectionPicker = false },
            onSelect = { selectedKey ->
                showBrowseSectionPicker = false
                viewModel.applyQuery(
                    state.query.copy(
                        sectionKey = selectedKey,
                        page = 1,
                    ),
                )
            },
        )
    }

    if (isBrowseMode && showBrowseQuickFilterPicker) {
        SteamPublicBrowseQuickFilterSheet(
            currentQuery = state.query,
            sortOptions = browseSortPickerOptions,
            periodOptions = browsePeriodPickerOptions,
            onDismiss = { showBrowseQuickFilterPicker = false },
            onApply = { nextQuery ->
                showBrowseQuickFilterPicker = false
                viewModel.applyQuery(nextQuery)
            },
        )
    }

    state.selectedItem?.let { item ->
        val description = item.description.ifBlank {
            item.shortDescription.ifBlank { "这个条目没有提供额外介绍。" }
        }
        val authorProfileUrl = item.authorProfileUrlOrFallback()
        val translationState = state.descriptionTranslationByPublishedFileId[item.publishedFileId]
            ?.takeIf { it.sourceFingerprint == textFingerprint(description.trim()) }
        WorkshopNativeModalBottomSheet(onDismissRequest = viewModel::dismissItemDetails) {
            WorkshopDetailSheet(
                item = item,
                description = description,
                translationState = translationState,
                animatedPreviewEnabled = state.animatedWorkshopPreviewEnabled,
                latestTask = state.downloadTasksByPublishedFileId[item.publishedFileId],
                showSubscriptionState = state.showSubscriptionState,
                downloadIdentityLabel = state.downloadIdentityLabel,
                downloadIdentityDescription = state.downloadIdentityDescription,
                isQueueing = state.queueingPublishedFileId == item.publishedFileId,
                isResolving = state.isResolvingSelection,
                authorProfileUrl = authorProfileUrl,
                onTranslateDescription = {
                    viewModel.translateItemDescription(
                        publishedFileId = item.publishedFileId,
                        sourceText = description,
                        forceRefresh = !translationState?.translatedText.isNullOrBlank(),
                    )
                },
                onShowOriginalDescription = { viewModel.showOriginalItemDescription(item.publishedFileId) },
                onShowTranslatedDescription = { viewModel.showTranslatedItemDescription(item.publishedFileId) },
                onOpenAuthorProfile = {
                    authorProfileUrl?.let { profileUrl ->
                        context.openUrlWithChooser(
                            url = profileUrl,
                            chooserTitle = "打开作者主页",
                        )
                    }
                },
                onDownload = { viewModel.enqueueDownload(item) },
                onOpenDownloads = onOpenDownloads,
            )
        }
    }
}

@Composable
private fun WorkshopLoadingCard(
    modifier: Modifier = Modifier,
    appName: String,
    launchMode: WorkshopLaunchMode,
    autoResolveDownloadInfo: Boolean,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.82f)),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor()),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator()
            Text(
                text = when (launchMode) {
                    WorkshopLaunchMode.Browse ->
                        if (appName.isBlank()) "正在读取创意工坊" else "正在读取 $appName 创意工坊"
                    WorkshopLaunchMode.AccountQuery ->
                        if (appName.isBlank()) "正在查询账号可见条目" else "正在查询 $appName 的账号可见条目"
                    WorkshopLaunchMode.Subscriptions ->
                        if (appName.isBlank()) "正在同步我的订阅" else "正在同步 $appName 的我的订阅"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when (launchMode) {
                    WorkshopLaunchMode.Browse -> if (autoResolveDownloadInfo) {
                        "正在加载 Steam 列表，可见条目的下载方式会在列表出现后继续补齐。"
                    } else {
                        "正在加载 Steam 列表，条目详情和下载方式会在点开时按需补齐。"
                    }
                    WorkshopLaunchMode.AccountQuery ->
                        "正在使用当前 Steam 账号查询可见条目，某些结果可能不会出现在公开工坊里。"
                    WorkshopLaunchMode.Subscriptions ->
                        "正在读取当前账号已订阅的条目，不会对订阅状态做任何修改。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when (launchMode) {
                    WorkshopLaunchMode.Browse -> "当前阶段：获取工坊列表与筛选条件"
                    WorkshopLaunchMode.AccountQuery -> "当前阶段：使用登录态检索账号可见结果"
                    WorkshopLaunchMode.Subscriptions -> "当前阶段：同步当前账号的订阅条目"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun WorkshopTopBarModern(
    appName: String,
    totalCount: Int,
    hasLoadedOnce: Boolean,
    launchMode: WorkshopLaunchMode,
    isRefreshing: Boolean,
    query: WorkshopBrowseQuery,
    searchText: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSourcePicker: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenBrowseSectionPicker: () -> Unit,
    onOpenBrowseQuickFilterPicker: () -> Unit,
    canOpenAccountQuery: Boolean,
    onOpenAccountQuery: () -> Unit,
    canOpenSubscriptions: Boolean,
    onOpenSubscriptions: () -> Unit,
    onSwitchToBrowse: () -> Unit,
    onSearchTextChange: (String) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    sectionOptions: List<WorkshopBrowseSectionOption>,
    sortOptions: List<WorkshopBrowseSortOption>,
    periodOptions: List<WorkshopBrowsePeriodOption>,
) {
    val darkTheme = LocalWorkshopDarkTheme.current
    val advancedFilterCount = query.activeAdvancedFilterCount()
    val isBrowseMode = launchMode == WorkshopLaunchMode.Browse
    val isAccountQueryMode = launchMode == WorkshopLaunchMode.AccountQuery
    val isSubscriptionMode = launchMode == WorkshopLaunchMode.Subscriptions
    val isInitialSync = !hasLoadedOnce
    val headerEyebrow = when (launchMode) {
        WorkshopLaunchMode.Browse -> "公开工坊"
        WorkshopLaunchMode.AccountQuery -> "账号可见"
        WorkshopLaunchMode.Subscriptions -> "我的订阅"
    }
    val currentSectionLabel = resolveSectionLabel(query.sectionKey, sectionOptions)
    val publicQuickFilterLabel = steamPublicQuickFilterLabel(
        query = query,
        sortOptions = sortOptions,
        periodOptions = periodOptions,
    )
    val headerMeta = buildList {
        if (!isInitialSync) {
            add(
                when (launchMode) {
                    WorkshopLaunchMode.Browse -> "$totalCount 个条目"
                    WorkshopLaunchMode.AccountQuery -> "$totalCount 个结果"
                    WorkshopLaunchMode.Subscriptions -> "已订阅 $totalCount 项"
                },
            )
        }
        if (isBrowseMode) {
            add(currentSectionLabel)
            add(publicQuickFilterLabel)
        }
    }.joinToString(" · ")
    val statusMessage = if (isRefreshing || isInitialSync) {
        when (launchMode) {
            WorkshopLaunchMode.Browse -> "正在同步工坊内容"
            WorkshopLaunchMode.AccountQuery -> "正在查询账号可见条目"
            WorkshopLaunchMode.Subscriptions -> "正在同步你的订阅条目"
        }
    } else {
        null
    }
    val headerDescription = when (launchMode) {
        WorkshopLaunchMode.Browse -> ""
        WorkshopLaunchMode.AccountQuery -> "使用当前 Steam 账号查询账号可见条目；某些结果不会出现在公开工坊页面。"
        WorkshopLaunchMode.Subscriptions -> "只读取当前账号已订阅的条目，不会执行任何订阅变更。"
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = workshopAdaptiveSurfaceColor(
            light = Color(0xFFF7F1EA).copy(alpha = 0.96f),
            dark = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        ),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.5f))),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (darkTheme) {
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.98f)
                            } else {
                                Color.White.copy(alpha = 0.82f)
                            },
                            if (darkTheme) {
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
                            } else {
                                Color(0xFFFFF7EF).copy(alpha = 0.64f)
                            },
                            if (darkTheme) {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                            } else {
                                Color(0xFFF2E5D7).copy(alpha = 0.38f)
                            },
                        ),
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CompactActionButton(
                    onClick = onBack,
                    icon = {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    },
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = headerEyebrow,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (darkTheme) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                Color(0xFFB36B42)
                            },
                        )
                        if (headerMeta.isNotBlank()) {
                            Text(
                                text = headerMeta,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (statusMessage != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        if (darkTheme) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color(0xFFE28954)
                                        },
                                        CircleShape,
                                    ),
                            )
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (darkTheme) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0xFF9A5B38)
                                },
                            )
                        }
                    } else if (headerDescription.isNotBlank()) {
                        Text(
                            text = headerDescription,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                CompactActionButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    icon = {
                        if (isRefreshing || isInitialSync) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.3.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                        }
                    },
                )
            }

            if (!isSubscriptionMode) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .onFocusChanged { focusState ->
                            onSearchFocusChanged(focusState.isFocused)
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            when {
                                keyEvent.type != KeyEventType.KeyUp -> false
                                keyEvent.key != Key.Enter && keyEvent.key != Key.NumPadEnter -> false
                                else -> {
                                    onSubmitSearch()
                                    true
                                }
                            }
                        },
                    singleLine = true,
                    placeholder = {
                        Text(
                            if (isAccountQueryMode) "搜索当前账号可见条目" else "搜索创意工坊条目",
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchText.isNotBlank()) {
                            IconButton(onClick = onClearSearch) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                            }
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSubmitSearch() },
                        onDone = { onSubmitSearch() },
                    ),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = workshopAdaptiveSurfaceColor(
                            light = Color.White.copy(alpha = 0.72f),
                            dark = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                        ),
                        unfocusedContainerColor = workshopAdaptiveSurfaceColor(
                            light = Color.White.copy(alpha = 0.58f),
                            dark = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                        ),
                        disabledContainerColor = workshopAdaptiveSurfaceColor(
                            light = Color.White.copy(alpha = 0.48f),
                            dark = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.68f),
                        ),
                        focusedBorderColor = Color(0xFFE69A69),
                        unfocusedBorderColor = workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.34f)),
                        focusedLeadingIconColor = Color(0xFFE96D43),
                        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        cursorColor = Color(0xFFE96D43),
                    ),
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canOpenAccountQuery || isAccountQueryMode) {
                        QuickFilterChip(
                            label = launchModeLabel(launchMode),
                            onClick = onOpenSourcePicker,
                        )
                    }
                    if (isBrowseMode) {
                        QuickFilterChip(
                            label = "浏览",
                            onClick = onOpenBrowseSectionPicker,
                        )
                    }
                    if (isBrowseMode) {
                        QuickFilterChip(
                            label = publicQuickFilterLabel,
                            onClick = onOpenBrowseQuickFilterPicker,
                        )
                    }
                    if (canOpenSubscriptions) {
                        QuickFilterChip(
                            label = "我的订阅",
                            onClick = onOpenSubscriptions,
                        )
                    }
                    if (isBrowseMode) {
                        QuickFilterChip(
                            label = if (advancedFilterCount > 0) "高级筛选 $advancedFilterCount" else "高级筛选",
                            onClick = onOpenFilters,
                            highlighted = advancedFilterCount > 0,
                            icon = { Icon(Icons.Rounded.FilterAlt, contentDescription = null) },
                        )
                    }
                }

                if (isBrowseMode && (advancedFilterCount > 0 || query.searchText.isNotBlank())) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (query.searchText.isNotBlank()) {
                            InfoPill(
                                text = "搜索 ${query.searchText}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (query.requiredTags.isNotEmpty()) {
                            InfoPill(text = "包含 ${query.requiredTags.size}")
                        }
                        if (query.excludedTags.isNotEmpty()) {
                            InfoPill(
                                text = "排除 ${query.excludedTags.size}",
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        if (query.showIncompatible) {
                            InfoPill(text = "含不兼容")
                        }
                        if (query.createdDateRange.isActive) {
                            InfoPill(text = query.createdDateRange.summaryLabel("发布时间"))
                        }
                        if (query.updatedDateRange.isActive) {
                            InfoPill(text = query.updatedDateRange.summaryLabel("最后更新时间"))
                        }
                    }
                }

                if (isBrowseMode && headerDescription.isNotBlank()) {
                    Text(
                        text = headerDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Surface(
                    color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.62f)),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, workshopAdaptiveBorderColor()),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "我的订阅",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "这里只读取当前账号已订阅的条目，不会执行任何订阅变更。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (canOpenAccountQuery) {
                                OutlinedButton(
                                    onClick = onOpenAccountQuery,
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text("账号可见")
                                }
                            }
                            OutlinedButton(
                                onClick = onSwitchToBrowse,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text("公开工坊")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactActionButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.68f)),
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.44f))),
        shadowElevation = 2.dp,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

@Composable
private fun QuickFilterChip(
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickChoiceSheet(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    WorkshopNativeModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.84f)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.4f))),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (key, label) ->
                        ChoiceRow(
                            label = label,
                            selected = selectedKey == key,
                            onClick = { onSelect(key) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SteamPublicBrowseQuickFilterSheet(
    currentQuery: WorkshopBrowseQuery,
    sortOptions: List<Pair<String, String>>,
    periodOptions: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onApply: (WorkshopBrowseQuery) -> Unit,
) {
    WorkshopNativeModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "公开工坊视图",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "与 Steam 当前公开页一致，最热门时才显示时间范围；All Time 会切到最受好评（发布至今）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.84f)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.4f))),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "排序",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    sortOptions.forEach { (key, label) ->
                        ChoiceRow(
                            label = label,
                            selected = currentQuery.sortKey == key,
                            onClick = {
                                onApply(
                                    currentQuery.copy(
                                        sortKey = key,
                                        page = 1,
                                    ),
                                )
                            },
                        )
                    }

                    if (sortSupportsPeriod(currentQuery.sortKey)) {
                        Text(
                            text = "时间范围",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        periodOptions.forEach { (key, label) ->
                            ChoiceRow(
                                label = label,
                                selected = key == currentQuery.periodDays.toString(),
                                onClick = {
                                    val selectedDays = key.toIntOrNull()
                                    val nextQuery = if (selectedDays == -1) {
                                        currentQuery.copy(
                                            sortKey = WorkshopBrowseQuery.SORT_TOP_RATED,
                                            page = 1,
                                        )
                                    } else {
                                        currentQuery.copy(
                                            sortKey = WorkshopBrowseQuery.SORT_TREND,
                                            periodDays = selectedDays ?: currentQuery.periodDays,
                                            page = 1,
                                        )
                                    }
                                    onApply(nextQuery)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WorkshopListItem(
    item: WorkshopItem,
    showSubscriptionState: Boolean,
    autoResolveDownloadInfo: Boolean,
    animatedPreviewEnabled: Boolean,
    latestTask: DownloadTaskEntity?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.84f)),
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.44f))),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    workshopAdaptiveGradientBrush(
                        lightStart = Color.White.copy(alpha = 0.97f),
                        lightEnd = Color(0xFFF7EEE4).copy(alpha = 0.92f),
                    ),
                    shape = RoundedCornerShape(22.dp),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (animatedPreviewEnabled) {
                AnimatedWorkshopThumbnail(
                    imageUrl = item.previewUrl,
                    fallbackText = item.title,
                    modifier = Modifier
                        .width(78.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                ArtworkThumbnail(
                    imageUrl = item.previewUrl,
                    fallbackText = item.title,
                    modifier = Modifier
                        .width(78.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentScale = ContentScale.Crop,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x12E96D43),
                        border = BorderStroke(1.dp, Color(0x2AE96D43)),
                    ) {
                        Text(
                            text = "详情",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color(0xFFE96D43),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoPill(
                        text = item.downloadModeLabel(autoResolveDownloadInfo),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    if (showSubscriptionState && item.isSubscribed) {
                        InfoPill(text = "已订阅")
                    }
                    latestTask?.let { task ->
                        DownloadStatusPill(task = task)
                    }
                }

                Text(
                    text = buildList {
                        if (item.authorName.isNotBlank()) add("作者 ${item.authorName}")
                        if (item.fileSize > 0L) add(formatBytes(item.fileSize))
                        if (item.timeUpdated > 0L) add(formatEpochSeconds(item.timeUpdated))
                    }.joinToString(" · ").ifBlank { "点开查看完整介绍与下载操作" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PaginationCard(
    currentPage: Int,
    totalPages: Int,
    totalCount: Int,
    pageSize: Int,
    isLoading: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val startIndex = if (totalCount == 0) 0 else ((currentPage - 1) * pageSize) + 1
    val endIndex = (currentPage * pageSize).coerceAtMost(totalCount)
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    workshopAdaptiveGradientBrush(
                        lightStart = Color.White.copy(alpha = 0.96f),
                        lightEnd = Color(0xFFF6EFE8).copy(alpha = 0.9f),
                    ),
                    shape = RoundedCornerShape(28.dp),
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "分页浏览",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "第 $currentPage 页 / 共 $totalPages 页",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InfoPill(
                    text = if (totalCount > 0) "$startIndex-$endIndex / $totalCount" else "0 / 0",
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onPrevious,
                    enabled = hasPrevious && !isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("上一页")
                }
                Button(
                    onClick = onNext,
                    enabled = hasNext && !isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("下一页")
                }
            }
        }
    }
}

@Composable
private fun WorkshopDetailSheet(
    item: WorkshopItem,
    description: String,
    translationState: com.slay.workshopnative.ui.InlineTranslationState?,
    animatedPreviewEnabled: Boolean,
    latestTask: DownloadTaskEntity?,
    showSubscriptionState: Boolean,
    downloadIdentityLabel: String,
    downloadIdentityDescription: String,
    isQueueing: Boolean,
    isResolving: Boolean,
    authorProfileUrl: String?,
    onTranslateDescription: () -> Unit,
    onShowOriginalDescription: () -> Unit,
    onShowTranslatedDescription: () -> Unit,
    onOpenAuthorProfile: () -> Unit,
    onDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val hasActiveTask = latestTask?.status == DownloadStatus.Queued ||
        latestTask?.status == DownloadStatus.Running
    val showAuthorCard = item.authorName.isNotBlank() || !authorProfileUrl.isNullOrBlank() || item.creatorSteamId > 0L

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            if (animatedPreviewEnabled) {
                AnimatedWorkshopThumbnail(
                    imageUrl = item.previewUrl,
                    fallbackText = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(194.dp),
                    shape = RoundedCornerShape(30.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                ArtworkThumbnail(
                    imageUrl = item.previewUrl,
                    fallbackText = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(194.dp),
                    shape = RoundedCornerShape(30.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(194.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xCC172131),
                            ),
                        ),
                        shape = RoundedCornerShape(30.dp),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPill(
                        text = item.downloadModeLabel(true),
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White,
                    )
                    if (showSubscriptionState && item.isSubscribed) {
                        InfoPill(
                            text = "已订阅",
                            containerColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White,
                        )
                    }
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (item.fileName != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(text = item.fileName.orEmpty())
            }
        }

        if (showAuthorCard) {
            WorkshopAuthorCard(
                authorName = item.authorName.ifBlank { "Steam 创作者" },
                authorProfileUrl = authorProfileUrl,
                creatorSteamId = item.creatorSteamId,
                onOpenAuthorProfile = onOpenAuthorProfile,
            )
        }

        if (isResolving) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.6.dp)
                    Text(
                        text = "正在读取这个创意工坊条目的详细信息…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            WorkshopOverviewCard(item = item, autoResolveDownloadInfo = true)

            TranslatableDescriptionCard(
                title = "条目介绍",
                originalText = description,
                translatedText = translationState?.translatedText,
                providerLabel = translationState?.providerLabel,
                sourceLanguageLabel = translationState?.sourceLanguageLabel,
                isTranslating = translationState?.isTranslating == true,
                showTranslated = translationState?.showTranslated == true,
                errorMessage = translationState?.errorMessage,
                collapsedMaxLines = 6,
                onTranslate = onTranslateDescription,
                onShowOriginal = onShowOriginalDescription,
                onShowTranslated = onShowTranslatedDescription,
            )

            if (item.tags.isNotEmpty()) {
                WorkshopTagsCard(tags = item.tags)
            }

            latestTask?.let { task ->
                DownloadStatusCard(task = task)
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "下载操作",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "支持的下载方式会根据条目来源自动选择，当前状态也会在下载中心同步更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPill(
                        text = downloadIdentityLabel,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = downloadIdentityDescription,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDownload,
                        enabled = !isResolving && (item.canDownload || item.hasChildItems) && !isQueueing && !hasActiveTask,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Text(
                            text = when {
                                isResolving -> "正在读取详情"
                                isQueueing -> "正在加入队列"
                                hasActiveTask -> "任务已在进行中"
                                item.hasChildItems && !item.canDownload -> "下载合集子项"
                                latestTask?.status == DownloadStatus.Failed -> "重新下载"
                                latestTask?.status == DownloadStatus.Success -> "再次下载"
                                else -> "加入下载队列"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenDownloads,
                        enabled = latestTask != null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("查看下载页")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun DownloadStatusCard(task: DownloadTaskEntity) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.82f)),
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor()),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "当前下载状态",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                DownloadStatusPill(task = task)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkshopFactTile(
                    modifier = Modifier.weight(1f),
                    label = "目标位置",
                    value = task.destinationLabel,
                )
                WorkshopFactTile(
                    modifier = Modifier.weight(1f),
                    label = "当前进度",
                    value = if (task.isConnectingToSource()) {
                        "连接中"
                    } else if (task.isFinalizingDownload()) {
                        "整理中"
                    } else {
                        "${task.progressPercent}%"
                    },
                )
            }

            if (task.status == DownloadStatus.Queued || task.status == DownloadStatus.Running) {
                if (task.isConnectingToSource() || task.isFinalizingDownload()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { task.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Text(
                text = if (task.isConnectingToSource()) {
                    "正在连接 CDN…"
                } else if (task.isFinalizingDownload()) {
                    "正在整理文件…"
                } else {
                    "${task.progressPercent}% · ${formatBytes(task.bytesDownloaded)} / ${formatBytes(task.totalBytes)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!task.errorMessage.isNullOrBlank()) {
                Text(
                    text = task.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!task.runtimeRouteLabel.isNullOrBlank() || !task.runtimeEndpointLabel.isNullOrBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WorkshopFactTile(
                        modifier = Modifier.weight(1f),
                        label = "本次链路",
                        value = task.runtimeRouteLabel ?: task.downloadAuthMode.name,
                    )
                    WorkshopFactTile(
                        modifier = Modifier.weight(1f),
                        label = "节点",
                        value = task.runtimeEndpointLabel ?: "等待确定",
                    )
                }
                if (!task.runtimeTransportLabel.isNullOrBlank()) {
                    Text(
                        text = "网络路径：${task.runtimeTransportLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkshopOverviewCard(
    item: WorkshopItem,
    autoResolveDownloadInfo: Boolean,
) {
    Surface(
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.4f))),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkshopFactTile(
                    modifier = Modifier.weight(1f),
                    label = "下载方式",
                    value = item.downloadModeLabel(autoResolveDownloadInfo),
                )
                WorkshopFactTile(
                    modifier = Modifier.weight(1f),
                    label = "更新时间",
                    value = if (item.timeUpdated > 0L) formatEpochSeconds(item.timeUpdated) else "未知",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkshopFactTile(
                    modifier = Modifier.weight(1f),
                    label = "文件大小",
                    value = if (item.fileSize > 0L) formatBytes(item.fileSize) else "未知",
                )
                WorkshopFactTile(
                    modifier = Modifier.weight(1f),
                    label = "订阅数量",
                    value = if (item.subscriptions > 0) item.subscriptions.toString() else "暂无",
                )
            }
        }
    }
}

@Composable
private fun WorkshopAuthorCard(
    authorName: String,
    authorProfileUrl: String?,
    creatorSteamId: Long,
    onOpenAuthorProfile: () -> Unit,
) {
    val canOpenAuthorProfile = !authorProfileUrl.isNullOrBlank()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenAuthorProfile, onClick = onOpenAuthorProfile),
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.4f))),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "作者",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = authorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        canOpenAuthorProfile -> "点击查看作者 Steam 主页"
                        creatorSteamId > 0L -> "作者主页链接暂不可用"
                        else -> "当前条目没有提供更多作者资料"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canOpenAuthorProfile) {
                OutlinedButton(
                    onClick = onOpenAuthorProfile,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                    Text(
                        text = "主页",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkshopTagsCard(tags: List<String>) {
    Surface(
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.4f))),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "标签",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.take(4).forEach { tag ->
                    InfoPill(text = tag)
                }
            }
        }
    }
}

@Composable
private fun WorkshopFactTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    currentQuery: WorkshopBrowseQuery,
    tagGroups: List<WorkshopBrowseTagGroup>,
    supportsIncompatibleFilter: Boolean,
    onDismiss: () -> Unit,
    onApply: (WorkshopBrowseQuery) -> Unit,
) {
    var draft by remember(currentQuery) { mutableStateOf(currentQuery) }
    var activeDatePicker by remember { mutableStateOf<WorkshopDatePickerRequest?>(null) }
    var expandedSections by rememberSaveable(currentQuery, tagGroups) {
        mutableStateOf(defaultExpandedWorkshopFilterSections(currentQuery, tagGroups))
    }

    activeDatePicker?.let { request ->
        WorkshopDatePickerDialog(
            title = request.dialogTitle(),
            boundary = request.boundary,
            initialEpochSeconds = draft.dateRangeFor(request.type).epochSecondsFor(request.boundary),
            onDismiss = { activeDatePicker = null },
            onConfirm = { epochSeconds ->
                draft = draft.updateDateRange(request.type) { range ->
                    when (request.boundary) {
                        WorkshopDateBoundary.Start -> range.copy(startEpochSeconds = epochSeconds)
                        WorkshopDateBoundary.End -> range.copy(endEpochSeconds = epochSeconds)
                    }.normalized()
                }
                activeDatePicker = null
            },
        )
    }

    WorkshopNativeModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "高级筛选",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "标签和兼容性在这里细调，搜索和排序继续走上面的快速入口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.84f)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.44f))),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "当前条件",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = listOfNotNull(
                            draft.requiredTags.takeIf { it.isNotEmpty() }?.let { "包含 ${it.size}" },
                            draft.excludedTags.takeIf { it.isNotEmpty() }?.let { "排除 ${it.size}" },
                            draft.showIncompatible.takeIf { it }?.let { "含不兼容" },
                            draft.createdDateRange.takeIf(WorkshopDateRangeFilter::isActive)
                                ?.summaryLabel("发布时间"),
                            draft.updatedDateRange.takeIf(WorkshopDateRangeFilter::isActive)
                                ?.summaryLabel("最后更新时间"),
                        ).joinToString(" · ").ifBlank { "当前还没有启用高级筛选条件" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FilterSection(
                title = "按日期筛选",
                subtitle = "与 Steam 官方公开页一致，可同时限制发布时间和最后更新时间。",
                summary = draft.dateRangeSummaryLabel(),
                expanded = "date" in expandedSections,
                onToggle = {
                    expandedSections = expandedSections.toggle("date")
                },
            ) {
                DateRangeFilterBlock(
                    title = "发布时间",
                    range = draft.createdDateRange,
                    onPickStart = {
                        activeDatePicker = WorkshopDatePickerRequest(
                            type = WorkshopDateRangeType.Created,
                            boundary = WorkshopDateBoundary.Start,
                        )
                    },
                    onPickEnd = {
                        activeDatePicker = WorkshopDatePickerRequest(
                            type = WorkshopDateRangeType.Created,
                            boundary = WorkshopDateBoundary.End,
                        )
                    },
                    onClearStart = {
                        draft = draft.updateDateRange(WorkshopDateRangeType.Created) { range ->
                            range.copy(startEpochSeconds = 0L).normalized()
                        }
                    },
                    onClearEnd = {
                        draft = draft.updateDateRange(WorkshopDateRangeType.Created) { range ->
                            range.copy(endEpochSeconds = 0L).normalized()
                        }
                    },
                    onClearRange = {
                        draft = draft.updateDateRange(WorkshopDateRangeType.Created) {
                            WorkshopDateRangeFilter()
                        }
                    },
                )
                DateRangeFilterBlock(
                    title = "最后更新时间",
                    range = draft.updatedDateRange,
                    onPickStart = {
                        activeDatePicker = WorkshopDatePickerRequest(
                            type = WorkshopDateRangeType.Updated,
                            boundary = WorkshopDateBoundary.Start,
                        )
                    },
                    onPickEnd = {
                        activeDatePicker = WorkshopDatePickerRequest(
                            type = WorkshopDateRangeType.Updated,
                            boundary = WorkshopDateBoundary.End,
                        )
                    },
                    onClearStart = {
                        draft = draft.updateDateRange(WorkshopDateRangeType.Updated) { range ->
                            range.copy(startEpochSeconds = 0L).normalized()
                        }
                    },
                    onClearEnd = {
                        draft = draft.updateDateRange(WorkshopDateRangeType.Updated) { range ->
                            range.copy(endEpochSeconds = 0L).normalized()
                        }
                    },
                    onClearRange = {
                        draft = draft.updateDateRange(WorkshopDateRangeType.Updated) {
                            WorkshopDateRangeFilter()
                        }
                    },
                )
            }

            if (supportsIncompatibleFilter) {
                FilterSection(
                    title = "兼容性",
                    subtitle = "控制是否把 Steam 标记为不兼容的条目也显示出来。",
                    summary = if (draft.showIncompatible) "显示不兼容条目" else "默认隐藏不兼容条目",
                    expanded = "incompatible" in expandedSections,
                    onToggle = {
                        expandedSections = expandedSections.toggle("incompatible")
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("显示不兼容条目", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "把 Steam 标记为不兼容的条目也一并显示出来",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.showIncompatible,
                            onCheckedChange = { draft = draft.copy(showIncompatible = it) },
                        )
                    }
                }
            }

            tagGroups.forEach { group ->
                FilterSection(
                    title = group.displayTitle(),
                    subtitle = when (group.selectionMode) {
                        WorkshopBrowseTagGroupSelectionMode.IncludeExclude -> "支持包含和排除两种条件"
                        WorkshopBrowseTagGroupSelectionMode.SingleSelect -> "与 Steam 官方一致，同组内只能选一个值"
                    },
                    summary = group.summaryLabel(draft),
                    expanded = group.sectionKey() in expandedSections,
                    onToggle = {
                        expandedSections = expandedSections.toggle(group.sectionKey())
                    },
                ) {
                    when (group.selectionMode) {
                        WorkshopBrowseTagGroupSelectionMode.IncludeExclude -> {
                            group.tags.forEach { tag ->
                                TagFilterRow(
                                    tagLabel = tag.displayLabel(),
                                    included = draft.requiredTags.contains(tag.value),
                                    excluded = draft.excludedTags.contains(tag.value),
                                    onInclude = {
                                        draft = draft.copy(
                                            requiredTags = draft.requiredTags
                                                .toMutableSet()
                                                .apply {
                                                    if (!add(tag.value)) remove(tag.value)
                                                },
                                            excludedTags = draft.excludedTags - tag.value,
                                        )
                                    },
                                    onExclude = {
                                        draft = draft.copy(
                                            requiredTags = draft.requiredTags - tag.value,
                                            excludedTags = draft.excludedTags
                                                .toMutableSet()
                                                .apply {
                                                    if (!add(tag.value)) remove(tag.value)
                                                },
                                        )
                                    },
                                )
                            }
                        }

                        WorkshopBrowseTagGroupSelectionMode.SingleSelect -> {
                            val groupValues = group.tags.map { tag -> tag.value }.toSet()
                            val selectedValue = group.tags.firstOrNull { tag ->
                                draft.requiredTags.contains(tag.value)
                            }?.value

                            ChoiceRow(
                                label = "不限",
                                selected = selectedValue == null,
                                onClick = {
                                    draft = draft.copy(
                                        requiredTags = draft.requiredTags - groupValues,
                                        excludedTags = draft.excludedTags - groupValues,
                                    )
                                },
                            )
                            group.tags.forEach { tag ->
                                ChoiceRow(
                                    label = tag.displayLabel(),
                                    selected = selectedValue == tag.value,
                                    onClick = {
                                        draft = draft.copy(
                                            requiredTags = if (selectedValue == tag.value) {
                                                draft.requiredTags - groupValues
                                            } else {
                                                (draft.requiredTags - groupValues) + tag.value
                                            },
                                            excludedTags = draft.excludedTags - groupValues,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.82f)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.4f))),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "应用筛选",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "会带着当前搜索词和快速筛选条件，一并应用这里的高级项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                draft = draft.copy(
                                    requiredTags = emptySet(),
                                    excludedTags = emptySet(),
                                    showIncompatible = false,
                                    createdDateRange = WorkshopDateRangeFilter(),
                                    updatedDateRange = WorkshopDateRangeFilter(),
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("清空高级项")
                        }
                        Button(
                            onClick = { onApply(draft.copy(page = 1)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("应用")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private enum class WorkshopDateRangeType {
    Created,
    Updated,
}

private enum class WorkshopDateBoundary {
    Start,
    End,
}

private data class WorkshopDatePickerRequest(
    val type: WorkshopDateRangeType,
    val boundary: WorkshopDateBoundary,
)

@Composable
private fun DateRangeFilterBlock(
    title: String,
    range: WorkshopDateRangeFilter,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onClearStart: () -> Unit,
    onClearEnd: () -> Unit,
    onClearRange: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = range.detailLabel().ifBlank { "未限制日期范围" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (range.isActive) {
                    TextButton(onClick = onClearRange) {
                        Text("清空")
                    }
                }
            }

            DateRangeValueRow(
                label = "起始日期",
                value = range.startEpochSeconds.formatWorkshopDateOrDefault(),
                hasValue = range.startEpochSeconds > 0L,
                onPick = onPickStart,
                onClear = onClearStart,
            )
            DateRangeValueRow(
                label = "结束日期",
                value = range.endEpochSeconds.formatWorkshopDateOrDefault(),
                hasValue = range.endEpochSeconds > 0L,
                onPick = onPickEnd,
                onClear = onClearEnd,
            )
        }
    }
}

@Composable
private fun DateRangeValueRow(
    label: String,
    value: String,
    hasValue: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onPick,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(if (hasValue) "修改" else "选择")
        }
        if (hasValue) {
            TextButton(onClick = onClear) {
                Text("移除")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkshopDatePickerDialog(
    title: String,
    boundary: WorkshopDateBoundary,
    initialEpochSeconds: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialEpochSeconds.toDatePickerInitialMillis(),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = datePickerState.selectedDateMillis != null,
                onClick = {
                    val selectedMillis = datePickerState.selectedDateMillis ?: return@TextButton
                    onConfirm(
                        when (boundary) {
                            WorkshopDateBoundary.Start -> selectedMillis.toEpochSecondsAtStartOfDay()
                            WorkshopDateBoundary.End -> selectedMillis.toEpochSecondsAtEndOfDay()
                        },
                    )
                },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    ) {
        DatePicker(
            state = datePickerState,
            title = { Text(title) },
            showModeToggle = false,
        )
    }
}

@Composable
private fun FilterSection(
    title: String,
    subtitle: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.46f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (selected) {
                InfoPill(
                    text = "已选",
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun TagFilterRow(
    tagLabel: String,
    included: Boolean,
    excluded: Boolean,
    onInclude: () -> Unit,
    onExclude: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tagLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SmallToggle(
                label = "包含",
                active = included,
                onClick = onInclude,
            )
            SmallToggle(
                label = "排除",
                active = excluded,
                onClick = onExclude,
                activeContainer = MaterialTheme.colorScheme.errorContainer,
                activeContent = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SmallToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    activeContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    activeContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (active) activeContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier
                .widthIn(min = 54.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (active) activeContent else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun InfoPill(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(999.dp),
        shadowElevation = 1.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun DownloadStatusPill(task: DownloadTaskEntity) {
    val (containerColor, contentColor) = statusColors(task.status)
    InfoPill(
        text = task.statusShortLabel(),
        containerColor = containerColor,
        contentColor = contentColor,
    )
}

@Composable
private fun statusColors(status: DownloadStatus): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        DownloadStatus.Queued -> scheme.secondaryContainer to scheme.onSecondaryContainer
        DownloadStatus.Running -> scheme.primaryContainer to scheme.onPrimaryContainer
        DownloadStatus.Paused -> scheme.surfaceVariant to scheme.onSurfaceVariant
        DownloadStatus.Success -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        DownloadStatus.Failed -> scheme.errorContainer to scheme.onErrorContainer
        DownloadStatus.Cancelled -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        DownloadStatus.Unavailable -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

private fun DownloadTaskEntity.statusShortLabel(): String {
    return when (status) {
        DownloadStatus.Queued -> "已排队"
        DownloadStatus.Running -> when {
            isFinalizingDownload() -> "整理中"
            progressPercent > 0 -> "${progressPercent}%"
            bytesDownloaded > 0L -> "进行中"
            else -> "连接中"
        }
        DownloadStatus.Paused -> "已暂停"
        DownloadStatus.Success -> "已完成"
        DownloadStatus.Failed -> "失败"
        DownloadStatus.Cancelled -> "已取消"
        DownloadStatus.Unavailable -> "不可下载"
    }
}

private fun DownloadTaskEntity.isConnectingToSource(): Boolean {
    return status == DownloadStatus.Running && progressPercent == 0 && bytesDownloaded == 0L
}

private fun DownloadTaskEntity.isFinalizingDownload(): Boolean {
    return status == DownloadStatus.Running &&
        totalBytes > 0L &&
        bytesDownloaded >= totalBytes
}

private fun launchModeLabel(launchMode: WorkshopLaunchMode): String {
    return when (launchMode) {
        WorkshopLaunchMode.Browse -> "公开工坊"
        WorkshopLaunchMode.AccountQuery -> "账号可见"
        WorkshopLaunchMode.Subscriptions -> "我的订阅"
    }
}

private fun WorkshopItem.downloadModeLabel(autoResolveEnabled: Boolean): String {
    return when {
        hasChildItems && !canDownload -> "合集子项下载"
        canSteamContentDownload -> "Steam 内容下载"
        canDirectDownload -> "公开直链下载"
        !isDownloadInfoResolved && autoResolveEnabled -> "检测下载方式中"
        !isDownloadInfoResolved -> "详情页检测"
        else -> "暂不可下载"
    }
}

private fun sectionLabel(sectionKey: String): String {
    return when (sectionKey) {
        WorkshopBrowseQuery.SECTION_MY_SUBSCRIPTIONS -> "我的订阅"
        WorkshopBrowseQuery.SECTION_ITEMS -> "项目"
        "collections" -> "合集"
        else -> sectionKey
    }
}

private fun steamPublicQuickFilterLabel(
    query: WorkshopBrowseQuery,
    sortOptions: List<WorkshopBrowseSortOption>,
    periodOptions: List<WorkshopBrowsePeriodOption>,
): String {
    val currentSortLabel = resolveSortLabel(query.sortKey, sortOptions)
    return when (query.sortKey) {
        WorkshopBrowseQuery.SORT_TREND -> "$currentSortLabel（${resolvePeriodLabel(query.periodDays, periodOptions)}）"
        else -> currentSortLabel
    }
}

private fun steamPublicSortLabel(sortKey: String): String {
    return when (sortKey) {
        WorkshopBrowseQuery.SORT_TREND -> "最热门"
        WorkshopBrowseQuery.SORT_TOP_RATED -> "最受好评（发布至今）"
        WorkshopBrowseQuery.SORT_MOST_RECENT -> "最近发行"
        WorkshopBrowseQuery.SORT_LAST_UPDATED -> "最新更新"
        WorkshopBrowseQuery.SORT_TOTAL_UNIQUE_SUBSCRIBERS -> "不重复订阅者总计"
        else -> sortKey
    }
}

private fun steamPublicPeriodLabel(days: Int): String {
    return when (days) {
        1 -> "今天"
        7 -> "1 周"
        30 -> "30 天"
        90 -> "3 个月"
        180 -> "6 个月"
        365 -> "1 年"
        else -> "${days} 天"
    }
}

private fun sortLabel(sortKey: String): String {
    return steamPublicSortLabel(sortKey)
}

private fun periodLabel(days: Int): String {
    return when (days) {
        -1 -> "All Time"
        else -> steamPublicPeriodLabel(days)
    }
}

private fun fallbackSectionOptions(currentKey: String): List<Pair<String, String>> {
    return buildList {
        add(WorkshopBrowseQuery.SECTION_ITEMS to sectionLabel(WorkshopBrowseQuery.SECTION_ITEMS))
        add("collections" to sectionLabel("collections"))
        if (currentKey !in setOf(WorkshopBrowseQuery.SECTION_ITEMS, "collections")) {
            add(currentKey to sectionLabel(currentKey))
        }
    }.distinctBy { it.first }
}

private fun fallbackSortOptions(currentKey: String): List<Pair<String, String>> {
    val defaults = listOf(
        WorkshopBrowseQuery.SORT_TREND,
        WorkshopBrowseQuery.SORT_TOP_RATED,
        WorkshopBrowseQuery.SORT_MOST_RECENT,
        WorkshopBrowseQuery.SORT_LAST_UPDATED,
        WorkshopBrowseQuery.SORT_TOTAL_UNIQUE_SUBSCRIBERS,
    )
    return buildList {
        defaults.forEach { key ->
            add(key to sortLabel(key))
        }
        if (currentKey !in defaults) {
            add(currentKey to sortLabel(currentKey))
        }
    }.distinctBy { it.first }
}

private fun fallbackPeriodOptions(currentDays: Int): List<Pair<String, String>> {
    val defaults = listOf(1, 7, 30, 90, 180, 365, -1)
    return buildList {
        defaults.forEach { days ->
            add(days.toString() to periodLabel(days))
        }
        if (currentDays !in defaults) {
            add(currentDays.toString() to periodLabel(currentDays))
        }
    }.distinctBy { it.first }
}

private fun sortSupportsPeriod(sortKey: String): Boolean {
    return sortKey == WorkshopBrowseQuery.SORT_TREND
}

private fun resolveSectionLabel(
    currentKey: String,
    options: List<WorkshopBrowseSectionOption>,
): String {
    return options.firstOrNull { option -> option.key == currentKey }?.label ?: sectionLabel(currentKey)
}

private fun resolveSortLabel(
    currentKey: String,
    options: List<WorkshopBrowseSortOption>,
): String {
    return options.firstOrNull { option -> option.key == currentKey }?.label ?: sortLabel(currentKey)
}

private fun resolvePeriodLabel(
    currentDays: Int,
    options: List<WorkshopBrowsePeriodOption>,
): String {
    return options.firstOrNull { option -> option.days == currentDays }?.label ?: periodLabel(currentDays)
}

private val filterGroupTranslations = mapOf(
    "Type" to "类型",
    "Age Rating" to "年龄分级",
    "Genre" to "风格",
    "Content Type" to "内容类型",
    "Addon Type" to "插件类型",
    "Resolution" to "分辨率",
    "Category" to "分类",
    "Asset Type" to "资源类型",
    "Asset Genre" to "资源风格",
    "Script Type" to "脚本类型",
    "Miscellaneous" to "杂项",
    "Map Type" to "地图类型",
    "Item Type" to "物品类型",
    "Vehicle Type" to "载具类型",
    "Skin Type" to "皮肤类型",
    "Object Type" to "对象类型",
)

private val filterOptionTranslations = mapOf(
    "Scene" to "场景",
    "Video" to "视频",
    "Application" to "应用程序",
    "Web" to "网页",
    "Everyone" to "全年龄",
    "Questionable" to "可能成人",
    "Mature" to "成人",
    "Standard Definition" to "标准分辨率",
    "Ultrawide Standard Definition" to "超宽标准分辨率",
    "Dual Standard Definition" to "双屏标准分辨率",
    "Triple Standard Definition" to "三屏标准分辨率",
    "Portrait Standard Definition" to "竖屏标准分辨率",
    "Other resolution" to "其他分辨率",
    "Dynamic resolution" to "动态分辨率",
    "Wallpaper" to "壁纸",
    "Preset" to "预设",
    "Asset" to "资源",
    "Particle" to "粒子",
    "Image" to "图像",
    "Sound" to "音频",
    "Model" to "模型",
    "Text" to "文本",
    "Sprite" to "精灵",
    "Fullscreen" to "全屏",
    "Composite" to "合成",
    "Script" to "脚本",
    "Effect" to "效果",
    "Audio Visualizer" to "音频可视化",
    "Background" to "背景",
    "Character" to "角色",
    "Clock" to "时钟",
    "Fire" to "火焰",
    "Interactive" to "互动",
    "Magic" to "魔法",
    "Post Processing" to "后期处理",
    "Smoke" to "烟雾",
    "Space" to "太空",
    "Boolean" to "布尔值",
    "Number" to "数值",
    "String" to "字符串",
    "No Animation" to "无动画",
    "Oversized" to "超大",
    "Approved" to "已审核",
    "Audio responsive" to "音频响应",
    "Customizable" to "可自定义",
    "HDR" to "高动态范围",
    "Media Integration" to "媒体集成",
    "User Shortcut" to "用户快捷方式",
    "Video Texture" to "视频纹理",
    "Asset Pack" to "资源包",
    "Abstract" to "抽象",
    "Animal" to "动物",
    "Anime" to "动漫",
    "Cartoon" to "卡通",
    "Cyberpunk" to "赛博朋克",
    "Fantasy" to "奇幻",
    "Game" to "游戏",
    "Girls" to "女孩",
    "Guys" to "男性",
    "Landscape" to "风景",
    "Medieval" to "中世纪",
    "Memes" to "梗图",
    "Music" to "音乐",
    "Nature" to "自然",
    "Pixel art" to "像素艺术",
    "Relaxing" to "放松",
    "Retro" to "复古",
    "Sci-Fi" to "科幻",
    "Sports" to "体育",
    "Technology" to "科技",
    "Television" to "电视",
    "Vehicle" to "载具",
    "Unspecified" to "未指定",
)

private fun defaultExpandedWorkshopFilterSections(
    query: WorkshopBrowseQuery,
    groups: List<WorkshopBrowseTagGroup>,
): Set<String> {
    return buildSet {
        if (query.createdDateRange.isActive || query.updatedDateRange.isActive) {
            add("date")
        }
        if (query.showIncompatible) {
            add("incompatible")
        }
        groups.filter { group -> group.hasActiveSelection(query) }
            .forEach { group -> add(group.sectionKey()) }
    }
}

private fun Set<String>.toggle(key: String): Set<String> {
    return if (key in this) this - key else this + key
}

private fun WorkshopBrowseTagGroup.sectionKey(): String = "group:$label"

private fun WorkshopBrowseTagGroup.displayTitle(): String {
    return localizeFilterDisplayText(label, filterGroupTranslations)
}

private fun WorkshopBrowseTagOption.displayLabel(): String {
    return localizeFilterDisplayText(label, filterOptionTranslations)
}

private fun WorkshopBrowseTagGroup.summaryLabel(query: WorkshopBrowseQuery): String {
    return when (selectionMode) {
        WorkshopBrowseTagGroupSelectionMode.IncludeExclude -> {
            val included = tags.filter { tag -> query.requiredTags.contains(tag.value) }
            val excluded = tags.filter { tag -> query.excludedTags.contains(tag.value) }
            listOfNotNull(
                included.takeIf(List<WorkshopBrowseTagOption>::isNotEmpty)?.let { selected ->
                    if (selected.size <= 2) {
                        "包含：${selected.joinToString("、") { tag -> tag.displayLabel() }}"
                    } else {
                        "包含 ${selected.size} 项"
                    }
                },
                excluded.takeIf(List<WorkshopBrowseTagOption>::isNotEmpty)?.let { selected ->
                    if (selected.size <= 2) {
                        "排除：${selected.joinToString("、") { tag -> tag.displayLabel() }}"
                    } else {
                        "排除 ${selected.size} 项"
                    }
                },
            ).joinToString(" · ").ifBlank { "未设置" }
        }

        WorkshopBrowseTagGroupSelectionMode.SingleSelect -> {
            tags.firstOrNull { tag -> query.requiredTags.contains(tag.value) }
                ?.displayLabel()
                ?.let { value -> "当前：$value" }
                ?: "不限"
        }
    }
}

private fun WorkshopBrowseTagGroup.hasActiveSelection(query: WorkshopBrowseQuery): Boolean {
    return tags.any { tag ->
        tag.value in query.requiredTags || tag.value in query.excludedTags
    }
}

private fun WorkshopBrowseQuery.dateRangeSummaryLabel(): String {
    return listOfNotNull(
        createdDateRange.takeIf(WorkshopDateRangeFilter::isActive)?.summaryLabel("发布时间"),
        updatedDateRange.takeIf(WorkshopDateRangeFilter::isActive)?.summaryLabel("最后更新时间"),
    ).joinToString(" · ").ifBlank { "未设置" }
}

private fun localizeFilterDisplayText(
    raw: String,
    translations: Map<String, String>,
): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return raw
    return translations[trimmed] ?: trimmed
}

private fun workshopPageCount(totalCount: Int, pageSize: Int): Int {
    if (totalCount <= 0) return 1
    return ((totalCount + pageSize - 1) / pageSize).coerceAtLeast(1)
}

private fun shouldShowPagination(
    currentPage: Int,
    totalCount: Int,
    hasMore: Boolean,
    pageSize: Int,
): Boolean {
    return currentPage > 1 || hasMore || totalCount > pageSize
}

private val workshopDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")

private fun WorkshopBrowseQuery.activeAdvancedFilterCount(): Int {
    return requiredTags.size +
        excludedTags.size +
        (if (showIncompatible) 1 else 0) +
        (if (createdDateRange.isActive) 1 else 0) +
        (if (updatedDateRange.isActive) 1 else 0)
}

private fun WorkshopBrowseQuery.dateRangeFor(type: WorkshopDateRangeType): WorkshopDateRangeFilter {
    return when (type) {
        WorkshopDateRangeType.Created -> createdDateRange
        WorkshopDateRangeType.Updated -> updatedDateRange
    }
}

private fun WorkshopBrowseQuery.updateDateRange(
    type: WorkshopDateRangeType,
    transform: (WorkshopDateRangeFilter) -> WorkshopDateRangeFilter,
): WorkshopBrowseQuery {
    return when (type) {
        WorkshopDateRangeType.Created -> copy(createdDateRange = transform(createdDateRange).normalized())
        WorkshopDateRangeType.Updated -> copy(updatedDateRange = transform(updatedDateRange).normalized())
    }
}

private fun WorkshopDatePickerRequest.dialogTitle(): String {
    val prefix = when (type) {
        WorkshopDateRangeType.Created -> "发布时间"
        WorkshopDateRangeType.Updated -> "最后更新时间"
    }
    val suffix = when (boundary) {
        WorkshopDateBoundary.Start -> "起始日期"
        WorkshopDateBoundary.End -> "结束日期"
    }
    return "$prefix - $suffix"
}

private fun WorkshopDateRangeFilter.epochSecondsFor(boundary: WorkshopDateBoundary): Long {
    return when (boundary) {
        WorkshopDateBoundary.Start -> startEpochSeconds
        WorkshopDateBoundary.End -> endEpochSeconds
    }
}

private fun WorkshopDateRangeFilter.summaryLabel(prefix: String): String {
    return "$prefix ${detailLabel()}"
}

private fun WorkshopDateRangeFilter.detailLabel(): String {
    val startLabel = startEpochSeconds.takeIf { it > 0L }?.formatWorkshopDate()
    val endLabel = endEpochSeconds.takeIf { it > 0L }?.formatWorkshopDate()
    return when {
        startLabel != null && endLabel != null -> "$startLabel 到 $endLabel"
        startLabel != null -> "$startLabel 之后"
        endLabel != null -> "$endLabel 之前"
        else -> ""
    }
}

private fun WorkshopItem.authorProfileUrlOrFallback(): String? {
    return authorProfileUrl?.takeIf(String::isNotBlank)
        ?: creatorSteamId.takeIf { it > 0L }?.let { steamId64 ->
            "https://steamcommunity.com/profiles/$steamId64/"
        }
}

private fun Long.formatWorkshopDateOrDefault(): String {
    return if (this > 0L) formatWorkshopDate() else "不限"
}

private fun Long.formatWorkshopDate(): String {
    return Instant.ofEpochSecond(this)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(workshopDateFormatter)
}

private fun Long.toDatePickerInitialMillis(): Long? {
    if (this <= 0L) return null
    val localDate = Instant.ofEpochSecond(this)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun Long.toEpochSecondsAtStartOfDay(): Long {
    val selectedDate = Instant.ofEpochMilli(this)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    return selectedDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
}

private fun Long.toEpochSecondsAtEndOfDay(): Long {
    val selectedDate = Instant.ofEpochMilli(this)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    return selectedDate
        .plusDays(1)
        .atStartOfDay(ZoneId.systemDefault())
        .minusSeconds(1)
        .toEpochSecond()
}
