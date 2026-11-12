package com.moontvplus.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moontvplus.phone.di.appViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moontvplus.phone.core.ui.theme.MoonTvTheme
import com.moontvplus.phone.feature.AppUiState
import com.moontvplus.phone.feature.AppViewModel
import com.moontvplus.phone.feature.detail.DetailScreen
import com.moontvplus.phone.feature.home.HomeScreen
import com.moontvplus.phone.feature.home.HomeTarget
import com.moontvplus.phone.feature.login.LoginScreen
import com.moontvplus.phone.feature.player.PlayerActivity
import com.moontvplus.phone.feature.search.SearchScreen
import com.moontvplus.phone.feature.server.ServerSetupScreen

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openSettingsRequest.value = intent?.action == ACTION_OPEN_SETTINGS
        setContent {
            MoonTvApp(openSettingsRequest)
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
    viewModel: AppViewModel = appViewModel {
        AppViewModel(
            settingsStore = it.graph.settingsStore,
            serverProvider = it.graph.serverProvider,
            sessions = it.graph.sessionManager,
            authRepository = it.graph.authRepository,
            serverRepository = it.graph.serverRepository,
            legacyMigration = it.graph.platformServices.legacy,
        )
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val editingServer by viewModel.editingServer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var currentRoute by remember { mutableStateOf<AppRoute>(AppRoute.Home) }

    LaunchedEffect(openSettingsRequest.value) {
        if (openSettingsRequest.value) {
            viewModel.openServerEditor()
            openSettingsRequest.value = false
        }
    }

    BackHandler(enabled = currentRoute !is AppRoute.Home) { currentRoute = AppRoute.Home }

    val darkTheme = when (settings.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    MoonTvTheme(darkTheme = darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (editingServer) {
                ServerSetupScreen(
                    onDone = viewModel::onServerSaved,
                    onCancel = viewModel::closeServerEditor,
                )
            } else {
                when (val current = state) {
                    is AppUiState.Loading -> LoadingBox()
                    is AppUiState.NeedServer -> ServerSetupScreen(
                        onDone = viewModel::onServerSaved,
                    )
                    is AppUiState.NeedLogin -> LoginScreen(
                        reason = current.reason,
                        onLoggedIn = viewModel::onLoggedIn,
                        onChangeServer = viewModel::changeServer,
                    )
                    is AppUiState.LoggedIn -> when (val route = currentRoute) {
                        is AppRoute.Search -> SearchScreen(
                            initialQuery = route.query,
                            onBack = { currentRoute = AppRoute.Home },
                            onOpenDetail = { source, videoId, title ->
                                currentRoute = AppRoute.Detail(source, videoId, title)
                            },
                        )
                        is AppRoute.Detail -> DetailScreen(
                            source = route.source,
                            videoId = route.videoId,
                            fallbackTitle = route.title,
                            onBack = { currentRoute = AppRoute.Home },
                            onPlay = { index, positionMs ->
                                context.startActivity(
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
                        is AppRoute.Home -> HomeScreen(
                            siteName = current.siteName,
                            onOpenCard = { target ->
                                currentRoute = when (target) {
                                    is HomeTarget.SourceSearch -> AppRoute.Search(target.title)
                                    is HomeTarget.Detail ->
                                        AppRoute.Detail(target.source, target.videoId, target.videoId)
                                    is HomeTarget.Resume ->
                                        AppRoute.Detail(target.source, target.videoId, "继续观看")
                                }
                            },
                            onOpenSearch = { currentRoute = AppRoute.Search(null) },
                            onLogout = viewModel::logout,
                            onChangeServer = viewModel::changeServer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "正在连接…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
