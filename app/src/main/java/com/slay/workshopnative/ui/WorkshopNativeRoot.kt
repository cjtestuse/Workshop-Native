package com.slay.workshopnative.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Games
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slay.workshopnative.data.preferences.SavedSteamAccount
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.model.SteamSessionState
import com.slay.workshopnative.core.util.openUrlWithChooser
import com.slay.workshopnative.ui.components.WorkshopBackdrop
import com.slay.workshopnative.ui.feature.downloads.DownloadedUpdatesSheet
import com.slay.workshopnative.ui.feature.downloads.DownloadsScreen
import com.slay.workshopnative.ui.feature.downloads.DownloadsViewModel
import com.slay.workshopnative.ui.feature.explore.ExploreScreen
import com.slay.workshopnative.ui.feature.library.LibraryScreen
import com.slay.workshopnative.ui.feature.login.LoginScreen
import com.slay.workshopnative.ui.feature.settings.SettingsScreen
import com.slay.workshopnative.ui.feature.workshop.WorkshopLaunchMode
import com.slay.workshopnative.ui.feature.workshop.WorkshopScreen
import com.slay.workshopnative.ui.theme.LocalWorkshopDarkTheme
import com.slay.workshopnative.ui.theme.workshopAdaptiveBorderColor
import com.slay.workshopnative.ui.theme.workshopAdaptiveGradientBrush
import com.slay.workshopnative.ui.theme.workshopAdaptiveSurfaceColor
import kotlinx.coroutines.flow.collectLatest

