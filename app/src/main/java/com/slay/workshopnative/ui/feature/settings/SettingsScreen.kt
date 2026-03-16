package com.slay.workshopnative.ui.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.preferences.CdnPoolPreference
import com.slay.workshopnative.data.preferences.CdnTransportPreference
import com.slay.workshopnative.data.preferences.DOWNLOAD_CHUNK_CONCURRENCY_OPTIONS
import com.slay.workshopnative.update.AppUpdateSource

private enum class SettingsDestination {
    DownloadLocation,
    DownloadStrategy,
    Workshop,
    Access,
    Update,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    isGuestMode: Boolean,
    onShowLogin: () -> Unit,
    onSwitchSavedAccount: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeDestination by remember { mutableStateOf<SettingsDestination?>(null) }
    val openExternalUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
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
            SettingsOverviewCard {
                SettingsOverviewRow(
                    title = "下载位置",
                    summary = buildString {
                        append(uiState.downloadTreeLabel ?: "手机下载")
                        append(" / ")
                        append(uiState.effectiveDownloadFolderName)
                    },
                    onClick = { activeDestination = SettingsDestination.DownloadLocation },
                )
                SettingsOverviewRow(
                    title = "下载策略",
                    summary = buildString {
                        append("${uiState.downloadChunkConcurrency} 线程")
                        append(" · ")
                        append(if (uiState.preferAnonymousDownloads) "匿名优先" else "账号优先")
                        append(" · ")
                        append(uiState.cdnTransportPreference.transportLabel())
                    },
                    onClick = { activeDestination = SettingsDestination.DownloadStrategy },
                )
                SettingsOverviewRow(
                    title = "创意工坊",
                    summary = buildString {
                        append("${uiState.workshopPageSize} / 页")
                        append(" · ")
                        append(if (uiState.workshopAutoResolveVisibleItems) "自动检测已开" else "自动检测已关")
                    },
                    onClick = { activeDestination = SettingsDestination.Workshop },
                )
                SettingsOverviewRow(
                    title = "匿名访问",
                    summary = if (uiState.defaultGuestMode) {
                        "启动时默认进入探索页"
                    } else {
                        "启动时优先恢复 Steam 登录"
                    },
                    onClick = { activeDestination = SettingsDestination.Access },
                )
                SettingsOverviewRow(
                    title = "应用更新",
                    summary = uiState.updateSummary ?: "当前版本 ${uiState.currentVersionName}",
                    onClick = { activeDestination = SettingsDestination.Update },
                )
            }
        }
    }

    activeDestination?.let { destination ->
        ModalBottomSheet(onDismissRequest = { activeDestination = null }) {
            SettingsSheetScaffold(
                title = when (destination) {
                    SettingsDestination.DownloadLocation -> "下载位置"
                    SettingsDestination.DownloadStrategy -> "下载策略"
                    SettingsDestination.Workshop -> "创意工坊"
                    SettingsDestination.Access -> "匿名访问"
                    SettingsDestination.Update -> "应用更新"
                },
                subtitle = when (destination) {
                    SettingsDestination.DownloadLocation -> "控制结果最终整理到哪里。"
                    SettingsDestination.DownloadStrategy -> "控制并发、匿名优先和 CDN 连接策略。"
                    SettingsDestination.Workshop -> "控制每页数量与下载方式检测。"
                    SettingsDestination.Access -> "控制启动时是否默认进入访客模式。"
                    SettingsDestination.Update -> "检查 GitHub Release 上的新版本，并打开下载链接。"
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
                        onConcurrencySelect = viewModel::saveDownloadChunkConcurrency,
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

                    SettingsDestination.Access -> GuestSettingsContent(
                        defaultGuestMode = uiState.defaultGuestMode,
                        onToggleDefaultGuestMode = viewModel::saveDefaultGuestMode,
                    )

                    SettingsDestination.Update -> UpdateSettingsContent(
                        uiState = uiState,
                        onSelectSource = viewModel::savePreferredUpdateSource,
                        onCheckUpdates = viewModel::checkForUpdates,
                        onOpenDownload = openExternalUrl,
                    )
                }
            }
        }
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
                            text = if (isGuestMode) "当前状态" else "当前账号",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.76f),
                        )
                        Text(
                            text = if (isGuestMode) {
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

                if (uiState.savedAccounts.isNotEmpty()) {
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
private fun SettingsOverviewCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsPanel(content = content)
}

@Composable
private fun SettingsOverviewRow(
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
            SettingsValuePill(text = "调整")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateSettingsContent(
    uiState: SettingsUiState,
    onSelectSource: (AppUpdateSource) -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenDownload: (String) -> Unit,
) {
    SettingsSubsectionTitle(
        title = "当前版本",
        subtitle = uiState.currentVersionName,
    )

    SettingsSubsectionTitle(
        title = "更新源",
        subtitle = "优先使用你选择的源；如果不可用，会自动回退到其他可访问源。",
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppUpdateSource.userSelectableSources().forEach { source ->
            SettingsChoiceChip(
                label = source.displayName,
                selected = uiState.preferredUpdateSource == source,
                onClick = { onSelectSource(source) },
            )
        }
    }

    Button(
        onClick = onCheckUpdates,
        enabled = !uiState.isCheckingUpdates,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        if (uiState.isCheckingUpdates) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.2.dp,
                color = Color.White,
            )
            Text("正在检查", modifier = Modifier.padding(start = 8.dp))
        } else {
            Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = null)
            Text("检查更新", modifier = Modifier.padding(start = 8.dp))
        }
    }

    uiState.updateSummary?.let { summary ->
        SettingsInlineHint(text = summary)
    }

    uiState.updateRelease?.let { release ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        SettingsSubsectionTitle(
            title = "最新版本",
            subtitle = release.rawTagName,
        )
        if (release.publishedAtDisplayText.isNotBlank()) {
            SettingsValuePill(text = "发布时间 ${release.publishedAtDisplayText}")
        }
        uiState.updateMetadataSource?.let { source ->
            SettingsInlineHint(text = "元数据来源：${source.displayName}")
        }

        if (release.notesText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            ) {
                Text(
                    text = release.notesText,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (uiState.hasUpdateAvailable) {
            uiState.updateDownloadResolution?.let { resolution ->
                Button(
                    onClick = { onOpenDownload(resolution.resolvedUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("前往下载")
                }
                SettingsInlineHint(text = "下载来源：${resolution.source.displayName}")
            } ?: SettingsInlineHint(text = "找到新版本，但暂时没有解析到可访问的下载地址。")
        }
    }
}

@Composable
private fun DownloadLocationContent(
    uiState: SettingsUiState,
    onFolderNameChange: (String) -> Unit,
    onChooseRoot: () -> Unit,
    onRestoreDefault: () -> Unit,
) {
    val targetSummary = buildString {
        append(uiState.downloadTreeLabel ?: "手机下载")
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
    onConcurrencySelect: (Int) -> Unit,
    onTogglePreferAnonymous: (Boolean) -> Unit,
    onToggleAllowAuthenticatedFallback: (Boolean) -> Unit,
    onCdnTransportSelect: (CdnTransportPreference) -> Unit,
    onCdnPoolSelect: (CdnPoolPreference) -> Unit,
) {
    SettingsSubsectionTitle(
        title = "分块并发",
        subtitle = "当前 ${uiState.downloadChunkConcurrency} 线程，移动端稳定上限 12。",
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DOWNLOAD_CHUNK_CONCURRENCY_OPTIONS.forEach { option ->
            SettingsChoiceChip(
                label = option.toString(),
                selected = uiState.downloadChunkConcurrency == option,
                onClick = { onConcurrencySelect(option) },
            )
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

    SettingsBooleanRow(
        title = "公开条目优先匿名下载",
        description = "已登录时也先尝试匿名下载公开条目。",
        checked = uiState.preferAnonymousDownloads,
        onCheckedChange = onTogglePreferAnonymous,
    )

    SettingsBooleanRow(
        title = "匿名失败后自动切已登录下载",
        description = "仅在当前已登录且任务绑定了当前账号时生效。",
        checked = uiState.allowAuthenticatedDownloadFallback,
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
        title = "自动检测下载方式",
        description = "开启后会预读当前可见条目的可下载能力；关闭后只在点进详情时检测。",
        checked = uiState.workshopAutoResolveVisibleItems,
        onCheckedChange = onToggleAutoResolve,
    )
}

@Composable
private fun GuestSettingsContent(
    defaultGuestMode: Boolean,
    onToggleDefaultGuestMode: (Boolean) -> Unit,
) {
    SettingsBooleanRow(
        title = "默认启动到访客模式",
        description = "开启后，应用启动时优先进入探索页，不主动恢复 Steam 登录。",
        checked = defaultGuestMode,
        onCheckedChange = onToggleDefaultGuestMode,
    )
}

@Composable
private fun SettingsBooleanRow(
    title: String,
    description: String,
    checked: Boolean,
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
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
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
        CdnTransportPreference.PreferSystem -> "优先系统"
        CdnTransportPreference.PreferDirect -> "优先直连"
    }
}

private fun CdnTransportPreference.transportDescription(): String {
    return when (this) {
        CdnTransportPreference.Auto -> "自动根据上次成功路径和当前网络环境选择。通常适合大多数情况。"
        CdnTransportPreference.PreferSystem -> "优先沿用系统当前网络路径。若浏览器或 Steam 客户端本来就快，通常先试这个。"
        CdnTransportPreference.PreferDirect -> "优先绕开系统代理直连 CDN。适合代理链路本身更慢或更不稳定的情况。"
    }
}

private fun CdnPoolPreference.poolLabel(): String {
    return when (this) {
        CdnPoolPreference.Auto -> "自动"
        CdnPoolPreference.TrustedOnly -> "高速范围"
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
