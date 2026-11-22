package com.moontvplus.phone

import com.moontvplus.phone.update.UpdateDialog
import com.moontvplus.phone.update.UpdateInstaller
import com.moontvplus.phone.update.UpdateStage
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.phone.di.appContainer
import com.moontvplus.phone.di.appViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moontvplus.ui.theme.MoonTvTheme
import com.moontvplus.ui.theme.WebColors
import com.moontvplus.ui.shell.WebSheet
import com.moontvplus.ui.screen.LibraryTab
import com.moontvplus.phone.feature.AppUiState
import com.moontvplus.phone.feature.AppViewModel
import com.moontvplus.phone.feature.detail.DetailScreen
import com.moontvplus.phone.feature.login.LoginScreen
import com.moontvplus.phone.feature.player.PlayerActivity
import com.moontvplus.phone.feature.search.SearchScreen
import com.moontvplus.phone.feature.server.ServerSetupScreen
import com.moontvplus.ui.app.MoonTvController
import com.moontvplus.ui.app.MoonTvShell
import com.moontvplus.ui.model.OpenTarget
import com.moontvplus.ui.model.ShellScreen

/** 桌面图标的「服务器设置」快捷方式走这个 action。 */
const val ACTION_OPEN_SETTINGS = "com.moontvplus.phone.OPEN_SETTINGS"

/** 已登录后的页面栈；M4 起再加播放页。 */
private sealed interface AppRoute {
    data object Home : AppRoute
    data class Search(val query: String?) : AppRoute
    data class Detail(val source: String, val videoId: String, val title: String) : AppRoute
}

class MainActivity : ComponentActivity() {

    private val openSettingsRequest: MutableState<Boolean> = mutableStateOf(false)
    private val updater get() = (application as MoonTvApplication).updater
    private val updateInstaller by lazy { UpdateInstaller(this, updater) }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            updater.state.first { !it.busy }
            updater.awaitIdle()
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                updateInstaller.resume()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openSettingsRequest.value = intent?.action == ACTION_OPEN_SETTINGS
        setContent {
            MoonTvApp(openSettingsRequest, onInstallUpdate = updateInstaller::install)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_SETTINGS) {
            openSettingsRequest.value = true
        }
    }
}

