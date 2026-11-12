package com.moontvplus.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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

/** 分类页：自适应网格，一屏能放多少列就放多少列（手机上通常是 3 列）。 */
@Composable
fun BrowseContent(
    state: BrowseUiState,
    onItemClick: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = 116.dp,
) {
    when {
        state.loading && state.items.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        }

        state.items.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = state.error ?: "暂无数据",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }

        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minCellWidth),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.items, key = { it.key }) { item ->
                PosterCard(item, onItemClick, width = null)
            }
        }
    }
}
