package com.slay.workshopnative.ui.feature.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slay.workshopnative.data.model.FavoriteWorkshopGame
import com.slay.workshopnative.data.model.GameDetails
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.ui.components.ArtworkThumbnail
import com.slay.workshopnative.ui.components.steamCapsuleUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    paddingValues: PaddingValues,
    onOpenGame: (Int, String) -> Unit,
    viewModel: ExploreViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val selectedGame = remember { mutableStateOf<WorkshopGameEntry?>(null) }
    val favoriteAppIds = state.favoriteGames.mapTo(linkedSetOf(), FavoriteWorkshopGame::appId)
    val favoriteEntries = if (state.isSearching) {
        emptyList()
    } else {
        state.favoriteGames.map(FavoriteWorkshopGame::toWorkshopGameEntry)
    }
    val listGames = if (state.isSearching) {
        state.visibleGames
    } else {
        state.visibleGames.filterNot { it.appId in favoriteAppIds }
    }

    LaunchedEffect(selectedGame.value?.appId) {
        selectedGame.value?.appId?.let(viewModel::loadGameDetails)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExploreControlPanel(
            resultCount = state.visibleGames.size,
            query = state.query,
            isSearching = state.isSearching,
            isRefreshing = state.isLoading,
            currentPage = state.browsePage,
            totalCount = state.totalCount,
            onQueryChange = viewModel::setQuery,
            onClearQuery = {
                viewModel.setQuery("")
                focusManager.clearFocus(force = true)
            },
            onRefresh = viewModel::refresh,
            onSearch = { focusManager.clearFocus(force = true) },
        )

        when {
            state.isLoading && state.visibleGames.isEmpty() -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp),
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
                            text = if (state.isSearching) {
                                "正在搜索公开创意工坊游戏…"
                            } else {
                                "正在读取最近热门的工坊游戏…"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.visibleGames.isEmpty() -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (state.isSearching) {
                                    "没有找到匹配名称的工坊游戏"
                                } else {
                                    "当前页还没有读取到热门工坊游戏"
                                },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                                text = state.errorMessage ?: if (state.isSearching) {
                                    "试试换个关键词继续搜索，这里会查整个公开工坊游戏列表，不只是当前页。"
                                } else {
                                    "稍后刷新或切换页码再试。"
                                },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    if (favoriteEntries.isNotEmpty()) {
                        item(key = "explore_section_favorites") {
                            ExploreSectionHeader(
                                title = "工坊收藏",
                                count = favoriteEntries.size,
                            )
                        }
                        items(
                            items = favoriteEntries,
                            key = { "favorite_${it.appId}" },
                        ) { game ->
                            ExploreGameRow(
                                game = game,
                                isFavorite = true,
                                onToggleFavorite = { viewModel.toggleFavorite(game) },
                                onShowDetails = {
                                    focusManager.clearFocus(force = true)
                                    selectedGame.value = game
                                },
                                onOpenWorkshop = {
                                    focusManager.clearFocus(force = true)
                                    viewModel.markWorkshopOpened(game.appId)
                                    onOpenGame(game.appId, game.name)
                                },
                            )
                        }
                    }

                    if (favoriteEntries.isNotEmpty()) {
                        item(key = "explore_section_all") {
                            ExploreSectionHeader(
                                title = "全部游戏",
                                count = listGames.size,
                            )
                        }
                    }

                    items(
                        items = listGames,
                        key = WorkshopGameEntry::appId,
                    ) { game ->
                        ExploreGameRow(
                            game = game,
                            isFavorite = game.appId in favoriteAppIds,
                            onToggleFavorite = { viewModel.toggleFavorite(game) },
                            onShowDetails = {
                                focusManager.clearFocus(force = true)
                                selectedGame.value = game
                            },
                            onOpenWorkshop = {
                                focusManager.clearFocus(force = true)
                                viewModel.markWorkshopOpened(game.appId)
                                onOpenGame(game.appId, game.name)
                            },
                        )
                    }

                    if (!state.isSearching) {
                        item {
                            ExplorePaginationBar(
                                currentPage = state.browsePage,
                                totalCount = state.totalCount,
                                hasPreviousPage = state.hasPreviousPage,
                                hasNextPage = state.hasNextPage,
                                onPrevious = viewModel::goToPreviousPage,
                                onNext = viewModel::goToNextPage,
                            )
                        }
                    }
                }
            }
        }
    }

    selectedGame.value?.let { game ->
        val details = state.gameDetailsByAppId[game.appId]
        val isLoadingDetails = state.loadingDetailsAppId == game.appId && details == null
        ModalBottomSheet(onDismissRequest = { selectedGame.value = null }) {
            ExploreGameDetailsSheet(
                game = game,
                details = details,
                isLoading = isLoadingDetails,
                isFavorite = game.appId in favoriteAppIds,
                onToggleFavorite = { viewModel.toggleFavorite(game) },
                onOpenWorkshop = {
                    selectedGame.value = null
                    viewModel.markWorkshopOpened(game.appId)
                    onOpenGame(game.appId, game.name)
                },
            )
        }
    }
}

