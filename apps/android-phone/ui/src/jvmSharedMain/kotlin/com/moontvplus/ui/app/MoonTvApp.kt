package com.moontvplus.ui.app

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.model.MainTab
import com.moontvplus.ui.model.PlayRequest
import com.moontvplus.ui.model.ShellScreen
import com.moontvplus.ui.model.OpenTarget
import com.moontvplus.ui.screen.BrowseContent
import com.moontvplus.ui.screen.DetailContent
import com.moontvplus.ui.screen.LibraryContent
import com.moontvplus.ui.screen.LibraryTab
import com.moontvplus.ui.screen.HomeContent
import com.moontvplus.ui.shell.CenteredMessage
import com.moontvplus.ui.shell.WebBottomBar
import com.moontvplus.ui.shell.ModalTextAction
import com.moontvplus.ui.shell.WebConfirmDialog
import com.moontvplus.ui.shell.WebModal
import com.moontvplus.ui.shell.WebTopBar
import com.moontvplus.ui.theme.MoonTvTheme
import com.moontvplus.ui.theme.WebColors

/**
 * Android 与桌面端共用的根界面。
 *
 * 宿主只负责：注入 [MoonTvController]、决定深浅色、接住点击（去详情/搜索/设置）。
 */
@Composable
fun MoonTvApp(
    controller: MoonTvController,
    darkTheme: Boolean,
    onPlay: (PlayRequest) -> Unit = {},
    onOpenTarget: (OpenTarget) -> Unit = {},
    onMore: (MainTab) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onToggleTheme: (() -> Unit)? = null,
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

                ShellStage.Ready -> ShellContent(
                    controller = controller,
                    onPlay = onPlay,
                    onOpenTarget = onOpenTarget,
                    onMore = onMore,
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings,
                    onToggleTheme = onToggleTheme,
                )
            }
        }
    }
}

