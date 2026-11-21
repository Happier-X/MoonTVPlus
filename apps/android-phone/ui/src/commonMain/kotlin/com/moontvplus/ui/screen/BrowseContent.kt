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
import com.moontvplus.ui.component.FilterSection
import com.moontvplus.ui.component.PosterCard
import com.moontvplus.ui.model.BrowseKind
import com.moontvplus.ui.model.FilterCategory
import com.moontvplus.ui.model.PosterItem

/** 分类页视图状态：数据集 + 当前筛选。 */
data class BrowseUiState(
    val kind: BrowseKind = BrowseKind.MOVIE,
    val loading: Boolean = false,
    val items: List<PosterItem> = emptyList(),
    val error: String? = null,
    val categories: List<FilterCategory> = emptyList(),
    val filters: Map<String, String> = emptyMap(),
)

/**
 * 分类页：与网页版 `/douban?type=` 一致 ——
 * 26sp 标题 + 13sp 副标题，筛选胶囊卡，然后 3 列海报网格（列距 8dp、行距 48dp）。
 */
@Composable
fun BrowseContent(
    state: BrowseUiState,
    onItemClick: (PosterItem) -> Unit,
    onFilterChange: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BrowseHeader(state.kind)
        }

        if (state.categories.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterSection(
                    categories = state.categories,
                    values = state.filters,
                    onChange = onFilterChange,
                )
            }
        }

        when {
            state.loading && state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.error ?: "暂无数据",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> items(state.items, key = { it.key }) { item ->
                PosterCard(item, onItemClick, width = null, titleLines = 2)
            }
        }
    }
}

@Composable
private fun BrowseHeader(kind: BrowseKind) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = kind.label,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = kind.description,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
}