private data class RootDestination(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

private enum class RootTab(val route: String) {
    Explore("explore"),
    Library("library"),
    Downloads("downloads"),
    Settings("settings"),
}

@Composable
private fun rootDestinations(showLibraryTab: Boolean): List<RootDestination> {
    return buildList {
        add(
            RootDestination(
                route = RootTab.Explore.route,
                label = "探索",
                icon = { Icon(Icons.Rounded.TravelExplore, contentDescription = null) },
            ),
        )
        if (showLibraryTab) {
            add(
                RootDestination(
                    route = RootTab.Library.route,
                    label = "我的内容",
                    icon = { Icon(Icons.Rounded.Games, contentDescription = null) },
                ),
            )
        }
        add(
            RootDestination(
                route = RootTab.Downloads.route,
                label = "下载",
                icon = { Icon(Icons.Rounded.DownloadForOffline, contentDescription = null) },
            ),
        )
        add(
            RootDestination(
                route = RootTab.Settings.route,
                label = "设置",
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            ),
        )
    }
}

@Composable
fun WorkshopNativeRoot(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val downloadsViewModel: DownloadsViewModel = hiltViewModel()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val isBootstrapping by viewModel.isBootstrapping.collectAsStateWithLifecycle()
    val guestMode by viewModel.guestMode.collectAsStateWithLifecycle()
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val isLoginFeatureEnabled by viewModel.isLoginFeatureEnabled.collectAsStateWithLifecycle()
    val showLibraryTab by viewModel.showLibraryTab.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val hasAcknowledgedDisclaimer by viewModel.hasAcknowledgedDisclaimer.collectAsStateWithLifecycle()
    val hasAcknowledgedUsageBoundary by viewModel.hasAcknowledgedUsageBoundary.collectAsStateWithLifecycle()
    val currentRootTabRoute by viewModel.currentRootTabRoute.collectAsStateWithLifecycle()
    val activeWorkshopAppId by viewModel.activeWorkshopAppId.collectAsStateWithLifecycle()
    val activeWorkshopAppName by viewModel.activeWorkshopAppName.collectAsStateWithLifecycle()
    val activeWorkshopMode by viewModel.activeWorkshopMode.collectAsStateWithLifecycle()
    val downloadUpdatesDialogState by downloadsViewModel.downloadUpdatesDialogState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var appUnlocked by rememberSaveable { mutableStateOf(false) }
    var forceLoginScreen by rememberSaveable { mutableStateOf(false) }
    val openExternalUrl: (String) -> Unit = { url ->
        if (!context.openUrlWithChooser(url, chooserTitle = "选择浏览器")) {
            Toast.makeText(context, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.userMessages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(sessionState.status, showLibraryTab) {
        when (sessionState.status) {
            SessionStatus.Authenticated -> {
                appUnlocked = true
                forceLoginScreen = false
                if (showLibraryTab && currentRootTabRoute == RootTab.Explore.route) {
                    viewModel.navigateRootTab(RootTab.Library.route)
                }
            }

            SessionStatus.Idle -> {
                if (!guestMode) {
                    appUnlocked = false
                }
                forceLoginScreen = false
            }

            else -> Unit
        }
    }

    LaunchedEffect(showLibraryTab) {
        if (!showLibraryTab && currentRootTabRoute == RootTab.Library.route) {
            viewModel.navigateRootTab(RootTab.Explore.route)
        }
    }

    LaunchedEffect(isLoginFeatureEnabled) {
        if (!isLoginFeatureEnabled) {
            forceLoginScreen = false
        }
    }

    val hasRememberedAccount = sessionState.account?.steamId64?.let { it > 0L } == true
    val showApplicationShell = !isLoginFeatureEnabled ||
        guestMode ||
        appUnlocked ||
        sessionState.status == SessionStatus.Authenticated ||
        (isLoginFeatureEnabled && sessionState.isRestoring && hasRememberedAccount)
    val showStartupOverlay = isLoginFeatureEnabled &&
        !showApplicationShell &&
        !forceLoginScreen &&
        isBootstrapping &&
        sessionState.status == SessionStatus.Idle
    val showRestoreScreen = isLoginFeatureEnabled &&
        !showApplicationShell &&
        !forceLoginScreen &&
        !showStartupOverlay &&
        (
            sessionState.isRestoring ||
                (hasRememberedAccount && sessionState.status == SessionStatus.Connecting) ||
                (hasRememberedAccount && sessionState.status == SessionStatus.Error)
        )
    val showDisclaimerDialog = hasAcknowledgedDisclaimer == false &&
        !showStartupOverlay &&
        !showRestoreScreen
    val showUsageBoundaryDialog = hasAcknowledgedDisclaimer == true &&
        hasAcknowledgedUsageBoundary == false &&
        !showStartupOverlay &&
        !showRestoreScreen

    LaunchedEffect(
        showApplicationShell,
        showDisclaimerDialog,
        showUsageBoundaryDialog,
        appUpdateState.showUpdateDialog,
    ) {
        if (
            showApplicationShell &&
            !showDisclaimerDialog &&
            !showUsageBoundaryDialog &&
            !appUpdateState.showUpdateDialog
        ) {
            downloadsViewModel.maybeAutoCheckDownloadedItemsForUpdatesOnStartup()
        }
    }

    if (!showApplicationShell) {
        if (showStartupOverlay) {
            WorkshopBackdrop {
                StartupStateOverlay()
            }
        } else if (showRestoreScreen) {
            WorkshopBackdrop {
                SessionStateOverlay(
                    sessionState = sessionState,
                    onRetryRestore = viewModel::retrySessionRestore,
                    onShowLogin = { forceLoginScreen = true },
                )
            }
        } else {
            LoginScreen(
                sessionState = sessionState,
                savedAccounts = savedAccounts,
                onLogin = viewModel::login,
                onSubmitAuthCode = viewModel::submitAuthCode,
                onSwitchSavedAccount = viewModel::switchSavedAccount,
                onContinueAsGuest = {
                    viewModel.enterGuestMode()
                    forceLoginScreen = false
                    appUnlocked = true
                    viewModel.closeWorkshop()
                    viewModel.navigateRootTab(RootTab.Explore.route)
                },
            )
        }
        if (showDisclaimerDialog) {
            AppDisclaimerDialogIfNeeded(
                onConfirm = viewModel::acknowledgeDisclaimer,
                onOpenRepository = { openExternalUrl(WorkshopNativeAbout.repositoryUrl) },
            )
        } else if (showUsageBoundaryDialog) {
            AppUsageBoundaryDialogIfNeeded(
                onConfirm = viewModel::acknowledgeUsageBoundary,
                onOpenRepository = { openExternalUrl(WorkshopNativeAbout.repositoryUrl) },
            )
        } else {
            AppUpdateDialogIfNeeded(
                appUpdateState = appUpdateState,
                onDismiss = viewModel::dismissUpdateDialog,
                onConfirmDownload = { downloadUrl ->
                    viewModel.dismissUpdateDialog()
                    openExternalUrl(downloadUrl)
                },
            )
        }
        return
    }

    val currentRootTab = RootTab.entries.firstOrNull { it.route == currentRootTabRoute } ?: RootTab.Explore
    val activeWorkshop = activeWorkshopAppId?.let { appId ->
        Triple(
            appId,
            activeWorkshopAppName,
            WorkshopLaunchMode.entries.firstOrNull { it.name == activeWorkshopMode } ?: WorkshopLaunchMode.Browse,
        )
    }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBar = activeWorkshop == null
    val showSessionBanner = showApplicationShell &&
        isLoginFeatureEnabled &&
        !guestMode &&
        (
            sessionState.status == SessionStatus.Error ||
                sessionState.isRestoring ||
                sessionState.status == SessionStatus.Connecting
        )
    val sessionBannerPadding = when {
        !showSessionBanner -> 0.dp
        else -> 76.dp
    }

    BackHandler(enabled = activeWorkshop != null && !imeVisible) {
        viewModel.closeWorkshop()
    }

    WorkshopBackdrop {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.0f),
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            },
            bottomBar = {
                if (!showBottomBar) return@Scaffold
                RootBottomBar(
                    destinations = rootDestinations(showLibraryTab),
                    currentRoute = currentRootTab.route,
                    onNavigate = viewModel::navigateRootTab,
                )
            },
        ) { paddingValues ->
            val shellPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + sessionBannerPadding,
                bottom = paddingValues.calculateBottomPadding(),
            )
            Box(modifier = Modifier.fillMaxSize()) {
                if (activeWorkshop != null) {
                    WorkshopScreen(
                        appId = activeWorkshop.first,
                        appName = activeWorkshop.second,
                        launchMode = activeWorkshop.third,
                        paddingValues = shellPadding,
                        onBack = {
                            viewModel.closeWorkshop()
                        },
                        onOpenDownloads = {
                            viewModel.navigateRootTab(RootTab.Downloads.route)
                            viewModel.closeWorkshop()
                        },
                    )
                } else {
                    when (currentRootTab) {
                        RootTab.Explore -> {
                            ExploreScreen(
                                paddingValues = shellPadding,
                                onOpenGame = { appId, name ->
                                    viewModel.openWorkshop(appId, name, WorkshopLaunchMode.Browse.name)
                                },
                            )
                        }

                        RootTab.Library -> {
                            if (guestMode || sessionState.status != SessionStatus.Authenticated) {
                                SignedInContentGate(
                                    paddingValues = shellPadding,
                                    guestMode = guestMode,
                                    sessionState = sessionState,
                                    savedAccounts = savedAccounts,
                                    onRetryRestore = viewModel::retrySessionRestore,
                                    onShowLogin = {
                                        viewModel.leaveGuestMode()
                                        appUnlocked = false
                                        forceLoginScreen = true
                                    },
                                    onSwitchSavedAccount = {
                                        viewModel.switchSavedAccount(it)
                                    },
                                )
                            } else {
                                LibraryScreen(
                                    paddingValues = shellPadding,
                                    accountName = sessionState.account?.accountName.orEmpty(),
                                    onOpenGame = { appId, name ->
                                        viewModel.openWorkshop(appId, name, WorkshopLaunchMode.Browse.name)
                                    },
                                    onOpenSubscriptions = { appId, name ->
                                        viewModel.openWorkshop(appId, name, WorkshopLaunchMode.Subscriptions.name)
                                    },
                                )
                            }
                        }

                        RootTab.Downloads -> {
                            DownloadsScreen(
                                paddingValues = shellPadding,
                                viewModel = downloadsViewModel,
                            )
                        }

                        RootTab.Settings -> {
                            SettingsScreen(
                                paddingValues = shellPadding,
                                isGuestMode = guestMode,
                                appUpdateUiState = appUpdateState,
                                onCheckAppUpdates = viewModel::checkForAppUpdates,
                                onShowLogin = {
                                    viewModel.leaveGuestMode()
                                    appUnlocked = false
                                    forceLoginScreen = true
                                },
                                onSwitchSavedAccount = viewModel::switchSavedAccount,
                                onLogout = viewModel::logout,
                            )
                        }
                    }
                }

                if (showSessionBanner) {
                    SessionStateBanner(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .zIndex(1f),
                        sessionState = sessionState,
                        onRetryRestore = viewModel::retrySessionRestore,
                        onShowLogin = {
                            appUnlocked = false
                            forceLoginScreen = true
                        },
                    )
                }
            }
        }
        if (showDisclaimerDialog) {
            AppDisclaimerDialogIfNeeded(
                onConfirm = viewModel::acknowledgeDisclaimer,
                onOpenRepository = { openExternalUrl(WorkshopNativeAbout.repositoryUrl) },
            )
        } else if (showUsageBoundaryDialog) {
            AppUsageBoundaryDialogIfNeeded(
                onConfirm = viewModel::acknowledgeUsageBoundary,
                onOpenRepository = { openExternalUrl(WorkshopNativeAbout.repositoryUrl) },
            )
        } else {
            AppUpdateDialogIfNeeded(
                appUpdateState = appUpdateState,
                onDismiss = viewModel::dismissUpdateDialog,
                onConfirmDownload = { downloadUrl ->
                    viewModel.dismissUpdateDialog()
                    openExternalUrl(downloadUrl)
                },
            )
            if (downloadUpdatesDialogState.isVisible && !appUpdateState.showUpdateDialog) {
                DownloadedUpdatesSheet(
                    state = downloadUpdatesDialogState,
                    onDismiss = downloadsViewModel::dismissDownloadUpdatesDialog,
                    onToggleSelection = downloadsViewModel::toggleDownloadUpdateSelection,
                    onUpdateAll = downloadsViewModel::enqueueAllDownloadUpdates,
                    onUpdateSelected = downloadsViewModel::enqueueSelectedDownloadUpdates,
                )
            }
        }
    }
}

