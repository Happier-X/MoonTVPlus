package com.moontvplus.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.model.MainTab
import com.moontvplus.ui.theme.WebColors

/**
 * 网页版移动端顶栏：49dp、半透明底 + 底部分割线；
 * 左侧搜索、中间站点名 24sp/700 品牌绿、右侧用户入口。
 */
@Composable
fun WebTopBar(
    title: String,
    onSearch: (() -> Unit)? = null,
    onProfile: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onToggleTheme: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkScheme()
    Column(modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (dark) MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    else Color.White.copy(alpha = 0.94f)
                )
                .height(48.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else if (onSearch != null) {
                    IconButton(onClick = onSearch, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.size(40.dp))
                }

                Spacer(Modifier.weight(1f))
                // 网页版顶栏右侧：主题切换 + 用户入口
                if (onToggleTheme != null) {
                    IconButton(onClick = onToggleTheme, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = if (dark) "切换到浅色" else "切换到深色",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (onProfile != null) {
                    IconButton(onClick = onProfile, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = "我的",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.size(40.dp))
                }
            }

            // 站点名居中（与网页版一致，不吃左右按钮的位置）
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = WebColors.Brand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = onTitleClick != null) { onTitleClick?.invoke() },
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = if (dark) WebColors.OutlineDark.copy(alpha = 0.6f) else WebColors.OutlineLight,
        )
    }
}

/** 网页版底部导航：57dp、图标 22dp + 10sp 标签，选中品牌绿。 */
@Composable
fun WebBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    tabs: List<MainTab> = MainTab.entries,
) {
    val dark = isDarkScheme()
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 1.dp,
            color = if (dark) WebColors.OutlineDark.copy(alpha = 0.6f) else WebColors.OutlineLight,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 网页版底栏每项固定 20vw，超过 5 项时横向滑动；
            // 等分会把「电视直播 / 网络直播」挤到截断。
            val scrollable = tabs.size > 5
            val itemWidth = maxWidth * 0.2f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                    .background(
                        if (dark) MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                        else Color.White.copy(alpha = 0.96f)
                    )
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    BottomItem(
                        tab = tab,
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = if (scrollable) Modifier.width(itemWidth) else Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomItem(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        selected && isDarkScheme() -> WebColors.AccentHover
        selected -> WebColors.AccentDeep
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .testTag("bottom-tab-${tab.name}")
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon(),
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            fontSize = 10.sp,
            color = tint,
            maxLines = 1,
        )
    }
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.HOME -> Icons.Outlined.Home
    MainTab.MOVIE -> Icons.Outlined.Movie
    MainTab.TV -> Icons.Outlined.Tv
    MainTab.ANIME -> Icons.Outlined.Pets
    MainTab.SHOW -> Icons.Outlined.Whatshot
    MainTab.LIVE -> Icons.Outlined.LiveTv
    MainTab.WEB_LIVE -> Icons.Outlined.Public
}

/**
 * 网页版的底部弹层：半透明遮罩 + 顶部 24dp 圆角面板，
 * 标题行 20sp/700，右上角关闭。
 */
@Composable
fun WebSheet(
    title: String,
    onDismiss: () -> Unit,
    expanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (expanded) Modifier.fillMaxHeight(0.9f) else Modifier),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (expanded) Modifier.fillMaxHeight(0.9f) else Modifier)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                content()
            }
        }
    }
}

@Composable
fun isDarkScheme(): Boolean =
    MaterialTheme.colorScheme.background == WebColors.BackgroundDark

/** 居中的空态 / 错误态。 */
@Composable
fun CenteredMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                action()
            }
        }
    }
}
