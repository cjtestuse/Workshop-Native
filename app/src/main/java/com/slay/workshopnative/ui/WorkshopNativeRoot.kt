package com.slay.workshopnative.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.slay.workshopnative.ui.components.WorkshopBackdrop
import com.slay.workshopnative.ui.feature.downloads.DownloadsScreen
import com.slay.workshopnative.ui.feature.explore.ExploreScreen
import com.slay.workshopnative.ui.feature.library.LibraryScreen
import com.slay.workshopnative.ui.feature.login.LoginScreen
import com.slay.workshopnative.ui.feature.settings.SettingsScreen
import com.slay.workshopnative.ui.feature.workshop.WorkshopScreen
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
private fun rootDestinations(): List<RootDestination> {
    return listOf(
        RootDestination(
            route = RootTab.Explore.route,
            label = "探索",
            icon = { Icon(Icons.Rounded.TravelExplore, contentDescription = null) },
        ),
        RootDestination(
            route = RootTab.Library.route,
            label = "我的内容",
            icon = { Icon(Icons.Rounded.Games, contentDescription = null) },
        ),
        RootDestination(
            route = RootTab.Downloads.route,
            label = "下载",
            icon = { Icon(Icons.Rounded.DownloadForOffline, contentDescription = null) },
        ),
        RootDestination(
            route = RootTab.Settings.route,
            label = "设置",
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
        ),
    )
}

@Composable
fun WorkshopNativeRoot(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val isBootstrapping by viewModel.isBootstrapping.collectAsStateWithLifecycle()
    val guestMode by viewModel.guestMode.collectAsStateWithLifecycle()
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var appUnlocked by remember { mutableStateOf(false) }
    var forceLoginScreen by remember { mutableStateOf(false) }
    var currentRootTabRoute by rememberSaveable { mutableStateOf(RootTab.Explore.route) }
    var activeWorkshopAppId by rememberSaveable { mutableStateOf<Int?>(null) }
    var activeWorkshopAppName by rememberSaveable { mutableStateOf("") }
    val openExternalUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.onAppForegrounded()
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

    LaunchedEffect(sessionState.status) {
        when (sessionState.status) {
            SessionStatus.Authenticated -> {
                appUnlocked = true
                forceLoginScreen = false
                if (currentRootTabRoute == RootTab.Explore.route) {
                    currentRootTabRoute = RootTab.Library.route
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

    val hasRememberedAccount = sessionState.account?.steamId64?.let { it > 0L } == true
    val showApplicationShell = guestMode ||
        appUnlocked ||
        sessionState.status == SessionStatus.Authenticated ||
        (sessionState.isRestoring && hasRememberedAccount)
    val showStartupOverlay = !showApplicationShell &&
        !forceLoginScreen &&
        isBootstrapping &&
        sessionState.status == SessionStatus.Idle
    val showRestoreScreen = !showApplicationShell &&
        !forceLoginScreen &&
        !showStartupOverlay &&
        (
            sessionState.isRestoring ||
                (hasRememberedAccount && sessionState.status == SessionStatus.Connecting) ||
                (hasRememberedAccount && sessionState.status == SessionStatus.Error)
        )

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
                    currentRootTabRoute = RootTab.Explore.route
                },
            )
        }
        AppUpdateDialogIfNeeded(
            appUpdateState = appUpdateState,
            onDismiss = viewModel::dismissUpdateDialog,
            onConfirmDownload = { downloadUrl ->
                viewModel.dismissUpdateDialog()
                openExternalUrl(downloadUrl)
            },
        )
        return
    }

    val currentRootTab = RootTab.entries.firstOrNull { it.route == currentRootTabRoute } ?: RootTab.Explore
    val activeWorkshop = activeWorkshopAppId?.let { appId ->
        appId to activeWorkshopAppName
    }
    val showBottomBar = activeWorkshop == null
    val showSessionBanner = showApplicationShell && !guestMode && sessionState.status == SessionStatus.Error
    val sessionBannerPadding = when {
        !showSessionBanner -> 0.dp
        else -> 76.dp
    }

    BackHandler(enabled = activeWorkshop != null) {
        activeWorkshopAppId = null
        activeWorkshopAppName = ""
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
                    destinations = rootDestinations(),
                    currentRoute = currentRootTab.route,
                    onNavigate = { route -> currentRootTabRoute = route },
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
                        paddingValues = shellPadding,
                        onBack = {
                            activeWorkshopAppId = null
                            activeWorkshopAppName = ""
                        },
                        onOpenDownloads = {
                            currentRootTabRoute = RootTab.Downloads.route
                            activeWorkshopAppId = null
                            activeWorkshopAppName = ""
                        },
                    )
                } else {
                    when (currentRootTab) {
                        RootTab.Explore -> {
                            ExploreScreen(
                                paddingValues = shellPadding,
                                onOpenGame = { appId, name ->
                                    activeWorkshopAppId = appId
                                    activeWorkshopAppName = name
                                },
                            )
                        }

                        RootTab.Library -> {
                            if (guestMode || sessionState.status != SessionStatus.Authenticated) {
                                SignedInContentGate(
                                    paddingValues = shellPadding,
                                    savedAccounts = savedAccounts,
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
                                        activeWorkshopAppId = appId
                                        activeWorkshopAppName = name
                                    },
                                )
                            }
                        }

                        RootTab.Downloads -> {
                            DownloadsScreen(paddingValues = shellPadding)
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
        AppUpdateDialogIfNeeded(
            appUpdateState = appUpdateState,
            onDismiss = viewModel::dismissUpdateDialog,
            onConfirmDownload = { downloadUrl ->
                viewModel.dismissUpdateDialog()
                openExternalUrl(downloadUrl)
            },
        )
    }
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
    savedAccounts: List<SavedSteamAccount>,
    onShowLogin: () -> Unit,
    onSwitchSavedAccount: (String) -> Unit,
) {
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
            color = Color.White.copy(alpha = 0.86f),
            shadowElevation = 10.dp,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "我的内容需要 Steam 账号",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Text(
                    text = "公开创意工坊仍然可以在探索页浏览。要查看已购买和家庭共享游戏，请登录 Steam 或切换到已保存账号。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onShowLogin,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("前往登录")
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
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
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
    Column(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                brush = if (selected) {
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFFF7E7D8),
                            Color(0xFFFFF4E8),
                        ),
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
                    if (selected) Color(0x1AE96D43) else Color.White.copy(alpha = 0.06f),
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
                Color(0xFF172131)
            } else {
                Color.White.copy(alpha = 0.72f)
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
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
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
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
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
    Surface(
        modifier = modifier,
        color = Color(0xFF182233).copy(alpha = 0.97f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
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
                    color = Color(0xFFF7E9D8),
                )
                Text(
                    text = "Steam 正在重连，联网操作恢复后会自动接上。",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
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
                    color = Color.White,
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
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    ) {
                        Text("登录页", color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
        }
    }
}
