package com.slay.workshopnative.ui.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.slay.workshopnative.core.util.textFingerprint
import com.slay.workshopnative.data.model.FavoriteWorkshopGame
import com.slay.workshopnative.data.model.GameDetails
import com.slay.workshopnative.data.model.OwnedGame
import com.slay.workshopnative.ui.components.ArtworkThumbnail
import com.slay.workshopnative.ui.components.ExpandableBodyText
import com.slay.workshopnative.ui.components.TranslatableDescriptionCard
import com.slay.workshopnative.ui.components.WorkshopNativeModalBottomSheet
import com.slay.workshopnative.ui.theme.workshopAdaptiveBorderColor
import com.slay.workshopnative.ui.theme.workshopAdaptiveGradientBrush
import com.slay.workshopnative.ui.theme.workshopAdaptiveSurfaceColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    paddingValues: PaddingValues,
    accountName: String,
    onOpenGame: (Int, String) -> Unit,
    onOpenSubscriptions: (Int, String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedGame = remember { mutableStateOf<OwnedGame?>(null) }
    val focusManager = LocalFocusManager.current

    val filteredGames = state.games.filter { game ->
        state.query.isBlank() || game.name.contains(state.query, ignoreCase = true)
    }
    val filteredFavoriteGames = state.favoriteGames.filter { game ->
        state.query.isBlank() || game.name.contains(state.query, ignoreCase = true)
    }
    val showSubscriptionEntry = state.isSubscriptionDisplayEnabled
    val favoriteAppIds = state.favoriteGames.mapTo(linkedSetOf(), FavoriteWorkshopGame::appId)
    val ownedGames = filteredGames.filterNot(OwnedGame::isFamilyShared)
    val sharedGames = filteredGames.filter(OwnedGame::isFamilyShared)

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
        LibraryControlPanel(
            accountName = accountName,
            gameCount = filteredGames.size,
            isRefreshing = state.isLoading,
            hasLoadedOnce = state.hasLoadedOnce,
            query = state.query,
            onQueryChange = viewModel::setQuery,
            onClearQuery = {
                viewModel.setQuery("")
                focusManager.clearFocus(force = true)
            },
            onRefresh = viewModel::refresh,
            onSearch = { focusManager.clearFocus(force = true) },
        )

        when {
            !state.isLoginFeatureEnabled || !state.isOwnedGamesDisplayEnabled -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "当前已关闭账号游戏库展示。",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "请先在设置页打开“账户行为 > 打开用户登录功能”和“用户已购买标识展示”，再查看已购与家庭共享游戏。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            (state.isLoading || !state.hasLoadedOnce) && state.games.isEmpty() -> {
                LibraryLoadingCard(accountName = accountName)
            }

            state.errorMessage != null -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            filteredGames.isEmpty() -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(
                        text = if (state.games.isEmpty()) {
                            "还没有读取到可访问的游戏"
                        } else {
                            "没有匹配当前搜索词的游戏"
                        },
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "你可以稍后刷新，或者换个关键词继续筛。",
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    favoriteGamesSection(
                        games = filteredFavoriteGames,
                        onShowDetails = { game ->
                            focusManager.clearFocus(force = true)
                            selectedGame.value = OwnedGame(
                                appId = game.appId,
                                name = game.name,
                                iconHash = "",
                            )
                        },
                        onToggleFavorite = { game ->
                            viewModel.toggleFavorite(
                                appId = game.appId,
                                name = game.name,
                                capsuleUrl = game.capsuleUrl,
                                previewUrl = game.previewUrl,
                            )
                        },
                        onOpenWorkshop = { game ->
                            focusManager.clearFocus(force = true)
                            viewModel.markWorkshopOpened(game.appId)
                            onOpenGame(game.appId, game.name)
                        },
                        onOpenSubscriptions = { game ->
                            focusManager.clearFocus(force = true)
                            viewModel.markWorkshopOpened(game.appId)
                            onOpenSubscriptions(game.appId, game.name)
                        },
                        showSubscriptionEntry = showSubscriptionEntry,
                    )
                    gamesSection(
                        title = "已购买",
                        count = ownedGames.size,
                        games = ownedGames,
                        favoriteAppIds = favoriteAppIds,
                        onShowDetails = { game ->
                            focusManager.clearFocus(force = true)
                            selectedGame.value = game
                        },
                        onToggleFavorite = { game ->
                            viewModel.toggleFavorite(
                                appId = game.appId,
                                name = game.name,
                                capsuleUrl = game.capsuleUrl,
                                previewUrl = game.iconUrl.ifBlank { null },
                            )
                        },
                        onOpenWorkshop = { game ->
                            focusManager.clearFocus(force = true)
                            viewModel.markWorkshopOpened(game.appId)
                            onOpenGame(game.appId, game.name)
                        },
                        onOpenSubscriptions = { game ->
                            focusManager.clearFocus(force = true)
                            viewModel.markWorkshopOpened(game.appId)
                            onOpenSubscriptions(game.appId, game.name)
                        },
                        showSubscriptionEntry = showSubscriptionEntry,
                    )
                    gamesSection(
                        title = "家庭共享",
                        count = sharedGames.size,
                        games = sharedGames,
                        favoriteAppIds = favoriteAppIds,
                        onShowDetails = { game ->
                            focusManager.clearFocus(force = true)
                            selectedGame.value = game
                        },
                        onToggleFavorite = { game ->
                            viewModel.toggleFavorite(
                                appId = game.appId,
                                name = game.name,
                                capsuleUrl = game.capsuleUrl,
                                previewUrl = game.iconUrl.ifBlank { null },
                            )
                        },
                        onOpenWorkshop = { game ->
                            focusManager.clearFocus(force = true)
                            viewModel.markWorkshopOpened(game.appId)
                            onOpenGame(game.appId, game.name)
                        },
                        onOpenSubscriptions = { game ->
                            focusManager.clearFocus(force = true)
                            viewModel.markWorkshopOpened(game.appId)
                            onOpenSubscriptions(game.appId, game.name)
                        },
                        showSubscriptionEntry = showSubscriptionEntry,
                    )
                }
            }
        }
    }

    selectedGame.value?.let { game ->
        val details = state.gameDetailsByAppId[game.appId]
        val isLoadingDetails = state.loadingDetailsAppId == game.appId && details == null
        val description = details?.about?.ifBlank { details.shortDescription }.orEmpty()
        val translationState = state.descriptionTranslationByAppId[game.appId]
            ?.takeIf { it.sourceFingerprint == textFingerprint(description.trim()) }
        WorkshopNativeModalBottomSheet(onDismissRequest = { selectedGame.value = null }) {
            GameDetailsSheet(
                game = game,
                details = details,
                translationState = translationState,
                isLoading = isLoadingDetails,
                isFavorite = game.appId in favoriteAppIds,
                onToggleFavorite = {
                    viewModel.toggleFavorite(
                        appId = game.appId,
                        name = game.name,
                        capsuleUrl = game.capsuleUrl,
                        previewUrl = game.iconUrl.ifBlank { null },
                    )
                },
                onTranslateDescription = {
                    viewModel.translateGameDescription(
                        appId = game.appId,
                        sourceText = description,
                        forceRefresh = !translationState?.translatedText.isNullOrBlank(),
                    )
                },
                onShowOriginalDescription = { viewModel.showOriginalGameDescription(game.appId) },
                onShowTranslatedDescription = { viewModel.showTranslatedGameDescription(game.appId) },
                onOpenWorkshop = {
                    selectedGame.value = null
                    viewModel.markWorkshopOpened(game.appId)
                    onOpenGame(game.appId, game.name)
                },
                onOpenSubscriptions = {
                    selectedGame.value = null
                    viewModel.markWorkshopOpened(game.appId)
                    onOpenSubscriptions(game.appId, game.name)
                },
                showSubscriptionEntry = showSubscriptionEntry,
            )
        }
    }
}