@Composable
private fun ExploreControlPanel(
    resultCount: Int,
    query: String,
    isSearching: Boolean,
    isRefreshing: Boolean,
    currentPage: Int,
    totalCount: Int,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.8f),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
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
                        text = "工坊探索",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (isSearching) {
                            "按名称搜索整个公开创意工坊游戏列表"
                        } else {
                            "最近热门的公开创意工坊游戏 · 共 $totalCount 项"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExploreMetaChip(
                        label = if (isSearching) {
                            "$resultCount 项"
                        } else {
                            "第 $currentPage 页"
                        },
                    )
                    Surface(
                        modifier = Modifier
                            .focusProperties { canFocus = false }
                            .clickable(
                                enabled = !isRefreshing,
                                onClick = onRefresh,
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.2.dp,
                                )
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "刷新工坊探索")
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    placeholder = {
                        Text(
                            text = "按名称搜索公开工坊游戏",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = onClearQuery) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                            }
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { onSearch() },
                        onDone = { onSearch() },
                    ),
                    shape = RoundedCornerShape(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ExploreGameRow(
    game: WorkshopGameEntry,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onShowDetails: () -> Unit,
    onOpenWorkshop: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.98f),
                            Color(0xFFF9F0E5).copy(alpha = 0.94f),
                        ),
                    ),
                    shape = RoundedCornerShape(22.dp),
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkThumbnail(
                imageUrl = game.capsuleUrl ?: steamCapsuleUrl(game.appId),
                alternateImageUrl = game.previewUrl,
                fallbackText = game.name,
                modifier = Modifier
                    .size(width = 78.dp, height = 50.dp),
                shape = RoundedCornerShape(16.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .focusProperties { canFocus = false }
                    .clickable(onClick = onShowDetails),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = buildString {
                        append("AppID ${game.appId}")
                        append("  ·  ")
                        append(
                            game.workshopItemCount?.let { "$it 个工坊条目" } ?: "公开创意工坊",
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .size(28.dp)
                            .focusProperties { canFocus = false },
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = if (isFavorite) "取消收藏" else "收藏工坊",
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    ExploreActionCapsule(
                        text = "进入工坊",
                        onClick = onOpenWorkshop,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExploreGameDetailsSheet(
    game: WorkshopGameEntry,
    details: GameDetails?,
    isLoading: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenWorkshop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            ArtworkThumbnail(
                imageUrl = details?.headerImageUrl ?: game.capsuleUrl ?: steamCapsuleUrl(game.appId),
                alternateImageUrl = game.previewUrl,
                fallbackText = game.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(188.dp)
                    .clip(RoundedCornerShape(32.dp)),
                shape = RoundedCornerShape(32.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(188.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xAA172131),
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExploreMetaChip(
                    label = if (details != null) "游戏详情" else "基础信息",
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                )
                Text(
                    text = details?.title?.ifBlank { game.name } ?: game.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExploreMetaChip(label = "AppID ${game.appId}")
            ExploreMetaChip(label = game.workshopItemCount?.let { "$it 个条目" } ?: "公开创意工坊")
        }

        when {
            isLoading -> {
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
                            text = "正在补全这款游戏的详细资料…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            details != null -> {
                val description = details.about.ifBlank { details.shortDescription }
                ExploreOverviewCard(
                    appId = game.appId,
                    developers = details.developers,
                    publishers = details.publishers,
                    genres = details.genres,
                )
                if (description.isNotBlank()) {
                    Surface(
                        color = Color.White.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "游戏介绍",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            else -> {
                Surface(
                    color = Color.White.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "暂时没有读取到这款游戏的详细介绍，但你仍然可以直接进入创意工坊。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "创意工坊",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "继续进入这个游戏的公开创意工坊列表，筛选并下载模组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onOpenWorkshop,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("进入工坊")
                }
            }
        }

        OutlinedActionRow(
            title = if (isFavorite) "已加入工坊收藏" else "加入工坊收藏",
            description = if (isFavorite) {
                "这款游戏会固定出现在探索和我的内容顶部，方便你快速回到它的工坊。"
            } else {
                "把常用的工坊游戏固定下来，下次不用再重新搜索。"
            },
            actionText = if (isFavorite) "取消收藏" else "收藏",
            onClick = onToggleFavorite,
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ExploreSectionHeader(
    title: String,
    count: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "$count 项",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OutlinedActionRow(
    title: String,
    description: String,
    actionText: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun ExplorePaginationBar(
    currentPage: Int,
    totalCount: Int,
    hasPreviousPage: Boolean,
    hasNextPage: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPrevious,
                enabled = hasPreviousPage,
            ) {
                Text("上一页")
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "第 $currentPage 页",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "当前热门工坊共 $totalCount 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onNext,
                enabled = hasNextPage,
            ) {
                Text("下一页")
            }
        }
    }
}

@Composable
private fun ExploreOverviewCard(
    appId: Int,
    developers: List<String>,
    publishers: List<String>,
    genres: List<String>,
) {
    Surface(
        color = Color.White.copy(alpha = 0.78f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExploreMetaTile(
                    modifier = Modifier.weight(1f),
                    label = "AppID",
                    value = appId.toString(),
                )
                ExploreMetaTile(
                    modifier = Modifier.weight(1f),
                    label = "来源",
                    value = "公开工坊",
                )
            }

            ExploreDetailGroup(label = "开发商", values = developers)
            ExploreDetailGroup(label = "发行商", values = publishers)
            ExploreDetailGroup(label = "类型", values = genres)
        }
    }
}

@Composable
private fun ExploreMetaTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ExploreDetailGroup(
    label: String,
    values: List<String>,
) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = values.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ExploreActionCapsule(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFFE96D43),
                            Color(0xFFF08A52),
                        ),
                    ),
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ExploreMetaChip(
    label: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}
