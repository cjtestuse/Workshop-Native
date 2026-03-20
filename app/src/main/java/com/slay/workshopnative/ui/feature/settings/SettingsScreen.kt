package com.slay.workshopnative.ui.feature.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.slay.workshopnative.core.util.openUrlWithChooser
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.preferences.CdnPoolPreference
import com.slay.workshopnative.data.preferences.CdnTransportPreference
import com.slay.workshopnative.data.preferences.DEFAULT_AZURE_TRANSLATOR_ENDPOINT
import com.slay.workshopnative.data.preferences.DownloadPerformanceMode
import com.slay.workshopnative.data.preferences.TranslationProvider
import com.slay.workshopnative.data.preferences.displayLabel
import com.slay.workshopnative.data.preferences.isExperimental
import com.slay.workshopnative.data.preferences.settingsStatusLabel
import com.slay.workshopnative.ui.AppNoticeItem
import com.slay.workshopnative.ui.AppUpdateUiState
import com.slay.workshopnative.ui.WorkshopNativeAbout

private enum class SettingsDestination {
    DownloadLocation,
    DownloadStrategy,
    Workshop,
    Translation,
    DataPrivacy,
    About,
    UsageBoundary,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    isGuestMode: Boolean,
    appUpdateUiState: AppUpdateUiState,
    onCheckAppUpdates: () -> Unit,
    onShowLogin: () -> Unit,
    onSwitchSavedAccount: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeDestinationName by rememberSaveable { mutableStateOf<String?>(null) }
    val activeDestination = activeDestinationName
        ?.let { destinationName -> SettingsDestination.entries.firstOrNull { it.name == destinationName } }
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showLoginRiskDialog by remember { mutableStateOf(false) }
    val openExternalUrl: (String) -> Unit = { url ->
        if (!context.openUrlWithChooser(url, chooserTitle = "选择浏览器")) {
            Toast.makeText(context, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val label = DocumentFile.fromTreeUri(context, uri)?.name ?: "已选择目录"
            viewModel.saveDownloadTree(uri.toString(), label)
        }
    }
    val logExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportSupportLogs(uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AccountCard(
                uiState = uiState,
                isGuestMode = isGuestMode,
                onShowLogin = onShowLogin,
                onLogout = onLogout,
                onSwitchSavedAccount = onSwitchSavedAccount,
                onRemoveSavedAccount = viewModel::removeSavedAccount,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(
                    title = "账户行为",
                    subtitle = "默认只保留匿名能力，所有 Steam 账号相关能力都需要你主动打开。",
                )
                SettingsPanel {
                    SettingsBooleanRow(
                        title = "打开用户登录功能",
                        description = if (uiState.isLoginFeatureEnabled) {
                            "当前已允许显示登录入口、已保存账号和 Steam 会话恢复。"
                        } else {
                            "当前已关闭。应用只保留匿名访问，不展示登录入口，也不会恢复已保存登录态。"
                        },
                        checked = uiState.isLoginFeatureEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !uiState.isLoginFeatureEnabled) {
                                showLoginRiskDialog = true
                            } else if (!enabled) {
                                viewModel.saveLoginFeatureEnabled(false)
                            }
                        },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    SettingsSubsectionTitle(
                        title = "用户信息获取",
                        subtitle = "下面 3 个能力彼此独立，默认全部关闭。",
                    )
                    SettingsBooleanRow(
                        title = "登录后下载",
                        description = if (!uiState.isLoginFeatureEnabled) {
                            "需先打开“用户登录功能”。未开启前，下载只允许匿名方式。"
                        } else if (uiState.isLoggedInDownloadEnabled) {
                            "当前已开启。条目需要账号时，才允许使用当前 Steam 登录态下载。"
                        } else {
                            "当前已关闭。即使已登录，也只允许匿名下载。"
                        },
                        checked = uiState.isLoggedInDownloadEnabled,
                        enabled = uiState.isLoginFeatureEnabled,
                        onCheckedChange = viewModel::saveLoggedInDownloadEnabled,
                    )
                    SettingsBooleanRow(
                        title = "用户已购买标识展示",
                        description = if (!uiState.isLoginFeatureEnabled) {
                            "需先打开“用户登录功能”。未开启前，不展示已购与家庭共享信息。"
                        } else if (uiState.isOwnedGamesDisplayEnabled) {
                            "当前已开启。会显示“我的内容”、已购游戏和家庭共享信息。"
                        } else {
                            "当前已关闭。不展示已购与家庭共享信息，也不保留相关入口。"
                        },
                        checked = uiState.isOwnedGamesDisplayEnabled,
                        enabled = uiState.isLoginFeatureEnabled,
                        onCheckedChange = viewModel::saveOwnedGamesDisplayEnabled,
                    )
                    SettingsBooleanRow(
                        title = "用户已订阅展示",
                        description = if (!uiState.isLoginFeatureEnabled) {
                            "需先打开“用户登录功能”。未开启前，不展示当前账号的订阅状态。"
                        } else if (uiState.isSubscriptionDisplayEnabled) {
                            "当前已开启。会显示“我的订阅”入口和当前账号的订阅标识。"
                        } else {
                            "当前已关闭。不展示“已订阅”标识，也不展示“我的订阅”入口。"
                        },
                        checked = uiState.isSubscriptionDisplayEnabled,
                        enabled = uiState.isLoginFeatureEnabled,
                        onCheckedChange = viewModel::saveSubscriptionDisplayEnabled,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(
                    title = "应用更新",
                    subtitle = "启动时自动检查一次，手动检查后直接弹出更新说明。",
                )
                SettingsPanel {
                    UpdateSettingsRow(
                        uiState = appUpdateUiState,
                        autoCheckEnabled = uiState.autoCheckAppUpdates,
                        onClick = onCheckAppUpdates,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    SettingsBooleanRow(
                        title = "启动时自动检查更新",
                        description = if (uiState.autoCheckAppUpdates) {
                            "当前已开启。应用每次启动时会自动检查一次 GitHub Release。"
                        } else {
                            "当前已关闭。不会自动联网检查，但仍可手动点上方立即检查。"
                        },
                        checked = uiState.autoCheckAppUpdates,
                        onCheckedChange = viewModel::saveAutoCheckAppUpdates,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(
                    title = "下载与浏览",
                    subtitle = "高频设置保留在首页，复杂选项再进入详情页。",
                )
                SettingsPanel {
                    SettingsNavigationRow(
                        icon = Icons.Rounded.FolderOpen,
                        iconTint = Color(0xFFB86A2A),
                        title = "下载位置",
                        summary = buildString {
                            append(uiState.downloadTreeLabel ?: "系统下载")
                            append(" / ")
                            append(uiState.effectiveDownloadFolderName)
                        },
                        onClick = { activeDestinationName = SettingsDestination.DownloadLocation.name },
                    )
                    SettingsNavigationRow(
                        icon = Icons.Rounded.Tune,
                        iconTint = Color(0xFF2F7F73),
                        title = "下载策略",
                        summary = buildString {
                            append(uiState.downloadPerformanceMode.performanceLabel())
                            append(" · ")
                            append(
                                when {
                                    !uiState.isLoginFeatureEnabled || !uiState.isLoggedInDownloadEnabled -> "仅匿名"
                                    uiState.preferAnonymousDownloads -> "匿名优先"
                                    else -> "账号优先"
                                },
                            )
                            append(" · ")
                            append(uiState.cdnTransportPreference.transportLabel())
                        },
                        onClick = { activeDestinationName = SettingsDestination.DownloadStrategy.name },
                    )
                    SettingsNavigationRow(
                        icon = Icons.Rounded.TravelExplore,
                        iconTint = Color(0xFF3568A8),
                        title = "创意工坊",
                        summary = buildString {
                            append("${uiState.workshopPageSize} / 页")
                            append(" · ")
                            append(if (uiState.workshopAutoResolveVisibleItems) "列表预读已开" else "列表预读已关")
                        },
                        onClick = { activeDestinationName = SettingsDestination.Workshop.name },
                    )
                    SettingsNavigationRow(
                        icon = Icons.Rounded.Translate,
                        iconTint = Color(0xFF7E5BA6),
                        title = "翻译服务",
                        summary = buildString {
                            append(uiState.translationProvider.displayLabel())
                            append(" · ")
                            append(uiState.translationProvider.settingsStatusLabel(uiState.isTranslationConfigured))
                        },
                        onClick = { activeDestinationName = SettingsDestination.Translation.name },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    SettingsBooleanRow(
                        title = "默认启动到访客模式",
                        description = if (!uiState.isLoginFeatureEnabled) {
                            "登录功能关闭时，应用始终以匿名方式启动。"
                        } else if (uiState.defaultGuestMode) {
                            "当前会先进入探索页，不主动恢复 Steam 登录。"
                        } else {
                            "当前会优先恢复上次登录状态，再进入内容页。"
                        },
                        checked = uiState.defaultGuestMode,
                        enabled = uiState.isLoginFeatureEnabled,
                        onCheckedChange = viewModel::saveDefaultGuestMode,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(
                    title = "数据与维护",
                    subtitle = "查看本地保存内容，处理缓存、历史和登录数据。",
                )
                SettingsPanel {
                    SettingsNavigationRow(
                        icon = Icons.Rounded.PrivacyTip,
                        iconTint = Color(0xFF8F4A4A),
                        title = "数据与隐私",
                        summary = uiState.maintenanceSummary
                            ?: "查看本地保存内容，清理缓存、登录状态和下载诊断。",
                        onClick = { activeDestinationName = SettingsDestination.DataPrivacy.name },
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionHeader(
                    title = "关于与说明",
                    subtitle = "查看项目来源、作者信息，以及首次启动时展示的说明。",
                )
                SettingsPanel {
                    SettingsNavigationRow(
                        icon = Icons.Rounded.Info,
                        iconTint = Color(0xFF8A633B),
                        title = "关于 Workshop Native",
                        summary = "开源地址、作者信息、版本信息与使用说明。",
                        onClick = { activeDestinationName = SettingsDestination.About.name },
                    )
                    SettingsNavigationRow(
                        icon = Icons.Rounded.PrivacyTip,
                        iconTint = Color(0xFF8F4A4A),
                        title = "学习交流与使用边界",
                        summary = "查看合法使用范围、禁止用途和权利反馈说明。",
                        onClick = { activeDestinationName = SettingsDestination.UsageBoundary.name },
                    )
                }
            }
        }
    }

    activeDestination?.let { destination ->
        ModalBottomSheet(
            onDismissRequest = { activeDestinationName = null },
            sheetState = settingsSheetState,
        ) {
            SettingsSheetScaffold(
                title = when (destination) {
                    SettingsDestination.DownloadLocation -> "下载位置"
                    SettingsDestination.DownloadStrategy -> "下载策略"
                    SettingsDestination.Workshop -> "创意工坊"
                    SettingsDestination.Translation -> "翻译服务"
                    SettingsDestination.DataPrivacy -> "数据与隐私"
                    SettingsDestination.About -> "关于 Workshop Native"
                    SettingsDestination.UsageBoundary -> "学习交流与使用边界"
                },
                subtitle = when (destination) {
                    SettingsDestination.DownloadLocation -> "控制结果最终整理到哪里。"
                    SettingsDestination.DownloadStrategy -> "控制并发、匿名优先和 CDN 连接策略。"
                    SettingsDestination.Workshop -> "控制每页数量与下载方式检测。"
                    SettingsDestination.Translation -> "配置简介翻译提供商，手动把游戏或工坊介绍翻译成中文。"
                    SettingsDestination.DataPrivacy -> "查看本地保存内容，并清理缓存、历史和诊断信息。"
                    SettingsDestination.About -> "查看作者、开源地址和首次启动时展示的说明。"
                    SettingsDestination.UsageBoundary -> "查看使用范围、权利边界和通过项目主页反馈问题的方式。"
                },
            ) {
                when (destination) {
                    SettingsDestination.DownloadLocation -> DownloadLocationContent(
                        uiState = uiState,
                        onFolderNameChange = viewModel::saveDownloadFolderName,
                        onChooseRoot = {
                            treeLauncher.launch(uiState.downloadTreeUri?.let(Uri::parse))
                        },
                        onRestoreDefault = viewModel::restoreDefaultDownloadLocation,
                    )

                    SettingsDestination.DownloadStrategy -> DownloadStrategyContent(
                        uiState = uiState,
                        onPerformanceModeSelect = viewModel::saveDownloadPerformanceMode,
                        onTogglePreferAnonymous = viewModel::savePreferAnonymousDownloads,
                        onToggleAllowAuthenticatedFallback = viewModel::saveAllowAuthenticatedDownloadFallback,
                        onCdnTransportSelect = viewModel::saveCdnTransportPreference,
                        onCdnPoolSelect = viewModel::saveCdnPoolPreference,
                    )

                    SettingsDestination.Workshop -> WorkshopSettingsContent(
                        uiState = uiState,
                        onPageSizeSelect = viewModel::saveWorkshopPageSize,
                        onToggleAutoResolve = viewModel::saveWorkshopAutoResolveVisibleItems,
                    )

                    SettingsDestination.Translation -> TranslationSettingsContent(
                        uiState = uiState,
                        onSave = viewModel::saveTranslationSettings,
                        onClear = viewModel::clearTranslationSettings,
                        onVerify = viewModel::verifyTranslationSettings,
                    )

                    SettingsDestination.DataPrivacy -> DataPrivacyContent(
                        uiState = uiState,
                        onExportSupportLogs = {
                            logExportLauncher.launch(uiState.supportLogs.exportFileName)
                        },
                        onClearRuntimeLogs = viewModel::clearRuntimeLogs,
                        onClearCrashLogs = viewModel::clearCrashLogs,
                        onClearAllSupportLogs = viewModel::clearAllSupportLogs,
                        onClearAccountData = viewModel::clearAllAccountData,
                        onClearOwnedGamesCache = viewModel::clearOwnedGamesCache,
                        onClearFavoriteGames = viewModel::clearFavoriteWorkshopGames,
                        onClearDownloadDiagnostics = viewModel::clearInactiveDownloadDiagnostics,
                        onClearDownloadHistory = viewModel::clearInactiveDownloadHistory,
                    )

                    SettingsDestination.About -> AboutContent(
                        versionName = appUpdateUiState.currentVersionName,
                        onOpenRepository = { openExternalUrl(WorkshopNativeAbout.repositoryUrl) },
                    )

                    SettingsDestination.UsageBoundary -> UsageBoundaryContent(
                        onOpenRepository = { openExternalUrl(WorkshopNativeAbout.repositoryUrl) },
                    )
                }
            }
        }
    }

    if (showLoginRiskDialog) {
        AlertDialog(
            onDismissRequest = { showLoginRiskDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        showLoginRiskDialog = false
                        viewModel.saveLoginFeatureEnabled(true)
                    },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("仍然开启")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showLoginRiskDialog = false },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("保持匿名")
                }
            },
            title = {
                Text(
                    text = "开启登录功能前请确认风险",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "非官方客户端，存在账号风险，不建议主账号使用，建议保持匿名",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun AccountCard(
    uiState: SettingsUiState,
    isGuestMode: Boolean,
    onShowLogin: () -> Unit,
    onLogout: () -> Unit,
    onSwitchSavedAccount: (String) -> Unit,
    onRemoveSavedAccount: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFF182333),
                            Color(0xFF243547),
                            Color(0xFF314861),
                        ),
                    ),
                )
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccountAvatar(
                        accountName = uiState.accountName,
                        avatarUrl = uiState.avatarUrl,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = when {
                                !uiState.isLoginFeatureEnabled -> "账户能力"
                                isGuestMode -> "当前状态"
                                else -> "当前账号"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.76f),
                        )
                        Text(
                            text = if (!uiState.isLoginFeatureEnabled) {
                                "当前仅开放匿名能力"
                            } else if (isGuestMode) {
                                "未连接 Steam"
                            } else {
                                uiState.accountName.ifBlank { "未登录" }
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }

                if (!uiState.isLoginFeatureEnabled) {
                    Text(
                        text = "登录入口、已保存账号、自动恢复、已购和订阅信息都会在下方“账户行为”里单独开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.76f),
                    )
                } else {
                    Button(
                        onClick = if (isGuestMode) onShowLogin else onLogout,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF07E49),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            if (isGuestMode) Icons.Rounded.AccountCircle else Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = null,
                        )
                        Text(
                            text = if (isGuestMode) "前往登录" else "退出登录",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                if (uiState.isLoginFeatureEnabled && uiState.savedAccounts.isNotEmpty()) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "已保存账号",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.74f),
                        )
                        uiState.savedAccounts.forEach { account ->
                            SavedAccountRow(
                                account = account,
                                isActive = !isGuestMode && account.matches(uiState.accountName),
                                onSwitch = { onSwitchSavedAccount(account.stableKey()) },
                                onRemove = { onRemoveSavedAccount(account.stableKey()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedAccountRow(
    account: com.slay.workshopnative.data.preferences.SavedSteamAccount,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.18f),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initialsOf(account.accountName),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = account.accountName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = if (account.steamId64 > 0L) "SteamID ${account.steamId64}" else "已保存登录态",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.68f),
                )
            }
            if (isActive) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = "当前",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onSwitch,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("切换")
                }
            }
            Surface(
                modifier = Modifier.clickable(onClick = onRemove),
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = 0.08f),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "移除账号",
                        tint = Color.White.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountAvatar(
    accountName: String,
    avatarUrl: String?,
) {
    Surface(
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.5f)),
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = accountName.ifBlank { "Steam 头像" },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (accountName.isBlank()) {
                    Icon(
                        imageVector = Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                } else {
                    Text(
                        text = initialsOf(accountName),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsPanel(
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color.White.copy(alpha = 0.86f),
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsSubsectionTitle(
    title: String,
    subtitle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = iconTint.copy(alpha = 0.12f),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                    )
                }
            }
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
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = "进入详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SettingsSheetScaffold(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
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
        SettingsPanel(content = content)
    }
}

@Composable
private fun UpdateSettingsRow(
    uiState: AppUpdateUiState,
    autoCheckEnabled: Boolean,
    onClick: () -> Unit,
) {
    val statusLabel = when {
        uiState.isChecking -> "检查中"
        uiState.hasUpdateAvailable -> "可更新"
        uiState.lastCheckSucceeded == true -> "最新"
        uiState.lastCheckSucceeded == false -> "重试"
        else -> "检查"
    }
    val statusContainerColor = when {
        uiState.hasUpdateAvailable -> Color(0xFFDCEFD8)
        uiState.isChecking -> Color(0xFFE8EDF7)
        uiState.lastCheckSucceeded == false -> Color(0xFFFBE1DD)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val statusContentColor = when {
        uiState.hasUpdateAvailable -> Color(0xFF215B28)
        uiState.isChecking -> Color(0xFF355A8A)
        uiState.lastCheckSucceeded == false -> Color(0xFFA03B2C)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val summary = when {
        uiState.isChecking -> "正在连接 GitHub Release 检查新版本。"
        uiState.hasUpdateAvailable && uiState.release != null ->
            "发现 ${uiState.release.rawTagName}，点击查看更新说明并前往浏览器下载。"
        uiState.lastCheckSucceeded == true && uiState.release != null ->
            "已检查到 ${uiState.release.rawTagName}，当前安装版本已是最新。"
        !uiState.summary.isNullOrBlank() -> uiState.summary
        autoCheckEnabled -> "当前版本 ${uiState.currentVersionName}，应用启动时会自动检查一次更新。"
        else -> "当前版本 ${uiState.currentVersionName}，已关闭自动检查更新，可手动检查。"
    }
    val metaLine = buildString {
        append("当前 ")
        append(uiState.currentVersionName)
        if (uiState.release != null) {
            append(" · 最新 ")
            append(uiState.release.rawTagName)
        }
        uiState.metadataSource?.let { source ->
            append(" · ")
            append(source.displayName)
        }
    }

    Surface(
        modifier = Modifier.clickable(enabled = !uiState.isChecking, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Box(
                    modifier = Modifier.size(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdateAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "应用更新",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.2.dp,
                    )
                }
                SettingsValuePill(
                    text = statusLabel,
                    containerColor = statusContainerColor,
                    contentColor = statusContentColor,
                    borderColor = statusContentColor.copy(alpha = 0.15f),
                )
            }
        }
    }
}

@Composable
private fun DataPrivacyContent(
    uiState: SettingsUiState,
    onExportSupportLogs: () -> Unit,
    onClearRuntimeLogs: () -> Unit,
    onClearCrashLogs: () -> Unit,
    onClearAllSupportLogs: () -> Unit,
    onClearAccountData: () -> Unit,
    onClearOwnedGamesCache: () -> Unit,
    onClearFavoriteGames: () -> Unit,
    onClearDownloadDiagnostics: () -> Unit,
    onClearDownloadHistory: () -> Unit,
) {
    SettingsSubsectionTitle(
        title = "问题反馈日志",
        subtitle = "导出运行日志、错误日志和崩溃日志，方便把问题现场发给作者定位。",
    )
    SettingsInlineHint(text = uiState.supportLogs.summary)
    uiState.supportLogs.latestCrashLabel?.let { latestCrash ->
        SettingsInlineHint(text = latestCrash)
    }
    SettingsInlineHint(text = uiState.supportLogs.retentionSummary)
    SettingsActionButton(
        text = if (uiState.supportLogs.isExporting) "正在导出日志包…" else "导出诊断日志包",
        enabled = !uiState.supportLogs.isExporting,
        onClick = onExportSupportLogs,
    )
    SettingsActionButton(
        text = "清除运行日志",
        enabled = uiState.supportLogs.hasRuntimeLogs && !uiState.supportLogs.isExporting,
        onClick = onClearRuntimeLogs,
    )
    SettingsActionButton(
        text = "清除崩溃日志",
        enabled = uiState.supportLogs.hasCrashLogs && !uiState.supportLogs.isExporting,
        onClick = onClearCrashLogs,
    )
    SettingsActionButton(
        text = "清除全部日志文件",
        enabled = uiState.supportLogs.hasAnyLogs && !uiState.supportLogs.isExporting,
        onClick = onClearAllSupportLogs,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsSubsectionTitle(
        title = "本地保存内容",
        subtitle = "以下数据只保存在当前设备，用于登录恢复、下载管理和界面体验。",
    )
    SettingsInlineHint(text = "登录状态只保存在当前设备，并通过系统安全能力加密保存。")
    SettingsInlineHint(text = "下载记录会保留结果路径；如不需要，可单独清除诊断信息和历史记录。")
    SettingsInlineHint(text = "应用会直接连接 Steam 官方相关下载节点，以兼容不同网络环境。")

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsSubsectionTitle(
        title = "清理动作",
        subtitle = "清理后不会影响应用设置项，但相关数据会在下次使用时重新生成。",
    )
    SettingsActionButton(
        text = "清除全部登录状态",
        onClick = onClearAccountData,
    )
    SettingsActionButton(
        text = "清除游戏库缓存",
        onClick = onClearOwnedGamesCache,
    )
    SettingsActionButton(
        text = "清除收藏列表",
        onClick = onClearFavoriteGames,
    )
    SettingsActionButton(
        text = "清除下载诊断信息",
        onClick = onClearDownloadDiagnostics,
    )
    SettingsActionButton(
        text = "删除下载历史",
        onClick = onClearDownloadHistory,
    )

    uiState.maintenanceSummary?.let { summary ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        SettingsInlineHint(text = summary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutContent(
    versionName: String,
    onOpenRepository: () -> Unit,
) {
    SettingsSubsectionTitle(
        title = "项目定位",
        subtitle = "一个面向 Android 的 Steam 创意工坊下载工具，默认强调开源、匿名优先和本地保存。",
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkshopNativeAbout.highlights.forEach { highlight ->
            SettingsValuePill(text = highlight)
        }
        SettingsValuePill(
            text = "版本 $versionName",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        )
    }

    SettingsInlineHint(text = "本应用不依赖自建后端，源码与版本发布都以 GitHub 官方仓库为准。")

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsSubsectionTitle(
        title = "作者与项目信息",
        subtitle = "以下信息可以帮助确认项目来源。",
    )
    SettingsInfoRow(label = "应用名称", value = WorkshopNativeAbout.appDisplayName)
    SettingsInfoRow(label = "开源地址", value = WorkshopNativeAbout.repositoryUrl)
    SettingsInfoRow(label = "B 站昵称", value = WorkshopNativeAbout.bilibiliNickname)
    SettingsInfoRow(label = "项目关系", value = "个人开源项目，与 Valve / Steam 无官方关联")
    SettingsActionButton(
        text = "打开 GitHub 仓库",
        onClick = onOpenRepository,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsSubsectionTitle(
        title = "使用说明与免责声明",
        subtitle = "首次启动已提示一次，这里保留完整说明，方便之后随时复看。",
    )
    WorkshopNativeAbout.disclaimerItems.forEach { item ->
        AppNoticeCard(item = item)
    }
}

@Composable
private fun UsageBoundaryContent(
    onOpenRepository: () -> Unit,
) {
    SettingsSubsectionTitle(
        title = "学习交流与使用边界",
        subtitle = "这部分说明聚焦合法使用范围、禁止用途、用户责任和权利反馈方式。",
    )

    WorkshopNativeAbout.usageBoundaryItems.forEach { item ->
        AppNoticeCard(item = item)
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsInlineHint(text = "如需反馈问题或权利相关事项，可优先通过项目 GitHub 主页联系作者。")
    SettingsActionButton(
        text = "打开项目主页",
        onClick = onOpenRepository,
    )
}

@Composable
private fun DownloadLocationContent(
    uiState: SettingsUiState,
    onFolderNameChange: (String) -> Unit,
    onChooseRoot: () -> Unit,
    onRestoreDefault: () -> Unit,
) {
    val targetSummary = buildString {
        append(uiState.downloadTreeLabel ?: "系统下载")
        append(" / ")
        append(uiState.effectiveDownloadFolderName)
    }

    SettingsSubsectionTitle(
        title = "保存目录",
        subtitle = "当前写入到 $targetSummary",
    )

    OutlinedTextField(
        value = uiState.downloadFolderName,
        onValueChange = onFolderNameChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("目录名") },
        placeholder = {
            Text(text = "留空时使用 ${uiState.effectiveDownloadFolderName}")
        },
        shape = RoundedCornerShape(20.dp),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onChooseRoot,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
            Text(
                text = if (uiState.downloadTreeUri == null) "选择根目录" else "更换根目录",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        OutlinedButton(
            onClick = onRestoreDefault,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Rounded.Restore, contentDescription = null)
            Text(
                text = "恢复默认",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    SettingsInlineHint(
        text = "下载会先落到应用内暂存，再整理到目标目录。自定义根目录通常比系统公共下载目录更快。",
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadStrategyContent(
    uiState: SettingsUiState,
    onPerformanceModeSelect: (DownloadPerformanceMode) -> Unit,
    onTogglePreferAnonymous: (Boolean) -> Unit,
    onToggleAllowAuthenticatedFallback: (Boolean) -> Unit,
    onCdnTransportSelect: (CdnTransportPreference) -> Unit,
    onCdnPoolSelect: (CdnPoolPreference) -> Unit,
) {
    val allowLoggedInDownload = uiState.isLoginFeatureEnabled && uiState.isLoggedInDownloadEnabled
    SettingsSubsectionTitle(
        title = "下载性能模式",
        subtitle = "当前 ${uiState.downloadPerformanceMode.performanceLabel()}。目标并发 ${uiState.downloadChunkConcurrency} 路，运行时仍会按设备内存自动收紧。",
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DownloadPerformanceMode.entries.forEach { option ->
            SettingsChoiceChip(
                label = option.performanceLabel(),
                selected = uiState.downloadPerformanceMode == option,
                onClick = { onPerformanceModeSelect(option) },
            )
        }
    }
    SettingsInlineHint(text = uiState.downloadPerformanceMode.performanceDescription())

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsBooleanRow(
        title = "公开内容优先匿名下载",
        description = if (!allowLoggedInDownload) {
            "当前只允许匿名下载，这个设置暂时不会生效。"
        } else {
            "公开内容会优先使用匿名方式，必要时再使用当前登录账号。"
        },
        checked = uiState.preferAnonymousDownloads,
        enabled = allowLoggedInDownload,
        onCheckedChange = onTogglePreferAnonymous,
    )

    SettingsBooleanRow(
        title = "匿名失败后回退到账号下载",
        description = if (!allowLoggedInDownload) {
            "需先在“账户行为”里打开“登录后下载”。"
        } else {
            "仅在你已登录且任务绑定当前账号时生效。"
        },
        checked = uiState.allowAuthenticatedDownloadFallback,
        enabled = allowLoggedInDownload,
        onCheckedChange = onToggleAllowAuthenticatedFallback,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsSubsectionTitle(
        title = "网络路径",
        subtitle = "决定先走系统网络还是直连。",
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CdnTransportPreference.entries.forEach { option ->
            SettingsChoiceChip(
                label = option.transportLabel(),
                selected = uiState.cdnTransportPreference == option,
                onClick = { onCdnTransportSelect(option) },
            )
        }
    }
    SettingsInlineHint(text = uiState.cdnTransportPreference.transportDescription())

    SettingsSubsectionTitle(
        title = "节点范围",
        subtitle = "决定优先尝试哪一类 CDN 节点。",
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CdnPoolPreference.entries.forEach { option ->
            SettingsChoiceChip(
                label = option.poolLabel(),
                selected = uiState.cdnPoolPreference == option,
                onClick = { onCdnPoolSelect(option) },
            )
        }
    }
    SettingsInlineHint(text = uiState.cdnPoolPreference.poolDescription())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkshopSettingsContent(
    uiState: SettingsUiState,
    onPageSizeSelect: (Int) -> Unit,
    onToggleAutoResolve: (Boolean) -> Unit,
) {
    SettingsSubsectionTitle(
        title = "每页数量",
        subtitle = "当前 ${uiState.workshopPageSize} / 页。数量越高，翻页越少但首次读取更重。",
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkshopBrowseQuery.PAGE_SIZE_OPTIONS.forEach { option ->
            SettingsChoiceChip(
                label = "$option / 页",
                selected = uiState.workshopPageSize == option,
                onClick = { onPageSizeSelect(option) },
            )
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsBooleanRow(
        title = "列表预读下载方式",
        description = "开启后会预读当前可见条目的可下载能力；关闭后列表先只加载条目，点进详情或开始下载时再检测。",
        checked = uiState.workshopAutoResolveVisibleItems,
        onCheckedChange = onToggleAutoResolve,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationSettingsContent(
    uiState: SettingsUiState,
    onSave: (TranslationProvider, String, String, String, String) -> Unit,
    onClear: () -> Unit,
    onVerify: () -> Unit,
) {
    var provider by remember(uiState.translationProvider) { mutableStateOf(uiState.translationProvider) }
    var azureEndpoint by remember(uiState.translationAzureEndpoint) { mutableStateOf(uiState.translationAzureEndpoint) }
    var azureRegion by remember(uiState.translationAzureRegion) { mutableStateOf(uiState.translationAzureRegion) }
    var azureApiKey by remember(uiState.translationAzureApiKey) { mutableStateOf(uiState.translationAzureApiKey) }
    var googleApiKey by remember(uiState.translationGoogleApiKey) { mutableStateOf(uiState.translationGoogleApiKey) }
    var azureApiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var googleApiKeyVisible by rememberSaveable { mutableStateOf(false) }
    val builtInProviders = remember {
        listOf(
            TranslationProvider.Disabled,
            TranslationProvider.AzureTranslator,
            TranslationProvider.GoogleCloudTranslate,
        )
    }
    val experimentalProviders = remember {
        listOf(
            TranslationProvider.GoogleWebTranslate,
            TranslationProvider.MicrosoftEdgeTranslate,
        )
    }
    val normalizedDraftAzureEndpoint = azureEndpoint.trim().ifBlank { DEFAULT_AZURE_TRANSLATOR_ENDPOINT }
    val normalizedDraftAzureRegion = azureRegion.trim()
    val normalizedDraftAzureApiKey = azureApiKey.trim()
    val normalizedDraftGoogleApiKey = googleApiKey.trim()
    val hasPendingChanges = provider != uiState.translationProvider ||
        normalizedDraftAzureEndpoint != uiState.translationAzureEndpoint ||
        normalizedDraftAzureRegion != uiState.translationAzureRegion ||
        normalizedDraftAzureApiKey != uiState.translationAzureApiKey ||
        normalizedDraftGoogleApiKey != uiState.translationGoogleApiKey
    val isDraftConfigured = isTranslationDraftConfigured(
        provider = provider,
        azureEndpoint = normalizedDraftAzureEndpoint,
        azureApiKey = normalizedDraftAzureApiKey,
        googleApiKey = normalizedDraftGoogleApiKey,
    )

    SettingsSubsectionTitle(
        title = "翻译提供商",
        subtitle = if (hasPendingChanges) {
            "当前有未保存的更改。保存后再测试，详情页才会使用最新配置。"
        } else if (provider == TranslationProvider.Disabled) {
            "当前未启用简介翻译。详情页不会发起额外翻译请求。"
        } else if (provider.isExperimental()) {
            "当前 ${uiState.translationProvider.displayLabel()} 为实验模式，无需填写密钥。"
        } else if (uiState.isTranslationConfigured) {
            "当前 ${uiState.translationProvider.displayLabel()} 已配置完成。"
        } else {
            "当前未配置。详情页点击“翻译成中文”前，请先完成这里的设置。"
        },
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        builtInProviders.forEach { option ->
            SettingsChoiceChip(
                label = option.displayLabel(),
                selected = provider == option,
                onClick = { provider = option },
            )
        }
    }
    SettingsInlineHint(text = "官方模式更稳定，但 Azure 和 Google Cloud 都需要你自己提供凭据。")

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsSubsectionTitle(
        title = "实验模式",
        subtitle = "无需密钥，但依赖非官方网页或客户端通道，可用性会随服务端策略变化。",
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        experimentalProviders.forEach { option ->
            SettingsChoiceChip(
                label = option.displayLabel(),
                selected = provider == option,
                onClick = { provider = option },
            )
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    when (provider) {
        TranslationProvider.Disabled -> {
            SettingsInlineHint(text = "关闭后不会移除已保存密钥，只是详情页不再发起翻译请求。")
        }

        TranslationProvider.AzureTranslator -> {
            SettingsSubsectionTitle(
                title = "Azure Translator",
                subtitle = "最少需要 Endpoint 和 API Key。Region 只有区域资源时才需要填写。",
            )
            OutlinedTextField(
                value = azureEndpoint,
                onValueChange = { azureEndpoint = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Endpoint") },
                shape = RoundedCornerShape(20.dp),
            )
            OutlinedTextField(
                value = azureRegion,
                onValueChange = { azureRegion = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Region") },
                placeholder = { Text("Global 资源可留空") },
                shape = RoundedCornerShape(20.dp),
            )
            OutlinedTextField(
                value = azureApiKey,
                onValueChange = { azureApiKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API Key") },
                trailingIcon = {
                    IconButton(onClick = { azureApiKeyVisible = !azureApiKeyVisible }) {
                        Icon(
                            imageVector = if (azureApiKeyVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (azureApiKeyVisible) "隐藏 Azure API Key" else "显示 Azure API Key",
                        )
                    }
                },
                visualTransformation = if (azureApiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(20.dp),
            )
            SettingsInlineHint(text = "默认全局 Endpoint 通常是 https://api.cognitive.microsofttranslator.com")
        }

        TranslationProvider.GoogleCloudTranslate -> {
            SettingsSubsectionTitle(
                title = "Google Cloud Translation",
                subtitle = "最少需要 API Key。建议在 Google Cloud 控制台中限制包名、SHA-1 和可调用 API。",
            )
            OutlinedTextField(
                value = googleApiKey,
                onValueChange = { googleApiKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API Key") },
                trailingIcon = {
                    IconButton(onClick = { googleApiKeyVisible = !googleApiKeyVisible }) {
                        Icon(
                            imageVector = if (googleApiKeyVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (googleApiKeyVisible) "隐藏 Google API Key" else "显示 Google API Key",
                        )
                    }
                },
                visualTransformation = if (googleApiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(20.dp),
            )
            SettingsInlineHint(text = "使用 Google 时，应用内展示翻译结果应保留 Google 归因说明。")
        }

        TranslationProvider.GoogleWebTranslate -> {
            SettingsSubsectionTitle(
                title = "Google Web 翻译",
                subtitle = "直接调用 Google 网页翻译通道，无需 API Key。",
            )
            SettingsInlineHint(text = "该模式依赖 Google 网页接口。可直接使用，但未来可能出现限流、失效或返回结构变化。")
        }

        TranslationProvider.MicrosoftEdgeTranslate -> {
            SettingsSubsectionTitle(
                title = "Microsoft Edge 翻译",
                subtitle = "直接复用 Microsoft Edge 翻译认证通道，无需 Azure 资源和 API Key。",
            )
            SettingsInlineHint(text = "该模式依赖 Edge 客户端通道。可直接使用，但未来可能被微软调整、限流或关闭。")
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    when {
        hasPendingChanges -> {
            SettingsInlineHint(text = "你修改了翻译参数，保存后才能使用“测试当前配置”。")
        }

        provider != TranslationProvider.Disabled && !uiState.isTranslationConfigured -> {
            SettingsInlineHint(text = "当前配置仍不完整。补齐必填项并保存后，详情页才会发起翻译请求。")
        }

        provider.isExperimental() -> {
            SettingsInlineHint(text = "实验模式无需密钥。保存后即可在详情页手动翻译，但测试通过也不代表长期稳定。")
        }

        provider != TranslationProvider.Disabled && isDraftConfigured -> {
            SettingsInlineHint(text = "详情页会在你手动点击“翻译成中文”后发起请求，原文会继续保留，可随时切回。")
        }
    }

    uiState.translationStatusSummary?.let { summary ->
        SettingsInlineHint(text = summary)
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsActionButton(
        text = "保存翻译配置",
        enabled = hasPendingChanges,
        onClick = {
            onSave(
                provider,
                azureEndpoint,
                azureRegion,
                azureApiKey,
                googleApiKey,
            )
        },
    )
    SettingsActionButton(
        text = if (uiState.isVerifyingTranslation) "正在测试配置…" else "测试当前配置",
        enabled = !hasPendingChanges &&
            uiState.translationProvider != TranslationProvider.Disabled &&
            uiState.isTranslationConfigured &&
            !uiState.isVerifyingTranslation,
        onClick = onVerify,
    )
    SettingsActionButton(
        text = "清空已保存配置",
        enabled = hasPendingChanges ||
            uiState.isTranslationConfigured ||
            uiState.translationProvider != TranslationProvider.Disabled,
        onClick = onClear,
    )
    SettingsInlineHint(
        text = if (provider.isExperimental()) {
            "“测试当前配置”会直接测试当前实验通道是否可访问。修改后请先保存。"
        } else {
            "“测试当前配置”会使用已经保存的参数发起一次示例翻译。修改后请先保存。"
        },
    )
}

private fun isTranslationDraftConfigured(
    provider: TranslationProvider,
    azureEndpoint: String,
    azureApiKey: String,
    googleApiKey: String,
): Boolean {
    return when (provider) {
        TranslationProvider.Disabled -> false
        TranslationProvider.AzureTranslator -> azureEndpoint.isNotBlank() && azureApiKey.isNotBlank()
        TranslationProvider.GoogleCloudTranslate -> googleApiKey.isNotBlank()
        TranslationProvider.GoogleWebTranslate,
        TranslationProvider.MicrosoftEdgeTranslate -> true
    }
}

@Composable
private fun SettingsBooleanRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            },
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun SettingsActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(text)
    }
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun AppNoticeCard(item: AppNoticeItem) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsInlineHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsValuePill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

private fun initialsOf(accountName: String): String {
    val letters = accountName
        .trim()
        .split(Regex("\\s+"))
        .flatMap { token -> token.take(1).map(Char::uppercaseChar) }
        .take(2)
        .joinToString("")

    return letters.ifBlank { "ST" }
}

private fun CdnTransportPreference.transportLabel(): String {
    return when (this) {
        CdnTransportPreference.Auto -> "自动"
        CdnTransportPreference.PreferSystem -> "跟随系统"
        CdnTransportPreference.PreferDirect -> "优先直连"
    }
}

private fun CdnTransportPreference.transportDescription(): String {
    return when (this) {
        CdnTransportPreference.Auto -> "自动根据上次成功路径和当前网络环境选择。通常适合大多数情况。"
        CdnTransportPreference.PreferSystem -> "优先沿用当前系统网络路径。适合浏览器或 Steam 客户端本来就稳定的情况。"
        CdnTransportPreference.PreferDirect -> "优先绕开系统代理直连 CDN。适合代理链路本身更慢或更不稳定的情况。"
    }
}

private fun DownloadPerformanceMode.performanceLabel(): String {
    return when (this) {
        DownloadPerformanceMode.Auto -> "自动高性能"
        DownloadPerformanceMode.Compatibility -> "兼容模式"
    }
}

private fun DownloadPerformanceMode.performanceDescription(): String {
    return when (this) {
        DownloadPerformanceMode.Auto -> "默认模式。会优先释放更高下载性能，但仍会按设备内存自动收紧，避免重新走回 OOM。"
        DownloadPerformanceMode.Compatibility -> "更保守地限制分块并发和连接激进度。适合旧设备，或之前出现过内存溢出的情况。"
    }
}

private fun CdnPoolPreference.poolLabel(): String {
    return when (this) {
        CdnPoolPreference.Auto -> "自动"
        CdnPoolPreference.TrustedOnly -> "稳定节点"
        CdnPoolPreference.PreferGoogle2 -> "优先 Google2"
        CdnPoolPreference.PreferFastly -> "优先 Fastly"
        CdnPoolPreference.PreferAkamai -> "优先 Akamai"
    }
}

private fun CdnPoolPreference.poolDescription(): String {
    return when (this) {
        CdnPoolPreference.Auto -> "按内置优先级和最近成功节点自动选择，兼容性最好。"
        CdnPoolPreference.TrustedOnly -> "只优先尝试 Google2、Fastly、Akamai 这类更稳的节点，首连通常更快。"
        CdnPoolPreference.PreferGoogle2 -> "把 Google2 节点放到最前面，其他节点仍可作为回退。"
        CdnPoolPreference.PreferFastly -> "把 Fastly 节点放到最前面，其他节点仍可作为回退。"
        CdnPoolPreference.PreferAkamai -> "把 Akamai 节点放到最前面，其他节点仍可作为回退。"
    }
}

private fun com.slay.workshopnative.data.preferences.SavedSteamAccount.matches(accountName: String): Boolean {
    return accountName.isNotBlank() && this.accountName.equals(accountName, ignoreCase = true)
}
