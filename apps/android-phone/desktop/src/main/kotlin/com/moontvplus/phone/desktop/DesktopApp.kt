package com.moontvplus.phone.desktop

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moontvplus.phone.core.common.ApiResult
import com.moontvplus.phone.core.common.userMessage
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.model.DoubanItem
import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.phone.core.storage.AppSettings
import com.moontvplus.phone.data.repository.ServerProbeResult
import com.moontvplus.phone.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Stage {
    data object Loading : Stage
    data object NeedServer : Stage
    data object NeedLogin : Stage
    data object Home : Stage
}

/**
 * 桌面端的临时外壳。
 *
 * 这一版先用它把 `shared` 的全链路（探测服务器 → 登录 → 首页推荐）在 Windows 上跑通；
 * 等界面抽成 KMP 模块之后，这里只保留窗口与菜单，页面全部换成共享实现。
 */
class DesktopController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    var stage by mutableStateOf<Stage>(Stage.Loading)
        private set
    var settings by mutableStateOf(AppSettings())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var movies by mutableStateOf<List<DoubanItem>>(emptyList())
        private set
    var session by mutableStateOf<AuthSession?>(null)
        private set

    val httpClient get() = graph.mediaClient
    val imageUrls get() = graph.imageUrlBuilder

    fun start() {
        scope.launch { boot() }
    }

    private suspend fun boot() {
        graph.initialize()
        settings = graph.settingsStore.snapshot()

        val address = ServerAddress.parseStored(settings.serverAddress)
        graph.serverProvider.set(address)
        if (address == null) {
            stage = Stage.NeedServer
            return
        }

        graph.sessionManager.load()
        val current = graph.sessionManager.session.value
        if (current == null) {
            stage = Stage.NeedLogin
            return
        }
        if (!graph.sessionManager.ensureFresh()) {
            graph.sessionManager.clear()
            stage = Stage.NeedLogin
            error = "登录已过期，请重新登录"
            return
        }

        session = current
        stage = Stage.Home
        loadHome()
    }

    fun connect(rawAddress: String) {
        scope.launch {
            busy = true
            error = null
            when (val result = graph.serverRepository.probe(rawAddress.trim())) {
                is ServerProbeResult.Success -> {
                    graph.serverRepository.save(result.address, result.config)
                    settings = graph.settingsStore.snapshot()
                    stage = Stage.NeedLogin
                }
                is ServerProbeResult.Failed -> error = result.message
            }
            busy = false
        }
    }

    fun login(username: String, password: String) {
        scope.launch {
            busy = true
            error = null
            when (val result = graph.authRepository.login(username, password)) {
                is ApiResult.Ok -> {
                    session = result.value
                    stage = Stage.Home
                    loadHome()
                }
                is ApiResult.Err -> error = result.error.userMessage()
            }
            busy = false
        }
    }

    fun changeServer() {
        scope.launch {
            graph.authRepository.logout()
            graph.serverRepository.clear()
            graph.serverProvider.set(null)
            settings = graph.settingsStore.snapshot()
            session = null
            movies = emptyList()
            stage = Stage.NeedServer
        }
    }

    fun logout() {
        scope.launch {
            graph.authRepository.logout()
            session = null
            movies = emptyList()
            stage = Stage.NeedLogin
        }
    }

    private suspend fun loadHome() {
        busy = true
        error = null
        val cached = graph.homeRepository.cached()
        if (cached != null) movies = cached.movies
        val result = graph.homeRepository.refresh(cached)
        movies = result.cache.movies
        if (movies.isEmpty() && result.errors.isNotEmpty()) {
            error = result.errors.values.firstOrNull()
        }
        busy = false
    }

    fun refresh() {
        scope.launch { loadHome() }
    }
}

@Composable
fun DesktopApp(controller: DesktopController) {
    LaunchedEffect(Unit) { controller.start() }

    when (val stage = controller.stage) {
        Stage.Loading -> CenteredCard {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在启动…", style = MaterialTheme.typography.bodySmall)
        }

        Stage.NeedServer -> ServerPane(controller)

        Stage.NeedLogin -> LoginPane(controller)

        Stage.Home -> HomePane(controller)
    }
}

@Composable
private fun CenteredCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun ServerPane(controller: DesktopController) {
    var input by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize()) {
        CenteredCard {
            Column(
                modifier = Modifier.width(480.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("MoonTVPlus", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "填写站点地址，例如 https://tv.example.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text("服务器地址") },
                    modifier = Modifier.fillMaxWidth(),
                )
                controller.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { controller.connect(input) },
                    enabled = !controller.busy && input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (controller.busy) "正在连接…" else "连接服务器")
                }
            }
        }
    }
}

@Composable
private fun LoginPane(controller: DesktopController) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val askUsername = !controller.settings.storageType.equals("localstorage", ignoreCase = true)

    Surface(modifier = Modifier.fillMaxSize()) {
        CenteredCard {
            Column(
                modifier = Modifier.width(420.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    controller.settings.siteName ?: "MoonTVPlus",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (askUsername) "请使用站点账号登录" else "请输入站点访问密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))

                if (askUsername) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        singleLine = true,
                        label = { Text("用户名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                controller.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { controller.login(username, password) },
                    enabled = !controller.busy && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (controller.busy) "正在登录…" else "登录")
                }
                TextButton(onClick = { controller.changeServer() }) { Text("更换服务器") }
            }
        }
    }
}

@Composable
private fun HomePane(controller: DesktopController) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    controller.settings.siteName ?: "MoonTVPlus",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = controller.session?.displayName.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { controller.refresh() }) { Text("刷新") }
                TextButton(onClick = { controller.logout() }) { Text("退出") }
            }

            controller.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (controller.movies.isEmpty() && controller.busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(controller.movies, key = { it.id }) { movie ->
                        MovieCard(movie, controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieCard(movie: DoubanItem, controller: DesktopController) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { /* 详情页在阶段四接入 */ },
    ) {
        RemoteImage(
            url = controller.imageUrls.proxied(movie.poster),
            client = controller.httpClient,
            contentDescription = movie.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = movie.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOf(movie.rate, movie.year).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