@Composable
private fun ShellContent(
    controller: MoonTvController,
    onPlay: (PlayRequest) -> Unit,
    onOpenTarget: (OpenTarget) -> Unit,
    onMore: (MainTab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTheme: (() -> Unit)?,
) {
    when (val screen = controller.screen) {
        ShellScreen.Main -> MainShell(
            controller = controller,
            onPlay = onPlay,
            onMore = onMore,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onToggleTheme = onToggleTheme,
        )

        is ShellScreen.Detail -> DetailPane(
            controller = controller,
            screen = screen,
            onPlay = onPlay,
        )

        ShellScreen.Library -> Box(Modifier.fillMaxSize()) {
            // 网页版里收藏/播放记录是弹层：主界面留在下面，上面盖一层圆角面板
            MainShell(
                controller = controller,
                onPlay = onPlay,
                onMore = onMore,
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                onToggleTheme = onToggleTheme,
            )
            LibraryModal(
                controller = controller,
                onPlay = onPlay,
            )
        }
    }
}

@Composable
private fun MainShell(
    controller: MoonTvController,
    onPlay: (PlayRequest) -> Unit,
    onMore: (MainTab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTheme: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize()) {
        // 内容区自己吃掉剩余高度，底部导航固定贴在窗口底边
        Column(Modifier.weight(1f).fillMaxWidth()) {
            WebTopBar(
                title = controller.siteName,
                onSearch = onOpenSearch,
                onProfile = onOpenSettings,
                onToggleTheme = onToggleTheme,
                onTitleClick = { controller.selectTab(MainTab.HOME) },
            )

            when (controller.tab) {
                MainTab.HOME -> HomeContent(
                    state = controller.home,
                    onItemClick = { item ->
                        when (val target = item.target) {
                            is OpenTarget.Resume -> onPlay(PlayRequest(target.source, target.id, target.title, target.index - 1, target.positionMs))
                            else -> controller.open(target)
                        }
                    },
                    onMore = { section ->
                        // 「更多」跳到对应分类页；返回时回到原来的标签页
                        sectionToTab(section.id)?.let(controller::openSectionTab)
                    },
                    onOpenHistory = { controller.openLibrary(LibraryTab.HISTORY) },
                )

                MainTab.LIVE -> CenteredMessage("直播模块正在接入")

                else -> BrowseContent(
                    state = controller.browse,
                    onItemClick = { item -> controller.open(item.target) },
                    onFilterChange = controller::setBrowseFilter,
                )
            }
        }

        WebBottomBar(selected = controller.tab, onSelect = controller::selectTab)
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
    onPlay: (PlayRequest) -> Unit = {},
    onOpenTarget: (OpenTarget) -> Unit = {},
    onMore: (MainTab) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onToggleTheme: (() -> Unit)? = null,
) {
    MoonTvTheme(darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            ShellContent(
                controller = controller,
                onPlay = onPlay,
                onOpenTarget = onOpenTarget,
                onMore = onMore,
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                onToggleTheme = onToggleTheme,
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

// ---- 详情页 -----------------------------------------------------------------

@Composable
private fun DetailPane(
    controller: MoonTvController,
    screen: ShellScreen.Detail,
    onPlay: (PlayRequest) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        WebTopBar(
            title = controller.siteName,
            onBack = controller::back,
        )

        DetailContent(
            state = controller.detail,
            onPlay = { index ->
                onPlay(
                    PlayRequest(
                        source = screen.source,
                        videoId = screen.videoId,
                        title = controller.detail.title.ifBlank { screen.title },
                        index = index,
                        positionMs = if (index == controller.detail.resumeIndex) controller.detail.resumePositionMs else 0,
                    )
                )
            },
            onToggleFavorite = controller::toggleFavorite,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---- 收藏 / 播放历史 ---------------------------------------------------------

/**
 * 我的收藏 / 播放记录：网页版是居中模态窗，
 * 标题行右侧是「编辑 / 全选 / 删除(n) / 清空全部」，编辑态点海报是选中。
 */
@Composable
private fun LibraryModal(
    controller: MoonTvController,
    onPlay: (PlayRequest) -> Unit,
) {
    val state = controller.library
    val isFavorites = state.tab == LibraryTab.FAVORITES
    WebModal(
        title = if (isFavorites) "我的收藏" else "播放记录",
        icon = if (isFavorites) Icons.Filled.Star else Icons.Outlined.History,
        iconTint = if (isFavorites) WebColors.Rating else WebColors.Info,
        count = state.entries.size,
        countTint = if (isFavorites) WebColors.Rating else WebColors.Info,
        onDismiss = controller::back,
        actions = {
            if (state.entries.isNotEmpty()) {
                if (state.editMode) {
                    ModalTextAction(
                        text = if (state.allSelected) "取消全选" else "全选",
                        onClick = controller::selectAllLibrary,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ModalTextAction(
                        text = "删除" + if (state.selected.isEmpty()) "" else "(${state.selected.size})",
                        onClick = controller::requestDeleteSelected,
                        color = WebColors.Danger,
                        enabled = state.selected.isNotEmpty(),
                    )
                    ModalTextAction(
                        text = "取消",
                        onClick = controller::toggleLibraryEdit,
                    )
                } else {
                    if (!isFavorites) {
                        ModalTextAction(
                            text = "编辑",
                            onClick = controller::toggleLibraryEdit,
                            color = WebColors.Info,
                        )
                    }
                    ModalTextAction(
                        text = "清空全部",
                        onClick = controller::requestClearLibrary,
                        color = WebColors.Danger,
                    )
                }
            }
        },
    ) {
        LibraryContent(
            state = state,
            onSelectTab = controller::selectLibraryTab,
            onOpen = { item ->
                when (val target = item.target) {
                    is OpenTarget.Resume -> onPlay(
                        PlayRequest(target.source, target.id, target.title, target.index - 1, target.positionMs)
                    )

                    else -> controller.open(target)
                }
            },
            onToggleSelect = controller::toggleLibrarySelect,
            modifier = Modifier.weight(1f),
        )
    }

    if (state.confirmClear) {
        WebConfirmDialog(
            title = if (isFavorites) "清空收藏" else "清空播放记录",
            message = "确定要清空所有${if (isFavorites) "收藏" else "播放记录"}吗？此操作不可恢复。",
            confirmText = "确定清空",
            onConfirm = controller::confirmClearLibrary,
            onDismiss = controller::dismissLibraryConfirm,
        )
    }

    if (state.confirmDelete) {
        WebConfirmDialog(
            title = "删除所选",
            message = "确定要删除选中的 ${state.selected.size} 项吗？此操作不可恢复。",
            confirmText = "确定删除",
            onConfirm = controller::confirmDeleteSelected,
            onDismiss = controller::dismissLibraryConfirm,
        )
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

