package com.moontvplus.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.model.MainTab
import com.moontvplus.ui.theme.SeleneColors

/** Selene 底部导航：选中绿 `#27AE60`，未选中灰，标签 12sp。 */
@Composable
fun SeleneBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 1.dp,
            color = if (isDarkScheme()) Color(0xFF333333).copy(alpha = 0.3f)
            else Color.White.copy(alpha = 0.2f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isDarkScheme()) SeleneColors.SurfaceDark.copy(alpha = 0.9f)
                    else SeleneColors.SurfaceLight.copy(alpha = 0.9f)
                )
                .padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MainTab.entries.forEach { tab ->
                BottomNavItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) SeleneColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon(),
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = tab.label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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
}

/** 顶栏：站点名 + 搜索入口，与 Selene 的 AppBar 一致（浅色白底/深色 #1E1E1E）。 */
@Composable
fun SeleneTopBar(
    title: String,
    subtitle: String? = null,
    onSearch: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    onHome: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val background = if (isDarkScheme()) {
        SeleneColors.SurfaceDark.copy(alpha = 0.9f)
    } else {
        SeleneColors.SurfaceLight.copy(alpha = 0.8f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSearch != null) {
                IconButton(onClick = onSearch, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(32.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = onHome != null) { onHome?.invoke() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = { onSettings?.invoke() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                actions()
            }
        }
    }
}

@Composable
private fun isDarkScheme(): Boolean =
    MaterialTheme.colorScheme.background == SeleneColors.BackgroundDark

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
