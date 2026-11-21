package com.moontvplus.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.component.PosterCard
import com.moontvplus.ui.model.PosterItem

/** 分类页视图状态。 */
data class BrowseUiState(
    val loading: Boolean = false,
    val items: List<PosterItem> = emptyList(),
    val error: String? = null,
)

/**
 * 分类页：与网页版 `/douban?type=` 一致 ——
 * 26sp 标题 + 13sp 副标题，下面 3 列海报网格（列间距 8dp、行间距 48dp、左右 16dp）。
 */
@Composable
fun BrowseContent(
    state: BrowseUiState,
    onItemClick: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
) {
    when {
        state.loading && state.items.isEmpty() -> Column(modifier.fillMaxSize()) {
            BrowseHeader(title, Modifier.padding(horizontal = 16.dp))
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        state.items.isEmpty() -> Column(modifier.fillMaxSize()) {
            BrowseHeader(title, Modifier.padding(horizontal = 16.dp))
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = state.error ?: "暂无数据",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseHeader(title, Modifier.fillMaxWidth())
            }
            items(state.items, key = { it.key }) { item ->
                PosterCard(item, onItemClick, width = null, titleLines = 2)
            }
        }
    }
}

@Composable
private fun BrowseHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "来自豆瓣的精选内容",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
