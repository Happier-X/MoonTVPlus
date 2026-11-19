package com.moontvplus.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.component.HomeSectionRow
import com.moontvplus.ui.model.HomeSection
import com.moontvplus.ui.model.PosterItem
import com.moontvplus.ui.theme.SeleneColors

/** 首页视图状态。 */
data class HomeUiState(
    val loading: Boolean = false,
    val continueWatching: HomeSection? = null,
    val sections: List<HomeSection> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }
}

/**
 * 首页：继续观看 + 热门电影 / 剧集 / 番剧 / 综艺 / 短剧 / 即将上映。
 * 结构和 Selene 的 `home_screen` 一致，纵向滚动、每个区块自己横向滑。
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    onItemClick: (PosterItem) -> Unit,
    onMore: (HomeSection) -> Unit = {},
    homeTab: HomeTab = HomeTab.HOME,
    onHomeTabChange: (HomeTab) -> Unit = {},
    library: LibraryUiState? = null,
    onOpenLibraryItem: (PosterItem) -> Unit = {},
    onRemoveLibraryItem: (PosterItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.loading && state.sections.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        }
        return
    }

    if (state.sections.isEmpty() && state.error != null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = state.error,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeTabSwitcher(selected = homeTab, onSelect = onHomeTabChange)
        if (homeTab == HomeTab.HISTORY || homeTab == HomeTab.FAVORITES) {
            val lib = library ?: LibraryUiState(tab = if (homeTab == HomeTab.HISTORY) LibraryTab.HISTORY else LibraryTab.FAVORITES)
            LibrarySection(
                state = lib.copy(tab = if (homeTab == HomeTab.HISTORY) LibraryTab.HISTORY else LibraryTab.FAVORITES),
                onOpen = onOpenLibraryItem,
                onRemove = onRemoveLibraryItem,
            )
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        state.sections.forEach { section ->
            if (section.items.isEmpty() && section.error == null) return@forEach
            HomeSectionRow(section, onItemClick, onMore = { onMore(section) })
        }

        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
/** Selene 首页顶部的三段胶囊：首页 / 播放历史 / 收藏夹。 */
enum class HomeTab(val label: String) {
    HOME("首页"),
    HISTORY("播放历史"),
    FAVORITES("收藏夹"),
}

@Composable
private fun HomeTabSwitcher(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val dark = MaterialTheme.colorScheme.background == SeleneColors.BackgroundDark
    val track = if (dark) Color(0xFF333333) else Color(0xFFE0E0E0)
    val thumb = if (dark) SeleneColors.SurfaceDark else Color.White
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(top = 20.dp, bottom = 8.dp)
                .size(width = 240.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(track),
        ) {
        Box(
            modifier = Modifier
                .padding(start = (HomeTab.entries.indexOf(selected) * 80).dp)
                .size(width = 80.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(thumb),
        )
            Row(modifier = Modifier.fillMaxSize()) {
                HomeTab.entries.forEach { tab ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelect(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            fontWeight = if (tab == selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                tab != selected && dark -> SeleneColors.InactiveDark
                                tab != selected -> SeleneColors.InactiveLight
                                dark -> Color.White
                                else -> SeleneColors.SeedLight
                            },
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun LibrarySection(
    state: LibraryUiState,
    onOpen: (PosterItem) -> Unit,
    onRemove: (PosterItem) -> Unit,
) {
    val entries = if (state.tab == LibraryTab.FAVORITES) state.favorites else state.history
    when {
        state.loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        }
        entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (state.tab == LibraryTab.FAVORITES) "还没有收藏" else "还没有播放记录",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyColumn {
            items(entries, key = { it.key }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(item) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.title,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRemove(item) }) { Text("删除", fontSize = 12.sp) }
                }
            }
        }
    }
}