@Composable
private fun LibraryLoadingCard(accountName: String) {
    Surface(
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
            Spacer(Modifier.height(6.dp))
            CircularProgressIndicator()
            Text(
                text = "正在读取游戏库",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (accountName.isBlank()) {
                    "正在同步已购买和家庭共享游戏，首次进入通常需要几秒钟。"
                } else {
                    "正在同步 $accountName 的已购买和家庭共享游戏，首次进入通常需要几秒钟。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "当前阶段：连接 Steam 后读取游戏列表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun LibraryControlPanel(
    accountName: String,
    gameCount: Int,
    isRefreshing: Boolean,
    hasLoadedOnce: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
) {
    val isInitialSync = !hasLoadedOnce
    val gameCountLabel = if (isInitialSync) "同步中" else "$gameCount 项"

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.8f)),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor()),
        contentColor = MaterialTheme.colorScheme.onSurface,
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
                        text = "游戏库",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (accountName.isBlank()) {
                            "已购与家庭共享游戏"
                        } else {
                            "$accountName 的已购与共享游戏"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaChip(label = gameCountLabel)
                    Surface(
                        modifier = Modifier.clickable(onClick = onRefresh),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isRefreshing || isInitialSync) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "刷新游戏库")
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
                    placeholder = { Text("搜索已购或共享游戏") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            TextButton(onClick = onClearQuery) {
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

private fun LazyListScope.gamesSection(
    title: String,
    count: Int,
    games: List<OwnedGame>,
    favoriteAppIds: Set<Int>,
    onShowDetails: (OwnedGame) -> Unit,
    onToggleFavorite: (OwnedGame) -> Unit,
    onOpenWorkshop: (OwnedGame) -> Unit,
    onOpenSubscriptions: (OwnedGame) -> Unit,
    showSubscriptionEntry: Boolean,
) {
    if (games.isEmpty()) return
    item(key = "section_$title") {
        LibrarySectionHeader(
            title = title,
            count = count,
        )
    }
    items(games, key = { it.appId }) { game ->
        GameRow(
            game = game,
            isFavorite = game.appId in favoriteAppIds,
            onShowDetails = { onShowDetails(game) },
            onToggleFavorite = { onToggleFavorite(game) },
            onOpenWorkshop = { onOpenWorkshop(game) },
            onOpenSubscriptions = { onOpenSubscriptions(game) },
            showSubscriptionEntry = showSubscriptionEntry,
        )
    }
}

private fun LazyListScope.favoriteGamesSection(
    games: List<FavoriteWorkshopGame>,
    onShowDetails: (FavoriteWorkshopGame) -> Unit,
    onToggleFavorite: (FavoriteWorkshopGame) -> Unit,
    onOpenWorkshop: (FavoriteWorkshopGame) -> Unit,
    onOpenSubscriptions: (FavoriteWorkshopGame) -> Unit,
    showSubscriptionEntry: Boolean,
) {
    if (games.isEmpty()) return
    item(key = "section_favorites") {
        LibrarySectionHeader(
            title = "工坊收藏",
            count = games.size,
        )
    }
    items(games, key = { "favorite_${it.appId}" }) { game ->
        FavoriteWorkshopRow(
            game = game,
            onShowDetails = { onShowDetails(game) },
            onToggleFavorite = { onToggleFavorite(game) },
            onOpenWorkshop = { onOpenWorkshop(game) },
            onOpenSubscriptions = { onOpenSubscriptions(game) },
            showSubscriptionEntry = showSubscriptionEntry,
        )
    }
}

@Composable
private fun LibrarySectionHeader(
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
            color = MaterialTheme.colorScheme.onSurface,
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
private fun GameRow(
    game: OwnedGame,
    isFavorite: Boolean,
    onShowDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenWorkshop: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    showSubscriptionEntry: Boolean,
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
                    brush = workshopAdaptiveGradientBrush(
                        lightStart = Color.White.copy(alpha = 0.98f),
                        lightEnd = Color(0xFFF9F0E5).copy(alpha = 0.94f),
                    ),
                    shape = RoundedCornerShape(22.dp),
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkThumbnail(
                imageUrl = game.capsuleUrl,
                alternateImageUrl = game.iconUrl.ifBlank { null },
                fallbackText = game.name,
                modifier = Modifier
                    .size(width = 78.dp, height = 50.dp),
                shape = RoundedCornerShape(16.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
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
                    text = buildAnnotatedString {
                        append("AppID ${game.appId}")
                        append("  ·  ")
                        pushStyle(
                            SpanStyle(
                                color = if (game.isFamilyShared) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            ),
                        )
                        append(game.sourceLabel)
                        pop()
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
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
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
                    if (showSubscriptionEntry) {
                        SecondaryActionCapsule(
                            text = "我的订阅",
                            onClick = onOpenSubscriptions,
                        )
                    }
                    ActionCapsule(
                        text = "进入工坊",
                        onClick = onOpenWorkshop,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameDetailsSheet(
    game: OwnedGame,
    details: GameDetails?,
    translationState: com.slay.workshopnative.ui.InlineTranslationState?,
    isLoading: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onTranslateDescription: () -> Unit,
    onShowOriginalDescription: () -> Unit,
    onShowTranslatedDescription: () -> Unit,
    onOpenWorkshop: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    showSubscriptionEntry: Boolean,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            ArtworkThumbnail(
                imageUrl = details?.headerImageUrl ?: game.capsuleUrl,
                alternateImageUrl = game.iconUrl.ifBlank { null },
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
                MetaChip(
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
            SourceBadge(game = game)
            MetaChip(label = "AppID ${game.appId}")
            MetaChip(label = "创意工坊")
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
                GameOverviewCard(
                    appId = game.appId,
                    sourceLabel = game.sourceLabel,
                    developers = details.developers,
                    publishers = details.publishers,
                    genres = details.genres,
                )
                if (description.isNotBlank()) {
                    TranslatableDescriptionCard(
                        title = "游戏介绍",
                        originalText = description,
                        translatedText = translationState?.translatedText,
                        providerLabel = translationState?.providerLabel,
                        sourceLanguageLabel = translationState?.sourceLanguageLabel,
                        isTranslating = translationState?.isTranslating == true,
                        showTranslated = translationState?.showTranslated == true,
                        errorMessage = translationState?.errorMessage,
                        collapsedMaxLines = 7,
                        onTranslate = onTranslateDescription,
                        onShowOriginal = onShowOriginalDescription,
                        onShowTranslated = onShowTranslatedDescription,
                    )
                }
            }

            else -> {
                Surface(
                    color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.78f)),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.4f))),
                    contentColor = MaterialTheme.colorScheme.onSurface,
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
                        text = "继续进入这个游戏的创意工坊列表，筛选并下载模组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showSubscriptionEntry) {
                    OutlinedButton(
                        onClick = onOpenSubscriptions,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("我的订阅")
                    }
                }
                Button(
                    onClick = onOpenWorkshop,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("进入工坊")
                }
            }
        }

        FavoriteActionRow(
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun FavoriteWorkshopRow(
    game: FavoriteWorkshopGame,
    onShowDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenWorkshop: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    showSubscriptionEntry: Boolean,
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
                    brush = workshopAdaptiveGradientBrush(
                        lightStart = Color.White.copy(alpha = 0.98f),
                        lightEnd = Color(0xFFF9F0E5).copy(alpha = 0.94f),
                    ),
                    shape = RoundedCornerShape(22.dp),
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkThumbnail(
                imageUrl = game.capsuleUrl,
                alternateImageUrl = game.previewUrl,
                fallbackText = game.name,
                modifier = Modifier.size(width = 78.dp, height = 50.dp),
                shape = RoundedCornerShape(16.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
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
                        append(game.workshopItemCount?.let { "$it 个工坊条目" } ?: "工坊收藏")
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
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Bookmark,
                            contentDescription = "取消收藏",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (showSubscriptionEntry) {
                        SecondaryActionCapsule(
                            text = "我的订阅",
                            onClick = onOpenSubscriptions,
                        )
                    }
                    ActionCapsule(
                        text = "进入工坊",
                        onClick = onOpenWorkshop,
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteActionRow(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
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
                    text = if (isFavorite) "已加入工坊收藏" else "加入工坊收藏",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (isFavorite) {
                        "这款游戏会固定出现在探索和我的内容顶部，方便你快速回到它的工坊。"
                    } else {
                        "把常用的工坊游戏固定下来，下次不用再重新搜索。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onToggleFavorite) {
                Text(if (isFavorite) "取消收藏" else "收藏")
            }
        }
    }
}

@Composable
private fun GameOverviewCard(
    appId: Int,
    sourceLabel: String,
    developers: List<String>,
    publishers: List<String>,
    genres: List<String>,
) {
    Surface(
        color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.78f)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.4f))),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GameMetaTile(
                    modifier = Modifier.weight(1f),
                    label = "AppID",
                    value = appId.toString(),
                )
                GameMetaTile(
                    modifier = Modifier.weight(1f),
                    label = "来源",
                    value = sourceLabel,
                )
            }

            DetailGroup(label = "开发商", values = developers)
            DetailGroup(label = "发行商", values = publishers)
            DetailGroup(label = "类型", values = genres)
        }
    }
}

@Composable
private fun ActionCapsule(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
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
private fun SecondaryActionCapsule(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DetailGroup(
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.take(4).forEach { value ->
                MetaChip(label = value)
            }
        }
    }
}

@Composable
private fun GameMetaTile(
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
            )
        }
    }
}

@Composable
private fun SourceBadge(game: OwnedGame) {
    Surface(
        color = if (game.isFamilyShared) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = game.sourceLabel,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (game.isFamilyShared) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        )
    }
}

@Composable
private fun MetaChip(
    label: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}
