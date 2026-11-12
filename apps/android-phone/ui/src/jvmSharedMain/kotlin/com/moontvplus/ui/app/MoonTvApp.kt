package com.moontvplus.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.model.MainTab
import com.moontvplus.ui.model.OpenTarget
import com.moontvplus.ui.screen.BrowseContent
import com.moontvplus.ui.screen.HomeContent
import com.moontvplus.ui.shell.CenteredMessage
import com.moontvplus.ui.shell.SeleneBottomBar
import com.moontvplus.ui.shell.SeleneTopBar
import com.moontvplus.ui.theme.MoonTvTheme
import com.moontvplus.ui.theme.SeleneColors

/**
 * Android 与桌面端共用的根界面。
 *
 * 宿主只负责：注入 [MoonTvController]、决定深浅色、接住点击（去详情/搜索/设置）。
 */
@Composable
fun MoonTvApp(
    controller: MoonTvController,
    darkTheme: Boolean,
    onOpenTarget: (OpenTarget) -> Unit = {},
    onMore: (MainTab) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    MoonTvTheme(darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LaunchedEffect(Unit) { controller.start() }

            when (val stage = controller.stage) {
                ShellStage.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                }

                ShellStage.NeedServer -> ServerPane(controller)

                is ShellStage.NeedLogin -> LoginPane(controller, stage.reason)

                ShellStage.Ready -> MainShell(
                    controller = controller,
                    onOpenTarget = onOpenTarget,
                    onMore = onMore,
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun MainShell(    controller: MoonTvController,
    onOpenTarget: (OpenTarget) -> Unit,
    onMore: (MainTab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 内容区自己吃掉剩余高度，底部导航固定贴在窗口底边
        Column(Modifier.weight(1f).fillMaxWidth()) {
            SeleneTopBar(
                title = if (controller.tab == MainTab.HOME) controller.siteName else controller.tab.label,
                subtitle = controller.session?.displayName?.takeIf { it.isNotBlank() },
                onSearch = onOpenSearch,
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("设置", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )

            when (controller.tab) {
                MainTab.HOME -> HomeContent(
                    state = controller.home,
                    onItemClick = { item -> item.target?.let(onOpenTarget) },
                    onMore = { section ->
                        // 「更多」跳到对应分类页
                        sectionToTab(section.id)?.let(controller::selectTab)
                    },
                )

                MainTab.LIVE -> CenteredMessage("直播模块正在接入")

                else -> BrowseContent(
                    state = controller.browse,
                    onItemClick = { item -> item.target?.let(onOpenTarget) },
                )
            }
        }

        SeleneBottomBar(selected = controller.tab, onSelect = controller::selectTab)
    }
}

/**
 * 只取「登录后的外壳」：宿主自己处理服务器地址与登录
 * （Android 的登录页要带 Turnstile，留在原生侧），外壳与首页/分类页仍然共享。
 */
@Composable
fun MoonTvShell(
    controller: MoonTvController,
    darkTheme: Boolean,
    onOpenTarget: (OpenTarget) -> Unit = {},
    onMore: (MainTab) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    MoonTvTheme(darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            MainShell(
                controller = controller,
                onOpenTarget = onOpenTarget,
                onMore = onMore,
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

private fun sectionToTab(sectionId: String): MainTab? = when (sectionId) {
    "hotMovies" -> MainTab.MOVIE
    "hotTv" -> MainTab.TV
    "bangumi" -> MainTab.ANIME
    "hotVariety" -> MainTab.SHOW
    else -> null
}

// ---- 服务器地址 -------------------------------------------------------------

@Composable
private fun ServerPane(controller: MoonTvController) {
    var input by remember { mutableStateOf("") }

    CenteredPane {
        Text("MoonTVPlus", fontSize = 26.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "填写站点地址，例如 https://tv.example.com",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            label = { Text("服务器地址") },
            modifier = Modifier.fillMaxWidth(),
        )
        controller.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { controller.connect(input) },
            enabled = !controller.busy && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (controller.busy) "正在连接…" else "连接服务器")
        }
    }
}

// ---- 登录 -------------------------------------------------------------------

@Composable
private fun LoginPane(controller: MoonTvController, reason: String?) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val askUsername = !controller.settings.storageType.equals("localstorage", ignoreCase = true)

    CenteredPane {
        Text(
            text = controller.siteName,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = reason ?: if (askUsername) "请使用站点账号登录" else "请输入站点访问密码",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))

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
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { controller.login(username, password) },
            enabled = !controller.busy && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (controller.busy) "正在登录…" else "登录")
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { controller.changeServer() }) {
            Text("更换服务器", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 竖屏居中的表单容器，宽度上限 420dp（桌面窗口拉宽时不会散开）。 */
@Composable
private fun CenteredPane(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

/** 与 Selene 一致的浅分隔线（供后续页面复用）。 */
@Composable
fun SeleneDivider() {
    HorizontalDivider(thickness = 1.dp, color = SeleneColors.InactiveLight.copy(alpha = 0.25f))
    Spacer(Modifier.width(0.dp).height(0.dp))
}

/** 空状态的占位内边距，避免各页面自己写魔法数。 */
val ScreenPadding = PaddingValues(horizontal = 16.dp)