@Composable
private fun MoonTvApp(
    openSettingsRequest: MutableState<Boolean>,
    onInstallUpdate: () -> Unit,
    viewModel: AppViewModel = appViewModel {
        AppViewModel(
            settingsStore = it.graph.settingsStore,
            serverProvider = it.graph.serverProvider,
            sessions = it.graph.sessionManager,
            authRepository = it.graph.authRepository,
            serverRepository = it.graph.serverRepository,
            legacyMigration = it.graph.platformServices.legacy,
            initialize = { it.graph.initialize() },
        )
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val editingServer by viewModel.editingServer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var currentRoute by remember { mutableStateOf<AppRoute>(AppRoute.Home) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val updateState by (context.applicationContext as MoonTvApplication).updater.state.collectAsStateWithLifecycle()
    val updater = (context.applicationContext as MoonTvApplication).updater

    LaunchedEffect(Unit) {
        // Silent, non-blocking startup check at most twice a day; failures stay out of the way.
        delay(4_000)
        updater.check(manual = false)
    }

    LaunchedEffect(openSettingsRequest.value) {
        if (openSettingsRequest.value) {
            viewModel.openServerEditor()
            openSettingsRequest.value = false
        }
    }

    // 共享外壳里的详情 / 收藏页也要吃掉系统返回键，先退共享页面，再退原生路由
    var shellController by remember { mutableStateOf<MoonTvController?>(null) }
    var playbackReturn by remember { mutableStateOf(0) }
    var detailParent by remember { mutableStateOf<AppRoute>(AppRoute.Home) }
    val playerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        playbackReturn++
        if (currentRoute == AppRoute.Home) shellController?.refreshAfterPlayback()
    }
    BackHandler(
        enabled = currentRoute !is AppRoute.Home || shellController?.screen != null &&
            shellController?.screen != ShellScreen.Main,
    ) {
        val shell = shellController
        if (currentRoute == AppRoute.Home && shell != null && shell.screen != ShellScreen.Main) {
            shell.back()
        } else {
            currentRoute = if (currentRoute is AppRoute.Detail) detailParent else AppRoute.Home
        }
    }

    // 主题：默认跟随系统；顶栏切换后记住选择（system / light / dark）。
    val darkTheme = when (settings.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    MoonTvTheme(darkTheme = darkTheme) {
        UpdateDialog(state = updateState, updater = updater, onInstall = onInstallUpdate)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 主 Activity 的所有页面统一避让并消费系统安全区域。
            // 子页面已有的 safeDrawingPadding 只会处理尚未消费的部分，不会重复留白。
            // 播放器使用独立 Activity，仍由它自己控制全屏画面与控件边距。
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (showAbout) {
                    AboutDialog(
                        updateState = updateState,
                        onCheck = {
                            showAbout = false
                            updater.show()
                            updater.check(manual = true)
                        },
                        onDismiss = { showAbout = false },
                    )
                }

                if (editingServer) {
                    ServerSetupScreen(
                        onDone = {
                            viewModel.forgetCredentials()
                            viewModel.onServerSaved()
                        },
                        onCancel = viewModel::closeServerEditor,
                    )
                } else {
                    when (val current = state) {
                        is AppUiState.Loading -> LoadingBox()
                        is AppUiState.NeedServer -> ServerSetupScreen(
                            onDone = {
                                viewModel.forgetCredentials()
                                viewModel.onServerSaved()
                            },
                        )
                        is AppUiState.NeedLogin -> LoginScreen(
                            reason = current.reason,
                            onLoggedIn = viewModel::onLoggedIn,
                            onChangeServer = viewModel::changeServerAndForgetCredentials,
                        )
                        is AppUiState.LoggedIn -> when (val route = currentRoute) {
                            is AppRoute.Search -> SearchScreen(
                                initialQuery = route.query,
                                siteName = current.siteName.orEmpty().ifBlank { "MoonTVPlus" },
                                onToggleTheme = viewModel::toggleTheme,
                                onBack = { currentRoute = AppRoute.Home },
                                onPlay = { source, videoId, title ->
                                    playerLauncher.launch(
                                        PlayerActivity.intent(
                                            context = context,
                                            source = source,
                                            videoId = videoId,
                                            title = title,
                                            index = 1,
                                        )
                                    )
                                },
                            )
                            is AppRoute.Detail -> DetailScreen(
                                source = route.source,
                                videoId = route.videoId,
                                fallbackTitle = route.title,
                                siteName = current.siteName.orEmpty().ifBlank { "MoonTVPlus" },
                                onToggleTheme = viewModel::toggleTheme,
                                playbackReturn = playbackReturn,
                                onBack = { currentRoute = detailParent },
                                onPlay = { index, positionMs ->
                                    playerLauncher.launch(
                                        PlayerActivity.intent(
                                            context = context,
                                            source = route.source,
                                            videoId = route.videoId,
                                            title = route.title,
                                            index = index,
                                            positionMs = positionMs,
                                        )
                                    )
                                },
                            )
                            is AppRoute.Home -> {
                                // 登录之后的外壳、首页、分类页都用共享 UI，和桌面端同一套
                                val container = appContainer(context)
                                val scope = rememberCoroutineScope()
                                val shell = remember(container) { MoonTvController(container.graph, scope) }
                                SideEffect { shellController = shell }
                                LaunchedEffect(shell) {
                                    // 安卓端保留自己的搜索页（能挑源），豆瓣卡片走它
                                    shell.onNeedSearch = { title -> currentRoute = AppRoute.Search(title) }
                                    shell.enterShell()
                                }

                                MoonTvShell(
                                    controller = shell,
                                    darkTheme = darkTheme,
                                    onToggleTheme = viewModel::toggleTheme,
                                    onOpenTarget = { target ->
                                        currentRoute = when (target) {
                                            is OpenTarget.Douban -> AppRoute.Search(target.title)
                                            is OpenTarget.Video ->
                                                AppRoute.Detail(target.source, target.id, target.title)
                                            is OpenTarget.Resume ->
                                                AppRoute.Detail(target.source, target.id, target.title)
                                        }
                                    },
                                    onPlay = { request ->
                                        // 共享详情页的「播放」交给原生播放器（ExoPlayer + PiP）
                                        playerLauncher.launch(
                                            PlayerActivity.intent(
                                                context = context,
                                                source = request.source,
                                                videoId = request.videoId,
                                                title = request.title,
                                                index = request.index + 1,
                                                positionMs = request.positionMs,
                                            )
                                        )
                                    },
                                    onOpenSearch = { currentRoute = AppRoute.Search(null) },
                                    onOpenSettings = { showSettings = true },
                                )
                            }
                        }
                    }
                }
                if (state !is AppUiState.LoggedIn) {
                    TextButton(
                        onClick = { showAbout = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) { Text("关于与更新") }
                }

                if (showSettings) {
                    SettingsDialog(
                        siteName = (state as? AppUiState.LoggedIn)?.siteName.orEmpty(),
                        onDismiss = { showSettings = false },
                        onOpenFavorites = {
                            showSettings = false
                            shellController?.openLibrary(LibraryTab.FAVORITES)
                        },
                        onOpenHistory = {
                            showSettings = false
                            shellController?.openLibrary(LibraryTab.HISTORY)
                        },
                        onLogout = {
                            showSettings = false
                            viewModel.logout()
                            viewModel.forgetCredentials()
                        },
                        onChangeServer = {
                            showSettings = false
                            viewModel.changeServerAndForgetCredentials()
                        },
                        onOpenAbout = {
                            showSettings = false
                            showAbout = true
                        },
                    )
                }

            }
        }
    }
}

@Composable
private fun SettingsDialog(
    siteName: String,
    onDismiss: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onLogout: () -> Unit,
    onChangeServer: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    WebSheet(title = siteName.ifBlank { "我的" }, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            SheetRow("我的收藏", onOpenFavorites, Icons.Outlined.FavoriteBorder)
            SheetRow("播放记录", onOpenHistory, Icons.Outlined.List)
            SheetRow("关于与更新", onOpenAbout, Icons.Outlined.Info)
            SheetRow("更换服务器", onChangeServer, Icons.Outlined.Settings)
            SheetRow("退出登录", onLogout, Icons.Outlined.ExitToApp, danger = true)
        }
    }
}

/** 网页版用户菜单里的行：图标 + 文字，圆角 8、点按高亮。 */
@Composable
private fun SheetRow(
    text: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
) {
    val color = if (danger) WebColors.Danger else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AboutDialog(updateState: com.moontvplus.phone.update.UpdateUiState, onCheck: () -> Unit, onDismiss: () -> Unit) {
    val status = when {
        updateState.stage == UpdateStage.READY -> "新版本 ${updateState.update?.version} 已下载，可安装。"
        updateState.stage == UpdateStage.AVAILABLE -> "发现新版本 ${updateState.update?.version}。"
        updateState.stage == UpdateStage.LATEST -> "没有发现可更新的安卓正式版本。"
        updateState.stage == UpdateStage.ERROR -> "上次检查失败，可重试。"
        updateState.busy -> "正在检查版本…"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 MoonTVPlus") },
        text = {
            Column {
                Text("安卓客户端 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                Text(
                    "从官方更新源获取安卓正式版本；国内网络优先走 MoonTVPlus 站点，下载完成后由系统确认安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = onCheck) { Text(if (updateState.busy) "查看更新进度" else "检查更新") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WebColors.SplashGradientLight),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(24.dp))
            Text(
                text = "正在检查登录状态...",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
