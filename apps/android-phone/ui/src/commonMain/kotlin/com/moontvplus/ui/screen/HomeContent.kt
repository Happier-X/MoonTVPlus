package com.moontvplus.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.component.HomeSectionRow
import com.moontvplus.ui.model.HomeSection
import com.moontvplus.ui.model.PosterItem

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
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
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