@Composable
private fun AppDisclaimerDialogIfNeeded(
    onConfirm: () -> Unit,
    onOpenRepository: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("我已了解")
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenRepository) {
                Text("开源地址")
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "首次启动说明",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "为减少误用和账户风险，首次使用前请先确认以下内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorkshopNativeAbout.highlights.forEach { highlight ->
                        UpdateMetaPill(text = highlight)
                    }
                }
                WorkshopNativeAbout.disclaimerItems.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                Text(
                    text = "后续可在 设置 > 关于与说明 > 关于 Workshop Native 中再次查看这份说明。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun AppUsageBoundaryDialogIfNeeded(
    onConfirm: () -> Unit,
    onOpenRepository: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("继续使用")
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenRepository) {
                Text("项目主页")
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "学习交流与使用边界",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "这部分说明只聚焦使用范围、权利边界和反馈方式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WorkshopNativeAbout.usageBoundaryItems.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                Text(
                    text = "后续可在 设置 > 关于与说明 > 学习交流与使用边界 中再次查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun AppUpdateDialogIfNeeded(
    appUpdateState: AppUpdateUiState,
    onDismiss: () -> Unit,
    onConfirmDownload: (String) -> Unit,
) {
    val release = appUpdateState.release
    val resolution = appUpdateState.downloadResolution
    if (!appUpdateState.showUpdateDialog || !appUpdateState.hasUpdateAvailable || release == null || resolution == null) {
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onConfirmDownload(resolution.resolvedUrl) },
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("前往下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后更新")
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "发现新版本 ${release.rawTagName}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "当前版本 ${appUpdateState.currentVersionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UpdateMetaPill(text = "GitHub 官方")
                    if (release.publishedAtDisplayText.isNotBlank()) {
                        UpdateMetaPill(text = release.publishedAtDisplayText)
                    }
                }
                if (release.notesText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Text(
                            text = release.notesText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "将通过浏览器直接下载 ${resolution.assetName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun UpdateMetaPill(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SignedInContentGate(
    paddingValues: PaddingValues,
    guestMode: Boolean,
    sessionState: SteamSessionState,
    savedAccounts: List<SavedSteamAccount>,
    onRetryRestore: () -> Unit,
    onShowLogin: () -> Unit,
    onSwitchSavedAccount: (String) -> Unit,
) {
    val isRecovering = !guestMode && (sessionState.isRestoring || sessionState.status == SessionStatus.Connecting)
    val canRestoreSavedSession = !guestMode &&
        (savedAccounts.isNotEmpty() || sessionState.account != null) &&
        sessionState.status == SessionStatus.Error
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            color = workshopAdaptiveSurfaceColor(light = Color.White.copy(alpha = 0.86f)),
            shadowElevation = 10.dp,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.45f))),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (isRecovering) {
                    CircularProgressIndicator()
                    Text(
                        text = "正在恢复 Steam 登录",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        text = "切回前台后会自动重连 Steam，恢复完成后这里会自动回到我的内容。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onRetryRestore,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("立即重试")
                    }
                } else {
                    Text(
                        text = if (canRestoreSavedSession) {
                            "Steam 连接已断开"
                        } else {
                            "我的内容需要 Steam 账号"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        text = if (canRestoreSavedSession) {
                            sessionState.errorMessage ?: "可以重试恢复当前账号，或者切换到其他已保存账号。"
                        } else {
                            "公开创意工坊仍然可以在探索页浏览。要查看已购买和家庭共享游戏，请登录 Steam 或切换到已保存账号。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (canRestoreSavedSession) {
                        Button(
                            onClick = onRetryRestore,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("重试恢复")
                        }
                    }
                    Button(
                        onClick = onShowLogin,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("前往登录")
                    }
                }
                if (savedAccounts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "快速切换到已保存账号",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        savedAccounts.forEach { account ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSwitchSavedAccount(account.stableKey()) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = account.accountName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = if (account.steamId64 > 0L) {
                                                "SteamID ${account.steamId64}"
                                            } else {
                                                "已保存登录态"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = "进入",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootBottomBar(
    destinations: List<RootDestination>,
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        color = Color(0xFF192130).copy(alpha = 0.94f),
        shape = RoundedCornerShape(32.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            destinations.forEach { destination ->
                val selected = currentRoute == destination.route
                RootBottomBarItem(
                    modifier = Modifier.weight(1f),
                    label = destination.label,
                    selected = selected,
                    icon = destination.icon,
                    onClick = {
                        if (!selected) onNavigate(destination.route)
                    },
                )
            }
        }
    }
}

@Composable
private fun RootBottomBarItem(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val darkTheme = LocalWorkshopDarkTheme.current
    Column(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                brush = if (selected) {
                    workshopAdaptiveGradientBrush(
                        lightStart = Color(0xFFF7E7D8),
                        lightEnd = Color(0xFFFFF4E8),
                        darkStart = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.98f),
                        darkEnd = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Transparent,
                        ),
                    )
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) {
                        if (darkTheme) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            Color(0x1AE96D43)
                        }
                    } else {
                        if (darkTheme) {
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.42f)
                        } else {
                            Color.White.copy(alpha = 0.06f)
                        }
                    },
                )
                ,
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            color = if (selected) {
                if (darkTheme) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color(0xFF172131)
                }
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StartupStateOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = workshopAdaptiveSurfaceColor(
                light = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                dark = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    text = "正在启动 Workshop Native…",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "正在检查已保存登录状态，随后会自动进入对应页面。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionStateOverlay(
    sessionState: SteamSessionState,
    onRetryRestore: () -> Unit,
    onShowLogin: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = workshopAdaptiveSurfaceColor(
                light = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                dark = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    sessionState.isRestoring || sessionState.status == SessionStatus.Connecting -> {
                        CircularProgressIndicator()
                        Text(
                            text = "正在恢复 Steam 登录…",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "正在连接 Steam 并准备账号数据，完成后会自动进入当前页面。",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Text(
                            text = sessionState.errorMessage ?: "Steam 连接已断开",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(
                            onClick = onRetryRestore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                        ) {
                            Text("重试恢复")
                        }
                        OutlinedButton(
                            onClick = onShowLogin,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) {
                            Text("切换到登录页")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionStateBanner(
    modifier: Modifier = Modifier,
    sessionState: SteamSessionState,
    onRetryRestore: () -> Unit,
    onShowLogin: () -> Unit,
) {
    val isRecovering = sessionState.isRestoring || sessionState.status == SessionStatus.Connecting
    val containerColor = workshopAdaptiveSurfaceColor(
        light = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        dark = Color(0xFF182233).copy(alpha = 0.97f),
    )
    val borderColor = workshopAdaptiveBorderColor(
        light = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        dark = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
    )
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, borderColor),
    ) {
        if (isRecovering) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 10.dp),
                    strokeWidth = 2.4.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Steam 正在重连，联网操作恢复后会自动接上。",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = sessionState.errorMessage ?: "Steam 连接已断开",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onRetryRestore,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("重试恢复")
                    }
                    OutlinedButton(
                        onClick = onShowLogin,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                    ) {
                        Text("登录页", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
