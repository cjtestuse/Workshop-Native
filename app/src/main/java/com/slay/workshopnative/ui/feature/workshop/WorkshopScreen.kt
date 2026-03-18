package com.slay.workshopnative.ui.feature.workshop

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slay.workshopnative.core.util.formatBytes
import com.slay.workshopnative.core.util.formatEpochSeconds
import com.slay.workshopnative.data.local.DownloadStatus
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.model.WorkshopBrowsePeriodOption
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.model.WorkshopBrowseSectionOption
import com.slay.workshopnative.data.model.WorkshopBrowseSortOption
import com.slay.workshopnative.data.model.WorkshopBrowseTagGroup
import com.slay.workshopnative.data.model.WorkshopItem
import com.slay.workshopnative.ui.components.ArtworkThumbnail
import com.slay.workshopnative.ui.components.ExpandableBodyText
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
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSectionPicker by rememberSaveable { mutableStateOf(false) }
    var showSortPicker by rememberSaveable { mutableStateOf(false) }
    var showPeriodPicker by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable(appId, launchMode.name) { mutableStateOf("") }
    val isSubscriptionMode = state.launchMode == WorkshopLaunchMode.Subscriptions

    LaunchedEffect(state.query.searchText) {
        if (searchText != state.query.searchText) {
            searchText = state.query.searchText
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

    LaunchedEffect(listState, state.items) {
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
                onOpenFilters = { showFilters = true },
                onOpenSectionPicker = { showSectionPicker = true },
                onOpenSortPicker = { showSortPicker = true },
                onOpenPeriodPicker = { showPeriodPicker = true },
                onSwitchToBrowse = viewModel::switchToBrowseMode,
                isRefreshing = state.isRefreshing,
                query = state.query,
                activeTagCount = state.query.requiredTags.size + state.query.excludedTags.size,
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                onSubmitSearch = {
                    focusManager.clearFocus(force = true)
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
                            autoResolveDownloadInfo = state.autoResolveDownloadInfo,
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

    if (!isSubscriptionMode && showFilters) {
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

    val sectionPickerOptions = remember(state.sectionOptions, state.query.sectionKey) {
        state.sectionOptions
            .takeIf(List<WorkshopBrowseSectionOption>::isNotEmpty)
            ?.map { it.key to it.label }
            ?: fallbackSectionOptions(state.query.sectionKey)
    }
    val sortPickerOptions = remember(state.sortOptions, state.query.sortKey) {
        state.sortOptions
            .takeIf(List<WorkshopBrowseSortOption>::isNotEmpty)
            ?.map { it.key to it.label }
            ?: fallbackSortOptions(state.query.sortKey)
    }
    val periodPickerOptions = remember(state.periodOptions, state.query.periodDays) {
        state.periodOptions
            .takeIf(List<WorkshopBrowsePeriodOption>::isNotEmpty)
            ?.map { it.days.toString() to it.label }
            ?: fallbackPeriodOptions(state.query.periodDays)
    }

    if (!isSubscriptionMode && showSectionPicker) {
        QuickChoiceSheet(
            title = "内容区",
            subtitle = "切换当前浏览的 Workshop 内容分类",
            options = sectionPickerOptions,
            selectedKey = state.query.sectionKey,
            onDismiss = { showSectionPicker = false },
            onSelect = { selectedKey ->
                showSectionPicker = false
                viewModel.applyQuery(state.query.copy(sectionKey = selectedKey, page = 1))
            },
        )
    }

    if (!isSubscriptionMode && showSortPicker) {
        QuickChoiceSheet(
            title = "排序",
            subtitle = "选择列表结果的排列方式",
            options = sortPickerOptions,
            selectedKey = state.query.sortKey,
            onDismiss = { showSortPicker = false },
            onSelect = { selectedKey ->
                showSortPicker = false
                val nextPeriod = if (sortSupportsPeriod(selectedKey)) {
                    state.query.periodDays
                } else {
                    WorkshopBrowseQuery().periodDays
                }
                viewModel.applyQuery(
                    state.query.copy(
                        sortKey = selectedKey,
                        periodDays = nextPeriod,
                        page = 1,
                    ),
                )
            },
        )
    }

    if (!isSubscriptionMode && showPeriodPicker) {
        QuickChoiceSheet(
            title = "时间范围",
            subtitle = "只对支持趋势排序的方式生效",
            options = periodPickerOptions,
            selectedKey = state.query.periodDays.toString(),
            onDismiss = { showPeriodPicker = false },
            onSelect = { selectedKey ->
                showPeriodPicker = false
                viewModel.applyQuery(
                    state.query.copy(
                        periodDays = selectedKey.toIntOrNull() ?: state.query.periodDays,
                        page = 1,
                    ),
                )
            },
        )
    }

    state.selectedItem?.let { item ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissItemDetails) {
            WorkshopDetailSheet(
                item = item,
                latestTask = state.downloadTasksByPublishedFileId[item.publishedFileId],
                downloadIdentityLabel = state.downloadIdentityLabel,
                downloadIdentityDescription = state.downloadIdentityDescription,
                isQueueing = state.queueingPublishedFileId == item.publishedFileId,
                isResolving = state.isResolvingSelection,
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
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.82f),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
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
                text = if (appName.isBlank()) "正在读取创意工坊" else "正在读取 $appName 创意工坊",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "正在加载 Steam 列表，下载方式会在列表出现后继续补齐。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "当前阶段：获取工坊列表与筛选条件",
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
    activeTagCount: Int,
    searchText: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenSectionPicker: () -> Unit,
    onOpenSortPicker: () -> Unit,
    onOpenPeriodPicker: () -> Unit,
    onSwitchToBrowse: () -> Unit,
    onSearchTextChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
) {
    val advancedFilterCount = activeTagCount + if (query.showIncompatible) 1 else 0
    val supportsPeriod = query.sortKey == WorkshopBrowseQuery.SORT_TREND
    val isSubscriptionMode = launchMode == WorkshopLaunchMode.Subscriptions
    val isInitialSync = !hasLoadedOnce
    val headerEyebrow = if (isSubscriptionMode) "我的订阅" else "创意工坊"
    val headerMeta = buildList {
        if (!isInitialSync) {
            add(if (isSubscriptionMode) "已订阅 $totalCount 项" else "$totalCount 个条目")
        }
        if (!isSubscriptionMode) {
            if (query.sectionKey != WorkshopBrowseQuery.SECTION_ITEMS) {
                add(sectionLabel(query.sectionKey))
            }
            add(sortLabel(query.sortKey))
            if (supportsPeriod) {
                add(periodLabel(query.periodDays))
            }
        }
    }.joinToString(" · ")
    val statusMessage = if (isRefreshing || isInitialSync) {
        if (isSubscriptionMode) "正在同步你的订阅条目" else "正在同步工坊内容"
    } else {
        null
    }
    val headerDescription = if (isSubscriptionMode) {
        "只读取当前账号已订阅的条目，不会执行任何订阅变更。"
    } else {
        ""
    }

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFFF7F1EA).copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.82f),
                            Color(0xFFFFF7EF).copy(alpha = 0.64f),
                            Color(0xFFF2E5D7).copy(alpha = 0.38f),
                        ),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactActionButton(
                    onClick = onBack,
                    icon = {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    },
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = headerEyebrow,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB36B42),
                    )
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (headerMeta.isNotBlank()) {
                        Text(
                            text = headerMeta,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (statusMessage != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFFE28954), CircleShape),
                            )
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9A5B38),
                            )
                        }
                    } else if (isSubscriptionMode) {
                        Text(
                            text = headerDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.64f)),
            )

            if (!isSubscriptionMode) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索创意工坊条目") },
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
                    keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.72f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.58f),
                        disabledContainerColor = Color.White.copy(alpha = 0.48f),
                        focusedBorderColor = Color(0xFFE69A69),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.34f),
                        focusedLeadingIconColor = Color(0xFFE96D43),
                        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        cursorColor = Color(0xFFE96D43),
                    ),
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickFilterChip(
                        label = sectionLabel(query.sectionKey),
                        onClick = onOpenSectionPicker,
                    )
                    QuickFilterChip(
                        label = sortLabel(query.sortKey),
                        onClick = onOpenSortPicker,
                    )
                    if (supportsPeriod) {
                        QuickFilterChip(
                            label = periodLabel(query.periodDays),
                            onClick = onOpenPeriodPicker,
                        )
                    }
                    QuickFilterChip(
                        label = if (advancedFilterCount > 0) "高级筛选 $advancedFilterCount" else "高级筛选",
                        onClick = onOpenFilters,
                        highlighted = advancedFilterCount > 0,
                        icon = { Icon(Icons.Rounded.FilterAlt, contentDescription = null) },
                    )
                }

                if (advancedFilterCount > 0 || query.searchText.isNotBlank()) {
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
                    }
                }

                if (headerDescription.isNotBlank()) {
                    Text(
                        text = headerDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Surface(
                    color = Color.White.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "我的订阅",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "这里只读取当前账号已订阅的条目，不会执行任何订阅变更。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = onSwitchToBrowse,
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("浏览全部工坊")
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
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.68f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.44f)),
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                color = Color.White.copy(alpha = 0.84f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
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
private fun WorkshopListItem(
    item: WorkshopItem,
    autoResolveDownloadInfo: Boolean,
    latestTask: DownloadTaskEntity?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.84f),
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.44f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.97f),
                            Color(0xFFF7EEE4).copy(alpha = 0.92f),
                        ),
                    ),
                    shape = RoundedCornerShape(22.dp),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkThumbnail(
                imageUrl = item.previewUrl,
                fallbackText = item.title,
                modifier = Modifier
                    .width(78.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                contentScale = ContentScale.Fit,
            )

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
                    if (item.isSubscribed) {
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
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.96f),
                            Color(0xFFF6EFE8).copy(alpha = 0.9f),
                        ),
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
    latestTask: DownloadTaskEntity?,
    downloadIdentityLabel: String,
    downloadIdentityDescription: String,
    isQueueing: Boolean,
    isResolving: Boolean,
    onDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val hasActiveTask = latestTask?.status == DownloadStatus.Queued ||
        latestTask?.status == DownloadStatus.Running

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            ArtworkThumbnail(
                imageUrl = item.previewUrl,
                fallbackText = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(194.dp),
                shape = RoundedCornerShape(30.dp),
                contentScale = ContentScale.Fit,
            )
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
                    if (item.isSubscribed) {
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.authorName.isNotBlank()) {
                InfoPill(text = item.authorName)
            }
            if (item.fileName != null) {
                InfoPill(text = item.fileName.orEmpty())
            }
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

            Surface(
                color = Color.White.copy(alpha = 0.8f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "条目介绍",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ExpandableBodyText(
                        text = item.description.ifBlank {
                            item.shortDescription.ifBlank { "这个条目没有提供额外介绍。" }
                        },
                        collapsedMaxLines = 6,
                    )
                }
            }

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
        color = Color.White.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
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
        color = Color.White.copy(alpha = 0.8f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
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
private fun WorkshopTagsCard(tags: List<String>) {
    Surface(
        color = Color.White.copy(alpha = 0.8f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
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
    val activeTagCount = draft.requiredTags.size + draft.excludedTags.size

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                color = Color.White.copy(alpha = 0.84f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.44f)),
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
                        ).joinToString(" · ").ifBlank { "当前还没有启用高级筛选条件" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (supportsIncompatibleFilter) {
                Surface(
                    color = Color.White.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    title = group.label,
                    subtitle = "支持包含和排除两种条件",
                ) {
                    group.tags.forEach { tag ->
                        TagFilterRow(
                            tagLabel = tag.label,
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
            }

            Surface(
                color = Color.White.copy(alpha = 0.82f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
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

@Composable
private fun FilterSection(
    title: String,
    subtitle: String,
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            }
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
        WorkshopBrowseQuery.SECTION_ITEMS -> "条目"
        "collections" -> "合集"
        else -> sectionKey
    }
}

private fun sortLabel(sortKey: String): String {
    return when (sortKey) {
        "trend" -> "热门"
        "mostrecent" -> "最新"
        "lastupdated" -> "最近更新"
        "totaluniquesubscribers" -> "最多订阅"
        else -> sortKey
    }
}

private fun periodLabel(days: Int): String {
    return when (days) {
        1 -> "今天"
        7 -> "一周"
        30 -> "30 天"
        90 -> "3 个月"
        180 -> "6 个月"
        365 -> "1 年"
        -1 -> "全部时间"
        else -> "${days} 天"
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
        "mostrecent",
        "lastupdated",
        "totaluniquesubscribers",
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

private fun workshopSummaryText(
    query: WorkshopBrowseQuery,
    activeTagCount: Int,
): String {
    val parts = buildList {
        add(sectionLabel(query.sectionKey))
        add(sortLabel(query.sortKey))
        if (query.sortKey == WorkshopBrowseQuery.SORT_TREND) {
            add(periodLabel(query.periodDays))
        }
        if (query.searchText.isNotBlank()) {
            add("搜索 ${query.searchText}")
        }
        if (activeTagCount > 0) {
            add("标签 $activeTagCount")
        }
        if (query.showIncompatible) {
            add("含不兼容")
        }
    }
    return parts.joinToString(" · ")
}
