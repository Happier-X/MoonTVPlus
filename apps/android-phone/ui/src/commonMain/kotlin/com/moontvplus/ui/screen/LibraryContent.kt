package com.moontvplus.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.component.PosterCard
import com.moontvplus.ui.model.PosterItem

enum class LibraryTab(val label: String) {
    FAVORITES("我的收藏"),
    HISTORY("播放记录"),
}

/** 收藏 / 播放历史视图状态。 */
data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.FAVORITES,
    val loading: Boolean = true,
    val favorites: List<PosterItem> = emptyList(),
    val history: List<PosterItem> = emptyList(),
    val error: String? = null,
    /** 删除后的可撤销提示，宿主可以直接展示。 */
    val message: String? = null,
) {
    val entries: List<PosterItem> get() = if (tab == LibraryTab.FAVORITES) favorites else history
}

/**
 * 我的收藏 / 播放记录（网页版是弹层）：
 * 顶部胶囊切换，下面与网页一致的 3 列海报网格，长按删除。
 */
@Composable
fun LibraryContent(
    state: LibraryUiState,
    onSelectTab: (LibraryTab) -> Unit,
    onOpen: (PosterItem) -> Unit,
    onRemove: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        LibraryTabs(state.tab, onSelectTab, state.entries.size)
        when {
            state.loading && state.entries.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            state.entries.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error
                        ?: if (state.tab == LibraryTab.FAVORITES) "暂无收藏内容" else "暂无播放记录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                items(state.entries, key = { it.key }) { item ->
                    PosterCard(
                        item = item,
                        onClick = onOpen,
                        width = null,
                        titleLines = 2,
                        onLongClick = { onRemove(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTabs(selected: LibraryTab, onSelect: (LibraryTab) -> Unit, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp),
        ) {
            LibraryTab.entries.forEach { tab ->
                val active = tab == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = tab.label,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = "${count}项",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
